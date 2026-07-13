package cuchaz.enigma.llm;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingUtil;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Builds the Minecraft rename-benchmark corpus directly from the (already Mojang-obfuscated) merged
 * client jar plus the Yarn / Mojmap / Parchment mappings. Unlike {@link ObfuscateCorpusTool} (which
 * re-obfuscates a NAMED jar and reflects over it), Minecraft is already obfuscated and cannot be
 * linked under a platform-only classloader (external Netty/LWJGL/Brigadier signatures), so this tool
 * enumerates members from ASM bytecode and derives ground truth from the mappings, joined on the raw
 * obf name (the same obf namespace intermediary/proguard use — the folder jar IS the yarn build's
 * merged jar).
 *
 * <p>Emits three ground-truth profiles over an IDENTICAL sampled population (so the harness scores
 * paired targets): {@code -yarn-groundtruth.jsonl} (realName = Yarn), {@code -mojmap-groundtruth.jsonl}
 * (realName = Mojmap / Parchment for params), {@code -union-groundtruth.jsonl} (realName = Yarn,
 * acceptable = union) for the lenient "usable" number. The raw obf jar is copied verbatim as
 * {@code -obf.jar} and string-scrubbed to {@code -obf-nostr.jar} (the structure-only track).
 *
 * <pre>args: &lt;mcJar&gt; &lt;intermediaryTiny&gt; &lt;yarnTinyOrMappingsDir&gt; &lt;proguardTxt&gt; &lt;parchmentZipOrJson&gt; &lt;outputDir&gt; [base]</pre>
 */
public final class MinecraftCorpusTool {
	private static final String OBFUSCATOR = "mojang";
	private static final long SEED = 1234567L;

	/**
	 * Universal contract names that are memorisable rather than recovered: JDK {@code Object},
	 * {@link Comparable}, {@link Iterable}/{@link java.util.Iterator}, {@link AutoCloseable} and the
	 * common functional-interface SAM names. Deliberately NARROW (Codex): we do not deny Collection-ish
	 * verbs like {@code get}/{@code add}/{@code size}, which Minecraft uses for genuinely recoverable
	 * concepts.
	 */
	private static final Set<String> CONTRACT_NAMES = Set.of(
			"toString", "equals", "hashCode", "compareTo", "iterator", "run", "call", "close",
			"accept", "apply", "clone", "finalize");

	// Sampling budget (final, pre-sampled in the builder; run the MC benchmark with keep-all bucket limits).
	private static final int CAP_PER_OWNER = 3;
	private static final int GROUP_FLOOR = 2;
	private static final int TARGET_API = 400;
	private static final int TARGET_PACKAGE = 150;
	private static final int TARGET_PRIVATE = 80;
	private static final int TARGET_PARAM = 200;
	private static final int PARAM_CAP_PER_METHOD = 2;
	private static final int PARAM_CAP_PER_OWNER = 4;

