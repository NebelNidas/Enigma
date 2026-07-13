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
		return isValid(key, name, false);
	}

	boolean isValid(EntryKey key, String name, boolean constantField) {
		if (constantField && key.kind() != EntryKind.FIELD) {
			return false;
		}

		if (!isValid(key.kind(), name, key.kind() != EntryKind.CLASS || !key.owner().contains("$"), constantField)) {
			return false;
		}

		return switch (key.kind()) {
		case CLASS -> !name.equals(key.owner()) && !name.equals(simpleClassName(key.owner()));
		case FIELD, METHOD -> !name.equals(key.name());
		case PARAMETER -> !name.equals(key.localName());
		};
	}

	private boolean isValid(EntryKind kind, String name, boolean allowClassPackageName) {
		return isValid(kind, name, allowClassPackageName, false);
	}

	private boolean isValid(EntryKind kind, String name, boolean allowClassPackageName, boolean constantField) {
		if (name == null || name.isBlank() || name.contains(".") || name.contains("$")) {
			return false;
		}

		return switch (kind) {
		case CLASS -> isValidClassName(name, allowClassPackageName);
		case FIELD -> constantField ? isUpperSnakeIdentifier(name) : isLowerCamelIdentifier(name);
		case METHOD, PARAMETER -> isLowerCamelIdentifier(name);
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

		// Underscores are not camelCase, so they are still rejected. Embedded acronyms (consecutive
		// uppercase, e.g. getURL / verifyCRC32) are ACCEPTED: the JDK and much real code use them, the
		// benchmark's normalizer already treats them as equivalent to word-cased forms, and hard-rejecting
		// them only discards otherwise-valid suggestions. Preferring acronyms-as-words is a soft prompt
		// guideline, not a validity rule.
		for (int i = 1; i < name.length(); i++) {
			if (name.charAt(i) == '_') {
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

	private static boolean isUpperSnakeIdentifier(String name) {
		if (!isValidIdentifier(name) || !Character.isUpperCase(name.charAt(0))) {
			return false;
		}

		boolean lastUnderscore = false;
		boolean hasLetter = false;

		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);

			if (c == '_') {
				if (i == 0 || lastUnderscore) {
					return false;
				}

				lastUnderscore = true;
			} else {
				if (Character.isLetter(c)) {
					hasLetter = true;

					if (!Character.isUpperCase(c)) {
						return false;
					}
				}

				lastUnderscore = false;
			}
		}

		return hasLetter && !lastUnderscore;
	}

	private static String simpleClassName(String name) {
		int packageEnd = name.lastIndexOf('/');
		String simpleName = packageEnd >= 0 ? name.substring(packageEnd + 1) : name;
		int innerEnd = simpleName.lastIndexOf('$');
		return innerEnd >= 0 ? simpleName.substring(innerEnd + 1) : simpleName;
	}
}
