package cuchaz.enigma.llm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.api.view.ProjectView;

/**
 * Phase A of the Hybrid 1.1 deobfuscation round-trip benchmark: drive the <em>real</em> suggestion
 * engine against an obfuscated corpus jar and score the recovered names against the ground truth.
 *
 * <p>For each {@code <base>-obf.jar} + {@code <base>-groundtruth.jsonl} pair produced by
 * {@link ObfuscateCorpusTool}, the harness
 * <ol>
 *   <li>opens the obfuscated jar headless via {@code Enigma.create().openJar(...)} — an
 *       {@link cuchaz.enigma.EnigmaProject}, which is the {@link ProjectView} the engine consumes
 *       (this is where the "an obf jar opens as a real project" assumption is verified);</li>
 *   <li>rebuilds the {@link LlmProjectIndex} from the same jar bytes (the same builder the production
 *       {@code LlmJarIndexerService} uses) and installs it on a fresh plugin;</li>
 *   <li>reconstructs an {@link EntryKey} for every ground-truth symbol and checks it actually
 *       resolves in that index — a mismatch means the recorded obfuscated identity drifted from the
 *       bytecode tiny-remapper emitted, which would silently starve the prompt of context;</li>
 *   <li>calls {@link LlmSuggestionEngine#requestSuggestion} per target and scores the result.</li>
 * </ol>
 *
 * <p>Scoring is gated to the recoverable slice (classes plus public/protected members) and reported
 * per-jar and per-kind. Exact and normalised matches are counted separately; the normalised match
 * folds case and non-alphanumeric noise so {@code getFoo}/{@code get_foo} count. Preservation-control
 * symbols ({@code obfuscated=false}) are bucketed apart and never averaged into recovery — success
 * there means the model recognised an already-meaningful name and returned it unchanged.
 *
 * <p>If the LLM endpoint is not configured ({@link LlmConfig#isConfigured()} is false) the harness
 * still runs the structural half — open, index, resolve every key — and reports it, so the round-trip
 * integrity can be verified without a live model. Set {@code ENIGMA_LLM_BENCH_LIMIT} to a positive
 * integer to sample that many targets per jar (deterministic stride) for a cheap smoke run.
 *
 * <pre>args: &lt;obfuscatedDir&gt; [resultsDir]</pre>
 */
public final class LlmObfuscationBenchmarkHarness {
	private static final String LIMIT_ENV = "ENIGMA_LLM_BENCH_LIMIT";
	/**
	 * Targeted re-run: when {@code ENIGMA_LLM_BENCH_RETRY_MANIFEST} points at a JSONL of
	 * {@code {jar, track, kind, obfOwner, obfName, obfDesc, localIndex}} keys, the harness skips its
	 * normal stratified sampling and scores ONLY those symbols (used to re-run infra-errored rows —
	 * timeout / context-overflow / 5xx — from a prior sweep without re-billing the rows that succeeded).
	 * Results land in a fresh {@code resultsDir} and are merged back by {@code retry_failed.py}; the
	 * preserved raw is never overwritten. The per-unit duplicate-name dedup starts empty over the retry
	 * subset (acceptable: the retried rows produced no suggestion originally, so held no prior name claim).
	 */
	private static final String RETRY_MANIFEST_ENV = "ENIGMA_LLM_BENCH_RETRY_MANIFEST";
	/** {@code base + "::" + trackLabel} -> symbol key-strings to retry; {@code null} = normal (full-sample) mode. */
	private static Map<String, Set<String>> retryKeys = null;

