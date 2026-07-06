package cuchaz.enigma.llm;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

public final class LlmEvaluationHarness {
	private static final Gson GSON = new Gson();

	private LlmEvaluationHarness() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.err.println("Usage: LlmEvaluationHarness <cases.jsonl> <results.jsonl>");
			System.exit(2);
		}

		LlmConfig config = LlmConfig.load();

		if (!config.isConfigured()) {
			throw new IllegalStateException("Set ENIGMA_LLM_MODEL before running evaluation");
		}

		EvaluationSummary summary = run(config, Path.of(args[0]), Path.of(args[1]));
		System.out.printf(Locale.ROOT,
				"Evaluated %d cases: accepted=%d exact=%d usable=%d failed=%d invalidJson=%d truncated=%d "
						+ "parallelism=%d avgLatencyMs=%.1f wallClockMs=%d throughputPerSec=%.2f%n",
				summary.total, summary.accepted, summary.exact, summary.usable, summary.failed, summary.invalidJson,
				summary.truncated, summary.parallelism, summary.averageLatencyMillis(), summary.wallClockMillis,
				summary.throughputPerSecond());
	}

	static EvaluationSummary run(LlmConfig config, Path casesPath, Path resultsPath) throws IOException, InterruptedException {
		List<EvaluationCase> cases = Files.readAllLines(casesPath, StandardCharsets.UTF_8).stream()
				.map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.map(LlmEvaluationHarness::parseCase)
				.toList();
		OpenAiCompatibleClient client = new OpenAiCompatibleClient(config);
		int parallelism = Math.max(1, config.batchParallelism());

		Files.createDirectories(resultsPath.toAbsolutePath().getParent());

		long wallStartNanos = System.nanoTime();
		List<EvaluationResult> results = evaluateCases(config, client, cases, parallelism);
		long wallMillis = (System.nanoTime() - wallStartNanos) / 1_000_000L;

		EvaluationSummary summary = new EvaluationSummary();
		summary.parallelism = parallelism;
		summary.wallClockMillis = wallMillis;
		StringBuilder jsonl = new StringBuilder();

		for (EvaluationResult result : results) {
			summary.add(result);
			jsonl.append(result.toJson()).append('\n');
		}

		Files.writeString(resultsPath, jsonl.toString(), StandardCharsets.UTF_8);
		return summary;
	}

	// Runs cases through a fixed thread pool so the benchmark can actually measure request
	// parallelism, while preserving deterministic result ordering (results are collected in the
	// original case order regardless of completion order). The shared OpenAiCompatibleClient wraps
	// a thread-safe java.net.http.HttpClient and holds no per-request state, so it is safe to share.
	private static List<EvaluationResult> evaluateCases(LlmConfig config, OpenAiCompatibleClient client,
			List<EvaluationCase> cases, int parallelism) throws InterruptedException {
		if (parallelism <= 1) {
			List<EvaluationResult> results = new ArrayList<>(cases.size());

			for (EvaluationCase testCase : cases) {
				results.add(evaluateCase(config, client, testCase));
			}

			return results;
		}

		ExecutorService pool = Executors.newFixedThreadPool(parallelism);

		try {
			List<Future<EvaluationResult>> futures = new ArrayList<>(cases.size());

			for (EvaluationCase testCase : cases) {
				futures.add(pool.submit(() -> evaluateCase(config, client, testCase)));
			}

			List<EvaluationResult> results = new ArrayList<>(cases.size());

			for (Future<EvaluationResult> future : futures) {
				try {
					results.add(future.get());
				} catch (ExecutionException e) {
					// evaluateCase never rethrows, so this only fires on a JVM-level fault.
					throw new IllegalStateException("Unexpected evaluation task failure", e.getCause());
				}
			}

			return results;
		} finally {
			pool.shutdown();
		}
	}

	private static EvaluationResult evaluateCase(LlmConfig config, OpenAiCompatibleClient client, EvaluationCase testCase) {
		long startNanos = System.nanoTime();

		try {
			LlmSuggestion suggestion = client.suggestName(testCase.kind, testCase.targetName, testCase.prompt);
			Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
			return EvaluationResult.success(config.model(), testCase, suggestion, latency);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
			return EvaluationResult.failure(config.model(), testCase, e, latency);
		} catch (IOException | RuntimeException e) {
			Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
			return EvaluationResult.failure(config.model(), testCase, e, latency);
		}
	}

	static EvaluationCase parseCase(String line) {
		JsonObject json = JsonParser.parseString(line).getAsJsonObject();
		Set<String> acceptable = new LinkedHashSet<>();

		if (json.has("acceptable") && json.get("acceptable").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("acceptable")) {
				acceptable.add(element.getAsString());
			}
		}

		String expected = requiredString(json, "expected");
		acceptable.add(expected);
		return new EvaluationCase(
				requiredString(json, "id"),
				EntryKind.valueOf(requiredString(json, "kind")),
				requiredString(json, "targetName"),
				requiredString(json, "prompt"),
				expected,
				Set.copyOf(acceptable)
		);
	}

	private static String requiredString(JsonObject json, String key) {
		if (!json.has(key) || json.get(key).isJsonNull()) {
			throw new IllegalArgumentException("Missing evaluation field: " + key);
		}

		return json.get(key).getAsString();
	}

	record EvaluationCase(String id, EntryKind kind, String targetName, String prompt, String expected, Set<String> acceptable) {
	}

	static String errorCategory(Exception error) {
		if (message(error).contains("truncated at token limit")) {
			return "truncated";
		}

		if (containsCause(error, JsonParseException.class)
				|| message(error).contains("not valid OpenAI-compatible suggestion JSON")) {
			return "invalid_json";
		}

		if (containsCause(error, HttpTimeoutException.class)) {
			return "timeout";
		}

		if (message(error).startsWith("LLM endpoint returned HTTP")) {
			return "http";
		}

		return "other";
	}

	private static boolean containsCause(Throwable error, Class<? extends Throwable> causeType) {
		Throwable current = error;

		while (current != null) {
			if (causeType.isInstance(current)) {
				return true;
			}

			current = current.getCause();
		}

		return false;
	}

	private static String message(Throwable error) {
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}

	record EvaluationResult(String model, String id, EntryKind kind, String targetName, String expected, String suggestedName, List<String> alternatives, double confidence, String reasoning, boolean accepted, boolean exact, boolean usable, long latencyMillis, String errorCategory, String error) {
		static EvaluationResult success(String model, EvaluationCase testCase, LlmSuggestion suggestion, Duration latency) {
			boolean exact = testCase.expected.equals(suggestion.suggestedName());
			boolean usable = testCase.acceptable.contains(suggestion.suggestedName()) || suggestion.alternatives().stream().anyMatch(testCase.acceptable::contains);
			return new EvaluationResult(model, testCase.id, testCase.kind, testCase.targetName, testCase.expected, suggestion.suggestedName(), suggestion.alternatives(), suggestion.confidence(), suggestion.reasoning(), true, exact, usable, latency.toMillis(), "", "");
		}

		static EvaluationResult failure(String model, EvaluationCase testCase, Exception error, Duration latency) {
			return new EvaluationResult(model, testCase.id, testCase.kind, testCase.targetName, testCase.expected, "", List.of(), 0.0, "", false, false, false, latency.toMillis(), LlmEvaluationHarness.errorCategory(error), message(error));
		}

		String toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("model", this.model);
			json.addProperty("id", this.id);
			json.addProperty("kind", this.kind.name());
			json.addProperty("targetName", this.targetName);
			json.addProperty("expected", this.expected);
			json.addProperty("suggestedName", this.suggestedName);
			JsonArray alternativesJson = new JsonArray();
			this.alternatives.forEach(alternativesJson::add);
			json.add("alternatives", alternativesJson);
			json.addProperty("confidence", this.confidence);
			json.addProperty("reasoning", this.reasoning);
			json.addProperty("accepted", this.accepted);
			json.addProperty("exact", this.exact);
			json.addProperty("usable", this.usable);
			json.addProperty("latencyMillis", this.latencyMillis);
			json.addProperty("errorCategory", this.errorCategory);
			json.addProperty("error", this.error);
			return GSON.toJson(json);
		}
	}

	static final class EvaluationSummary {
		int total;
		int accepted;
		int exact;
		int usable;
		int failed;
		int invalidJson;
		int truncated;
		int parallelism = 1;
		long latencyMillis;
		long wallClockMillis;

		void add(EvaluationResult result) {
			this.total++;
			this.latencyMillis += result.latencyMillis;

			if (result.accepted) {
				this.accepted++;
			} else {
				this.failed++;

				if (result.errorCategory.equals("invalid_json")) {
					this.invalidJson++;
				} else if (result.errorCategory.equals("truncated")) {
					this.truncated++;
				}
			}

			if (result.exact) {
				this.exact++;
			}

			if (result.usable) {
				this.usable++;
			}
		}

		double averageLatencyMillis() {
			return this.total == 0 ? 0.0 : (double) this.latencyMillis / this.total;
		}

		double throughputPerSecond() {
			return this.wallClockMillis == 0 ? 0.0 : (double) this.total * 1000.0 / this.wallClockMillis;
		}
	}
}
