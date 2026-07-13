package cuchaz.enigma.llm;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;

/**
 * Baseline obfuscator: renames project classes, methods and fields to opaque tokens and applies the
 * map with tiny-remapper (which propagates method renames across override groups, keeping the jar
 * self-consistent).
 *
 * <p>The rename map is derived by parsing every class's bytecode with ASM ({@link ClassReader} into a
 * {@link ClassNode}) — never loading or linking the corpus classes. This avoids executing any
 * untrusted third-party bytecode just to enumerate members, sidesteps dependency-resolution drift,
 * and — crucially — reads the {@code MethodParameters}/{@code LocalVariableTable} debug attributes
 * directly so parameter names (and their JVM slots) become ground truth. Synthetic/bridge flags and
 * access modifiers come straight off the parsed nodes; the override check that skips members declared
 * outside the jar (JDK/library contracts such as {@code toString}, {@code iterator}, {@code compareTo})
 * walks the class hierarchy over the parsed nodes plus ASM-parsed platform classes, without ever
 * calling {@code Class.forName}.
 */
final class TinyRemapperObfuscator implements Obfuscator {
	private static final String FLAT_PACKAGE = "obf/";
	/** How many members per jar to leave un-renamed as the preservation control (Grok/Codex: 5–15). */
	private static final int PRESERVE_PER_JAR = 12;

	/**
	 * Conservative library-contract names used ONLY when an external supertype cannot be resolved on
	 * the platform classpath (so we cannot inspect its declared methods). The primary override check is
	 * the ASM hierarchy walk; this denylist is a fallback so an unresolved supertype never lets a
	 * memorisable contract name slip through into the scored slice.
	 */
	private static final Set<String> CONTRACT_NAMES = Set.of(
			"toString", "hashCode", "equals", "compareTo", "iterator", "spliterator",
			"close", "run", "call", "get", "accept", "apply", "test", "add", "remove",
			"read", "write", "flush");

	private final String flatPackage;

	TinyRemapperObfuscator() {
		this(FLAT_PACKAGE);
	}

	TinyRemapperObfuscator(String flatPackage) {
		this.flatPackage = flatPackage;
	}

	@Override
	public String name() {
		return "tiny-remapper";
	}

	@Override
	public ObfuscationResult obfuscate(Path inputJar, Path outputJar) throws IOException {
		// Parse every class into a deterministic (sorted) node map. LVT is needed for the parameter
		// fallback, so we keep code/debug — only SKIP_FRAMES (stack-map frames are irrelevant here).
		Map<String, ClassNode> nodes = readClassNodes(inputJar);

		OpaqueNames names = new OpaqueNames(flatPackage);
		// Pass 1: rename EVERY class (except module-info) up front — including anonymous and
		// package-info classes — so no original coordinate (com/google/gson ...) survives to leak
		// into the context the model sees, and so descriptor remapping in pass 2 sees a fully
		// populated class map. Anonymous / package-info classes are renamed but not scored.
		List<String> renamedClasses = new ArrayList<>();

		for (String internalName : nodes.keySet()) {
			if (internalName.endsWith("module-info")) {
				continue;
			}

			names.classToken(internalName);
			renamedClasses.add(internalName);
		}

		List<ObfuscatedSymbol> symbols = new ArrayList<>();
		List<Mapping> mappings = new ArrayList<>();
		List<MemberEntry> members = new ArrayList<>();

		for (String internalName : renamedClasses) {
			mappings.add(Mapping.forClass(internalName, names.classToken(internalName)));
		}

		// Cache of ASM-parsed platform (JDK/external) supertypes, so each external class is read once.
		Map<String, ExternalInfo> externalCache = new LinkedHashMap<>();

		for (String internalName : renamedClasses) {
			if (isUnscoredClass(internalName)) {
				continue;
			}

			ClassNode node = nodes.get(internalName);
			collectClass(internalName, node, names, symbols);
			collectMethods(internalName, node, nodes, externalCache, names, members);
			collectFields(internalName, node, names, members);
		}

		// Preservation control: leave a small, stratified, seeded sample of members un-renamed (real
		// name kept) so a separate bucket can test whether the model recognises an already-meaningful
		// name instead of renaming reflexively. Retracting the mapping keeps the real name in the jar.
		Set<Integer> preserved = selectPreservation(members, inputJar);

		for (int i = 0; i < members.size(); i++) {
			MemberEntry member = members.get(i);
			ObfuscatedSymbol symbol;

			if (preserved.contains(i)) {
				symbol = member.symbol().asPreserved();
				symbols.add(symbol);
			} else {
				symbol = member.symbol();
				symbols.add(symbol);
				mappings.add(member.mapping());
			}

			// Parameters are located by their owning method's FINAL obf identity (obfName is the token,
			// or the real name if the method is preserved) plus the JVM slot. Params are never renamed or
			// preserved themselves — they ride along with whatever the method became.
			for (PendingParam param : member.params()) {
				symbols.add(new ObfuscatedSymbol(EntryKind.PARAMETER, symbol.obfOwner(), symbol.obfName(),
						symbol.obfDesc(), member.symbol().realOwner(), param.name(), Set.of(param.name()),
						param.access(), true, param.slot(), ""));
			}
		}

		applyRemap(inputJar, outputJar, mappings);
		// tiny-remapper preserves debug/source metadata; strip it so original parameter names and
		// the original SourceFile don't leak into the prompt the model is scored against.
		DebugStripper.strip(outputJar);
		return new ObfuscationResult(name(), outputJar, symbols);
	}

