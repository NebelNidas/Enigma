package cuchaz.enigma.llm;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Obfuscates every jar in the corpus directory with the baseline {@link TinyRemapperObfuscator} and
 * writes, next to each, an {@code -obf.jar} and a {@code -groundtruth.jsonl} describing every scored
 * symbol. Wired as the {@code obfuscateCorpus} Gradle task.
 *
 * <pre>args: &lt;corpusDir&gt; &lt;outputDir&gt;</pre>
 */
public final class ObfuscateCorpusTool {
	private ObfuscateCorpusTool() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println("usage: ObfuscateCorpusTool <corpusDir> <outputDir>");
			System.exit(2);
			return;
		}

		Path corpusDir = Path.of(args[0]);
		Path outputDir = Path.of(args[1]);
		Files.createDirectories(outputDir);

		List<Path> jars = new ArrayList<>();

		try (Stream<Path> stream = Files.list(corpusDir)) {
			stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.sorted()
					.forEach(jars::add);
		}

		if (jars.isEmpty()) {
			System.err.println("no corpus jars found in " + corpusDir + " (run :enigma-llm-plugin:downloadCorpus first)");
			System.exit(1);
			return;
		}

		Obfuscator obfuscator = new TinyRemapperObfuscator();

		for (Path jar : jars) {
			String base = stripJar(jar.getFileName().toString());
			Path obfJar = outputDir.resolve(base + "-obf.jar");
			Path nostrJar = outputDir.resolve(base + "-obf-nostr.jar");
			Path groundTruth = outputDir.resolve(base + "-groundtruth.jsonl");

			ObfuscationResult result = obfuscator.obfuscate(jar, obfJar);
			writeGroundTruth(groundTruth, jar, result);
			// Structure-only track: same jar, every program string blanked (see StringScrubber). Shares
			// the ground truth and structure with the realistic jar; only string payloads differ.
			StringScrubber.scrub(obfJar, nostrJar);
			System.out.println(summary(base, result));
		}
	}

	private static void writeGroundTruth(Path file, Path sourceJar, ObfuscationResult result) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			for (ObfuscatedSymbol symbol : result.symbols()) {
				JsonObject object = new JsonObject();
				object.addProperty("obfuscator", result.obfuscatorName());
				object.addProperty("sourceJar", sourceJar.getFileName().toString());
				object.addProperty("kind", symbol.kind().name());
				object.addProperty("obfOwner", symbol.obfOwner());
				object.addProperty("obfName", symbol.obfName());
				object.addProperty("obfDesc", symbol.obfDesc());
				object.addProperty("realOwner", symbol.realOwner());
				object.addProperty("realName", symbol.realName());
				JsonArray acceptable = new JsonArray();
				symbol.acceptableRealNames().forEach(acceptable::add);
				object.add("acceptableRealNames", acceptable);
				object.addProperty("visibility", symbol.visibility());
				object.addProperty("slice", symbol.slice());
				object.addProperty("recoverableSlice", symbol.isRecoverableSlice());
				object.addProperty("obfuscated", symbol.obfuscated());
				object.addProperty("localIndex", symbol.localIndex());
				object.addProperty("localName", symbol.localName());
				writer.write(object.toString());
				writer.write('\n');
			}
		}
	}

	private static String summary(String base, ObfuscationResult result) {
		Map<String, Integer> byKind = new TreeMap<>();
		int recoverable = 0;

		for (ObfuscatedSymbol symbol : result.symbols()) {
			byKind.merge(symbol.kind().name(), 1, Integer::sum);

			if (symbol.isRecoverableSlice()) {
				recoverable++;
			}
		}

		return String.format("%-28s obfuscator=%s symbols=%d recoverable=%d %s",
				base, result.obfuscatorName(), result.symbols().size(), recoverable, byKind);
	}

	private static String stripJar(String fileName) {
		return fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - ".jar".length()) : fileName;
	}
}
