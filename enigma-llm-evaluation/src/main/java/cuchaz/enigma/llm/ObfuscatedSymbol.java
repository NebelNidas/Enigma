package cuchaz.enigma.llm;

import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * One ground-truth symbol in an obfuscated corpus jar.
 *
 * <p>The obfuscated identity ({@link #obfOwner}/{@link #obfName}/{@link #obfDesc}) locates the
 * target inside the obfuscated {@link cuchaz.enigma.api.view.ProjectView} so the harness can build
 * an {@link EntryKey} and drive the real suggestion engine; {@link #acceptableRealNames} is what a
 * correct recovery must match.
 *
 * @param obfuscated {@code false} marks a symbol deliberately left un-obfuscated (its real name is
 *                   kept, only its descriptor types are remapped). These are the preservation
 *                   control: the model should recognise the name is already meaningful and leave
 *                   it alone. Scored separately from recovery, never averaged in.
 * @param access     the original member's JVM access flags (visibility bucketing).
 * @param localIndex for a {@link EntryKind#PARAMETER} symbol, the JVM local-variable slot of the
 *                   parameter inside its owning method (this=0 if instance, wide types take 2 slots);
 *                   {@code -1} for every non-parameter symbol.
 * @param localName  the parameter's name as it appears in the obfuscated jar. Always {@code ""}: the
 *                   obfuscated jar has its debug/parameter metadata stripped, so no name is visible;
 *                   {@link #realName} carries the answer. {@code ""} for every non-parameter symbol.
 */
record ObfuscatedSymbol(
		EntryKind kind,
		String obfOwner,
		String obfName,
		String obfDesc,
		String realOwner,
		String realName,
		Set<String> acceptableRealNames,
		int access,
		boolean obfuscated,
		int localIndex,
		String localName) {
	/**
	 * The visibility slice this symbol is scored in: {@code api} (class names plus public/protected
	 * members — the externally recoverable surface), {@code package} (package-private members, scored
	 * as a separate secondary bucket), or {@code private} (least recoverable — reported diagnostically,
	 * never in the headline). The three slices are never averaged together.
	 */
	String slice() {
		if (kind == EntryKind.CLASS || Modifier.isPublic(access) || Modifier.isProtected(access)) {
			return "api";
		} else if (Modifier.isPrivate(access)) {
			return "private";
		}

		return "package";
	}

	/** The api slice (class names plus public/protected members); preservation controls draw from it. */
	boolean isRecoverableSlice() {
		return slice().equals("api");
	}

	/**
	 * A copy marked as a preservation control: the real name is kept as the obfuscated identity (only
	 * the descriptor types stay remapped, since the classes they reference are still renamed), and the
	 * {@link #obfuscated} flag is cleared. Scored in a separate bucket — did the model recognise the
	 * name is already meaningful and leave it alone?
	 */
	ObfuscatedSymbol asPreserved() {
		return new ObfuscatedSymbol(kind, obfOwner, realName, obfDesc, realOwner, realName,
				acceptableRealNames, access, false, localIndex, localName);
	}

	String visibility() {
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