	private MinecraftCorpusTool() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 6) {
			System.err.println("usage: MinecraftCorpusTool <mcJar> <intermediaryTiny> <yarnTinyOrMappingsDir> <proguardTxt> <parchment> <outDir> [base]");
			System.exit(2);
			return;
		}

		Path mcJar = Path.of(args[0]);
		Path tiny = Path.of(args[1]);
		Path yarnMappings = Path.of(args[2]);
		Path proguard = Path.of(args[3]);
		Path parchment = Path.of(args[4]);
		Path outDir = Path.of(args[5]);
		String base = args.length > 6 ? args[6] : "minecraft-1.21.11";
		Files.createDirectories(outDir);

		MemoryMappingTree tree = buildTree(tiny, yarnMappings, proguard);
		int nsYarn = tree.getNamespaceId("yarn");
		int nsMoj = tree.getNamespaceId("mojmap");
		Parchment parch = Parchment.load(parchment);

		List<Candidate> candidates = collect(mcJar, tree, nsYarn, nsMoj, parch);
		System.out.println("scorable candidates: " + candidates.size());

		List<Candidate> sample = sample(candidates);
		System.out.println("sampled: " + sample.size() + "  " + distribution(sample));
		validate(sample);

		// Build the raw obf jar + string-scrubbed structure-only jar once, then hardlink into each profile dir.
		Path sharedObf = outDir.resolve(base + "-obf.jar");
		Files.copy(mcJar, sharedObf, StandardCopyOption.REPLACE_EXISTING);
		Path sharedNostr = outDir.resolve(base + "-obf-nostr.jar");
		StringScrubber.scrub(sharedObf, sharedNostr);

		// Each truth profile is emitted as its own corpus directory so the harness (which pairs
		// <stem>-obf.jar to <stem>-groundtruth.jsonl) finds a jar. The sampled population is pre-sampled
		// here and identical across profiles, so the three dirs differ only in the truth names. Run the
		// harness once per directory (the union dir gives the lenient "usable" number).
		for (Profile profile : Profile.values()) {
			Path dir = outDir.resolve(profile.name().toLowerCase(Locale.ROOT));
			Files.createDirectories(dir);
			writeProfile(dir.resolve(base + "-groundtruth.jsonl"), base, sample, profile);
			link(sharedObf, dir.resolve(base + "-obf.jar"));
			link(sharedNostr, dir.resolve(base + "-obf-nostr.jar"));
		}

		System.out.println("wrote 3 profile corpora (yarn/mojmap/union) under " + outDir);
	}

	// ------------------------------------------------------------------ enumeration

	private static List<Candidate> collect(Path mcJar, MappingTree tree, int nsYarn, int nsMoj, Parchment parch) throws IOException {
		Map<String, ClassNode> nodes = readNodes(mcJar);
		List<Candidate> out = new ArrayList<>();
		Map<String, Integer> dropped = new TreeMap<>();

		for (ClassNode cn : nodes.values()) {
			MappingTree.ClassMapping cm = tree.getClass(cn.name);
			String yOwner = cm == null ? null : cm.getDstName(nsYarn);
			String mOwner = cm == null ? null : cm.getDstName(nsMoj);

			boolean classNamed = yOwner != null && mOwner != null
					&& isRecoveredName(simpleName(yOwner)) && isRecoveredName(simpleName(mOwner));
			boolean classScored = classNamed && !isAnonymousOrLocal(cn.name)
					&& (cn.access & Opcodes.ACC_SYNTHETIC) == 0;

			if (classScored) {
				out.add(Candidate.forClass(cn, yOwner, mOwner));
			} else {
				dropped.merge("class", 1, Integer::sum);
			}

			if (!classScored) {
				continue;
			}

			for (MethodNode mn : cn.methods) {
				collectMethod(cn, mn, cm, yOwner, mOwner, nsYarn, nsMoj, parch, out, dropped);
			}

			for (FieldNode fn : cn.fields) {
				collectField(cn, fn, cm, yOwner, mOwner, nsYarn, nsMoj, out, dropped);
			}
		}

		System.out.println("drops: " + dropped);
		return out;
	}

	private static void collectMethod(ClassNode cn, MethodNode mn, MappingTree.ClassMapping cm,
			String yOwner, String mOwner, int nsYarn, int nsMoj, Parchment parch,
			List<Candidate> out, Map<String, Integer> dropped) {
		if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
			return;
		}

		if ((mn.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
			dropped.merge("method:synthetic/bridge", 1, Integer::sum);
			return;
		}

		MappingTree.MethodMapping mm = cm.getMethod(mn.name, mn.desc);
		String yName = mm == null ? null : mm.getDstName(nsYarn);
		String mName = mm == null ? null : mm.getDstName(nsMoj);

		if (!isRecoveredName(yName) || !isRecoveredName(mName)) {
			dropped.merge("method:not-in-both", 1, Integer::sum);
			return;
		}

		if ((cn.access & Opcodes.ACC_ENUM) != 0 && (yName.equals("values") || yName.equals("valueOf"))) {
			return;
		}

		if (CONTRACT_NAMES.contains(yName) || CONTRACT_NAMES.contains(mName)) {
			dropped.merge("method:contract-name", 1, Integer::sum);
			return;
		}

		out.add(Candidate.forMethod(cn, mn, yOwner, yName, mOwner, mName));

		// Parameters: one row per param that has a Yarn ARG name. localIndex = LVT slot (Yarn ARG /
		// Parchment / Enigma all agree). Validate the slot exists in the ASM-derived slot map.
		Set<Integer> validSlots = argumentSlots(mn.access, mn.desc);
		Map<Integer, String> parchParams = parch.params(mOwner, mName, mojDesc(mn.desc, cm, nsMoj));

		for (MappingTree.MethodArgMapping arg : mm.getArgs()) {
			String argYarn = arg.getDstName(nsYarn);
			int slot = arg.getLvIndex();
			String argParch = parchParams.get(slot);

			// Require BOTH a Yarn ARG name AND a Parchment (Mojmap-profile) name at this slot, so every
			// param row is emitted by all three profiles -> the sampled population stays identical.
			if (!isRecoveredName(argYarn) || !isRecoveredName(argParch) || !validSlots.contains(slot)) {
				continue;
			}

			out.add(Candidate.forParam(cn, mn, yOwner, slot, argYarn, mOwner, argParch));
		}
	}

	private static void collectField(ClassNode cn, FieldNode fn, MappingTree.ClassMapping cm,
			String yOwner, String mOwner, int nsYarn, int nsMoj, List<Candidate> out, Map<String, Integer> dropped) {
		if ((fn.access & Opcodes.ACC_SYNTHETIC) != 0) {
			dropped.merge("field:synthetic", 1, Integer::sum);
			return;
		}

		if ((cn.access & Opcodes.ACC_ENUM) != 0 && fn.name.equals("$VALUES")) {
			return;
		}

		MappingTree.FieldMapping fm = cm.getField(fn.name, fn.desc);
		String yName = fm == null ? null : fm.getDstName(nsYarn);
		String mName = fm == null ? null : fm.getDstName(nsMoj);

		if (!isRecoveredName(yName) || !isRecoveredName(mName)) {
			dropped.merge("field:not-in-both", 1, Integer::sum);
			return;
		}

		out.add(Candidate.forField(cn, fn, yOwner, yName, mOwner, mName));
	}

	// ------------------------------------------------------------------ sampling

	private static List<Candidate> sample(List<Candidate> all) {
		List<Candidate> classesApi = new ArrayList<>();
		List<Candidate> membersApi = new ArrayList<>();
		List<Candidate> membersPackage = new ArrayList<>();
		List<Candidate> membersPrivate = new ArrayList<>();
		List<Candidate> params = new ArrayList<>();

		for (Candidate c : all) {
			if (c.kind.equals("PARAMETER")) {
				params.add(c);
			} else if (c.kind.equals("CLASS")) {
				classesApi.add(c);
			} else if (c.slice.equals("api")) {
				membersApi.add(c);
			} else if (c.slice.equals("package")) {
				membersPackage.add(c);
			} else {
				membersPrivate.add(c);
			}
		}

		List<Candidate> picked = new ArrayList<>();
		// api target split between class names and public/protected members.
		picked.addAll(pick(classesApi, TARGET_API / 2, CAP_PER_OWNER));
		picked.addAll(pick(membersApi, TARGET_API - TARGET_API / 2, CAP_PER_OWNER));
		picked.addAll(pick(membersPackage, TARGET_PACKAGE, CAP_PER_OWNER));
		picked.addAll(pick(membersPrivate, TARGET_PRIVATE, CAP_PER_OWNER));
		picked.addAll(pickParams(params));
		return picked;
	}

	/**
	 * Coarse functional-unit stratified pick -- the sampling poll's compromise (Codex/Grok wanted
	 * recognizable subsystem strata since leaf-package depth is a Mojang artifact; Gemini/Claude wanted
	 * proportional-to-codebase weighting). Groups by {@link Candidate#topPackage()} (coarse functional
	 * unit); each group's quota is proportional to {@code sqrt(group size)} -- dampened so dense trees
	 * (world/level/block) don't dominate -- with a {@link #GROUP_FLOOR} so small subsystems still appear;
	 * the per-owner cap stops any single class dominating; leftovers top the sample up to {@code target}.
	 */
	private static List<Candidate> pick(List<Candidate> pool, int target, int perOwner) {
		if (pool.isEmpty() || target <= 0) {
			return new ArrayList<>();
		}

		Map<String, List<Candidate>> byGroup = new TreeMap<>();

		for (Candidate c : pool) {
			byGroup.computeIfAbsent(c.topPackage(), k -> new ArrayList<>()).add(c);
		}

		Random random = new Random(SEED ^ target);
		double weightSum = 0;

		for (List<Candidate> group : byGroup.values()) {
			Collections.shuffle(group, random);
			weightSum += Math.sqrt(group.size());
		}

		List<Candidate> result = new ArrayList<>();
		List<Candidate> leftover = new ArrayList<>();
		Map<String, Integer> ownerCount = new HashMap<>();

		for (List<Candidate> group : byGroup.values()) {
			int quota = Math.max(GROUP_FLOOR, (int) Math.round(target * Math.sqrt(group.size()) / weightSum));
			int taken = 0;

			for (Candidate c : group) {
				if (result.size() < target && taken < quota && ownerCount.getOrDefault(c.obfOwner, 0) < perOwner) {
					result.add(c);
					ownerCount.merge(c.obfOwner, 1, Integer::sum);
					taken++;
				} else {
					leftover.add(c);
				}
			}
		}

		// Rounding + per-owner caps can leave us short; top up from the leftovers, still capped per owner.
		Collections.shuffle(leftover, random);

		for (Candidate c : leftover) {
			if (result.size() >= target) {
				break;
			}

			if (ownerCount.getOrDefault(c.obfOwner, 0) < perOwner) {
				result.add(c);
				ownerCount.merge(c.obfOwner, 1, Integer::sum);
			}
		}

		return result;
	}

	private static List<Candidate> pickParams(List<Candidate> pool) {
		Random random = new Random(SEED ^ 0x9E3779B97F4A7C15L);
		List<Candidate> shuffled = new ArrayList<>(pool);
		Collections.shuffle(shuffled, random);
		Map<String, Integer> perMethod = new HashMap<>();
		Map<String, Integer> perOwner = new HashMap<>();
		List<Candidate> result = new ArrayList<>();

		for (Candidate c : shuffled) {
			if (result.size() >= TARGET_PARAM) {
				break;
			}

			String method = c.obfOwner + "#" + c.obfName + c.obfDesc;

			if (perMethod.getOrDefault(method, 0) >= PARAM_CAP_PER_METHOD
					|| perOwner.getOrDefault(c.obfOwner, 0) >= PARAM_CAP_PER_OWNER) {
				continue;
			}

			result.add(c);
			perMethod.merge(method, 1, Integer::sum);
			perOwner.merge(c.obfOwner, 1, Integer::sum);
		}

		return result;
	}

	// ------------------------------------------------------------------ output

	private enum Profile { YARN, MOJMAP, UNION }

	private static void writeProfile(Path file, String base, List<Candidate> sample, Profile profile) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			for (Candidate c : sample) {
				String yarn = c.yarnName;
				String mojmap = c.mojmapName;
				String primary;
				JsonArray acceptable = new JsonArray();

				switch (profile) {
				case YARN -> {
					primary = yarn;

					if (yarn != null) {
						acceptable.add(yarn);
					}
				}
				case MOJMAP -> {
					primary = mojmap;

					if (mojmap != null) {
						acceptable.add(mojmap);
					}
				}
				default -> {
					primary = yarn != null ? yarn : mojmap;

					if (yarn != null) {
						acceptable.add(yarn);
					}

					if (mojmap != null && !mojmap.equals(yarn)) {
						acceptable.add(mojmap);
					}
				}
				}

				if (primary == null) {
					continue; // mojmap profile skips params with no Parchment name
				}

				JsonObject o = new JsonObject();
				o.addProperty("obfuscator", OBFUSCATOR);
				o.addProperty("sourceJar", base + ".jar");
				o.addProperty("kind", c.kind);
				o.addProperty("obfOwner", c.obfOwner);
				o.addProperty("obfName", c.obfName);
				o.addProperty("obfDesc", c.obfDesc);
				o.addProperty("realOwner", profile == Profile.MOJMAP ? c.mojmapOwner : c.yarnOwner);
				o.addProperty("realName", primary);
				o.add("acceptableRealNames", acceptable);
				o.addProperty("visibility", c.visibility);
				o.addProperty("slice", c.slice);
				o.addProperty("recoverableSlice", c.slice.equals("api"));
				o.addProperty("obfuscated", true);
				o.addProperty("localIndex", c.localIndex);
				o.addProperty("localName", "");
				writer.write(o.toString());
				writer.write('\n');
			}
		}
	}

	// ------------------------------------------------------------------ mapping tree

	static MemoryMappingTree buildTree(Path tiny, Path yarnMappings, Path proguard) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingReader.read(tiny, tree); // official -> intermediary

		Map<String, String> renameYarn = new HashMap<>();
		renameYarn.put("intermediary", "intermediary");
		renameYarn.put("named", "yarn");
		renameYarn.put(MappingUtil.NS_SOURCE_FALLBACK, "intermediary");
		renameYarn.put(MappingUtil.NS_TARGET_FALLBACK, "yarn");

		// Fabric's published Yarn v2 artifact uses intermediary -> named, so merge it through the
		// intermediary namespace already present in the official -> intermediary tree.
		if (Files.isDirectory(yarnMappings)) {
			MappingReader.read(yarnMappings, MappingFormat.ENIGMA_DIR,
					new MappingNsRenamer(new MappingSourceNsSwitch(tree, "intermediary"), renameYarn));
		} else {
			MappingReader.read(yarnMappings,
					new MappingNsRenamer(new MappingSourceNsSwitch(tree, "intermediary"), renameYarn));
		}

		MemoryMappingTree pg = new MemoryMappingTree();
		MappingReader.read(proguard, pg); // mojmap(source) -> obf(target)
		Map<String, String> renameMoj = new HashMap<>();
		renameMoj.put(pg.getSrcNamespace(), "mojmap");
		renameMoj.put("target", "official");
		pg.accept(new MappingNsRenamer(new MappingSourceNsSwitch(tree, "official"), renameMoj));
		return tree;
	}

	private static Map<String, ClassNode> readNodes(Path jar) throws IOException {
		Map<String, ClassNode> nodes = new HashMap<>();

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();

				if (e.isDirectory() || !e.getName().endsWith(".class") || e.getName().endsWith("module-info.class")) {
					continue;
				}

				try (InputStream in = zip.getInputStream(e)) {
					ClassNode cn = new ClassNode();
					new ClassReader(in).accept(cn, ClassReader.SKIP_CODE);
					nodes.put(cn.name, cn);
				}
			}
		}

		return nodes;
	}

	// ------------------------------------------------------------------ helpers

	private static final java.util.regex.Pattern VALID_IDENTIFIER =
			java.util.regex.Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");
	/** Intermediary / SRG auto-generated placeholders that are NOT recovered human names. */
	private static final java.util.regex.Pattern PLACEHOLDER_NAME =
			java.util.regex.Pattern.compile("^(class_|method_|field_|comp_|p_|m_|f_|C_)\\d.*");

	/**
	 * True if {@code name} is a genuine recovered human identifier: non-blank, a valid Java identifier,
	 * and not an intermediary/SRG placeholder like {@code method_34872} / {@code comp_1234} / {@code p_49}.
	 * Guards against evaluating the model on recovering an auto-generated id instead of a real name.
	 */
	private static boolean isRecoveredName(String name) {
		return name != null && !name.isBlank()
				&& VALID_IDENTIFIER.matcher(name).matches()
				&& !PLACEHOLDER_NAME.matcher(name).matches();
	}

	private static String simpleName(String internal) {
		int cut = Math.max(internal.lastIndexOf('/'), internal.lastIndexOf('$'));
		return cut >= 0 ? internal.substring(cut + 1) : internal;
	}

	/** Hardlink {@code source} to {@code target} (the profile dirs share one obf jar); fall back to a copy. */
	private static void link(Path source, Path target) throws IOException {
		Files.deleteIfExists(target);

		try {
			Files.createLink(target, source);
		} catch (IOException | UnsupportedOperationException fallback) {
			Files.copy(source, target);
		}
	}

	/** True if any {@code $}-separated segment starts with a digit (anonymous {@code $1} or local {@code $1Foo}). */
	private static boolean isAnonymousOrLocal(String internalName) {
		for (String segment : internalName.split("\\$")) {
			if (!segment.isEmpty() && Character.isDigit(segment.charAt(0))) {
				return true;
			}
		}

		return false;
	}

	/** The set of valid JVM local-variable slots for the method's parameters (this=0 if instance, wide types skip). */
	private static Set<Integer> argumentSlots(int access, String desc) {
		Set<Integer> slots = new java.util.HashSet<>();
		int slot = Modifier.isStatic(access) ? 0 : 1;

		for (Type t : Type.getArgumentTypes(desc)) {
			slots.add(slot);
			slot += t.getSize();
		}

		return slots;
	}

	/** Remap an obf method descriptor to the mojmap namespace (for Parchment lookup, which is keyed on Mojmap coords). */
	private static String mojDesc(String obfDesc, MappingTree.ClassMapping ownerClass, int nsMoj) {
		MappingTree tree = ownerClass.getTree();
		StringBuilder sb = new StringBuilder("(");

		for (Type t : Type.getArgumentTypes(obfDesc)) {
			sb.append(remapType(t, tree, nsMoj));
		}

		sb.append(')').append(remapType(Type.getReturnType(obfDesc), tree, nsMoj));
		return sb.toString();
	}

	private static String remapType(Type t, MappingTree tree, int ns) {
		switch (t.getSort()) {
		case Type.OBJECT -> {
			MappingTree.ClassMapping cm = tree.getClass(t.getInternalName());
			String mapped = cm == null ? null : cm.getDstName(ns);
			return mapped == null ? t.getDescriptor() : "L" + mapped + ";";
		}
		case Type.ARRAY -> {
			StringBuilder sb = new StringBuilder();

			for (int i = 0; i < t.getDimensions(); i++) {
				sb.append('[');
			}

			return sb.append(remapType(t.getElementType(), tree, ns)).toString();
		}
		default -> {
			return t.getDescriptor();
		}
		}
	}

	/**
	 * Hard pre-write garbage-checks (Codex): the emitted target key set must be unique (including the
	 * param slot), every PARAMETER must carry a real slot ({@code >= 0}) and every non-param must carry
	 * {@code -1}, and no truth name may be blank. Aborts the build rather than emit unscorable rows.
	 */
	private static void validate(List<Candidate> sample) {
		Set<String> keys = new java.util.HashSet<>();

		for (Candidate c : sample) {
			String key = c.kind + "\0" + c.obfOwner + "\0" + c.obfName + "\0" + c.obfDesc + "\0" + c.localIndex;

			if (!keys.add(key)) {
				throw new IllegalStateException("duplicate target key: " + key);
			}

			boolean isParam = c.kind.equals("PARAMETER");

			if (isParam && c.localIndex < 0) {
				throw new IllegalStateException("PARAMETER without a slot: " + key);
			}

			if (!isParam && c.localIndex != -1) {
				throw new IllegalStateException("non-param with a slot: " + key);
			}

			if (c.yarnName == null || c.yarnName.isBlank() || c.mojmapName == null || c.mojmapName.isBlank()) {
				throw new IllegalStateException("blank truth name: " + key);
			}
		}
	}

	private static String distribution(List<Candidate> sample) {
		Map<String, Integer> byKind = new TreeMap<>();
		Map<String, Integer> bySlice = new TreeMap<>();

		for (Candidate c : sample) {
			byKind.merge(c.kind, 1, Integer::sum);
			bySlice.merge(c.slice, 1, Integer::sum);
		}

		return "kind=" + byKind + " slice=" + bySlice;
	}

	// ------------------------------------------------------------------ records

	/** One scorable candidate before sampling; carries obf coords + both truth names. */
	private static final class Candidate {
		final String kind;
		final String obfOwner;
		final String obfName;
		final String obfDesc;
		final int localIndex;
		final String yarnOwner;
		final String yarnName;
		final String mojmapOwner;
		final String mojmapName;
		final String visibility;
		final String slice;

		private Candidate(String kind, String obfOwner, String obfName, String obfDesc, int localIndex,
				String yarnOwner, String yarnName, String mojmapOwner, String mojmapName, int access, boolean isClass) {
			this.kind = kind;
			this.obfOwner = obfOwner;
			this.obfName = obfName;
			this.obfDesc = obfDesc;
			this.localIndex = localIndex;
			this.yarnOwner = yarnOwner;
			this.yarnName = yarnName;
			this.mojmapOwner = mojmapOwner;
			this.mojmapName = mojmapName;
			this.visibility = visibility(access);
			this.slice = slice(kind, access);
		}

		static Candidate forClass(ClassNode cn, String yOwner, String mOwner) {
			return new Candidate("CLASS", cn.name, simpleName(cn.name), "", -1, yOwner, simpleName(yOwner), mOwner, simpleName(mOwner), cn.access, true);
		}

		static Candidate forMethod(ClassNode cn, MethodNode mn, String yOwner, String yName, String mOwner, String mName) {
			return new Candidate("METHOD", cn.name, mn.name, mn.desc, -1, yOwner, yName, mOwner, mName, mn.access, false);
		}

		static Candidate forField(ClassNode cn, FieldNode fn, String yOwner, String yName, String mOwner, String mName) {
			return new Candidate("FIELD", cn.name, fn.name, fn.desc, -1, yOwner, yName, mOwner, mName, fn.access, false);
		}

		static Candidate forParam(ClassNode cn, MethodNode mn, String yOwner, int slot,
				String yarnArg, String mOwner, String parchArg) {
			return new Candidate("PARAMETER", cn.name, mn.name, mn.desc, slot, yOwner, yarnArg, mOwner, parchArg, mn.access, false);
		}

		/**
		 * The coarse functional unit to spread the sample across. Bipartite intersection of BOTH
		 * human-curated taxonomies: {@code yarnCoarse::mojmapCoarse}, each the first up-to-3 package
		 * segments (e.g. {@code net/minecraft/world}). Minecraft's obf jar is flat, so the only package
		 * structure lives in the deobfuscated mappings; intersecting Yarn's community taxonomy with
		 * Mojang's official one means a unit stays whole when both agree and splits when they diverge, so
		 * the sample is representative of the structure BOTH ground truths recognise.
		 */
		String topPackage() {
			return coarse(yarnOwner != null ? yarnOwner : obfOwner)
					+ "::" + coarse(mojmapOwner != null ? mojmapOwner : obfOwner);
		}

		private static String coarse(String owner) {
			String[] segments = owner.split("/");
			int depth = Math.min(3, segments.length - 1);

			if (depth <= 0) {
				return owner;
			}

			StringBuilder sb = new StringBuilder(segments[0]);

			for (int i = 1; i < depth; i++) {
				sb.append('/').append(segments[i]);
			}

			return sb.toString();
		}

		private static String slice(String kind, int access) {
			if (kind.equals("CLASS") || Modifier.isPublic(access) || Modifier.isProtected(access)) {
				return "api";
			} else if (Modifier.isPrivate(access)) {
				return "private";
			}

			return "package";
		}

		private static String visibility(int access) {
			if (Modifier.isPublic(access)) {
				return "public";
			} else if (Modifier.isProtected(access)) {
				return "protected";
			} else if (Modifier.isPrivate(access)) {
				return "private";
			}

			return "package";
		}

		private static String simpleName(String internal) {
			int cut = Math.max(internal.lastIndexOf('/'), internal.lastIndexOf('$'));
			return cut >= 0 ? internal.substring(cut + 1) : internal;
		}
	}

	/** Parchment parameter names, keyed on Mojmap (official) method identity; supplies params the Mojmap profile lacks. */
	private static final class Parchment {
		private final Map<String, Map<Integer, String>> byMethod;

		private Parchment(Map<String, Map<Integer, String>> byMethod) {
			this.byMethod = byMethod;
		}

		static Parchment load(Path path) throws IOException {
			Map<String, Map<Integer, String>> map = new HashMap<>();
			String json = readParchmentJson(path);
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();

			if (root.has("classes")) {
				for (JsonElement ce : root.getAsJsonArray("classes")) {
					JsonObject cls = ce.getAsJsonObject();
					String owner = cls.get("name").getAsString();

					if (!cls.has("methods")) {
						continue;
					}

					for (JsonElement me : cls.getAsJsonArray("methods")) {
						JsonObject m = me.getAsJsonObject();

						if (!m.has("parameters")) {
							continue;
						}

						Map<Integer, String> params = new HashMap<>();

						for (JsonElement pe : m.getAsJsonArray("parameters")) {
							JsonObject p = pe.getAsJsonObject();

							if (p.has("name")) {
								params.put(p.get("index").getAsInt(), p.get("name").getAsString());
							}
						}

						if (!params.isEmpty()) {
							map.put(key(owner, m.get("name").getAsString(), m.get("descriptor").getAsString()), params);
						}
					}
				}
			}

			return new Parchment(map);
		}

		Map<Integer, String> params(String mojOwner, String mojName, String mojDesc) {
			return byMethod.getOrDefault(key(mojOwner, mojName, mojDesc), Map.of());
		}

		private static String key(String owner, String name, String desc) {
			return owner + "#" + name + desc;
		}

		private static String readParchmentJson(Path path) throws IOException {
			if (path.getFileName().toString().endsWith(".json")) {
				return Files.readString(path);
			}

			try (ZipFile zip = new ZipFile(path.toFile())) {
				Enumeration<? extends ZipEntry> entries = zip.entries();

				while (entries.hasMoreElements()) {
					ZipEntry e = entries.nextElement();

					if (e.getName().endsWith(".json")) {
						try (InputStream in = zip.getInputStream(e)) {
							return new String(in.readAllBytes(), StandardCharsets.UTF_8);
						}
					}
				}
			}

			throw new IOException("no .json in Parchment archive " + path);
		}
	}
}