	private LlmObfuscationBenchmarkHarness() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.err.println("usage: LlmObfuscationBenchmarkHarness <obfuscatedDir> [resultsDir]");
			System.exit(2);
			return;
		}

		Path obfuscatedDir = Path.of(args[0]);
		Path resultsDir = args.length >= 2 ? Path.of(args[1]) : obfuscatedDir.resolve("benchmark");

		LlmConfig config = LlmConfig.load();
		boolean online = config.isConfigured();

		if (online) {
			// Route each model's results into its own subdir so a multi-model sweep never overwrites
			// itself (the per-jar/-track file names carry no model identifier). Offline structural
			// runs stay at the top level, unchanged.
			resultsDir = resultsDir.resolve(sanitizeModel(config.model()));
		}

		Files.createDirectories(resultsDir);

		retryKeys = loadRetryManifest();

		if (retryKeys != null) {
			int keyCount = retryKeys.values().stream().mapToInt(Set::size).sum();
			System.out.printf("  RETRY MODE: %d unit(s), %d target key(s) from %s%n",
					retryKeys.size(), keyCount, System.getenv(RETRY_MANIFEST_ENV));
		}

		if (online) {
			System.out.printf("Endpoint: %s model=%s%n  results -> %s%n", config.baseUrl(), config.model(), resultsDir);
			System.out.printf("  sample/jar/track seed=%d api=%s package=%s private=%s preservation=%s (0 = all)%n",
					SAMPLE_SEED, bucketLimit(Bucket.RECOVERY_API), bucketLimit(Bucket.RECOVERY_PACKAGE),
					bucketLimit(Bucket.RECOVERY_PRIVATE), bucketLimit(Bucket.PRESERVATION));
		} else {
			System.out.println("Endpoint not configured -- running structural check only (open + index + resolve).");
		}

		List<Path> groundTruths = new ArrayList<>();

		try (Stream<Path> stream = Files.list(obfuscatedDir)) {
			stream.filter(p -> p.getFileName().toString().endsWith("-groundtruth.jsonl"))
					.sorted()
					.forEach(groundTruths::add);
		}

		if (groundTruths.isEmpty()) {
			System.err.println("no *-groundtruth.jsonl found in " + obfuscatedDir + " (run :enigma-llm-evaluation:obfuscateCorpus first)");
			System.exit(1);
			return;
		}

		Map<Track, List<JarReport>> reports = new EnumMap<>(Track.class);

		for (Track track : Track.values()) {
			reports.put(track, new ArrayList<>());
		}

		// Phase 1 -- prepare every jar-track unit SEQUENTIALLY. Opening the jar and building the index is
		// cheap (seconds) and is the one step not proven thread-safe in Enigma core, so it never runs
		// concurrently; each prepared unit then owns an isolated project/plugin/engine/index.
		List<PreparedUnit> units = new ArrayList<>();

		for (Path groundTruth : groundTruths) {
			String base = stripSuffix(groundTruth.getFileName().toString(), "-groundtruth.jsonl");
			List<GroundTruthSymbol> symbols = loadGroundTruth(groundTruth, base);

			for (Track track : Track.values()) {
				Path obfJar = obfuscatedDir.resolve(base + track.jarSuffix());

				if (!Files.exists(obfJar)) {
					continue;
				}

				units.add(prepareUnit(base, obfJar, track, symbols, resultsDir));
			}
		}

		// Phase 2 -- score the units. Each unit's own loop stays strictly sequential (the order-dependent
		// duplicate-name dedup lives in its per-unit accumulator), but independent units run concurrently
		// when ENIGMA_LLM_BENCH_PARALLEL_UNITS > 1, feeding the multi-slot server so the GPU is not starved
		// between requests. Concurrent co-decode is NOT bit-identical to a single stream (see the handoff);
		// that is an accepted trade for throughput. Default 1 = the exact sequential behaviour.
		int parallelUnits = Math.max(1, readEnvInt("ENIGMA_LLM_BENCH_PARALLEL_UNITS", 1));

		if (parallelUnits > 1 && units.size() > 1) {
			System.out.printf("  parallel jar-track units: %d (of %d)%n", parallelUnits, units.size());
			runUnitsConcurrently(units, reports, resultsDir, config, online, parallelUnits);
		} else {
			for (PreparedUnit unit : units) {
				reports.get(unit.track()).add(runUnit(unit, resultsDir, config, online));
			}
		}

		for (Track track : Track.values()) {
			printSummary(track, reports.get(track), online);
		}
	}

	/**
	 * The two obfuscation tracks, never averaged together. {@code realistic} keeps string literals
	 * intact — faithful to how ProGuard/R8 (and Minecraft) leave strings untouched, so it measures what
	 * a reverse-engineer gets in practice. {@code structure-only} blanks every program string (see
	 * {@link StringScrubber}), isolating recovery from bytecode structure alone; their difference is the
	 * contribution of string context.
	 */
	private enum Track {
		REALISTIC("-obf.jar", "realistic"),
		STRUCTURE_ONLY("-obf-nostr.jar", "structure-only");

		private final String jarSuffix;
		private final String label;

		Track(String jarSuffix, String label) {
			this.jarSuffix = jarSuffix;
			this.label = label;
		}

		String jarSuffix() {
			return this.jarSuffix;
		}

		String label() {
			return this.label;
		}
	}

	/** An opened, indexed jar-track ready to score. Prepared sequentially; scored (possibly) in parallel. */
	// Code-inclusive context arm (M+C): when ENIGMA_LLM_BENCH_CODE=true, each METHOD/PARAMETER target's
	// decompiled (and track-normalized) body is appended to the metadata prompt, so the arm differs from the
	// metadata-only arm only by the added code. Off by default -> the existing metadata-only runs are unchanged.
	private static final boolean CODE_CONTEXT = Boolean.parseBoolean(
			System.getenv().getOrDefault("ENIGMA_LLM_BENCH_CODE", "false"));

	// Token-volume control arm (M++): when ENIGMA_LLM_BENCH_CODE_CONTROL=sterile (requires CODE_CONTEXT=true),
	// every METHOD/PARAMETER target that WOULD receive its real decompiled body instead receives a sterile,
	// unrelated boilerplate block character-matched to that body's length and labelled as padding. So M++ equals
	// M+C in prompt length but carries no code content of the target -> M+C-vs-M++ isolates code CONTENT from
	// token VOLUME. Pairs exactly with M+C: filler is emitted only where the real body could be extracted.
	private static final boolean CODE_CONTROL = "sterile".equalsIgnoreCase(
			System.getenv().getOrDefault("ENIGMA_LLM_BENCH_CODE_CONTROL", "off").strip());

	// Rotates the sterile pool start (pad-choice sensitivity check); default 0. Only used when CODE_CONTROL is on.
	private static final int CODE_CONTROL_SEED = resolveControlSeed();

	private static int resolveControlSeed() {
		String raw = System.getenv("ENIGMA_LLM_BENCH_CODE_CONTROL_SEED");

		if (raw == null || raw.isBlank()) {
			return 0;
		}

		try {
			return Integer.parseInt(raw.strip());
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	// When ENIGMA_LLM_DUMP_PROMPTS=<dir> is set, each scored target's fully rendered system+user prompt is
	// written (with its ground truth) to <dir>/<jar>-<track>-prompts.jsonl. This lets an offline structural
	// run (no endpoint) export the exact v2 prompts for replay through a model that has no HTTP endpoint
	// (e.g. Claude Fable via the subscription-only Agent tool), then score the replies offline.
	private static final Path DUMP_PROMPTS_DIR = resolveDumpDir();

	private static Path resolveDumpDir() {
		String dir = System.getenv("ENIGMA_LLM_DUMP_PROMPTS");
		return dir == null || dir.isBlank() ? null : Path.of(dir);
	}

	private record PreparedUnit(String base, Track track, Path obfJar, List<GroundTruthSymbol> symbols,
			ProjectView project, LlmNameProposalPlugin plugin, LlmSuggestionEngine engine, Path resultsFile,
			DecompiledMethodBodyProvider codeProvider) {
	}

	/**
	 * SEQUENTIAL setup for one jar-track: open the obfuscated jar as a real project and build the index.
	 * Kept off the concurrent path because Enigma's {@code openJar} is not proven thread-safe; each
	 * prepared unit owns an isolated project/plugin/engine so the scoring can then run in parallel.
	 */
	private static PreparedUnit prepareUnit(String base, Path obfJar, Track track, List<GroundTruthSymbol> symbols,
			Path resultsDir) throws IOException {
		ProjectView project = Enigma.create().openJar(obfJar, List.of(), ProgressListener.none());
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		plugin.setIndex(buildIndex(obfJar));
		LlmSuggestionEngine engine = new LlmSuggestionEngine(plugin);
		Path resultsFile = resultsDir.resolve(base + "-" + track.label() + "-benchmark.jsonl");
		DecompiledMethodBodyProvider codeProvider = CODE_CONTEXT ? new DecompiledMethodBodyProvider(obfJar) : null;
		return new PreparedUnit(base, track, obfJar, symbols, project, plugin, engine, resultsFile, codeProvider);
	}

	/**
	 * Score one prepared unit. This loop is strictly sequential per unit (its per-unit accumulator drives
	 * the order-dependent duplicate-name dedup); only whole units run concurrently. Touches nothing shared
	 * except the read-only config/symbols and the thread-safe HTTP client, and writes its own files.
	 */
	private static JarReport runUnit(PreparedUnit unit, Path resultsDir, LlmConfig config, boolean online)
			throws IOException {
		JarReport report = new JarReport(unit.base());

		try {
			// Retry mode: resolve THIS unit's manifest targets up front. Skip units with none (no empty result
			// file, no leak audit), and fail loud if any manifest key does not resolve to a ground-truth symbol
			// (the corpus changed since the run being retried), rather than silently scoring fewer rows.
			List<GroundTruthSymbol> retryTargets = null;

			if (retryKeys != null) {
				Set<String> wanted = retryKeys.getOrDefault(unit.base() + "::" + unit.track().label(), Set.of());

				if (wanted.isEmpty()) {
					return report;
				}

				retryTargets = new ArrayList<>();
				Set<String> matched = new HashSet<>();

				for (GroundTruthSymbol symbol : unit.symbols()) {
					if (wanted.contains(symbolKey(symbol))) {
						retryTargets.add(symbol);
						matched.add(symbolKey(symbol));
					}
				}

				if (!matched.containsAll(wanted)) {
					Set<String> unresolved = new HashSet<>(wanted);
					unresolved.removeAll(matched);
					throw new IllegalStateException("retry manifest has " + unresolved.size()
							+ " key(s) with no ground-truth symbol in " + unit.base() + "::" + unit.track().label()
							+ " (corpus changed?): " + unresolved);
				}

				retryTargets.sort(SAMPLE_ORDER);
			}

			// Write to a sibling .partial file and atomically promote it only after the whole unit finishes, so an
			// interrupted or failed re-run can never truncate a previously complete result file.
			Path resultsFile = unit.resultsFile();
			Path partialFile = resultsFile.resolveSibling(resultsFile.getFileName().toString() + ".partial");

			try (BufferedWriter writer = Files.newBufferedWriter(partialFile, StandardCharsets.UTF_8)) {
				if (retryTargets != null) {
					// Targeted re-run: score ONLY the manifest symbols, in the deterministic SAMPLE_ORDER,
					// keeping each symbol's real bucket for the summary.
					for (GroundTruthSymbol symbol : retryTargets) {
						TargetScore score = scoreTarget(unit.engine(), unit.codeProvider(), unit.track(), config,
								unit.project(), unit.plugin().getIndex(), symbol, online);
						report.add(score, bucketFor(symbol));
						writer.write(score.toJson().toString());
						writer.write('\n');
					}
				} else {
					Map<Bucket, List<GroundTruthSymbol>> buckets = new EnumMap<>(Bucket.class);

					for (Bucket bucket : Bucket.values()) {
						buckets.put(bucket, new ArrayList<>());
					}

					for (GroundTruthSymbol symbol : unit.symbols()) {
						buckets.get(bucketFor(symbol)).add(symbol);
					}

					for (Bucket bucket : Bucket.values()) {
						for (GroundTruthSymbol symbol : sample(unit.base(), bucket, buckets.get(bucket))) {
							TargetScore score = scoreTarget(unit.engine(), unit.codeProvider(), unit.track(), config,
									unit.project(), unit.plugin().getIndex(), symbol, online);
							report.add(score, bucket);
							writer.write(score.toJson().toString());
							writer.write('\n');
						}
					}
				}
			}

			promoteAtomically(partialFile, resultsFile);

			// The leak audit is a full-population diagnostic; it is meaningless for a targeted retry subset, so
			// skip it in retry mode (the merge never consumes leaks files anyway).
			if (retryKeys == null) {
				report.leaks = auditLeaks(unit.obfJar(), unit.symbols(), resultsDir, unit.base(), unit.track());
			}

			System.out.println(report.line(online));
			return report;
		} finally {
			closeQuietly(unit.codeProvider());
		}
	}

	private static void promoteAtomically(Path partial, Path target) throws IOException {
		// Atomic move only. If the target filesystem cannot do it, fail and leave the prior complete result (and
		// the .partial) untouched rather than risk a non-atomic replace that could drop it:
		// AtomicMoveNotSupportedException propagates, so an interrupted run never truncates a good result file.
		Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}

		try {
			closeable.close();
		} catch (Exception ignored) {
			// Best effort: a failure to release the decompiler's jar handle must not fail the run.
		}
	}

	/**
	 * Run the prepared units through a fixed thread pool of {@code parallelUnits}. Results are collected
	 * back into the per-track report lists; a failing unit aborts the run rather than silently dropping a
	 * jar-track from the totals.
	 */
	private static void runUnitsConcurrently(List<PreparedUnit> units, Map<Track, List<JarReport>> reports,
			Path resultsDir, LlmConfig config, boolean online, int parallelUnits) {
		ExecutorService pool = Executors.newFixedThreadPool(parallelUnits);
		Map<PreparedUnit, Future<JarReport>> futures = new LinkedHashMap<>();

		try {
			for (PreparedUnit unit : units) {
				Callable<JarReport> task = () -> runUnit(unit, resultsDir, config, online);
				futures.put(unit, pool.submit(task));
			}

			for (Map.Entry<PreparedUnit, Future<JarReport>> entry : futures.entrySet()) {
				reports.get(entry.getKey().track()).add(awaitReport(entry.getValue()));
			}
		} finally {
			pool.shutdown();
		}
	}

	private static JarReport awaitReport(Future<JarReport> future) {
		try {
			return future.get();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while awaiting a benchmark unit", interrupted);
		} catch (ExecutionException failure) {
			Throwable cause = failure.getCause();
			throw new IllegalStateException("benchmark unit failed", cause == null ? failure : cause);
		}
	}

	/**
	 * Recovery is scored in three visibility slices — {@code api} (headline), {@code package}
	 * (secondary), {@code private} (diagnostic, never in the headline) — plus the preservation control.
	 * The buckets are reported separately and never averaged.
	 */
	private enum Bucket {
		RECOVERY_API,
		RECOVERY_PACKAGE,
		RECOVERY_PRIVATE,
		PRESERVATION;
	}

	private static Bucket bucketFor(GroundTruthSymbol symbol) {
		if (!symbol.obfuscated()) {
			// Preservation controls keep their real name on purpose; they are drawn from the api slice.
			return Bucket.PRESERVATION;
		}

		return switch (symbol.slice()) {
		case "api" -> Bucket.RECOVERY_API;
		case "package" -> Bucket.RECOVERY_PACKAGE;
		default -> Bucket.RECOVERY_PRIVATE;
		};
	}

	/**
	 * Runs the diagnostic leak audit against the obfuscated jar and writes the sampled hits to
	 * {@code <base>-leaks.jsonl}. The oracle is every obfuscated symbol's real name (all slices);
	 * preservation-control names are excluded since their real name is kept on purpose.
	 */
	private static LeakAudit.LeakReport auditLeaks(Path obfJar, List<GroundTruthSymbol> symbols,
			Path resultsDir, String base, Track track) throws IOException {
		Set<String> realClassNames = new TreeSet<>();
		Set<String> realBinaryNames = new TreeSet<>();
		Set<String> realMemberNames = new TreeSet<>();

		for (GroundTruthSymbol symbol : symbols) {
			if (!symbol.obfuscated()) {
				continue;
			}

			if (symbol.kind() == EntryKind.CLASS) {
				realClassNames.add(symbol.realName());
				realBinaryNames.add(symbol.realOwner());
			} else {
				realMemberNames.add(symbol.realName());
			}
		}

		LeakAudit.LeakReport leaks = LeakAudit.audit(obfJar, realClassNames, realBinaryNames, realMemberNames);
		Path leaksFile = resultsDir.resolve(base + "-" + track.label() + "-leaks.jsonl");

		try (BufferedWriter writer = Files.newBufferedWriter(leaksFile, StandardCharsets.UTF_8)) {
			for (LeakAudit.LeakSample sample : leaks.samples()) {
				writer.write(sample.toJson().toString());
				writer.write('\n');
			}
		}

		return leaks;
	}

	private static TargetScore scoreTarget(LlmSuggestionEngine engine, DecompiledMethodBodyProvider codeProvider,
			Track track, LlmConfig config, ProjectView project,
			LlmProjectIndex index, GroundTruthSymbol symbol, boolean online) {
		EntryKey key = keyFor(symbol);
		boolean resolved = index.entry(key).isPresent();
		// Code-inclusive arm: the normalized decompiled body of the target method (METHOD/PARAMETER only),
		// or null when the arm is off, the kind is out of scope, or the body cannot be extracted.
		String codeSection = codeSection(codeProvider, track, symbol, key);
		// AUTO resolves per-target to graph or simple context; tag it per row so the api headline can be
		// split by backend post-hoc (otherwise one model can look better only because it drew richer contexts).
		String contextBackend = LlmPromptBuilder.resolveBackend(key, index, config.contextBackend()).configValue();
		// What AUTO would independently route this target to, regardless of the configured backend. Logging
		// it under every forced-backend run lets a post-hoc join answer "did AUTO pick the winning backend?":
		// score each target under forced owner AND forced graph, then check per target whether the empirically
		// better backend matches autoBackend. Divergence = AUTO's heuristic leaves recovery on the table.
		String autoBackend = LlmPromptBuilder.resolveBackend(key, index, LlmContextBackend.AUTO).configValue();
		// Rebuild the delivered prompt (same backend the engine uses) purely to log its length and whether the
		// client-side char cap fired. Prompt construction is local (no network) so this also populates the
		// structural/offline path -> a no-endpoint run is a GPU-free prompt/truncation audit. Batch suggestions
		// are empty on the per-target api slice, so this matches what requestSuggestion sends.
		String loggedPrompt = new LlmPromptBuilder().build(key, project, index, config.contextBackend(),
				config.analysisHints(), List.of(), codeSection, CODE_CONTROL);
		int promptChars = loggedPrompt.length();
		boolean promptTruncated = loggedPrompt.contains(LlmPromptBuilder.TRUNCATION_MARKER);
		// A code-shaped block is present (real body in M+C, sterile filler in M++). codeIncluded stays true for
		// both so the block's presence and codeChars are logged; contextMode is the authoritative arm label.
		boolean codeIncluded = codeSection != null;
		int codeChars = codeSection == null ? 0 : codeSection.length();
		String contextMode = contextMode(codeIncluded);

		if (DUMP_PROMPTS_DIR != null) {
			dumpPrompt(config, track, symbol, loggedPrompt, codeIncluded, contextMode);
		}

		if (!online) {
			return TargetScore.structural(symbol, resolved, contextBackend, autoBackend, promptChars, promptTruncated,
					contextMode, codeIncluded, codeChars);
		}

		String suggested = null;
		List<String> alternatives = List.of();
		double confidence = 0.0;
		String error = null;

		// Wall-clock latency of the single suggestion round-trip (network + inference), for weighing
		// slower/stronger models against faster ones. Includes only requestSuggestion, not local scoring.
		long startNanos = System.nanoTime();

		try {
			LlmSuggestion suggestion = codeSection != null
					? engine.requestSuggestion(config, project, key, codeSection, CODE_CONTROL)
					: engine.requestSuggestion(config, project, key);
			suggested = suggestion.suggestedName();
			alternatives = suggestion.alternatives();
			confidence = suggestion.confidence();
		} catch (RuntimeException ex) {
			error = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
		}

		// Preservation-control correction: on a preservation target (never obfuscated) the correct action is to
		// KEEP the name, but the engine rejects "suggest the current name" as a no-op rename
		// (LlmNameValidator: !name.equals(key.name())) and throws, which would otherwise be logged as an error
		// instead of a correct preservation -- so the control could never credit a correct keep (empirically
		// clean-preservation count was 0). Detect that exact case from the validation message and score it as
		// the correct kept name. Gated on !obfuscated AND the parsed name equalling the real name, so it never
		// masks a genuine invalid-identifier failure on a recovery target.
		if (error != null && !symbol.obfuscated()
				&& error.contains("Invalid Java identifier suggested for")
				&& error.endsWith(": " + symbol.realName())) {
			suggested = symbol.realName();
			error = null;
		}

		long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;

		return TargetScore.scored(symbol, resolved, contextBackend, autoBackend, promptChars, promptTruncated,
				suggested, alternatives, confidence, error, latencyMs, contextMode, codeIncluded, codeChars);
	}

	/** The arm label for a scored row. Distinguishes M+C (real code) from M++ (sterile length-control padding). */
	private static String contextMode(boolean codeIncluded) {
		if (codeIncluded) {
			return CODE_CONTROL ? "metadata+lengthcontrol" : "metadata+code";
		}

		return CODE_CONTEXT ? "metadata_only(code-unavailable)" : "metadata_only";
	}

	/**
	 * The normalized decompiled snippet to append for the code arm, or {@code null} when the arm is off, no
	 * supported code snippet can be decompiled/found, or the target kind is still deferred. For METHOD and
	 * PARAMETER this is the target/containing method body; for FIELD this is an inline decompiled initializer
	 * declaration when Vineflower renders one. CLASS member-body context is still deferred until it has a
	 * deterministic target-name-blind member selection policy.
	 */
	private static String codeSection(DecompiledMethodBodyProvider codeProvider, Track track,
			GroundTruthSymbol symbol, EntryKey key) {
		if (codeProvider == null || (key.kind() != EntryKind.METHOD
				&& key.kind() != EntryKind.PARAMETER && key.kind() != EntryKind.FIELD)) {
			return null;
		}

		Optional<String> raw = key.kind() == EntryKind.FIELD
				? codeProvider.fieldInitializerSource(symbol.obfOwner(), symbol.obfName())
				: codeProvider.methodSource(symbol.obfOwner(), symbol.obfName(), symbol.obfDesc());

		if (raw.isEmpty()) {
			return null;
		}

		CodePromptNormalizer.Track normTrack = track.label().startsWith("realistic")
				? CodePromptNormalizer.Track.REALISTIC
				: CodePromptNormalizer.Track.STRUCTURE_ONLY;
		String normalized = CodePromptNormalizer.normalize(raw.get(), normTrack);

		if (normalized == null || normalized.isBlank()) {
			return null;
		}

		// M++ token-volume control: replace the real body with a sterile block matched so the FULL appended
		// block (header + body) equals the M+C block character-for-character. The padding header is shorter
		// than the code header, so the filler body absorbs that deficit. Emitted only here, so a
		// target the code arm skipped (no body) is skipped by the control arm too.
		if (CODE_CONTROL) {
			int fillerChars = normalized.length() + LlmPromptBuilder.lengthControlHeaderDeficit();
			return CodePromptControl.sterileFiller(fillerChars, CODE_CONTROL_SEED);
		}

		return normalized;
	}

	private static synchronized void dumpPrompt(LlmConfig config, Track track, GroundTruthSymbol symbol,
			String userPrompt, boolean codeIncluded, String contextMode) {
		try {
			Files.createDirectories(DUMP_PROMPTS_DIR);
			Path file = DUMP_PROMPTS_DIR.resolve(symbol.jar() + "-" + track.label() + "-prompts.jsonl");
			JsonObject object = new JsonObject();
			object.addProperty("jar", symbol.jar());
			object.addProperty("kind", symbol.kind().name());
			object.addProperty("slice", symbol.slice());
			object.addProperty("preservationControl", !symbol.obfuscated());
			object.addProperty("obfOwner", symbol.obfOwner());
			object.addProperty("obfName", symbol.obfName());
			object.addProperty("obfDesc", symbol.obfDesc());
			object.addProperty("localIndex", symbol.localIndex());
			object.addProperty("expected", symbol.realName());
			JsonArray acceptable = new JsonArray();
			symbol.acceptableRealNames().forEach(acceptable::add);
			object.add("acceptable", acceptable);
			object.addProperty("codeIncluded", codeIncluded);
			object.addProperty("contextMode", contextMode);
			object.addProperty("systemPrompt", OpenAiCompatibleClient.systemMessage(config));
			object.addProperty("userPrompt", userPrompt);
			Files.writeString(file, object + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			System.err.println("prompt dump failed for " + symbol.obfName() + ": " + e.getMessage());
		}
	}

	private static EntryKey keyFor(GroundTruthSymbol symbol) {
		if (symbol.kind() == EntryKind.CLASS) {
			// EntryKey for a class carries the full internal name in both owner and name.
			return new EntryKey(EntryKind.CLASS, symbol.obfOwner(), symbol.obfOwner(), "");
		}

		if (symbol.kind() == EntryKind.PARAMETER) {
			// Parameters are located by the owning method plus the JVM local-variable slot; localName is
			// left blank (never the truth name -- EntryKey ignores it for equality and it must not leak).
			return new EntryKey(EntryKind.PARAMETER, symbol.obfOwner(), symbol.obfName(), symbol.obfDesc(),
					symbol.localIndex(), "");
		}

		return new EntryKey(symbol.kind(), symbol.obfOwner(), symbol.obfName(), symbol.obfDesc());
	}

	/** Rebuilds the LLM index from the obfuscated jar bytes -- the same builder the real service uses. */
	private static LlmProjectIndex buildIndex(Path obfJar) throws IOException {
		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();

		try (ZipFile zip = new ZipFile(obfJar.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String entryName = entry.getName();

				if (entry.isDirectory() || !entryName.endsWith(".class") || entryName.endsWith("module-info.class")) {
					continue;
				}

				try (InputStream in = zip.getInputStream(entry)) {
					ClassNode node = new ClassNode();
					new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
					builder.accept(node);
				}
			}
		}

		return builder.build();
	}

	/**
	 * Stable identity of a symbol within its (jar, track): the same tuple {@link #keyFor} keys the index on,
	 * flattened to a string so it can be matched against the retry manifest without reconstructing EntryKeys.
	 */
	private static String symbolKey(GroundTruthSymbol symbol) {
		return symbol.kind().name() + "|" + symbol.obfOwner() + "|" + symbol.obfName() + "|"
				+ symbol.obfDesc() + "|" + symbol.localIndex();
	}

	/**
	 * Loads the optional retry manifest ({@link #RETRY_MANIFEST_ENV}) into {@code base::track -> {symbolKey}}.
	 * Returns {@code null} when the env var is unset/blank (normal full-sample mode). Each manifest line is
	 * {@code {jar, track, kind, obfOwner, obfName, obfDesc, localIndex}}; {@code jar} is the corpus base name
	 * and {@code track} the track label ("realistic" / "structure-only"), matching {@code prepareUnit}.
	 */
	private static Map<String, Set<String>> loadRetryManifest() throws IOException {
		String path = System.getenv(RETRY_MANIFEST_ENV);

		if (path == null || path.isBlank()) {
			return null;
		}

		Map<String, Set<String>> map = new HashMap<>();

		try (BufferedReader reader = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}

				JsonObject object = JsonParser.parseString(line).getAsJsonObject();
				String unitKey = object.get("jar").getAsString() + "::" + object.get("track").getAsString();
				String symKey = object.get("kind").getAsString() + "|" + object.get("obfOwner").getAsString() + "|"
						+ object.get("obfName").getAsString() + "|" + object.get("obfDesc").getAsString() + "|"
						+ (object.has("localIndex") ? object.get("localIndex").getAsInt() : -1);
				map.computeIfAbsent(unitKey, k -> new HashSet<>()).add(symKey);
			}
		}

		return map;
	}

	private static List<GroundTruthSymbol> loadGroundTruth(Path file, String jar) throws IOException {
		List<GroundTruthSymbol> symbols = new ArrayList<>();

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}

				JsonObject object = JsonParser.parseString(line).getAsJsonObject();
				Set<String> acceptable = new TreeSet<>();
				JsonArray array = object.getAsJsonArray("acceptableRealNames");

				if (array != null) {
					array.forEach(element -> acceptable.add(element.getAsString()));
				}

				boolean recoverable = object.get("recoverableSlice").getAsBoolean();
				String visibility = object.get("visibility").getAsString();
				// Newer ground truth carries an explicit slice; fall back to deriving it from the older
				// recoverableSlice + visibility fields so stale corpora still load.
				String slice = object.has("slice") ? object.get("slice").getAsString()
						: recoverable ? "api" : visibility.equals("private") ? "private" : "package";

				symbols.add(new GroundTruthSymbol(
						jar,
						EntryKind.valueOf(object.get("kind").getAsString()),
						object.get("obfOwner").getAsString(),
						object.get("obfName").getAsString(),
						object.get("obfDesc").getAsString(),
						object.get("realOwner").getAsString(),
						object.get("realName").getAsString(),
						Set.copyOf(acceptable),
						visibility,
						slice,
						recoverable,
						object.get("obfuscated").getAsBoolean(),
						object.has("localIndex") ? object.get("localIndex").getAsInt() : -1,
						object.has("localName") ? object.get("localName").getAsString() : ""));
			}
		}

		return symbols;
	}

	/**
	 * Seeded stratified-random sample WITHOUT replacement, per {@code (base, bucket)}. The seed depends
	 * ONLY on the jar base and the bucket -- never the track or the model -- so realistic vs
	 * structure-only and every model in the sweep score the IDENTICAL target set (paired comparisons),
	 * and the whole sweep is reproducible. A per-bucket cap ({@link #bucketLimit}) lets the api headline
	 * slice be sampled deeply while the diagnostic private slice stays shallow. {@code limit <= 0} or
	 * {@code >= size} keeps everything (offline structural runs score the full population).
	 */
	private static List<GroundTruthSymbol> sample(String base, Bucket bucket, List<GroundTruthSymbol> symbols) {
		List<GroundTruthSymbol> sorted = new ArrayList<>(symbols);
		sorted.sort(SAMPLE_ORDER);

		int limit = bucketLimit(bucket);

		if (limit <= 0 || limit >= sorted.size()) {
			return sorted;
		}

		long seed = SAMPLE_SEED ^ ((long) base.hashCode() << 21) ^ bucket.name().hashCode();
		Collections.shuffle(sorted, new Random(seed));
		List<GroundTruthSymbol> picked = new ArrayList<>(sorted.subList(0, limit));
		picked.sort(SAMPLE_ORDER);
		return picked;
	}

	private static final Comparator<GroundTruthSymbol> SAMPLE_ORDER =
			Comparator.comparing((GroundTruthSymbol symbol) -> symbol.kind().name())
					.thenComparing(GroundTruthSymbol::obfOwner)
					.thenComparing(GroundTruthSymbol::obfName)
					.thenComparing(GroundTruthSymbol::obfDesc)
					.thenComparingInt(GroundTruthSymbol::localIndex);

	private static final long SAMPLE_SEED = readEnvInt("ENIGMA_LLM_BENCH_SEED", 1234567);

	/**
	 * Per-bucket sample cap. Each bucket reads its own env var; unset falls back to the uniform
	 * {@code ENIGMA_LLM_BENCH_LIMIT} (the smoke {@code -Plimit}), else 0 = score everything.
	 */
	private static int bucketLimit(Bucket bucket) {
		String var = switch (bucket) {
		case RECOVERY_API -> "ENIGMA_LLM_BENCH_API";
		case RECOVERY_PACKAGE -> "ENIGMA_LLM_BENCH_PACKAGE";
		case RECOVERY_PRIVATE -> "ENIGMA_LLM_BENCH_PRIVATE";
		case PRESERVATION -> "ENIGMA_LLM_BENCH_PRESERVATION";
		};

		int perBucket = readEnvInt(var, -1);
		return perBucket >= 0 ? perBucket : readLimit();
	}

	private static int readLimit() {
		return Math.max(0, readEnvInt(LIMIT_ENV, 0));
	}

	private static int readEnvInt(String name, int fallback) {
		String raw = System.getenv(name);

		if (raw == null || raw.isBlank()) {
			return fallback;
		}

		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException ignored) {
			System.err.println("ignoring non-integer " + name + "=" + raw);
			return fallback;
		}
	}

	private static void printSummary(Track track, List<JarReport> reports, boolean online) {
		if (reports.isEmpty()) {
			return;
		}

		System.out.println();
		System.out.println("=== Phase A summary [" + track.label() + "] ===");

		JarReport total = new JarReport("TOTAL");

		for (JarReport report : reports) {
			total.absorb(report);
		}

		for (JarReport report : reports) {
			System.out.println(report.line(online));
		}

		System.out.println(total.line(online));

		if (online) {
			System.out.println();
			System.out.println("Per-kind (recovery api slice, all jars):");
			total.stats(Bucket.RECOVERY_API).printByKind();

			KindStats pkg = total.stats(Bucket.RECOVERY_PACKAGE);

			if (pkg.attempted > 0) {
				System.out.println("Per-kind (recovery package slice, all jars):");
				pkg.printByKind();
			}

			KindStats priv = total.stats(Bucket.RECOVERY_PRIVATE);

			if (priv.attempted > 0) {
				System.out.println("Recovery private slice (diagnostic, never in the headline): " + priv.describe());
			}

			KindStats preservation = total.stats(Bucket.PRESERVATION);

			if (preservation.attempted > 0) {
				System.out.println("Preservation control (kept-unchanged, all jars): "
						+ preservation.describePreservation());
			}
		}
	}

	private static String stripSuffix(String value, String suffix) {
		return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
	}

	/** Turn a model id (may contain {@code @ / : .}) into a safe single path segment for the results dir. */
	private static String sanitizeModel(String model) {
		String cleaned = model.strip().replaceAll("[^A-Za-z0-9._-]+", "_");
		return cleaned.isEmpty() ? "model" : cleaned;
	}

	private static String normalize(String value) {
		if (value == null) {
			return "";
		}

		StringBuilder builder = new StringBuilder(value.length());

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (Character.isLetterOrDigit(c)) {
				builder.append(Character.toLowerCase(c));
			}
		}

		return builder.toString();
	}

	/** One ground-truth row loaded back from the {@code -groundtruth.jsonl}. */
	private record GroundTruthSymbol(String jar, EntryKind kind, String obfOwner, String obfName, String obfDesc,
			String realOwner, String realName, Set<String> acceptableRealNames, String visibility, String slice,
			boolean recoverable, boolean obfuscated, int localIndex, String localName) {
	}

	/** The outcome of one target: structural resolution plus (if online) the scored suggestion. */
	private record TargetScore(GroundTruthSymbol symbol, boolean resolvedInIndex, boolean attempted,
			String contextBackend, String autoBackend, int promptChars, boolean promptTruncated,
			String suggested, List<String> alternatives, double confidence, String error,
			boolean exact, boolean normalized, boolean usable, long latencyMs,
			String contextMode, boolean codeIncluded, int codeChars) {
		static TargetScore structural(GroundTruthSymbol symbol, boolean resolved, String contextBackend,
				String autoBackend, int promptChars, boolean promptTruncated,
				String contextMode, boolean codeIncluded, int codeChars) {
			return new TargetScore(symbol, resolved, false, contextBackend, autoBackend, promptChars, promptTruncated,
					null, List.of(), 0.0, null, false, false, false, -1L, contextMode, codeIncluded, codeChars);
		}

		static TargetScore scored(GroundTruthSymbol symbol, boolean resolved, String contextBackend,
				String autoBackend, int promptChars, boolean promptTruncated, String suggested,
				List<String> alternatives, double confidence, String error, long latencyMs,
				String contextMode, boolean codeIncluded, int codeChars) {
			boolean exact = suggested != null && symbol.acceptableRealNames().contains(suggested);
			boolean normalized = suggested != null && matchesNormalized(symbol.acceptableRealNames(), suggested);
			boolean usable = exact || normalized || matchesAny(symbol.acceptableRealNames(), alternatives);
			return new TargetScore(symbol, resolved, true, contextBackend, autoBackend, promptChars, promptTruncated,
					suggested, alternatives, confidence, error, exact, normalized, usable, latencyMs,
					contextMode, codeIncluded, codeChars);
		}

		private static boolean matchesNormalized(Set<String> acceptable, String candidate) {
			String needle = normalize(candidate);

			for (String name : acceptable) {
				if (normalize(name).equals(needle)) {
					return true;
				}
			}

			return false;
		}

		private static boolean matchesAny(Set<String> acceptable, List<String> candidates) {
			for (String candidate : candidates) {
				if (acceptable.contains(candidate) || matchesNormalized(acceptable, candidate)) {
					return true;
				}
			}

			return false;
		}

		JsonObject toJson() {
			JsonObject object = new JsonObject();
			object.addProperty("jar", this.symbol.jar());
			object.addProperty("kind", this.symbol.kind().name());
			object.addProperty("visibility", this.symbol.visibility());
			object.addProperty("slice", this.symbol.slice());
			object.addProperty("preservationControl", !this.symbol.obfuscated());
			object.addProperty("obfOwner", this.symbol.obfOwner());
			object.addProperty("obfName", this.symbol.obfName());
			object.addProperty("obfDesc", this.symbol.obfDesc());
			object.addProperty("localIndex", this.symbol.localIndex());
			object.addProperty("localName", this.symbol.localName());
			object.addProperty("expected", this.symbol.realName());
			JsonArray acceptable = new JsonArray();
			this.symbol.acceptableRealNames().forEach(acceptable::add);
			object.add("acceptable", acceptable);
			object.addProperty("resolvedInIndex", this.resolvedInIndex);
			object.addProperty("contextBackend", this.contextBackend);
			object.addProperty("autoBackend", this.autoBackend);
			object.addProperty("promptChars", this.promptChars);
			object.addProperty("promptTruncated", this.promptTruncated);
			object.addProperty("attempted", this.attempted);
			object.addProperty("suggested", this.suggested);
			JsonArray alternatives = new JsonArray();
			this.alternatives.forEach(alternatives::add);
			object.add("alternatives", alternatives);
			object.addProperty("confidence", this.confidence);
			object.addProperty("exact", this.exact);
			object.addProperty("normalized", this.normalized);
			object.addProperty("usable", this.usable);
			object.addProperty("latencyMs", this.latencyMs);
			object.addProperty("contextMode", this.contextMode);
			object.addProperty("codeIncluded", this.codeIncluded);
			object.addProperty("codeChars", this.codeChars);
			object.addProperty("error", this.error);
			return object;
		}
	}

	/** Running counts for one bucket (recovery or preservation). */
	private static final class KindStats {
		private int attempted;
		private int resolved;
		private int exact;
		private int normalized;
		private int usable;
		private int errors;
		private final Map<EntryKind, int[]> byKind = new TreeMap<>();

		void add(TargetScore score) {
			this.attempted++;

			if (score.resolvedInIndex()) {
				this.resolved++;
			}

			int[] slot = this.byKind.computeIfAbsent(score.symbol().kind(), k -> new int[2]);
			slot[0]++;

			if (score.error() != null) {
				this.errors++;
			}

			if (score.exact() || score.normalized()) {
				this.exact += score.exact() ? 1 : 0;
				this.normalized += score.normalized() && !score.exact() ? 1 : 0;
				slot[1]++;
			}

			if (score.usable()) {
				this.usable++;
			}
		}

		void absorb(KindStats other) {
			this.attempted += other.attempted;
			this.resolved += other.resolved;
			this.exact += other.exact;
			this.normalized += other.normalized;
			this.usable += other.usable;
			this.errors += other.errors;
			other.byKind.forEach((kind, counts) -> {
				int[] slot = this.byKind.computeIfAbsent(kind, k -> new int[2]);
				slot[0] += counts[0];
				slot[1] += counts[1];
			});
		}

		String describe() {
			int matched = this.exact + this.normalized;
			return String.format("n=%d resolved=%d exact=%d norm=%d matched=%d usable=%d err=%d",
					this.attempted, this.resolved, this.exact, this.normalized, matched, this.usable, this.errors);
		}

		/**
		 * Preservation-bucket view: for a kept-unchanged symbol the only meaningful outcomes are
		 * {@code left_unchanged} (the model returned the real name verbatim — exact) and
		 * {@code unnecessary_rename} (it proposed anything else); errors are counted apart.
		 */
		String describePreservation() {
			int renamed = this.attempted - this.exact - this.errors;
			return String.format("n=%d resolved=%d left_unchanged=%d unnecessary_rename=%d err=%d",
					this.attempted, this.resolved, this.exact, renamed, this.errors);
		}

		void printByKind() {
			this.byKind.forEach((kind, counts) ->
					System.out.printf("  %-9s n=%d matched=%d%n", kind, counts[0], counts[1]));
		}
	}

	/** Per-jar aggregation over the four buckets plus the leak audit. */
	private static final class JarReport {
		private final String base;
		private final Map<Bucket, KindStats> buckets = new EnumMap<>(Bucket.class);
		private LeakAudit.LeakReport leaks;

		JarReport(String base) {
			this.base = base;

			for (Bucket bucket : Bucket.values()) {
				this.buckets.put(bucket, new KindStats());
			}
		}

		void add(TargetScore score, Bucket bucket) {
			this.buckets.get(bucket).add(score);
		}

		KindStats stats(Bucket bucket) {
			return this.buckets.get(bucket);
		}

		void absorb(JarReport other) {
			for (Bucket bucket : Bucket.values()) {
				this.buckets.get(bucket).absorb(other.buckets.get(bucket));
			}
		}

		String line(boolean online) {
			KindStats api = stats(Bucket.RECOVERY_API);
			KindStats pkg = stats(Bucket.RECOVERY_PACKAGE);
			KindStats priv = stats(Bucket.RECOVERY_PRIVATE);
			KindStats preservation = stats(Bucket.PRESERVATION);

			if (!online) {
				return String.format(
						"%-28s api[n=%d resolved=%d] pkg[n=%d resolved=%d] priv[n=%d resolved=%d]"
								+ " preservation[n=%d resolved=%d]%s",
						this.base, api.attempted, api.resolved, pkg.attempted, pkg.resolved,
						priv.attempted, priv.resolved, preservation.attempted, preservation.resolved, leakPart());
			}

			// api is the headline; pkg is secondary; priv (starred) is diagnostic, never blended in.
			String pkgPart = pkg.attempted > 0 ? "  pkg[" + pkg.describe() + "]" : "";
			String privPart = priv.attempted > 0 ? "  priv*[" + priv.describe() + "]" : "";
			String preservationPart = preservation.attempted > 0
					? "  preservation[" + preservation.describePreservation() + "]" : "";
			return String.format("%-28s api[%s]%s%s%s%s", this.base, api.describe(),
					pkgPart, privPart, preservationPart, leakPart());
		}

		private String leakPart() {
			return this.leaks == null ? "" : "  leaks[" + this.leaks.describe() + "]";
		}
	}
}
