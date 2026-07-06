package cuchaz.enigma.llm;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
		System.out.printf(Locale.ROOT, "Evaluated %d cases: accepted=%d exact=%d usable=%d failed=%d invalidJson=%d avgLatencyMs=%.1f%n",
				summary.total, summary.accepted, summary.exact, summary.usable, summary.failed, summary.invalidJson, summary.averageLatencyMillis());
	}

	static EvaluationSummary run(LlmConfig config, Path casesPath, Path resultsPath) throws IOException, InterruptedException {
		List<EvaluationCase> cases = Files.readAllLines(casesPath, StandardCharsets.UTF_8).stream()
				.map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.map(LlmEvaluationHarness::parseCase)
				.toList();
		OpenAiCompatibleClient client = new OpenAiCompatibleClient(config);
		EvaluationSummary summary = new EvaluationSummary();
		StringBuilder jsonl = new StringBuilder();

		Files.createDirectories(resultsPath.toAbsolutePath().getParent());

		for (EvaluationCase testCase : cases) {
			long startNanos = System.nanoTime();
			EvaluationResult result;

			try {
				LlmSuggestion suggestion = client.suggestName(testCase.kind, testCase.targetName, testCase.prompt);
				Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
				result = EvaluationResult.success(config.model(), testCase, suggestion, latency);
			} catch (IOException | RuntimeException e) {
				Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
				result = EvaluationResult.failure(config.model(), testCase, e, latency);
			}

			summary.add(result);
			jsonl.append(result.toJson()).append('\n');
		}

		Files.writeString(resultsPath, jsonl.toString(), StandardCharsets.UTF_8);
		return summary;
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
		long latencyMillis;

		void add(EvaluationResult result) {
			this.total++;
			this.latencyMillis += result.latencyMillis;

			if (result.accepted) {
				this.accepted++;
			} else {
				this.failed++;

				if (result.errorCategory.equals("invalid_json")) {
					this.invalidJson++;
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
	}
}
