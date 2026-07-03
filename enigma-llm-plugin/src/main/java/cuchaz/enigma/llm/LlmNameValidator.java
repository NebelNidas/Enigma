package cuchaz.enigma.llm;

import java.util.Set;

class LlmNameValidator {
	private static final Set<String> KEYWORDS = Set.of(
			"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
			"continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
			"for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
			"new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
			"super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
			"volatile", "while", "true", "false", "null", "_"
	);

	boolean isValid(EntryKind kind, String name) {
		return isValid(kind, name, true);
	}

	boolean isValid(EntryKey key, String name) {
		if (!isValid(key.kind(), name, key.kind() != EntryKind.CLASS || !key.owner().contains("$"))) {
			return false;
		}

		return switch (key.kind()) {
		case CLASS -> !name.equals(key.owner()) && !name.equals(simpleClassName(key.owner()));
		case FIELD, METHOD -> !name.equals(key.name());
		case PARAMETER -> !name.equals(key.localName());
		};
	}

	private boolean isValid(EntryKind kind, String name, boolean allowClassPackageName) {
		if (name == null || name.isBlank() || name.contains(".") || name.contains("$")) {
			return false;
		}

		return switch (kind) {
		case CLASS -> isValidClassName(name, allowClassPackageName);
		case FIELD, METHOD, PARAMETER -> isLowerCamelIdentifier(name);
		};
	}

	private static boolean isValidClassName(String name, boolean allowPackageName) {
		String[] parts = allowPackageName ? name.split("/", -1) : new String[] { name };

		if (parts.length == 0) {
			return false;
		}

		for (String part : parts) {
			if (!isValidIdentifier(part)) {
				return false;
			}
		}

		return Character.isUpperCase(parts[parts.length - 1].charAt(0));
	}

	private static boolean isLowerCamelIdentifier(String name) {
		if (!isValidIdentifier(name) || !Character.isLowerCase(name.charAt(0))) {
			return false;
		}

		for (int i = 1; i < name.length(); i++) {
			char c = name.charAt(i);

			if (c == '_' || Character.isUpperCase(c) && i + 1 < name.length() && Character.isUpperCase(name.charAt(i + 1))) {
				return false;
			}
		}

		return true;
	}

	private static boolean isValidIdentifier(String name) {
		if (name.isBlank() || KEYWORDS.contains(name)) {
			return false;
		}

		if (!Character.isJavaIdentifierStart(name.charAt(0))) {
			return false;
		}

		for (int i = 1; i < name.length(); i++) {
			if (!Character.isJavaIdentifierPart(name.charAt(i))) {
				return false;
			}
		}

		return true;
	}

	private static String simpleClassName(String name) {
		int packageEnd = name.lastIndexOf('/');
		String simpleName = packageEnd >= 0 ? name.substring(packageEnd + 1) : name;
		int innerEnd = simpleName.lastIndexOf('$');
		return innerEnd >= 0 ? simpleName.substring(innerEnd + 1) : simpleName;
	}
}
