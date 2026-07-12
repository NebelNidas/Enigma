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
		case FIELD -> isReadableFieldName(key.name());
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

	private static boolean isReadableFieldName(String name) {
		// A field name counts as already-meaningful if it is either an UPPER_CASE constant (MAX_VALUE,
		// UTF_8, LOGGER, INSTANCE, ID) or a readable lowerCamelCase name (>=4 chars, same conventional-name
		// test used for methods). The obfuscator (OpaqueNames) only ever emits lowercase-led "f"+digits
		// tokens, so an uppercase-led name is never its output, and the lowerCamel test rejects those
		// tokens by requiring at least two letters — keeping the gate conservative so a genuine rename is
		// never suppressed by mistake.
		return isUpperCaseConstant(name) || isMeaningfulLowerCamelName(name, 4);
	}

	private static boolean isUpperCaseConstant(String name) {
		// UPPER_CASE, optionally underscore-segmented. Single-word constants (LOGGER, INSTANCE, ID) count
		// too: obfuscator field tokens are always lowercase-led, so an uppercase-led name cannot collide
		// with them; we only reject degenerate single characters and malformed underscore placement.
		if (name.length() < 2 || !Character.isUpperCase(name.codePointAt(0))) {
			return false;
		}

		if (name.startsWith("_") || name.endsWith("_") || name.contains("__")) {
			return false;
		}

		return name.codePoints().allMatch(cp -> cp == '_'
				|| (Character.isLetterOrDigit(cp) && !Character.isLowerCase(cp)));
	}

	private static boolean isReadableClassName(String simpleName) {
		if (simpleName.length() < 4 || !Character.isUpperCase(simpleName.codePointAt(0))) {
			return false;
		}

		if (!simpleName.codePoints().allMatch(Character::isLetterOrDigit)) {
			return false;
		}

		// The obfuscator (OpaqueNames) emits class tokens "C"+counter (e.g. C1234): one letter plus digits.
		// A real class name carries at least two letters, so this closes the same synthetic-token hole the
		// field and method gates guard against.
		return simpleName.codePoints().filter(Character::isLetter).count() >= 2;
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

		if (!name.codePoints().allMatch(Character::isLetterOrDigit) || looksGeneratedLocalName(name)) {
			return false;
		}

		// A single leading letter followed by digits (the OpaqueNames obfuscator's "f789"/"m456" tokens,
		// and synthetic single-letter names generally) is not a real identifier; require two letters.
		return name.codePoints().filter(Character::isLetter).count() >= 2;
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
