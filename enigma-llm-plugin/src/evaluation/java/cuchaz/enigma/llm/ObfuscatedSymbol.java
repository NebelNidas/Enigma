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
		boolean obfuscated) {
	/** The recoverable slice for Phase A is public/protected members plus class names. */
	boolean isRecoverableSlice() {
		return kind == EntryKind.CLASS || Modifier.isPublic(access) || Modifier.isProtected(access);
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
