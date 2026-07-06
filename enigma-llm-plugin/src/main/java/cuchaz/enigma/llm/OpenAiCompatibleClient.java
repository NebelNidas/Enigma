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
	// Tuned for the default non-thinking interactive path. Reasoning/deep-analysis modes need a separate, larger response budget.
	private static final int MAX_RESPONSE_TOKENS = 384;

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
		body.addProperty("max_tokens", MAX_RESPONSE_TOKENS);
		body.add("response_format", suggestionResponseFormat());

		JsonArray messages = new JsonArray();
		messages.add(message("system", systemMessage(this.config)));
		messages.add(message("user", prompt));
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

	List<String> listModels() throws IOException, InterruptedException {
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
				.uri(modelsUri())
				.timeout(this.config.timeout())
				.header("Accept", "application/json")
				.GET();

		if (this.config.apiKey() != null && !this.config.apiKey().isBlank()) {
			requestBuilder.header("Authorization", "Bearer " + this.config.apiKey());
		}

		HttpResponse<String> response = this.client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException("LLM endpoint returned HTTP " + response.statusCode() + ": " + response.body());
		}

		return parseModels(response.body());
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

	static List<String> parseModels(String responseBody) throws IOException {
		try {
			JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonArray data = response.getAsJsonArray("data");

			if (data == null) {
				throw new IOException("LLM models response did not contain data");
			}

			List<String> models = new ArrayList<>();

			for (JsonElement element : data) {
				if (!element.isJsonObject()) {
					continue;
				}

				JsonElement id = element.getAsJsonObject().get("id");

				if (isString(id) && !id.getAsString().isBlank()) {
					models.add(id.getAsString());
				}
			}

			return models;
		} catch (IllegalStateException | JsonParseException e) {
			throw new IOException("LLM models response was not valid OpenAI-compatible JSON", e);
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

	private URI modelsUri() {
		String base = this.config.baseUrl().strip();

		if (base.endsWith("/models")) {
			return URI.create(base);
		}

		if (base.endsWith("/chat/completions")) {
			base = base.substring(0, base.length() - "chat/completions".length());
		}

		if (base.endsWith("/")) {
			return URI.create(base + "models");
		}

		return URI.create(base + "/models");
	}

	private static JsonObject message(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	static String systemMessage(LlmConfig config) {
		String message = """
				You are an expert Java reverse engineer working on decompiled and bytecode-derived context.
				Suggest one precise Java identifier for the requested obfuscated target.
				Focus only on the target; use surrounding members as context.
				Use only evidence from the supplied bytecode context. Do not guess domain-specific names from the project, jar, package, or game alone.

				Default naming rules, adapted from Yarn's general naming conventions:
				- Use UpperCamelCase for class names, such as FileReader, ColorPalette, or BoundingBox.
				- Use lowerCamelCase for method names, parameter names, local variable names, and fields that are not both static and final.
				- Use UPPER_SNAKE_CASE for fields that are both static and final, such as DEFAULT_TIMEOUT or MAX_VALUE.
				- Method names should generally be verb phrases, such as readFile, setColor, or getValue, except common forms such as withX, toX, fromX, of, factory methods, and builder methods.
				- Class names and non-boolean field or variable names should generally be noun phrases, such as color, itemCount, or outputPath.
				- Boolean field and variable names should be adjective phrases or present-tense verb phrases; prefer readable names such as active, visible, or canOpen over isActive or hasColor when possible.
				- Prefer natural language order, such as FileReader rather than ReaderFile. Do not reorder words just to group related names by prefix.
				- Use American English spelling.
				- Prefer descriptive names over very short names, but omit words made redundant by parameter names or owner class names, such as getValue(key) rather than getValueForKey(key).
				- Avoid abbreviations unless they are widely understood or conventional in Java/library names, such as id, pos, init, min, max, json, or html.
				- Treat acronyms as words, such as JsonObject rather than JSONObject.
				- Use plural names for collections and maps when appropriate, such as entries or values; name maps in valuesByKeys form when that clarifies their role.
				- For coordinates, use x/y/z when clear; otherwise put the qualifier before the axis, such as velocityX.
				- Do not name methods based on implementation details; name what they do, not how they do it.
				- Avoid Java-type bookkeeping in names, such as I prefixes for interfaces or Enum prefixes for enum types.

				For top-level classes, you may return either a simple class name or a JVM internal name with package separators.
				Do not suggest generic names such as Manager, Loader, Handler, Registry, Data, or Utils unless fields, methods, inheritance, or call sites directly prove that role.
				For fields, methods, and parameters, do not append a short unchanged obfuscated target token, such as ak -> createAk.
				If the evidence is weak or only suggests a broad category, choose a conservative descriptive name and set confidence below 0.50.
				The confidence field is a rough model self-assessment score for ranking suggestions, not a calibrated probability.
				Generate 2-4 alternatives before choosing the best suggestion.
				Respond only with valid JSON: {"reasoning":"string","alternatives":["string"],"suggestedName":"string","confidence":0.0}
				""";

		if (config.promptExtension() == null || config.promptExtension().isBlank()) {
			return message;
		}

		return message + "\nAdditional user naming rules:\n" + config.promptExtension().strip() + "\n";
	}

	private static JsonObject suggestionResponseFormat() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");

		JsonObject properties = new JsonObject();
		properties.add("reasoning", stringSchema());

		JsonObject alternatives = new JsonObject();
		alternatives.addProperty("type", "array");
		alternatives.add("items", stringSchema());
		properties.add("alternatives", alternatives);

		properties.add("suggestedName", stringSchema());

		JsonObject confidence = new JsonObject();
		confidence.addProperty("type", "number");
		confidence.addProperty("minimum", 0.0);
		confidence.addProperty("maximum", 1.0);
		properties.add("confidence", confidence);

		JsonArray required = new JsonArray();
		required.add("reasoning");
		required.add("alternatives");
		required.add("suggestedName");
		required.add("confidence");

		schema.add("properties", properties);
		schema.add("required", required);
		schema.addProperty("additionalProperties", false);

		JsonObject jsonSchema = new JsonObject();
		jsonSchema.addProperty("name", "enigma_name_suggestion");
		jsonSchema.addProperty("strict", true);
		jsonSchema.add("schema", schema);

		JsonObject responseFormat = new JsonObject();
		responseFormat.addProperty("type", "json_schema");
		responseFormat.add("json_schema", jsonSchema);
		return responseFormat;
	}

	private static JsonObject stringSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "string");
		return schema;
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