	private void collectClass(String internalName, ClassNode node, OpaqueNames names, List<ObfuscatedSymbol> symbols) {
		int access = classAccess(node);

		// Preserve the reflection quirk: a synthetic class produces NO class symbol, but its methods and
		// fields are still collected (the whole class is NOT skipped).
		if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
			return;
		}

		String obfInternal = names.classToken(internalName);
		symbols.add(new ObfuscatedSymbol(EntryKind.CLASS, obfInternal, simpleName(obfInternal),
				"", internalName, simpleName(internalName), Set.of(simpleName(internalName)),
				access, true, -1, ""));
	}

	private void collectMethods(String internalName, ClassNode node, Map<String, ClassNode> nodes,
			Map<String, ExternalInfo> externalCache, OpaqueNames names, List<MemberEntry> members) {
		boolean isEnum = (node.access & Opcodes.ACC_ENUM) != 0;
		List<MethodNode> declared = new ArrayList<>(node.methods);
		declared.sort(Comparator
				.comparing((MethodNode method) -> method.name)
				.thenComparing(method -> method.desc));

		for (MethodNode method : declared) {
			// Reflection's getDeclaredMethods() never returned constructors or the static initialiser.
			if (method.name.equals("<init>") || method.name.equals("<clinit>")) {
				continue;
			}

			if ((method.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
				continue;
			}

			// Enum values()/valueOf() are compiler-generated; their names are fixed, not recovered.
			if (isEnum && (method.name.equals("values") || method.name.equals("valueOf"))) {
				continue;
			}

			if (overridesExternal(internalName, method.name, nodes, externalCache)) {
				continue;
			}

			String desc = method.desc;
			String token = names.methodToken(method.name, desc);
			String remappedDesc = remapMethodDescriptor(desc, names);
			Mapping mapping = Mapping.forMethod(internalName, method.name, desc, token);
			ObfuscatedSymbol symbol = new ObfuscatedSymbol(EntryKind.METHOD, names.classToken(internalName), token,
					remappedDesc, internalName, method.name,
					Set.of(method.name), method.access, true, -1, "");
			members.add(new MemberEntry(symbol, mapping, collectParameters(method)));
		}
	}

	private void collectFields(String internalName, ClassNode node, OpaqueNames names, List<MemberEntry> members) {
		List<FieldNode> declared = new ArrayList<>(node.fields);
		declared.sort(Comparator
				.comparing((FieldNode field) -> field.name)
				.thenComparing(field -> field.desc));

		for (FieldNode field : declared) {
			if ((field.access & Opcodes.ACC_SYNTHETIC) != 0) {
				continue;
			}

			String desc = field.desc;
			String token = names.fieldToken(field.name, desc);
			Mapping mapping = Mapping.forField(internalName, field.name, desc, token);
			ObfuscatedSymbol symbol = new ObfuscatedSymbol(EntryKind.FIELD, names.classToken(internalName), token,
					remapType(desc, names), internalName, field.name,
					Set.of(field.name), field.access, true, -1, "");
			members.add(new MemberEntry(symbol, mapping, List.of()));
		}
	}

	/**
	 * Collects the real-named parameters of a scored method as {@link PendingParam}s (slot + name +
	 * the method's access). Precedence: {@code MethodParameters} (authoritative, carries synthetic /
	 * mandated flags) then, ONLY if that attribute is absent, the {@code LocalVariableTable}. One row
	 * per JVM argument slot at most; slots without a real name are dropped.
	 */
	private static List<PendingParam> collectParameters(MethodNode method) {
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
		Type[] argTypes = Type.getArgumentTypes(method.desc);

		// Ordinal -> JVM slot, and the set of valid argument slots (this=0 if instance, wide types +2).
		int[] paramSlots = new int[argTypes.length];
		Set<Integer> argSlots = new HashSet<>();
		int slot = isStatic ? 0 : 1;

		for (int i = 0; i < argTypes.length; i++) {
			paramSlots[i] = slot;
			argSlots.add(slot);
			slot += argTypes[i].getSize();
		}

		// slot -> name; deterministic, at most one entry per slot.
		Map<Integer, String> named = new TreeMap<>();

		List<ParameterNode> parameters = method.parameters;

		if (parameters != null && !parameters.isEmpty()) {
			// MethodParameters present: authoritative. Match each entry to its argument slot by ordinal.
			for (int i = 0; i < parameters.size() && i < paramSlots.length; i++) {
				ParameterNode parameter = parameters.get(i);

				if ((parameter.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_MANDATED)) != 0) {
					continue;
				}

				String name = parameter.name;

				if (name == null || name.isBlank()) {
					continue;
				}

				named.put(paramSlots[i], name);
			}
		} else if (method.localVariables != null) {
			// Fallback: LVT. Only entries whose slot is an argument slot; skip synthetic-looking names.
			// Do NOT merge LVT into MethodParameters — this branch runs only when the attribute is absent.
			//
			// A parameter's local occupies its slot from method entry; other locals may later reuse the
			// same slot, and ASM does not guarantee LVT order. So for each argument slot pick the entry
			// whose scope STARTS EARLIEST (lowest instruction index of local.start) — that is the actual
			// parameter — THEN apply the name filters. One name per slot; no fallback to a later local.
			Map<Integer, LocalVariableNode> earliest = new TreeMap<>();
			Map<Integer, Integer> earliestPos = new TreeMap<>();

			for (LocalVariableNode local : method.localVariables) {
				if (!argSlots.contains(local.index)) {
					continue;
				}

				int pos = method.instructions.indexOf(local.start);
				Integer best = earliestPos.get(local.index);

				if (best == null || pos < best) {
					earliestPos.put(local.index, pos);
					earliest.put(local.index, local);
				}
			}

			for (Map.Entry<Integer, LocalVariableNode> entry : earliest.entrySet()) {
				String name = entry.getValue().name;

				if (name == null || name.equals("this") || name.startsWith("$") || name.matches("arg\\d+")) {
					continue;
				}

				named.put(entry.getKey(), name);
			}
		}

		if (named.isEmpty()) {
			return List.of();
		}

		List<PendingParam> params = new ArrayList<>();

		for (Map.Entry<Integer, String> entry : named.entrySet()) {
			params.add(new PendingParam(entry.getKey(), entry.getValue(), method.access));
		}

		return params;
	}

	/**
	 * Reflection reports a nested class's modifiers from the {@code InnerClasses} attribute, not the
	 * class file's top-level {@code access_flags} (which for a nested class lacks its
	 * private/protected/static visibility and carries {@code ACC_SUPER}). Mirror that so the derived
	 * visibility / slice / synthetic checks match the old reflection output exactly.
	 */
	private static int classAccess(ClassNode node) {
		if (node.innerClasses != null) {
			for (InnerClassNode inner : node.innerClasses) {
				if (inner.name.equals(node.name)) {
					return inner.access;
				}
			}
		}

		return node.access;
	}

	/**
	 * Selects the preservation-control sample: indices into {@code members} whose real name is kept.
	 *
	 * <p>Candidates are restricted to fields and <em>static</em> methods — both are safe from
	 * tiny-remapper's override-group propagation, which would otherwise drag a rename onto a kept
	 * virtual method that shares a group with a renamed one. Selection is stratified by
	 * kind × visibility and seeded off the jar name, so the control set is reproducible and spread
	 * across the recoverable slice rather than clustered.
	 */
	private static Set<Integer> selectPreservation(List<MemberEntry> members, Path inputJar) {
		Map<String, List<Integer>> strata = new TreeMap<>();

		for (int i = 0; i < members.size(); i++) {
			ObfuscatedSymbol symbol = members.get(i).symbol();
			boolean eligible = symbol.isRecoverableSlice()
					&& (symbol.kind() == EntryKind.FIELD || Modifier.isStatic(symbol.access()));

			if (eligible) {
				strata.computeIfAbsent(symbol.kind() + "/" + symbol.visibility(), k -> new ArrayList<>()).add(i);
			}
		}

		Random random = new Random(inputJar.getFileName().toString().hashCode());

		for (List<Integer> stratum : strata.values()) {
			java.util.Collections.shuffle(stratum, random);
		}

		// Round-robin across strata so every (kind, visibility) bucket contributes before any repeats.
		Set<Integer> preserved = new TreeSet<>();
		List<List<Integer>> pools = new ArrayList<>(strata.values());
		int cursor = 0;

		while (preserved.size() < PRESERVE_PER_JAR && !pools.isEmpty()) {
			List<Integer> pool = pools.get(cursor % pools.size());

			if (pool.isEmpty()) {
				pools.remove(cursor % pools.size());
				continue;
			}

			preserved.add(pool.remove(pool.size() - 1));
			cursor++;
		}

		return preserved;
	}

	/**
	 * True if a supertype declared outside the jar declares a method of this name. Name-only (params
	 * ignored) on purpose: it is conservative (never breaks an override) and deliberately also drops
	 * library-contract names like {@code close}/{@code run}/{@code compareTo} that are memorisable
	 * rather than genuinely recoverable.
	 *
	 * <p>The hierarchy is walked over the parsed project nodes and ASM-parsed external classes — no
	 * class loading. Transitive over the WHOLE tree: both superclasses and every super-interface,
	 * recursively (missing that would let a JDK override like {@code Consumer.accept} slip through and
	 * get renamed, breaking the jar).
	 *
	 * <p>Conservative on an INCOMPLETE hierarchy: if any external supertype cannot be resolved on the
	 * platform classpath (e.g. a non-shaded library dep), we cannot prove the method is NOT an override
	 * of it, so we drop the method rather than risk renaming a real external override (which would break
	 * the jar). This mirrors the old reflection tool, which skipped a whole class on a linkage failure.
	 * (Only the method is dropped here; the containing class/field symbols are still emitted.)
	 */
	private boolean overridesExternal(String ownerInternal, String methodName, Map<String, ClassNode> nodes,
			Map<String, ExternalInfo> externalCache) {
		Set<String> visited = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		enqueueSupertypes(ownerInternal, nodes, externalCache, queue);
		boolean hierarchyIncomplete = false;

		while (!queue.isEmpty()) {
			String type = queue.poll();

			if (type == null || !visited.add(type)) {
				continue;
			}

			if (!nodes.containsKey(type)) {
				ExternalInfo info = resolveExternal(type, externalCache);

				if (info == null) {
					// Unresolved external supertype: the hierarchy is incomplete. Keep the contract
					// denylist as an additional early-positive, but remember the gap for the final verdict.
					hierarchyIncomplete = true;

					if (CONTRACT_NAMES.contains(methodName)) {
						return true;
					}
				} else if (info.methodNames().contains(methodName)) {
					return true;
				}
			}

			enqueueSupertypes(type, nodes, externalCache, queue);
		}

		// Not matched against any resolved supertype — but if the walk hit an unresolvable external type,
		// we cannot rule out an override, so drop conservatively (safe: never renames a real override).
		return hierarchyIncomplete;
	}

	private void enqueueSupertypes(String type, Map<String, ClassNode> nodes,
			Map<String, ExternalInfo> externalCache, Deque<String> queue) {
		ClassNode node = nodes.get(type);

		if (node != null) {
			if (node.superName != null) {
				queue.add(node.superName);
			}

			queue.addAll(node.interfaces);
			return;
		}

		ExternalInfo info = resolveExternal(type, externalCache);

		if (info != null) {
			if (info.superName() != null) {
				queue.add(info.superName());
			}

			queue.addAll(info.interfaces());
		}
	}

	/**
	 * Parses an external (non-project) class off the platform classpath with ASM, collecting its
	 * declared method names plus its own supertypes, and caches the result. Returns {@code null} if the
	 * class cannot be resolved (not on the platform classpath) — callers then apply the denylist. Never
	 * loads or initialises the class.
	 */
	private ExternalInfo resolveExternal(String internalName, Map<String, ExternalInfo> cache) {
		ExternalInfo cached = cache.get(internalName);

		if (cached != null) {
			return cached == ExternalInfo.UNRESOLVED ? null : cached;
		}

		try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(internalName + ".class")) {
			if (in == null) {
				cache.put(internalName, ExternalInfo.UNRESOLVED);
				return null;
			}

			ClassNode node = new ClassNode();
			new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			Set<String> methodNames = new HashSet<>();

			for (MethodNode method : node.methods) {
				methodNames.add(method.name);
			}

			ExternalInfo info = new ExternalInfo(methodNames, node.superName, new ArrayList<>(node.interfaces));
			cache.put(internalName, info);
			return info;
		} catch (Throwable unresolved) {
			cache.put(internalName, ExternalInfo.UNRESOLVED);
			return null;
		}
	}

	private void applyRemap(Path inputJar, Path outputJar, List<Mapping> mappings) throws IOException {
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

	/** Parses every {@code .class} entry into a deterministic (sorted) internal-name -> node map. */
	private static Map<String, ClassNode> readClassNodes(Path jar) throws IOException {
		Map<String, ClassNode> nodes = new TreeMap<>();

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String entryName = entry.getName();

				if (entry.isDirectory() || !entryName.endsWith(".class")) {
					continue;
				}

				try (InputStream in = zip.getInputStream(entry)) {
					ClassNode node = new ClassNode();
					// Keep code + debug (LVT feeds the parameter fallback); frames are irrelevant here.
					new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
					nodes.put(node.name, node);
				}
			}
		}

		return nodes;
	}

	/**
	 * Classes that are renamed (to avoid coordinate leaks) but never scored: package-info and
	 * anonymous classes (an {@code Outer$1} "name" carries no signal to recover).
	 */
	private static boolean isUnscoredClass(String internalName) {
		if (internalName.endsWith("package-info")) {
			return true;
		}

		String simple = simpleName(internalName);
		return !simple.isEmpty() && simple.chars().allMatch(Character::isDigit);
	}

	private static String simpleName(String internalName) {
		int cut = Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$'));
		return cut >= 0 ? internalName.substring(cut + 1) : internalName;
	}

	private static String remapMethodDescriptor(String descriptor, OpaqueNames names) {
		Type methodType = Type.getMethodType(descriptor);
		StringBuilder builder = new StringBuilder("(");

		for (Type argument : methodType.getArgumentTypes()) {
			builder.append(remap(argument, names).getDescriptor());
		}

		return builder.append(')').append(remap(methodType.getReturnType(), names).getDescriptor()).toString();
	}

	private static String remapType(String descriptor, OpaqueNames names) {
		return remap(Type.getType(descriptor), names).getDescriptor();
	}

	private static Type remap(Type type, OpaqueNames names) {
		switch (type.getSort()) {
		case Type.OBJECT: {
			String token = names.classTokenIfPresent(type.getInternalName());
			return token == null ? type : Type.getObjectType(token);
		}

		case Type.ARRAY: {
			Type element = remap(type.getElementType(), names);
			StringBuilder descriptor = new StringBuilder();

			for (int i = 0; i < type.getDimensions(); i++) {
				descriptor.append('[');
			}

			return Type.getType(descriptor.append(element.getDescriptor()).toString());
		}

		default:
			return type;
		}
	}

	/** A collected member: its scored ground-truth symbol, the rename that would apply it, and any params. */
	private record MemberEntry(ObfuscatedSymbol symbol, Mapping mapping, List<PendingParam> params) {
	}

	/** A real-named method parameter awaiting emission: its JVM slot, name, and the owning method's access. */
	private record PendingParam(int slot, String name, int access) {
	}

	/** A cached, ASM-parsed external supertype: its declared method names and its own supertypes. */
	private record ExternalInfo(Set<String> methodNames, String superName, List<String> interfaces) {
		/** Sentinel cached for a class that could not be resolved on the platform classpath. */
		static final ExternalInfo UNRESOLVED = new ExternalInfo(Set.of(), null, List.of());
	}

	/** A single rename fed to tiny-remapper, keyed on the original identity. */
	private record Mapping(EntryKind kind, String owner, String name, String desc, String token) {
		static Mapping forClass(String internalName, String token) {
			return new Mapping(EntryKind.CLASS, internalName, "", "", token);
		}

		static Mapping forMethod(String owner, String name, String desc, String token) {
			return new Mapping(EntryKind.METHOD, owner, name, desc, token);
		}

		static Mapping forField(String owner, String name, String desc, String token) {
			return new Mapping(EntryKind.FIELD, owner, name, desc, token);
		}

		void acceptInto(IMappingProvider.MappingAcceptor acceptor) {
			switch (kind) {
			case CLASS -> acceptor.acceptClass(owner, token);
			case METHOD -> acceptor.acceptMethod(new IMappingProvider.Member(owner, name, desc), token);
			case FIELD -> acceptor.acceptField(new IMappingProvider.Member(owner, name, desc), token);
			default -> throw new IllegalStateException("unmappable kind " + kind);
			}
		}
	}
}
