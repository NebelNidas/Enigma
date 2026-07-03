package cuchaz.enigma.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

class OpenAiCompatibleClient {
	private static final Gson GSON = new Gson();

	private final LlmConfig config;
	private final HttpClient client;

	OpenAiCompatibleClient(LlmConfig config) {
		this.config = config;
		this.client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(config.timeout())
				.build();
	}

	LlmSuggestion suggestName(EntryKind kind, String obfuscatedName, String prompt) throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("model", this.config.model());
		body.addProperty("temperature", 0.2);

		JsonArray messages = new JsonArray();
		messages.add(message("system", systemMessage()));
		messages.add(message("user", "Target Type: " + kind + "\nObfuscated Name: " + obfuscatedName + "\nContext:\n" + prompt));
		body.add("messages", messages);

		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
				.uri(chatCompletionsUri())
				.timeout(this.config.timeout())
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));

		if (this.config.apiKey() != null && !this.config.apiKey().isBlank()) {
			requestBuilder.header("Authorization", "Bearer " + this.config.apiKey());
		}

		HttpResponse<String> response = this.client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("LLM endpoint returned HTTP " + response.statusCode() + ": " + response.body());
		}

		return parseSuggestion(response.body());
	}

	static LlmSuggestion parseSuggestion(String responseBody) throws IOException {
		try {
			JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonArray choices = response.getAsJsonArray("choices");

			if (choices == null || choices.isEmpty()) {
				throw new IOException("LLM response did not contain choices");
			}

			JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");

			if (message == null || !message.has("content")) {
				throw new IOException("LLM response did not contain message content");
			}

			String content = extractJsonObject(stripMarkdownFence(requiredString(message, "content")));
			JsonObject suggestionJson = JsonParser.parseString(content).getAsJsonObject();
			String suggestedName = requiredString(suggestionJson, "suggestedName");
			double confidence = parseConfidence(suggestionJson);
			String reasoning = optionalString(suggestionJson, "reasoning");
			List<String> alternatives = new ArrayList<>();

			if (suggestionJson.has("alternatives") && !suggestionJson.get("alternatives").isJsonNull()) {
				if (!suggestionJson.get("alternatives").isJsonArray()) {
					throw new IOException("LLM suggestion alternatives must be an array");
				}

				for (JsonElement alternative : suggestionJson.getAsJsonArray("alternatives")) {
					if (!alternative.isJsonPrimitive() || !alternative.getAsJsonPrimitive().isString()) {
						throw new IOException("LLM suggestion alternatives must be strings");
					}

					alternatives.add(alternative.getAsString());
				}
			}

			return new LlmSuggestion(suggestedName, alternatives, confidence, reasoning);
		} catch (IllegalStateException | JsonParseException | NumberFormatException e) {
			throw new IOException("LLM response was not valid OpenAI-compatible suggestion JSON", e);
		}
	}

	private URI chatCompletionsUri() {
		String base = this.config.baseUrl().strip();

		if (base.endsWith("/chat/completions")) {
			return URI.create(base);
		}

		if (base.endsWith("/")) {
			return URI.create(base + "chat/completions");
		}

		return URI.create(base + "/chat/completions");
	}

	private static JsonObject message(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	private static String systemMessage() {
		return """
				You are an expert Java reverse engineer working on decompiled and bytecode-derived context.
				Suggest one precise Java identifier for the requested obfuscated target.
				Focus only on the target; use surrounding members as context.
				Use only evidence from the supplied bytecode context. Do not guess domain-specific names from the project, jar, package, or game alone.
				Use PascalCase for classes and camelCase for fields, methods, and parameters.
				For fields, methods, and parameters, never use underscores, screaming-case, or mixed constant-style names such as CONSTANT_A or cONSTANT_A.
				For top-level classes, you may return either a simple class name or a JVM internal name with package separators such as net/minecraft/TextureManager.
				Do not suggest names such as TextureManager, ShaderProgram, ModelLoader, Manager, Loader, Handler, or Registry unless fields, methods, inheritance, or call sites directly prove that role.
				If the evidence is weak or only suggests a broad category, choose a conservative descriptive name and set confidence below 0.50.
				Generate 2-4 alternatives before choosing the best suggestion.
				Respond only with valid JSON: {"reasoning":"string","alternatives":["string"],"suggestedName":"string","confidence":0.0}
				""";
	}

	private static String stripMarkdownFence(String content) {
		String stripped = content.strip();

		if (stripped.startsWith("```")) {
			int firstNewline = stripped.indexOf('\n');
			int lastFence = stripped.lastIndexOf("```");

			if (firstNewline >= 0 && lastFence > firstNewline) {
				return stripped.substring(firstNewline + 1, lastFence).strip();
			}
		}

		return stripped;
	}

	private static String extractJsonObject(String content) {
		String stripped = content.strip();
		int objectStart = stripped.indexOf('{');

		if (objectStart < 0) {
			return stripped;
		}

		int depth = 0;
		boolean inString = false;
		boolean escaped = false;

		for (int i = objectStart; i < stripped.length(); i++) {
			char c = stripped.charAt(i);

			if (escaped) {
				escaped = false;
				continue;
			}

			if (c == '\\' && inString) {
				escaped = true;
				continue;
			}

			if (c == '"') {
				inString = !inString;
				continue;
			}

			if (inString) {
				continue;
			}

			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;

				if (depth == 0) {
					return stripped.substring(objectStart, i + 1);
				}
			}
		}

		return stripped;
	}

	private static double parseConfidence(JsonObject json) throws IOException {
		if (!json.has("confidence") || json.get("confidence").isJsonNull()) {
			return 0.0;
		}

		JsonElement confidenceElement = json.get("confidence");
		double confidence;

		if (confidenceElement.isJsonPrimitive() && confidenceElement.getAsJsonPrimitive().isString()) {
			confidence = Double.parseDouble(confidenceElement.getAsString());
		} else {
			confidence = confidenceElement.getAsDouble();
		}

		if (!Double.isFinite(confidence)) {
			throw new IOException("LLM suggestion confidence must be finite");
		}

		return confidence;
	}

	private static String requiredString(JsonObject json, String key) throws IOException {
		if (!json.has(key) || !isString(json.get(key)) || json.get(key).getAsString().isBlank()) {
			throw new IOException("LLM suggestion did not contain " + key);
		}

		return json.get(key).getAsString();
	}

	private static String optionalString(JsonObject json, String key) throws IOException {
		if (!json.has(key) || json.get(key).isJsonNull()) {
			return "";
		}

		if (!isString(json.get(key))) {
			throw new IOException("LLM suggestion " + key + " must be a string");
		}

		return json.get(key).getAsString();
	}

	private static boolean isString(JsonElement element) {
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
	}
}
