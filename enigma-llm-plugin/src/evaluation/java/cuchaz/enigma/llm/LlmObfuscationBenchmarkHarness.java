package cuchaz.enigma.llm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.api.view.ProjectView;

/**
 * Phase A of the Hybrid 1.1 deobfuscation round-trip benchmark: drive the <em>real</em> suggestion
 * engine against an obfuscated corpus jar and score the recovered names against the ground truth.
 *
 * <p>For each {@code <base>-obf.jar} + {@code <base>-groundtruth.jsonl} pair produced by
 * {@link ObfuscateCorpusTool}, the harness
 * <ol>
 *   <li>opens the obfuscated jar headless via {@code Enigma.create().openJar(...)} — an
 *       {@link cuchaz.enigma.EnigmaProject}, which is the {@link ProjectView} the engine consumes
 *       (this is where the "an obf jar opens as a real project" assumption is verified);</li>
 *   <li>rebuilds the {@link LlmProjectIndex} from the same jar bytes (the same builder the production
 *       {@code LlmJarIndexerService} uses) and installs it on a fresh plugin;</li>
 *   <li>reconstructs an {@link EntryKey} for every ground-truth symbol and checks it actually
 *       resolves in that index — a mismatch means the recorded obfuscated identity drifted from the
 *       bytecode tiny-remapper emitted, which would silently starve the prompt of context;</li>
 *   <li>calls {@link LlmSuggestionEngine#requestSuggestion} per target and scores the result.</li>
 * </ol>
 *
 * <p>Scoring is gated to the recoverable slice (classes plus public/protected members) and reported
 * per-jar and per-kind. Exact and normalised matches are counted separately; the normalised match
 * folds case and non-alphanumeric noise so {@code getFoo}/{@code get_foo} count. Preservation-control
 * symbols ({@code obfuscated=false}) are bucketed apart and never averaged into recovery — success
 * there means the model recognised an already-meaningful name and returned it unchanged.
 *
 * <p>If the LLM endpoint is not configured ({@link LlmConfig#isConfigured()} is false) the harness
 * still runs the structural half — open, index, resolve every key — and reports it, so the round-trip
 * integrity can be verified without a live model. Set {@code ENIGMA_LLM_BENCH_LIMIT} to a positive
 * integer to sample that many targets per jar (deterministic stride) for a cheap smoke run.
 *
 * <pre>args: &lt;obfuscatedDir&gt; [resultsDir]</pre>
 */
public final class LlmObfuscationBenchmarkHarness {
	private static final String LIMIT_ENV = "ENIGMA_LLM_BENCH_LIMIT";

