package cuchaz.enigma.llm;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

/**
 * Builds the "fresh Minecraft" rename-benchmark corpus: a memorization control whose scored targets are
 * classes that appeared only AFTER the frontier models' training cutoff, so a correct recovery cannot be
 * rote recall of a name seen in training.
 *
 * <p>Modern Minecraft (26.x) is shipped de-obfuscated by Mojang: the official client jar carries real
 * human names. To turn it into a rename benchmark we obfuscate it ourselves, exactly like the other
 * obscure corpora ({@link ObfuscateCorpusTool}): tiny-remapper applies a synthesized {@code official->obf}
 * map (compact {@code mc/C{i}} / {@code m{i}} / {@code f{i}} tokens) and propagates method renames across
 * override groups. The obf names are keyed on the community intermediary token, so every override group
 * gets one token and the jar stays self-consistent; members with no intermediary token (external contract
 * overrides such as {@code run}/{@code close}) are left un-renamed, which is both correct for jar validity
 * and correct for scoring (those names are memorisable, not recovered). Debug data is stripped afterwards
 * so no parameter or local names leak.
 *
 * <p>Ground truth joins on the mapping tree: {@code official} (Mojang's real name) is the primary "mojmap"
 * truth and {@code named} (RelativityMC Yarn) is the acceptable alternative, mirroring
 * {@link MinecraftCorpusTool}. Mojang publishes no parameter names in its proguard mapping, but the
 * official client jar carries real parameter names in its {@code LocalVariableTable}; those supply the
 * official PARAMETER truth (Yarn's tiny {@code p} rows supply the Yarn truth). Three profiles are emitted
 * over the SAME sampled population ({@code mojmap}/{@code yarn}/{@code union}) so the harness scores paired
 * targets.
 *
 * <p>All inputs are downloadable: the official client jar (Mojang), the merged {@code official/intermediary
 * /named} tiny and the baseline merged tiny (RelativityMC's public codemc maven). The baseline is the merged
 * yarn of the previous release (not the pure intermediary tiny): both sides must share the same class-inclusion
 * rules, otherwise non-obfuscated {@code com/mojang/*} classes present only in the merged artifact would be
 * mis-flagged as new. No local RelativityMC build is required.
 *
 * <pre>args: &lt;officialJar&gt; &lt;mergedTinyV2&gt; &lt;baselineTiny&gt; &lt;outDir&gt; [base]</pre>
 */
public final class MinecraftFreshCorpusTool {
	private static final String OBFUSCATOR = "fresh-mc-tinyremap";
	private static final long SEED = 1234567L;
	private static final String OBF_PACKAGE = "mc/";

	/** Same narrow universal-contract deny-list as {@link MinecraftCorpusTool}: memorisable, not recovered. */
	private static final Set<String> CONTRACT_NAMES = Set.of(
			"toString", "equals", "hashCode", "compareTo", "iterator", "run", "call", "close",
			"accept", "apply", "clone", "finalize");

	private static final int CAP_PER_OWNER = 3;
	private static final int GROUP_FLOOR = 2;
	private static final int TARGET_API = 400;
	private static final int TARGET_PACKAGE = 150;
	private static final int TARGET_PRIVATE = 80;
	private static final int TARGET_PARAM = 200;
	private static final int PARAM_CAP_PER_METHOD = 2;
	private static final int PARAM_CAP_PER_OWNER = 4;

