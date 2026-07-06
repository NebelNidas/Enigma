package cuchaz.enigma.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class LlmEvaluationSummaryTool {
	private LlmEvaluationSummaryTool() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("Usage: LlmEvaluationSummaryTool <results.jsonl>");
			System.exit(2);
		}

		Map<String, Summary> summaries = summarizeByGroup(Path.of(args[0]));

		for (Map.Entry<String, Summary> entry : summaries.entrySet()) {
			Summary summary = entry.getValue();
			System.out.printf(Locale.ROOT, "%s: total=%d accepted=%d exact=%d usable=%d failed=%d invalidJson=%d avgLatencyMs=%.1f%n",
					entry.getKey(), summary.total, summary.accepted, summary.exact, summary.usable, summary.failed, summary.invalidJson, summary.averageLatencyMillis());
		}
	}

	static Map<String, Summary> summarizeByGroup(Path resultsPath) throws IOException {
		Map<String, Summary> summaries = new LinkedHashMap<>();

		for (String rawLine : Files.readAllLines(resultsPath, StandardCharsets.UTF_8)) {
			String line = rawLine.strip();

			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}

			JsonObject json = JsonParser.parseString(line).getAsJsonObject();
			String group = json.has("backend") && !json.get("backend").isJsonNull() ? json.get("backend").getAsString() : "all";
			summaries.computeIfAbsent(group, ignored -> new Summary()).add(json);
		}

		return summaries;
	}

	static final class Summary {
		int total;
		int accepted;
		int exact;
		int usable;
		int failed;
		int invalidJson;
		long latencyMillis;

		void add(JsonObject result) {
			this.total++;
			this.latencyMillis += result.has("latencyMillis") && !result.get("latencyMillis").isJsonNull() ? result.get("latencyMillis").getAsLong() : 0L;

			if (booleanField(result, "accepted")) {
				this.accepted++;
			} else {
				this.failed++;

				if (stringField(result, "errorCategory").equals("invalid_json")) {
					this.invalidJson++;
				}
			}

			if (booleanField(result, "exact")) {
				this.exact++;
			}

			if (booleanField(result, "usable")) {
				this.usable++;
			}
		}

		double averageLatencyMillis() {
			return this.total == 0 ? 0.0 : (double) this.latencyMillis / this.total;
		}

		private static boolean booleanField(JsonObject json, String field) {
			return json.has(field) && !json.get(field).isJsonNull() && json.get(field).getAsBoolean();
		}

		private static String stringField(JsonObject json, String field) {
			return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsString() : "";
		}
	}
}
