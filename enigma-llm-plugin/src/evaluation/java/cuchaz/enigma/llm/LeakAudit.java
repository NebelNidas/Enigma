package cuchaz.enigma.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonObject;

/**
 * Diagnostic leak audit for an obfuscated corpus jar: measures — it does not scrub — how many real
 * identifiers still reach what a reader (human reverse-engineer, or the model) sees through channels
 * other than the already-stripped debug metadata. The point is to quantify the magnitude and decide,
 * per corpus, whether a leak large enough to distort the recovery score warrants scrubbing or a
 * leak-adjusted caveat.
 *
 * <p>The oracle is the ground-truth <em>real</em> name set (class simple names and member names of the
 * obfuscated symbols). Preservation-control names are excluded by the caller — their real name is kept
 * on purpose, so it is not a leak. The audit walks each class's constant pool and reports two
 * high-signal channels, deliberately ignoring the low-signal ones:
 *
 * <ul>
 *   <li><b>type-ref</b> — {@code CONSTANT_Class} internal names. After tiny-remapper every project type
 *       reference should be an opaque token; a project <em>binary</em> name surviving here is a genuine
 *       <em>mapping gap</em>, the strongest leak. Matched against the real binary-name set (full path),
 *       not simple names — so a JDK reference like {@code java/util/zip/CRC32} whose simple name happens
 *       to equal a project class {@code CRC32} is not miscounted.</li>
 *   <li><b>string-literal</b> — {@code CONSTANT_String} values (what {@code ldc} loads). A real name in
 *       a literal is what a reverse-engineer reads off error messages, reflection strings, etc. Matched
 *       against class and (length-gated, stoplisted) member names.</li>
 * </ul>
 *
 * <p>Deliberately <em>not</em> matched: {@code NameAndType} names and descriptors. The constant pool
 * necessarily lists every method/field name a class invokes — including JDK calls like {@code iterator}
 * or {@code valueOf} — so matching those against the real-name set produces overwhelming false positives
 * from call references that are not leaks at all. Non-class resources are scanned against class names
 * only (resource text does not reach the class-derived prompt, but reveals library identity).
 *
 * <p>Each hit is {@code strict} when the whole entry is name/path-shaped and its last {@code /.$}-segment
 * equals the real name (a bare {@code JsonReader} or an unmapped {@code com/google/gson/JsonReader}),
 * else {@code loose} (the name is one identifier token inside a larger string). Member matches require
 * length &gt;= {@value #MEMBER_MIN_LEN} and pass a stoplist of ultra-common words; class matches (higher
 * signal, rarely common words) require only length &gt;= {@value #CLASS_MIN_LEN}.
 */
final class LeakAudit {
	private static final int CLASS_MIN_LEN = 4;
	private static final int MEMBER_MIN_LEN = 5;
	private static final int MAX_SAMPLES = 50;
	private static final int MAX_RESOURCE_BYTES = 1 << 20;
	private static final int CONSTANT_UTF8 = 1;
	private static final int CONSTANT_CLASS = 7;
	private static final int CONSTANT_STRING = 8;
	/** Ultra-common member words that match by coincidence rather than by leaking real intent. */
	private static final Set<String> STOPLIST = Set.of(
			"value", "values", "index", "count", "write", "close", "clone", "equals", "empty", "first",
			"start", "length", "offset", "result", "buffer", "string", "object", "array", "field");

	private LeakAudit() {
	}

	/**
	 * Audits {@code obfJar} against the real-name oracle. {@code realClassNames} are simple class names
	 * (string-literal/resource oracle), {@code realBinaryNames} full internal names (type-ref oracle),
	 * {@code realMemberNames} method/field names; all must already exclude preservation-control names.
	 */
	static LeakReport audit(Path obfJar, Set<String> realClassNames, Set<String> realBinaryNames,
			Set<String> realMemberNames) throws IOException {
		Set<String> classNeedles = new TreeSet<>();
		Set<String> memberNeedles = new TreeSet<>();

		for (String name : realClassNames) {
			if (name != null && name.length() >= CLASS_MIN_LEN) {
				classNeedles.add(name);
			}
		}

		for (String name : realMemberNames) {
			if (name != null && name.length() >= MEMBER_MIN_LEN && !STOPLIST.contains(name)) {
				memberNeedles.add(name);
			}
		}

		LeakReport report = new LeakReport();

		try (ZipFile zip = new ZipFile(obfJar.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();

				if (entry.isDirectory()) {
					continue;
				}

				if (entry.getName().endsWith(".class")) {
					auditClass(zip, entry, classNeedles, realBinaryNames, memberNeedles, report);
				} else {
					auditResource(zip, entry, classNeedles, report);
				}
			}
		}

		return report;
	}