	private LlmObfuscationBenchmarkHarness() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.err.println("usage: LlmObfuscationBenchmarkHarness <obfuscatedDir> [resultsDir]");
			System.exit(2);
			return;
		}

		Path obfuscatedDir = Path.of(args[0]);
		Path resultsDir = args.length >= 2 ? Path.of(args[1]) : obfuscatedDir.resolve("benchmark");
		Files.createDirectories(resultsDir);

		int limit = readLimit();
		LlmConfig config = LlmConfig.load();
		boolean online = config.isConfigured();

		if (online) {
			System.out.printf("Endpoint: %s model=%s%n", config.baseUrl(), config.model());
		} else {
			System.out.println("Endpoint not configured -- running structural check only (open + index + resolve).");
		}

		List<Path> groundTruths = new ArrayList<>();

		try (Stream<Path> stream = Files.list(obfuscatedDir)) {
			stream.filter(p -> p.getFileName().toString().endsWith("-groundtruth.jsonl"))
					.sorted()
					.forEach(groundTruths::add);
		}

		if (groundTruths.isEmpty()) {
			System.err.println("no *-groundtruth.jsonl found in " + obfuscatedDir + " (run :enigma-llm-plugin:obfuscateCorpus first)");
			System.exit(1);
			return;
		}

		List<JarReport> reports = new ArrayList<>();

		for (Path groundTruth : groundTruths) {
			JarReport report = processJar(obfuscatedDir, groundTruth, resultsDir, config, online, limit);

			if (report != null) {
				reports.add(report);
			}
		}

		printSummary(reports, online);
	}

	private static JarReport processJar(Path obfuscatedDir, Path groundTruth, Path resultsDir,
			LlmConfig config, boolean online, int limit) throws IOException {
		String base = stripSuffix(groundTruth.getFileName().toString(), "-groundtruth.jsonl");
		Path obfJar = obfuscatedDir.resolve(base + "-obf.jar");

		if (!Files.exists(obfJar)) {
			System.err.println("skip " + base + ": missing " + obfJar.getFileName());
			return null;
		}

		List<GroundTruthSymbol> symbols = loadGroundTruth(groundTruth, base);

		// Open the obfuscated jar as a real project -- the assumption Phase A exists to verify.
		ProjectView project = Enigma.create().openJar(obfJar, List.of(), ProgressListener.none());

		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		plugin.setIndex(buildIndex(obfJar));
		LlmSuggestionEngine engine = new LlmSuggestionEngine(plugin);

		List<GroundTruthSymbol> recovery = new ArrayList<>();
		List<GroundTruthSymbol> preservation = new ArrayList<>();

		for (GroundTruthSymbol symbol : symbols) {
			if (!symbol.recoverable()) {
				continue;
			}

			if (symbol.obfuscated()) {
				recovery.add(symbol);
			} else {
				preservation.add(symbol);
			}
		}

		List<GroundTruthSymbol> sampledRecovery = sample(recovery, limit);
		List<GroundTruthSymbol> sampledPreservation = sample(preservation, limit);

		Path resultsFile = resultsDir.resolve(base + "-benchmark.jsonl");
		JarReport report = new JarReport(base);

		try (BufferedWriter writer = Files.newBufferedWriter(resultsFile, StandardCharsets.UTF_8)) {
			for (GroundTruthSymbol symbol : sampledRecovery) {
				TargetScore score = scoreTarget(engine, config, project, plugin.getIndex(), symbol, online);
				report.add(score, false);
				writer.write(score.toJson().toString());
				writer.write('\n');
			}

			for (GroundTruthSymbol symbol : sampledPreservation) {
				TargetScore score = scoreTarget(engine, config, project, plugin.getIndex(), symbol, online);
				report.add(score, true);
				writer.write(score.toJson().toString());
				writer.write('\n');
			}
		}

		System.out.println(report.line(online));
		return report;
	}

	private static TargetScore scoreTarget(LlmSuggestionEngine engine, LlmConfig config, ProjectView project,
			LlmProjectIndex index, GroundTruthSymbol symbol, boolean online) {
		EntryKey key = keyFor(symbol);
		boolean resolved = index.entry(key).isPresent();

		if (!online) {
			return TargetScore.structural(symbol, resolved);
		}

		String suggested = null;
		List<String> alternatives = List.of();
		double confidence = 0.0;
		String error = null;

		try {
			LlmSuggestion suggestion = engine.requestSuggestion(config, project, key);
			suggested = suggestion.suggestedName();
			alternatives = suggestion.alternatives();
			confidence = suggestion.confidence();
		} catch (RuntimeException ex) {
			error = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
		}

		return TargetScore.scored(symbol, resolved, suggested, alternatives, confidence, error);
	}

	private static EntryKey keyFor(GroundTruthSymbol symbol) {
		if (symbol.kind() == EntryKind.CLASS) {
			// EntryKey for a class carries the full internal name in both owner and name.
			return new EntryKey(EntryKind.CLASS, symbol.obfOwner(), symbol.obfOwner(), "");
		}

		return new EntryKey(symbol.kind(), symbol.obfOwner(), symbol.obfName(), symbol.obfDesc());
	}

	/** Rebuilds the LLM index from the obfuscated jar bytes -- the same builder the real service uses. */
	private static LlmProjectIndex buildIndex(Path obfJar) throws IOException {
		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();

		try (ZipFile zip = new ZipFile(obfJar.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String entryName = entry.getName();

				if (entry.isDirectory() || !entryName.endsWith(".class") || entryName.endsWith("module-info.class")) {
					continue;
				}

				try (InputStream in = zip.getInputStream(entry)) {
					ClassNode node = new ClassNode();
					new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
					builder.accept(node);
				}
			}
		}

		return builder.build();
	}

	private static List<GroundTruthSymbol> loadGroundTruth(Path file, String jar) throws IOException {
		List<GroundTruthSymbol> symbols = new ArrayList<>();

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}

				JsonObject object = JsonParser.parseString(line).getAsJsonObject();
				Set<String> acceptable = new TreeSet<>();
				JsonArray array = object.getAsJsonArray("acceptableRealNames");

				if (array != null) {
					array.forEach(element -> acceptable.add(element.getAsString()));
				}

				symbols.add(new GroundTruthSymbol(
						jar,
						EntryKind.valueOf(object.get("kind").getAsString()),
						object.get("obfOwner").getAsString(),
						object.get("obfName").getAsString(),
						object.get("obfDesc").getAsString(),
						object.get("realName").getAsString(),
						Set.copyOf(acceptable),
						object.get("visibility").getAsString(),
						object.get("recoverableSlice").getAsBoolean(),
						object.get("obfuscated").getAsBoolean()));
			}
		}

		return symbols;
	}

	/** Deterministic stride sample: {@code limit <= 0} or {@code >= size} keeps everything. */
	private static List<GroundTruthSymbol> sample(List<GroundTruthSymbol> symbols, int limit) {
		List<GroundTruthSymbol> sorted = new ArrayList<>(symbols);
		sorted.sort(Comparator.comparing(GroundTruthSymbol::obfOwner)
				.thenComparing(GroundTruthSymbol::obfName)
				.thenComparing(GroundTruthSymbol::obfDesc));

		if (limit <= 0 || limit >= sorted.size()) {
			return sorted;
		}

		List<GroundTruthSymbol> picked = new ArrayList<>(limit);

		for (int i = 0; i < limit; i++) {
			picked.add(sorted.get((int) ((long) i * sorted.size() / limit)));
		}

		return picked;
	}

	private static int readLimit() {
		String raw = System.getenv(LIMIT_ENV);

		if (raw == null || raw.isBlank()) {
			return 0;
		}

		try {
			return Math.max(0, Integer.parseInt(raw.trim()));
		} catch (NumberFormatException ignored) {
			System.err.println("ignoring non-integer " + LIMIT_ENV + "=" + raw);
			return 0;
		}
	}

	private static void printSummary(List<JarReport> reports, boolean online) {
		System.out.println();
		System.out.println("=== Phase A summary ===");

		JarReport total = new JarReport("TOTAL");

		for (JarReport report : reports) {
			total.absorb(report);
		}

		for (JarReport report : reports) {
			System.out.println(report.line(online));
		}

		System.out.println(total.line(online));

		if (online) {
			System.out.println();
			System.out.println("Per-kind (recovery, all jars):");
			total.recovery.printByKind();

			if (total.preservation.attempted > 0) {
				System.out.println("Preservation control (kept-unchanged, all jars): "
						+ total.preservation.describePreservation());
			}
		}
	}

	private static String stripSuffix(String value, String suffix) {
		return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
	}

	private static String normalize(String value) {
		if (value == null) {
			return "";
		}

		StringBuilder builder = new StringBuilder(value.length());

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (Character.isLetterOrDigit(c)) {
				builder.append(Character.toLowerCase(c));
			}
		}

		return builder.toString();
	}

	/** One ground-truth row loaded back from the {@code -groundtruth.jsonl}. */
	private record GroundTruthSymbol(String jar, EntryKind kind, String obfOwner, String obfName, String obfDesc,
			String realName, Set<String> acceptableRealNames, String visibility, boolean recoverable,
			boolean obfuscated) {
	}

	/** The outcome of one target: structural resolution plus (if online) the scored suggestion. */
	private record TargetScore(GroundTruthSymbol symbol, boolean resolvedInIndex, boolean attempted,
			String suggested, List<String> alternatives, double confidence, String error,
			boolean exact, boolean normalized, boolean usable) {
		static TargetScore structural(GroundTruthSymbol symbol, boolean resolved) {
			return new TargetScore(symbol, resolved, false, null, List.of(), 0.0, null, false, false, false);
		}

		static TargetScore scored(GroundTruthSymbol symbol, boolean resolved, String suggested,
				List<String> alternatives, double confidence, String error) {
			boolean exact = suggested != null && symbol.acceptableRealNames().contains(suggested);
			boolean normalized = suggested != null && matchesNormalized(symbol.acceptableRealNames(), suggested);
			boolean usable = exact || normalized || matchesAny(symbol.acceptableRealNames(), alternatives);
			return new TargetScore(symbol, resolved, true, suggested, alternatives, confidence, error,
					exact, normalized, usable);
		}

		private static boolean matchesNormalized(Set<String> acceptable, String candidate) {
			String needle = normalize(candidate);

			for (String name : acceptable) {
				if (normalize(name).equals(needle)) {
					return true;
				}
			}

			return false;
		}

		private static boolean matchesAny(Set<String> acceptable, List<String> candidates) {
			for (String candidate : candidates) {
				if (acceptable.contains(candidate) || matchesNormalized(acceptable, candidate)) {
					return true;
				}
			}

			return false;
		}

		JsonObject toJson() {
			JsonObject object = new JsonObject();
			object.addProperty("jar", this.symbol.jar());
			object.addProperty("kind", this.symbol.kind().name());
			object.addProperty("visibility", this.symbol.visibility());
			object.addProperty("preservationControl", !this.symbol.obfuscated());
			object.addProperty("obfOwner", this.symbol.obfOwner());
			object.addProperty("obfName", this.symbol.obfName());
			object.addProperty("obfDesc", this.symbol.obfDesc());
			object.addProperty("expected", this.symbol.realName());
			JsonArray acceptable = new JsonArray();
			this.symbol.acceptableRealNames().forEach(acceptable::add);
			object.add("acceptable", acceptable);
			object.addProperty("resolvedInIndex", this.resolvedInIndex);
			object.addProperty("attempted", this.attempted);
			object.addProperty("suggested", this.suggested);
			JsonArray alternatives = new JsonArray();
			this.alternatives.forEach(alternatives::add);
			object.add("alternatives", alternatives);
			object.addProperty("confidence", this.confidence);
			object.addProperty("exact", this.exact);
			object.addProperty("normalized", this.normalized);
			object.addProperty("usable", this.usable);
			object.addProperty("error", this.error);
			return object;
		}
	}

	/** Running counts for one bucket (recovery or preservation). */
	private static final class KindStats {
		private int attempted;
		private int resolved;
		private int exact;
		private int normalized;
		private int usable;
		private int errors;
		private final Map<EntryKind, int[]> byKind = new TreeMap<>();

		void add(TargetScore score) {
			this.attempted++;

			if (score.resolvedInIndex()) {
				this.resolved++;
			}

			int[] slot = this.byKind.computeIfAbsent(score.symbol().kind(), k -> new int[2]);
			slot[0]++;

			if (score.error() != null) {
				this.errors++;
			}

			if (score.exact() || score.normalized()) {
				this.exact += score.exact() ? 1 : 0;
				this.normalized += score.normalized() && !score.exact() ? 1 : 0;
				slot[1]++;
			}

			if (score.usable()) {
				this.usable++;
			}
		}

		void absorb(KindStats other) {
			this.attempted += other.attempted;
			this.resolved += other.resolved;
			this.exact += other.exact;
			this.normalized += other.normalized;
			this.usable += other.usable;
			this.errors += other.errors;
			other.byKind.forEach((kind, counts) -> {
				int[] slot = this.byKind.computeIfAbsent(kind, k -> new int[2]);
				slot[0] += counts[0];
				slot[1] += counts[1];
			});
		}

		String describe() {
			int matched = this.exact + this.normalized;
			return String.format("n=%d resolved=%d exact=%d norm=%d matched=%d usable=%d err=%d",
					this.attempted, this.resolved, this.exact, this.normalized, matched, this.usable, this.errors);
		}

		/**
		 * Preservation-bucket view: for a kept-unchanged symbol the only meaningful outcomes are
		 * {@code left_unchanged} (the model returned the real name verbatim — exact) and
		 * {@code unnecessary_rename} (it proposed anything else); errors are counted apart.
		 */
		String describePreservation() {
			int renamed = this.attempted - this.exact - this.errors;
			return String.format("n=%d resolved=%d left_unchanged=%d unnecessary_rename=%d err=%d",
					this.attempted, this.resolved, this.exact, renamed, this.errors);
		}

		void printByKind() {
			this.byKind.forEach((kind, counts) ->
					System.out.printf("  %-9s n=%d matched=%d%n", kind, counts[0], counts[1]));
		}
	}

	/** Per-jar aggregation over both buckets. */
	private static final class JarReport {
		private final String base;
		private final KindStats recovery = new KindStats();
		private final KindStats preservation = new KindStats();

		JarReport(String base) {
			this.base = base;
		}

		void add(TargetScore score, boolean preservationBucket) {
			if (preservationBucket) {
				this.preservation.add(score);
			} else {
				this.recovery.add(score);
			}
		}

		void absorb(JarReport other) {
			this.recovery.absorb(other.recovery);
			this.preservation.absorb(other.preservation);
		}

		String line(boolean online) {
			if (!online) {
				return String.format("%-28s recovery[n=%d resolved=%d] preservation[n=%d resolved=%d]",
						this.base, this.recovery.attempted, this.recovery.resolved,
						this.preservation.attempted, this.preservation.resolved);
			}

			String preservationPart = this.preservation.attempted > 0
					? "  preservation[" + this.preservation.describePreservation() + "]" : "";
			return String.format("%-28s recovery[%s]%s", this.base, this.recovery.describe(), preservationPart);
		}
	}
}