	private MinecraftFreshCorpusTool() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 4) {
			System.err.println("usage: MinecraftFreshCorpusTool <officialJar> <mergedTinyV2> <baselineTiny> <outDir> [base]");
			System.exit(2);
			return;
		}

		Path officialJar = Path.of(args[0]);
		Path mergedTiny = Path.of(args[1]);
		Path baselineTiny = Path.of(args[2]);
		Path outDir = Path.of(args[3]);
		String base = args.length > 4 ? args[4] : "minecraft-fresh";
		Files.createDirectories(outDir);

		MemoryMappingTree tree = new MemoryMappingTree();
		MappingReader.read(mergedTiny, tree);
		int nsInter = tree.getNamespaceId("intermediary");
		int nsNamed = tree.getNamespaceId("named");

		if (nsInter < 0 || nsNamed < 0) {
			throw new IOException("merged tiny must carry official/intermediary/named namespaces");
		}

		// NEW-since-baseline: official class names present now but absent in the baseline version.
		Set<String> newOfficial = classSrcNames(tree);
		newOfficial.removeAll(classSrcNames(readTree(baselineTiny)));
		System.out.println("new official classes since baseline: " + newOfficial.size());

		ObfMap obf = ObfMap.build(officialJar, tree, nsInter);
		System.out.println("obf tokens: classes=" + obf.classMap.size()
				+ " methods=" + obf.methodTokenByInter.size() + " fields=" + obf.fieldTokenByInter.size());

		List<Candidate> candidates = collect(officialJar, tree, nsInter, nsNamed, obf, newOfficial);
		System.out.println("scorable candidates (new classes only): " + candidates.size());

		List<Candidate> sample = sample(candidates);
		System.out.println("sampled: " + sample.size() + "  " + distribution(sample));
		validate(sample);

		Path sharedObf = outDir.resolve(base + "-obf.jar");
		obf.apply(officialJar, sharedObf);
		DebugStripper.strip(sharedObf);
		Path sharedNostr = outDir.resolve(base + "-obf-nostr.jar");
		StringScrubber.scrub(sharedObf, sharedNostr);

		for (Profile profile : Profile.values()) {
			Path dir = outDir.resolve(profile.name().toLowerCase(Locale.ROOT));
			Files.createDirectories(dir);
			writeProfile(dir.resolve(base + "-groundtruth.jsonl"), base, sample, profile);
			link(sharedObf, dir.resolve(base + "-obf.jar"));
			link(sharedNostr, dir.resolve(base + "-obf-nostr.jar"));
		}

		System.out.println("wrote 3 profile corpora (mojmap/yarn/union) under " + outDir);
	}

	// ------------------------------------------------------------------ mapping tree helpers

	private static MemoryMappingTree readTree(Path tiny) throws IOException {
		MemoryMappingTree tree = new MemoryMappingTree();
		MappingReader.read(tiny, tree);
		return tree;
	}

	/** Source-namespace (official) class internal names of a tree; works for tiny v1 and v2. */
	private static Set<String> classSrcNames(MappingTree tree) {
		Set<String> out = new HashSet<>();

		for (MappingTree.ClassMapping cm : tree.getClasses()) {
			if (cm.getSrcName() != null) {
				out.add(cm.getSrcName());
			}
		}

		return out;
	}

	// ------------------------------------------------------------------ obf mapping (official -> compact tokens)

	/**
	 * The synthesized {@code official->obf} rename, plus its tiny-remapper application. Classes flatten into
	 * a single {@code mc/} package as {@code mc/C{i}} (nested keep their {@code $}-nesting); method/field
	 * obf tokens are keyed on the intermediary token so every override group renames identically. Every jar
	 * class gets a token (so no real class name leaks); only intermediary-mapped members are renamed (so
	 * external contract overrides stay valid).
	 */
	private static final class ObfMap {
		final Map<String, String> classMap;              // official internal -> obf internal
		final Map<String, String> methodTokenByInter;    // intermediary method token -> m{i}
		final Map<String, String> fieldTokenByInter;     // intermediary field token -> f{i}
		private final List<Mapping> mappings;

		private ObfMap(Map<String, String> classMap, Map<String, String> methodTokenByInter,
				Map<String, String> fieldTokenByInter, List<Mapping> mappings) {
			this.classMap = classMap;
			this.methodTokenByInter = methodTokenByInter;
			this.fieldTokenByInter = fieldTokenByInter;
			this.mappings = mappings;
		}

		static ObfMap build(Path officialJar, MappingTree tree, int nsInter) throws IOException {
			// Every class in the jar gets an obf name (leak-proof), tree-mapped or not; shallowest first so a
			// nested class's outer is always resolved before it.
			List<String> classes = new ArrayList<>(jarClassNames(officialJar));
			classes.sort(Comparator
					.comparingInt((String s) -> (int) s.chars().filter(ch -> ch == '$').count())
					.thenComparing(Comparator.naturalOrder()));

			Map<String, String> classMap = new LinkedHashMap<>();
			int topCounter = 0;
			int nestedCounter = 0;

			for (String internal : classes) {
				if (internal.equals("module-info") || internal.endsWith("/module-info")) {
					continue;
				}

				int dollar = internal.lastIndexOf('$');

				if (dollar < 0) {
					classMap.put(internal, OBF_PACKAGE + "C" + topCounter++);
				} else {
					String outer = internal.substring(0, dollar);
					String outerObf = classMap.getOrDefault(outer, OBF_PACKAGE + "COuter" + nestedCounter);
					classMap.put(internal, outerObf + "$C" + nestedCounter++);
				}
			}

			// Member obf tokens: one dense token per distinct intermediary token, in sorted order.
			Set<String> methodTokens = new TreeSet<>();
			Set<String> fieldTokens = new TreeSet<>();

			for (MappingTree.ClassMapping cm : tree.getClasses()) {
				for (MappingTree.MethodMapping mm : cm.getMethods()) {
					if (mm.getDstName(nsInter) != null) {
						methodTokens.add(mm.getDstName(nsInter));
					}
				}

				for (MappingTree.FieldMapping fm : cm.getFields()) {
					if (fm.getDstName(nsInter) != null) {
						fieldTokens.add(fm.getDstName(nsInter));
					}
				}
			}

			Map<String, String> methodTokenByInter = new LinkedHashMap<>();
			int m = 0;

			for (String token : methodTokens) {
				methodTokenByInter.put(token, "m" + m++);
			}

			Map<String, String> fieldTokenByInter = new LinkedHashMap<>();
			int f = 0;

			for (String token : fieldTokens) {
				fieldTokenByInter.put(token, "f" + f++);
			}

			// Build the tiny-remapper mapping list from the tree, keyed on official coordinates.
			List<Mapping> mappings = new ArrayList<>();

			for (Map.Entry<String, String> e : classMap.entrySet()) {
				mappings.add(Mapping.forClass(e.getKey(), e.getValue()));
			}

			for (MappingTree.ClassMapping cm : tree.getClasses()) {
				String owner = cm.getSrcName();

				if (owner == null) {
					continue;
				}

				for (MappingTree.MethodMapping mm : cm.getMethods()) {
					String token = methodTokenByInter.get(mm.getDstName(nsInter));

					if (token != null && mm.getSrcName() != null && mm.getSrcDesc() != null) {
						mappings.add(Mapping.forMethod(owner, mm.getSrcName(), mm.getSrcDesc(), token));
					}
				}

				for (MappingTree.FieldMapping fm : cm.getFields()) {
					String token = fieldTokenByInter.get(fm.getDstName(nsInter));

					if (token != null && fm.getSrcName() != null && fm.getSrcDesc() != null) {
						mappings.add(Mapping.forField(owner, fm.getSrcName(), fm.getSrcDesc(), token));
					}
				}
			}

			return new ObfMap(classMap, methodTokenByInter, fieldTokenByInter, mappings);
		}

		/** obf descriptor for a member, remapping object types through the class map (external types kept). */
		String remapDesc(String desc) {
			return remap(Type.getType(desc)).getDescriptor();
		}

		String remapMethodDesc(String desc) {
			Type type = Type.getMethodType(desc);
			StringBuilder sb = new StringBuilder("(");

			for (Type arg : type.getArgumentTypes()) {
				sb.append(remap(arg).getDescriptor());
			}

			return sb.append(')').append(remap(type.getReturnType()).getDescriptor()).toString();
		}

		private Type remap(Type type) {
			switch (type.getSort()) {
			case Type.OBJECT -> {
				String obf = classMap.get(type.getInternalName());
				return obf == null ? type : Type.getObjectType(obf);
			}
			case Type.ARRAY -> {
				StringBuilder sb = new StringBuilder();

				for (int i = 0; i < type.getDimensions(); i++) {
					sb.append('[');
				}

				return Type.getType(sb.append(remap(type.getElementType()).getDescriptor()).toString());
			}
			default -> {
				return type;
			}
			}
		}

		void apply(Path inputJar, Path outputJar) throws IOException {
			IMappingProvider provider = acceptor -> {
				for (Mapping mapping : mappings) {
					mapping.acceptInto(acceptor);
				}
			};

			TinyRemapper remapper = TinyRemapper.newRemapper()
					.withMappings(provider)
					.ignoreConflicts(true)
					.threads(1)
					.build();

			Files.deleteIfExists(outputJar);

			if (outputJar.getParent() != null) {
				Files.createDirectories(outputJar.getParent());
			}

			try (OutputConsumerPath output = new OutputConsumerPath.Builder(outputJar).assumeArchive(true).build()) {
				remapper.readInputs(inputJar);
				output.addNonClassFiles(inputJar, NonClassCopyMode.FIX_META_INF, remapper);
				remapper.apply(output);
			} finally {
				remapper.finish();
			}
		}

		private static Set<String> jarClassNames(Path jar) throws IOException {
			Set<String> out = new HashSet<>();

			try (ZipFile zip = new ZipFile(jar.toFile())) {
				Enumeration<? extends ZipEntry> entries = zip.entries();

				while (entries.hasMoreElements()) {
					ZipEntry e = entries.nextElement();

					if (!e.isDirectory() && e.getName().endsWith(".class")) {
						out.add(e.getName().substring(0, e.getName().length() - ".class".length()));
					}
				}
			}

			return out;
		}
	}

	/** A single rename fed to tiny-remapper, keyed on the original (official) identity. */
	private record Mapping(Kind kind, String owner, String name, String desc, String token) {
		private enum Kind { CLASS, METHOD, FIELD }

		static Mapping forClass(String internalName, String token) {
			return new Mapping(Kind.CLASS, internalName, "", "", token);
		}

		static Mapping forMethod(String owner, String name, String desc, String token) {
			return new Mapping(Kind.METHOD, owner, name, desc, token);
		}

		static Mapping forField(String owner, String name, String desc, String token) {
			return new Mapping(Kind.FIELD, owner, name, desc, token);
		}

		void acceptInto(IMappingProvider.MappingAcceptor acceptor) {
			switch (kind) {
			case CLASS -> acceptor.acceptClass(owner, token);
			case METHOD -> acceptor.acceptMethod(new IMappingProvider.Member(owner, name, desc), token);
			case FIELD -> acceptor.acceptField(new IMappingProvider.Member(owner, name, desc), token);
			default -> throw new IllegalStateException();
			}
		}
	}

	// ------------------------------------------------------------------ enumeration (over new classes only)

	private static List<Candidate> collect(Path officialJar, MappingTree tree, int nsInter, int nsNamed,
			ObfMap obf, Set<String> newOfficial) throws IOException {
		List<Candidate> out = new ArrayList<>();
		Map<String, Integer> dropped = new TreeMap<>();

		try (ZipFile zip = new ZipFile(officialJar.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();

				if (e.isDirectory() || !e.getName().endsWith(".class")) {
					continue;
				}

				String internal = e.getName().substring(0, e.getName().length() - ".class".length());

				if (!newOfficial.contains(internal)) {
					continue;
				}

				try (InputStream in = zip.getInputStream(e)) {
					ClassNode cn = new ClassNode();
					new ClassReader(in).accept(cn, 0);
					collectClass(cn, tree, nsInter, nsNamed, obf, out, dropped);
				}
			}
		}

		System.out.println("drops: " + dropped);
		return out;
	}

	private static void collectClass(ClassNode cn, MappingTree tree, int nsInter, int nsNamed,
			ObfMap obf, List<Candidate> out, Map<String, Integer> dropped) {
		MappingTree.ClassMapping cm = tree.getClass(cn.name);
		String named = cm == null ? null : cm.getDstName(nsNamed);
		String obfOwner = obf.classMap.get(cn.name);

		boolean classNamed = isRecoveredName(simpleName(cn.name)) && named != null && isRecoveredName(simpleName(named));
		boolean classScored = classNamed && obfOwner != null && !isAnonymousOrLocal(cn.name)
				&& (cn.access & Opcodes.ACC_SYNTHETIC) == 0;

		if (!classScored) {
			return;
		}

		out.add(Candidate.forClass(cn, obfOwner, named));

		for (MethodNode mn : cn.methods) {
			collectMethod(cn, mn, cm, named, obf, nsInter, nsNamed, out, dropped);
		}

		for (FieldNode fn : cn.fields) {
			collectField(cn, fn, cm, named, obf, nsInter, nsNamed, out, dropped);
		}
	}

	private static void collectMethod(ClassNode cn, MethodNode mn, MappingTree.ClassMapping cm, String yarnOwner,
			ObfMap obf, int nsInter, int nsNamed, List<Candidate> out, Map<String, Integer> dropped) {
		if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
			return;
		}

		if ((mn.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
			dropped.merge("method:synthetic/bridge", 1, Integer::sum);
			return;
		}

		MappingTree.MethodMapping mm = cm.getMethod(mn.name, mn.desc);
		String inter = mm == null ? null : mm.getDstName(nsInter);
		String yarn = mm == null ? null : mm.getDstName(nsNamed);
		String obfName = inter == null ? null : obf.methodTokenByInter.get(inter);

		if (obfName == null || !isRecoveredName(mn.name) || !isRecoveredName(yarn)) {
			dropped.merge("method:not-recovered", 1, Integer::sum);
			return;
		}

		if ((cn.access & Opcodes.ACC_ENUM) != 0 && (mn.name.equals("values") || mn.name.equals("valueOf"))) {
			return;
		}

		if (CONTRACT_NAMES.contains(mn.name) || CONTRACT_NAMES.contains(yarn)) {
			dropped.merge("method:contract-name", 1, Integer::sum);
			return;
		}

		// Drop trivial method bodies. The frontier prompt pipeline (prepare_codex_prompt_batches.py) keeps
		// only "interesting" methods, judged from the decompiled body (branches / body-calls / statements);
		// thin getters/delegators/one-liners are filtered out. New-since-cutoff Minecraft classes (records,
		// data holders, advancement/registry scaffolding) are dominated by such thin methods, so an unfiltered
		// sample collapsed to ~7 survivors. Pre-filter here on a bytecode proxy so the sampled methods are the
		// ones that will actually survive that filter and yield a usable METHOD benchmark.
		if (!interestingBody(mn)) {
			dropped.merge("method:thin-body", 1, Integer::sum);
			return;
		}

		out.add(Candidate.forMethod(cn, mn, obf, yarnOwner, obfName, mn.name, yarn));

		// Parameters: require BOTH an official LVT name AND a Yarn arg name at the slot so every param row is
		// emitted by all three profiles and the sampled population stays identical.
		Map<Integer, String> officialArgs = lvtParams(mn);
		Map<Integer, String> yarnArgs = new HashMap<>();

		for (MappingTree.MethodArgMapping arg : mm.getArgs()) {
			String argYarn = arg.getDstName(nsNamed);

			if (argYarn != null && !argYarn.isBlank()) {
				yarnArgs.put(arg.getLvIndex(), argYarn);
			}
		}

		Set<Integer> validSlots = argumentSlots(mn.access, mn.desc);

		for (Map.Entry<Integer, String> entry : officialArgs.entrySet()) {
			int slot = entry.getKey();
			String officialArg = entry.getValue();
			String yarnArg = yarnArgs.get(slot);

			if (!validSlots.contains(slot) || !isRecoveredName(officialArg) || !isRecoveredName(yarnArg)) {
				continue;
			}

			out.add(Candidate.forParam(cn, mn, obf, yarnOwner, obfName, slot, officialArg, yarnArg));
		}
	}

	/**
	 * Bytecode proxy for {@code prepare_codex_prompt_batches.py}'s source-based "interesting method" test
	 * (kept if {@code branches>=1 || body_calls>=2 || statements>=4}). Deliberately a touch stricter than a
	 * literal translation so survivors reliably clear the decompiled-source filter downstream: keep if the
	 * body has any branch, three or more invocations, or a non-trivial instruction count.
	 */
	private static boolean interestingBody(MethodNode mn) {
		int branches = 0;
		int invokes = 0;
		int real = 0;

		for (AbstractInsnNode insn : mn.instructions) {
			int type = insn.getType();

			if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.LINE || type == AbstractInsnNode.FRAME) {
				continue;
			}

			real++;

			if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
				branches++;
			}

			int op = insn.getOpcode();

			if (op >= Opcodes.INVOKEVIRTUAL && op <= Opcodes.INVOKEDYNAMIC) {
				invokes++;
			}
		}

		return branches >= 1 || invokes >= 3 || real >= 14;
	}

	private static void collectField(ClassNode cn, FieldNode fn, MappingTree.ClassMapping cm, String yarnOwner,
			ObfMap obf, int nsInter, int nsNamed, List<Candidate> out, Map<String, Integer> dropped) {
		if ((fn.access & Opcodes.ACC_SYNTHETIC) != 0) {
			dropped.merge("field:synthetic", 1, Integer::sum);
			return;
		}

		if ((cn.access & Opcodes.ACC_ENUM) != 0 && fn.name.equals("$VALUES")) {
			return;
		}

		MappingTree.FieldMapping fm = cm.getField(fn.name, fn.desc);
		String inter = fm == null ? null : fm.getDstName(nsInter);
		String yarn = fm == null ? null : fm.getDstName(nsNamed);
		String obfName = inter == null ? null : obf.fieldTokenByInter.get(inter);

		if (obfName == null || !isRecoveredName(fn.name) || !isRecoveredName(yarn)) {
			dropped.merge("field:not-recovered", 1, Integer::sum);
			return;
		}

		out.add(Candidate.forField(cn, fn, obf, yarnOwner, obfName, fn.name, yarn));
	}

	/** slot -> real parameter name from the official LVT (first entry seen at each argument slot). */
	private static Map<Integer, String> lvtParams(MethodNode mn) {
		Map<Integer, String> out = new HashMap<>();

		if (mn.localVariables == null) {
			return out;
		}

		Set<Integer> slots = argumentSlots(mn.access, mn.desc);

		for (LocalVariableNode lv : mn.localVariables) {
			if (slots.contains(lv.index) && !lv.name.equals("this") && !out.containsKey(lv.index)) {
				out.put(lv.index, lv.name);
			}
		}

		return out;
	}

	// ------------------------------------------------------------------ sampling (mirrors MinecraftCorpusTool)

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
		picked.addAll(pick(classesApi, TARGET_API / 2, CAP_PER_OWNER));
		picked.addAll(pick(membersApi, TARGET_API - TARGET_API / 2, CAP_PER_OWNER));
		picked.addAll(pick(membersPackage, TARGET_PACKAGE, CAP_PER_OWNER));
		picked.addAll(pick(membersPrivate, TARGET_PRIVATE, CAP_PER_OWNER));
		picked.addAll(pickParams(params));
		return picked;
	}

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

	private enum Profile { MOJMAP, YARN, UNION }

	private static void writeProfile(Path file, String base, List<Candidate> sample, Profile profile) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			for (Candidate c : sample) {
				String official = c.officialName;
				String yarn = c.yarnName;
				String primary;
				JsonArray acceptable = new JsonArray();

				switch (profile) {
				case MOJMAP -> {
					primary = official;

					if (official != null) {
						acceptable.add(official);
					}
				}
				case YARN -> {
					primary = yarn;

					if (yarn != null) {
						acceptable.add(yarn);
					}
				}
				default -> {
					primary = official != null ? official : yarn;

					if (official != null) {
						acceptable.add(official);
					}

					if (yarn != null && !yarn.equals(official)) {
						acceptable.add(yarn);
					}
				}
				}

				if (primary == null) {
					continue;
				}

				JsonObject o = new JsonObject();
				o.addProperty("obfuscator", OBFUSCATOR);
				o.addProperty("sourceJar", base + ".jar");
				o.addProperty("kind", c.kind);
				o.addProperty("obfOwner", c.obfOwner);
				o.addProperty("obfName", c.obfName);
				o.addProperty("obfDesc", c.obfDesc);
				o.addProperty("realOwner", profile == Profile.YARN ? c.yarnOwner : c.officialOwner);
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

	// ------------------------------------------------------------------ helpers (mirror MinecraftCorpusTool)

	private static final java.util.regex.Pattern VALID_IDENTIFIER =
			java.util.regex.Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");
	private static final java.util.regex.Pattern PLACEHOLDER_NAME =
			java.util.regex.Pattern.compile("^(class_|method_|field_|comp_|p_|m_|f_|C_)\\d.*");

	private static boolean isRecoveredName(String name) {
		return name != null && !name.isBlank()
				&& VALID_IDENTIFIER.matcher(name).matches()
				&& !PLACEHOLDER_NAME.matcher(name).matches();
	}

	private static String simpleName(String internal) {
		int cut = Math.max(internal.lastIndexOf('/'), internal.lastIndexOf('$'));
		return cut >= 0 ? internal.substring(cut + 1) : internal;
	}

	private static void link(Path source, Path target) throws IOException {
		Files.deleteIfExists(target);

		try {
			Files.createLink(target, source);
		} catch (IOException | UnsupportedOperationException fallback) {
			Files.copy(source, target);
		}
	}

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
		Set<Integer> slots = new HashSet<>();
		int slot = Modifier.isStatic(access) ? 0 : 1;

		for (Type t : Type.getArgumentTypes(desc)) {
			slots.add(slot);
			slot += t.getSize();
		}

		return slots;
	}

	private static void validate(List<Candidate> sample) {
		Set<String> keys = new HashSet<>();

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

			if (c.officialName == null || c.officialName.isBlank() || c.yarnName == null || c.yarnName.isBlank()) {
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

	private static final class Candidate {
		final String kind;
		final String obfOwner;
		final String obfName;
		final String obfDesc;
		final int localIndex;
		final String officialOwner;
		final String officialName;
		final String yarnOwner;
		final String yarnName;
		final String visibility;
		final String slice;

		private Candidate(String kind, String obfOwner, String obfName, String obfDesc, int localIndex,
				String officialOwner, String officialName, String yarnOwner, String yarnName, int access) {
			this.kind = kind;
			this.obfOwner = obfOwner;
			this.obfName = obfName;
			this.obfDesc = obfDesc;
			this.localIndex = localIndex;
			this.officialOwner = officialOwner;
			this.officialName = officialName;
			this.yarnOwner = yarnOwner;
			this.yarnName = yarnName;
			this.visibility = visibility(access);
			this.slice = slice(kind, access);
		}

		static Candidate forClass(ClassNode cn, String obfOwner, String yarnOwner) {
			return new Candidate("CLASS", obfOwner, simpleName(obfOwner), "", -1,
					cn.name, simpleName(cn.name), yarnOwner, simpleName(yarnOwner), cn.access);
		}

		static Candidate forMethod(ClassNode cn, MethodNode mn, ObfMap obf, String yarnOwner, String obfName,
				String officialName, String yarnName) {
			return new Candidate("METHOD", obf.classMap.get(cn.name), obfName, obf.remapMethodDesc(mn.desc), -1,
					cn.name, officialName, yarnOwner, yarnName, mn.access);
		}

		static Candidate forField(ClassNode cn, FieldNode fn, ObfMap obf, String yarnOwner, String obfName,
				String officialName, String yarnName) {
			return new Candidate("FIELD", obf.classMap.get(cn.name), obfName, obf.remapDesc(fn.desc), -1,
					cn.name, officialName, yarnOwner, yarnName, fn.access);
		}

		static Candidate forParam(ClassNode cn, MethodNode mn, ObfMap obf, String yarnOwner, String obfMethodName,
				int slot, String officialArg, String yarnArg) {
			return new Candidate("PARAMETER", obf.classMap.get(cn.name), obfMethodName, obf.remapMethodDesc(mn.desc), slot,
					cn.name, officialArg, yarnOwner, yarnArg, mn.access);
		}

		/** Functional unit for spreading the sample: the intersection of both real taxonomies (official::yarn). */
		String topPackage() {
			return coarse(officialOwner) + "::" + coarse(yarnOwner);
		}

		private static String coarse(String owner) {
			if (owner == null) {
				return "";
			}

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
	}
}
