package cuchaz.enigma.llm;

import java.nio.file.Path;
import java.util.List;

/** The obfuscated jar plus the ground truth for every scored symbol it contains. */
record ObfuscationResult(String obfuscatorName, Path obfuscatedJar, List<ObfuscatedSymbol> symbols) {
}
