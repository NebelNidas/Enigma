package cuchaz.enigma.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic opaque-token generator for obfuscation.
 *
 * <p>Classes are flattened into a single synthetic package (dropping the original coordinates so
 * the prompt can never leak "com/google/gson"), while inner-class nesting is preserved so the
 * {@code InnerClasses} attribute stays consistent. Methods and fields are tokenised per
 * {@code (name, descriptor)}: members that share a signature — i.e. an override group — therefore
 * receive the identical token automatically, without the caller computing the class hierarchy, and
 * any collision is between members that already share a name (so the ground truth stays
 * unambiguous).
 */
final class OpaqueNames {
	private final String flatPackage;
	private final Map<String, String> classTokens = new LinkedHashMap<>();
	private final Map<String, String> methodTokens = new LinkedHashMap<>();
	private final Map<String, String> fieldTokens = new LinkedHashMap<>();
	private int classCounter;
	private int methodCounter;
	private int fieldCounter;

	OpaqueNames(String flatPackage) {
		this.flatPackage = flatPackage;
	}

	/** Assigns (or returns) the opaque internal name for a class, preserving inner-class nesting. */
	String classToken(String internalName) {
		String existing = classTokens.get(internalName);

		if (existing != null) {
			return existing;
		}

		String token;
		int dollar = internalName.lastIndexOf('$');

		if (dollar >= 0) {
			String outerToken = classToken(internalName.substring(0, dollar));
			token = outerToken + "$C" + (++classCounter);
		} else {
			token = flatPackage + "C" + (++classCounter);
		}

		classTokens.put(internalName, token);
		return token;
	}

	/** The opaque name for a class, or {@code null} if it was never assigned one (left as-is). */
	String classTokenIfPresent(String internalName) {
		return classTokens.get(internalName);
	}

	String methodToken(String name, String descriptor) {
		return methodTokens.computeIfAbsent(name + descriptor, k -> "m" + (++methodCounter));
	}

	String fieldToken(String name, String descriptor) {
		return fieldTokens.computeIfAbsent(name + descriptor, k -> "f" + (++fieldCounter));
	}
}