	private static void auditClass(ZipFile zip, ZipEntry entry, Set<String> classNeedles,
			Set<String> realBinaryNames, Set<String> memberNeedles, LeakReport report) throws IOException {
		byte[] bytes;

		try (InputStream in = zip.getInputStream(entry)) {
			bytes = in.readAllBytes();
		}

		String obfClass = stripClassSuffix(entry.getName());

		if (bytes.length < 10) {
			return;
		}

		int count = u2(bytes, 8);
		String[] utf8 = new String[count];
		int[] tag = new int[count];
		int[] ref = new int[count];
		int offset = 10;

		for (int i = 1; i < count && offset < bytes.length; i++) {
			tag[i] = bytes[offset] & 0xff;

			switch (tag[i]) {
			case CONSTANT_UTF8: {
				int len = u2(bytes, offset + 1);
				utf8[i] = new String(bytes, offset + 3, len, StandardCharsets.UTF_8);
				offset += 3 + len;
				break;
			}

			case CONSTANT_CLASS:
			case CONSTANT_STRING:
				ref[i] = u2(bytes, offset + 1);
				offset += 3;
				break;
			case 16: case 19: case 20:
				offset += 3;
				break;
			case 15:
				offset += 4;
				break;
			case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
				offset += 5;
				break;
			case 5: case 6:
				offset += 9;
				i++;
				break;
			default:
				// Unknown tag: the pool layout is no longer trustworthy, so stop rather than misread.
				return;
			}
		}

		for (int i = 1; i < count; i++) {
			String value = tag[i] == CONSTANT_CLASS || tag[i] == CONSTANT_STRING ? utf8[ref[i]] : null;

			if (value == null) {
				continue;
			}

			if (tag[i] == CONSTANT_CLASS) {
				matchTypeRef(value, obfClass, realBinaryNames, report);
			} else {
				matchInto(value, obfClass, "string-literal", classNeedles, memberNeedles, report);
			}
		}
	}

	private static void auditResource(ZipFile zip, ZipEntry entry, Set<String> classNeedles,
			LeakReport report) throws IOException {
		if (entry.getSize() > MAX_RESOURCE_BYTES) {
			return;
		}

		byte[] bytes;

		try (InputStream in = zip.getInputStream(entry)) {
			bytes = in.readAllBytes();
		}

		String text = new String(bytes, StandardCharsets.UTF_8);

		// Scan line by line so distinct occurrences are attributed separately. Only class names are
		// matched: member-name matches in prose/build metadata are pure English coincidence.
		for (String line : text.split("\\R")) {
			if (!line.isBlank()) {
				matchInto(line, entry.getName(), "resource", classNeedles, null, report);
			}
		}
	}

	/**
	 * Records every real-name token in {@code haystack}. {@code memberNeedles} may be {@code null} to
	 * match class names only (type references and resources).
	 */
	private static void matchInto(String haystack, String owner, String channel, Set<String> classNeedles,
			Set<String> memberNeedles, LeakReport report) {
		List<String> tokens = tokenize(haystack);

		if (tokens.isEmpty()) {
			return;
		}

		String lastSegment = tokens.get(tokens.size() - 1);
		boolean pathShaped = isPathShaped(haystack);

		for (String token : tokens) {
			String matchKind = null;

			if (classNeedles.contains(token)) {
				matchKind = "class";
			} else if (memberNeedles != null && memberNeedles.contains(token)) {
				matchKind = "member";
			}

			if (matchKind == null) {
				continue;
			}

			boolean strict = pathShaped && token.equals(lastSegment);
			report.record(new LeakSample(owner, channel, matchKind, token, strict, trim(haystack)));
		}
	}

