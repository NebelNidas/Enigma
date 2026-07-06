package cuchaz.enigma.llm;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import java.util.Set;
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

				collectClass(internalName, cls, names, symbols);
				collectMethods(internalName, cls, projectClasses, names, symbols, mappings);
				collectFields(internalName, cls, names, symbols, mappings);
			}
		}

		applyRemap(inputJar, outputJar, mappings);
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
			OpaqueNames names, List<ObfuscatedSymbol> symbols, List<Mapping> mappings) {
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
			mappings.add(Mapping.forMethod(internalName, method.getName(), desc, token));
			symbols.add(new ObfuscatedSymbol(EntryKind.METHOD, names.classToken(internalName), token,
					remapMethodDescriptor(desc, names), internalName, method.getName(),
					Set.of(method.getName()), method.getModifiers(), true));
		}
	}

	private void collectFields(String internalName, Class<?> cls, OpaqueNames names,
			List<ObfuscatedSymbol> symbols, List<Mapping> mappings) {
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
			mappings.add(Mapping.forField(internalName, field.getName(), desc, token));
			symbols.add(new ObfuscatedSymbol(EntryKind.FIELD, names.classToken(internalName), token,
					remapType(desc, names), internalName, field.getName(),
					Set.of(field.getName()), field.getModifiers(), true));
		}
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
