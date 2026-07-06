package cuchaz.enigma.llm;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
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
import org.objectweb.asm.Type;

/**
 * Baseline obfuscator: renames project classes, methods and fields to opaque tokens and applies the
 * map with tiny-remapper (which propagates method renames across override groups, keeping the jar
 * self-consistent).
 *
 * <p>The rename map is derived by reflecting over the input jar through a private
 * {@link URLClassLoader}. Reflection hands us synthetic/bridge flags and access modifiers for free
 * and, crucially, lets us skip methods that override a member declared outside the jar (JDK/library
 * contracts such as {@code toString}, {@code iterator}, {@code compareTo}) — renaming those would
 * both break the override and hand the model a trivially memorisable name.
 */
final class TinyRemapperObfuscator implements Obfuscator {
	private static final String FLAT_PACKAGE = "obf/";
	/** How many members per jar to leave un-renamed as the preservation control (Grok/Codex: 5–15). */
	private static final int PRESERVE_PER_JAR = 12;

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
		List<String> classNames = readClassNames(inputJar);
		Set<String> projectClasses = new HashSet<>(classNames);

		OpaqueNames names = new OpaqueNames(flatPackage);
		// Pass 1: rename EVERY class (except module-info) up front — including anonymous and
		// package-info classes — so no original coordinate (com/google/gson ...) survives to leak
		// into the context the model sees, and so descriptor remapping in pass 2 sees a fully
		// populated class map. Anonymous / package-info classes are renamed but not scored.
		List<String> renamedClasses = new ArrayList<>();

		for (String internalName : classNames) {
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

		// Parent = platform loader (JDK only), NOT the app classpath. The plugin itself bundles gson,
		// so delegating to the app loader would resolve e.g. com.google.gson classes from the plugin's
		// gson instead of the corpus jar under test — silently mixing a different library version's
		// members into the ground truth. Platform-first isolation forces every corpus class to load
		// from inputJar; anything that then fails to link (a missing external dep) is caught below and
		// simply left unscored. Our corpus jars are self-contained (JDK-only deps), so this is safe.
		try (URLClassLoader loader = new URLClassLoader(new URL[] {inputJar.toUri().toURL()},
				ClassLoader.getPlatformClassLoader())) {
			for (String internalName : renamedClasses) {
				if (isUnscoredClass(internalName)) {
					continue;
				}

				Class<?> cls;

				try {
					cls = Class.forName(internalName.replace('/', '.'), false, loader);
				} catch (Throwable unresolved) {
					// Class can't be linked here (missing optional dep). tiny-remapper still remaps its
					// bytecode consistently via propagation; we simply don't score it.
					continue;
				}

				// Reflecting over the members (getDeclaredMethods/Fields, field.getType, getSuperclass)
				// can still throw linkage errors later if a member signature references an absent type.
				// Collect into per-class buffers and merge only on full success, so one unlinkable class
				// is skipped (its bytecode is still remapped) rather than aborting the whole corpus.
				List<ObfuscatedSymbol> classSymbols = new ArrayList<>();
				List<MemberEntry> classMembers = new ArrayList<>();

				try {
					collectClass(internalName, cls, names, classSymbols);
					collectMethods(internalName, cls, projectClasses, names, classMembers);
					collectFields(internalName, cls, names, classMembers);
				} catch (Throwable linkage) {
					continue;
				}

				symbols.addAll(classSymbols);
				members.addAll(classMembers);
			}
		}

		// Preservation control: leave a small, stratified, seeded sample of members un-renamed (real
		// name kept) so a separate bucket can test whether the model recognises an already-meaningful
		// name instead of renaming reflexively. Retracting the mapping keeps the real name in the jar.
		Set<Integer> preserved = selectPreservation(members, inputJar);

		for (int i = 0; i < members.size(); i++) {
			MemberEntry member = members.get(i);

			if (preserved.contains(i)) {
				symbols.add(member.symbol().asPreserved());
			} else {
				symbols.add(member.symbol());
				mappings.add(member.mapping());
			}
		}

		applyRemap(inputJar, outputJar, mappings);
		// tiny-remapper preserves debug/source metadata; strip it so original parameter names and
		// the original SourceFile don't leak into the prompt the model is scored against.
		DebugStripper.strip(outputJar);
		return new ObfuscationResult(name(), outputJar, symbols);
	}