	/**
	 * Records a type-ref leak iff the {@code CONSTANT_Class} internal name, once array wrapping is
	 * stripped, is exactly a project real binary name — i.e. a project type reference tiny-remapper
	 * failed to map. JDK/library references (never in the binary-name set) are ignored by construction.
	 */
	private static void matchTypeRef(String value, String owner, Set<String> realBinaryNames, LeakReport report) {
		String internal = value;

		while (internal.startsWith("[")) {
			internal = internal.substring(1);
		}

		if (internal.startsWith("L") && internal.endsWith(";")) {
			internal = internal.substring(1, internal.length() - 1);
		}

		if (realBinaryNames.contains(internal)) {
			int cut = Math.max(internal.lastIndexOf('/'), internal.lastIndexOf('$'));
			String simple = cut >= 0 ? internal.substring(cut + 1) : internal;
			report.record(new LeakSample(owner, "type-ref", "class", simple, true, trim(value)));
		}
	}

	/** Splits on everything that is not a Java identifier part, and additionally on {@code $ / .}. */
	private static List<String> tokenize(String value) {
		List<String> tokens = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (Character.isLetterOrDigit(c) || c == '_') {
				current.append(c);
			} else {
				flush(current, tokens);
			}
		}

		flush(current, tokens);
		return tokens;
	}

	private static void flush(StringBuilder current, List<String> tokens) {
		if (current.length() > 0) {
			tokens.add(current.toString());
			current.setLength(0);
		}
	}

	/** True if the whole string is a bare name or a {@code /.$}-separated path of identifiers. */
	private static boolean isPathShaped(String value) {
		if (value.isEmpty()) {
			return false;
		}

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (!Character.isLetterOrDigit(c) && c != '_' && c != '/' && c != '.' && c != '$') {
				return false;
			}
		}

		return true;
	}

	private static int u2(byte[] b, int offset) {
		return ((b[offset] & 0xff) << 8) | (b[offset + 1] & 0xff);
	}

	private static String stripClassSuffix(String name) {
		return name.endsWith(".class") ? name.substring(0, name.length() - ".class".length()) : name;
	}

	private static String trim(String value) {
		return value.length() <= 80 ? value : value.substring(0, 77) + "...";
	}

	/** One leaked-identifier occurrence. */
	record LeakSample(String owner, String channel, String matchKind, String realName, boolean strict,
			String context) {
		JsonObject toJson() {
			JsonObject object = new JsonObject();
			object.addProperty("owner", this.owner);
			object.addProperty("channel", this.channel);
			object.addProperty("matchKind", this.matchKind);
			object.addProperty("realName", this.realName);
			object.addProperty("strict", this.strict);
			object.addProperty("context", this.context);
			return object;
		}
	}

	/** Aggregated leak counts for one jar, plus a capped list of samples. */
	static final class LeakReport {
		private int strict;
		private int loose;
		private final java.util.Map<String, Integer> byChannel = new TreeMap<>();
		private final Set<String> uniqueNames = new TreeSet<>();
		private final List<LeakSample> samples = new ArrayList<>();

		void record(LeakSample sample) {
			this.loose++;

			if (sample.strict()) {
				this.strict++;
			}

			this.byChannel.merge(sample.channel(), 1, Integer::sum);
			this.uniqueNames.add(sample.realName());

			if (this.samples.size() < MAX_SAMPLES) {
				this.samples.add(sample);
			}
		}

		List<LeakSample> samples() {
			return this.samples;
		}

		String describe() {
			int typeRef = this.byChannel.getOrDefault("type-ref", 0);
			int literal = this.byChannel.getOrDefault("string-literal", 0);
			int resource = this.byChannel.getOrDefault("resource", 0);
			return String.format("strict=%d loose=%d unique=%d type-ref=%d string=%d resource=%d",
					this.strict, this.loose, this.uniqueNames.size(), typeRef, literal, resource);
		}
	}
}
