package cuchaz.enigma.llm;

import java.util.List;
import java.util.Optional;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class LlmBytecodePatterns {
	private LlmBytecodePatterns() {
	}

	static Optional<FunctionalInterfacePattern> functionalInterfacePattern(IndexedClass owner) {
		if ((owner.access() & Opcodes.ACC_INTERFACE) == 0) {
			return Optional.empty();
		}

		List<IndexedMethod> abstractMethods = owner.methods().stream()
				.filter(method -> !method.key().name().startsWith("<"))
				.filter(method -> (method.access() & Opcodes.ACC_ABSTRACT) != 0)
				.toList();

		if (abstractMethods.size() != 1) {
			return Optional.empty();
		}

		IndexedMethod singleAbstractMethod = abstractMethods.get(0);
		String singleAbstractTarget = methodTarget(singleAbstractMethod.key());
		List<IndexedMethod> compositionalMethods = owner.methods().stream()
				.filter(method -> !method.key().equals(singleAbstractMethod.key()))
				.filter(method -> !method.key().name().startsWith("<"))
				.filter(method -> (method.access() & Opcodes.ACC_ABSTRACT) == 0)
				.filter(method -> method.key().descriptor().contains("L" + owner.key().owner() + ";")
						|| method.calls().contains(singleAbstractTarget))
				.toList();

		return Optional.of(new FunctionalInterfacePattern(singleAbstractMethod, compositionalMethods));
	}

	static boolean isPreservedMethodName(LlmProjectIndex index, EntryKey key) {
		return key.kind() == EntryKind.METHOD && isObjectMethod(key);
	}

	static boolean isParameterOfPreservedMethod(LlmProjectIndex index, EntryKey key) {
		if (key.kind() != EntryKind.PARAMETER) {
			return false;
		}

		return isPreservedMethodName(index, new EntryKey(EntryKind.METHOD, key.owner(), key.name(), key.descriptor()));
	}

	static boolean isPreservedOriginalName(LlmProjectIndex index, EntryKey key) {
		return switch (key.kind()) {
		case CLASS -> isReadableClassName(simpleClassName(key.owner()));
		case FIELD -> false;
		case METHOD -> isReadableMethodName(index, key);
		case PARAMETER -> false;
		};
	}

	private static boolean isObjectMethod(EntryKey key) {
		return key.name().equals("equals") && key.descriptor().equals("(Ljava/lang/Object;)Z")
				|| key.name().equals("hashCode") && key.descriptor().equals("()I")
				|| key.name().equals("toString") && key.descriptor().equals("()Ljava/lang/String;");
	}

	private static boolean isReadableMethodName(LlmProjectIndex index, EntryKey key) {
		if (isStaticFactoryOf(index, key)) {
			return true;
		}

		return isMeaningfulLowerCamelName(key.name(), 4);
	}

	private static boolean isReadableClassName(String simpleName) {
		if (simpleName.length() < 4 || !Character.isUpperCase(simpleName.codePointAt(0))) {
			return false;
		}

		return simpleName.codePoints().allMatch(Character::isLetterOrDigit);
	}

	private static boolean isStaticFactoryOf(LlmProjectIndex index, EntryKey key) {
		if (!key.name().equals("of")) {
			return false;
		}

		return index.entry(key)
				.filter(IndexedMethod.class::isInstance)
				.map(IndexedMethod.class::cast)
				.filter(method -> (method.access() & Opcodes.ACC_STATIC) != 0)
				.map(method -> {
					try {
						return Type.getReturnType(method.key().descriptor()).getDescriptor().equals("L" + method.key().owner() + ";");
					} catch (IllegalArgumentException ignored) {
						return false;
					}
				})
				.orElse(false);
	}

	private static boolean isMeaningfulLowerCamelName(String name, int minLength) {
		if (name.length() < minLength || !Character.isLowerCase(name.codePointAt(0))) {
			return false;
		}

		return name.codePoints().allMatch(Character::isLetterOrDigit)
				&& !looksGeneratedLocalName(name);
	}

	private static boolean looksGeneratedLocalName(String name) {
		return name.startsWith("p_")
				|| name.startsWith("arg")
				|| name.startsWith("var")
				|| name.startsWith("param");
	}

	private static String simpleClassName(String name) {
		int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('$'));
		return separator >= 0 ? name.substring(separator + 1) : name;
	}

	private static String methodTarget(EntryKey key) {
		return key.owner() + "." + key.name() + key.descriptor();
	}

	record FunctionalInterfacePattern(IndexedMethod singleAbstractMethod, List<IndexedMethod> compositionalMethods) {
	}
}
