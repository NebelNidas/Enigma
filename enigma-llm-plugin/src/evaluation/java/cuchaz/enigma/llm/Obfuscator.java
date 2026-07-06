package cuchaz.enigma.llm;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Produces an obfuscated copy of a jar together with the ground truth needed to score name
 * recovery: for every renamed symbol, the opaque token it was given and the human-authored name
 * that should be recovered.
 *
 * <p>Implementations are the "obfuscation pattern" axis of the Hybrid 1.1 deobfuscation
 * round-trip benchmark. {@link TinyRemapperObfuscator} is the baseline; a ProGuard-backed stress
 * variant is a separate track (never averaged with the baseline, per the design review).
 */
interface Obfuscator {
	/** Stable identifier used in result files and TSV rows (e.g. {@code "tiny-remapper"}). */
	String name();

	/**
	 * Obfuscates {@code inputJar}, writing the result to {@code outputJar} (overwritten if it
	 * exists), and returns the ground truth describing every scored symbol.
	 */
	ObfuscationResult obfuscate(Path inputJar, Path outputJar) throws IOException;
}