	private void collectClass(String internalName, Class<?> cls, OpaqueNames names, List<ObfuscatedSymbol> symbols) {
		if (cls.isSynthetic()) {
			return;
		}

		String obfInternal = names.classToken(internalName);
		symbols.add(new ObfuscatedSymbol(EntryKind.CLASS, obfInternal, simpleName(obfInternal),
				"", internalName, simpleName(internalName), Set.of(simpleName(internalName)),
				cls.getModifiers(), true));
	}

	private void collectMethods(String internalName, Class<?> cls, Set<String> projectClasses,
			OpaqueNames names, List<MemberEntry> members) {
		Method[] declared = cls.getDeclaredMethods();
		java.util.Arrays.sort(declared, Comparator
				.comparing(Method::getName)
				.thenComparing(method -> Type.getMethodDescriptor(method)));
		for (Method method : declared) {
			if (method.isSynthetic() || method.isBridge()) {
				continue;
			}

			// Enum values()/valueOf() are compiler-generated; their names are fixed, not recovered.
			if (cls.isEnum() && (method.getName().equals("values") || method.getName().equals("valueOf"))) {
				continue;
			}

			if (overridesExternal(cls, method.getName(), projectClasses)) {
				continue;
			}

			String desc = Type.getMethodDescriptor(method);
			String token = names.methodToken(method.getName(), desc);
			Mapping mapping = Mapping.forMethod(internalName, method.getName(), desc, token);
			ObfuscatedSymbol symbol = new ObfuscatedSymbol(EntryKind.METHOD, names.classToken(internalName), token,
					remapMethodDescriptor(desc, names), internalName, method.getName(),
					Set.of(method.getName()), method.getModifiers(), true);
			members.add(new MemberEntry(symbol, mapping));
		}
	}

	private void collectFields(String internalName, Class<?> cls, OpaqueNames names, List<MemberEntry> members) {
		Field[] declared = cls.getDeclaredFields();
		java.util.Arrays.sort(declared, Comparator
				.comparing(Field::getName)
				.thenComparing(f -> Type.getDescriptor(f.getType())));
		for (Field field : declared) {
			if (field.isSynthetic()) {
				continue;
			}

			String desc = Type.getDescriptor(field.getType());
			String token = names.fieldToken(field.getName(), desc);
			Mapping mapping = Mapping.forField(internalName, field.getName(), desc, token);
			ObfuscatedSymbol symbol = new ObfuscatedSymbol(EntryKind.FIELD, names.classToken(internalName), token,
					remapType(desc, names), internalName, field.getName(),
					Set.of(field.getName()), field.getModifiers(), true);
			members.add(new MemberEntry(symbol, mapping));
		}
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
	 */
	private static boolean overridesExternal(Class<?> owner, String methodName, Set<String> projectClasses) {
		Set<Class<?>> visited = new HashSet<>();
		Deque<Class<?>> queue = new ArrayDeque<>();
		enqueueSupertypes(owner, queue);

		while (!queue.isEmpty()) {
			Class<?> type = queue.poll();

			if (type == null || !visited.add(type)) {
				continue;
			}

			String internal = type.getName().replace('.', '/');

			if (!projectClasses.contains(internal)) {
				for (Method candidate : declaredMethodsQuietly(type)) {
					if (candidate.getName().equals(methodName)) {
						return true;
					}
				}
			}

			enqueueSupertypes(type, queue);
		}

		return false;
	}

	private static void enqueueSupertypes(Class<?> type, Deque<Class<?>> queue) {
		Class<?> superClass = type.getSuperclass();

		if (superClass != null) {
			queue.add(superClass);
		}

		queue.addAll(java.util.Arrays.asList(type.getInterfaces()));
	}

	private static Method[] declaredMethodsQuietly(Class<?> type) {
		try {
			return type.getDeclaredMethods();
		} catch (Throwable t) {
			return new Method[0];
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

	private static List<String> readClassNames(Path jar) throws IOException {
		Set<String> names = new TreeSet<>();

		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String entryName = entry.getName();

				if (entry.isDirectory() || !entryName.endsWith(".class")) {
					continue;
				}

				names.add(entryName.substring(0, entryName.length() - ".class".length()));
			}
		}

		return new ArrayList<>(names);
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

	/** A collected member: its scored ground-truth symbol paired with the rename that would apply it. */
	private record MemberEntry(ObfuscatedSymbol symbol, Mapping mapping) {
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
