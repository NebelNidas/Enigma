package cuchaz.enigma.llm.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class BridgeServer implements AutoCloseable {
	private static final Gson GSON = new Gson();

	private final String host;
	private final int configuredPort;
	private final String authToken;
	private final BridgeProvider provider;
	private HttpServer server;
	private ExecutorService executor;

	BridgeServer(String host, int port, String authToken, BridgeProvider provider) {
		this.host = host;
		this.configuredPort = port;
		this.authToken = authToken == null ? "" : authToken.strip();
		this.provider = provider;
	}

	void start() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(this.host, this.configuredPort), 0);
		this.server.createContext("/healthz", this::handleHealth);
		this.server.createContext("/v1/models", this::handleModels);
		this.server.createContext("/v1/chat/completions", this::handleChatCompletions);
		this.executor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "enigma-llm-bridge");
			return thread;
		});
		this.server.setExecutor(this.executor);
		this.server.start();
	}

	int port() {
		return this.server == null ? this.configuredPort : this.server.getAddress().getPort();
	}

	@Override
	public void close() throws Exception {
		if (this.server != null) {
			this.server.stop(0);
		}

		if (this.executor != null) {
			this.executor.shutdownNow();
		}

		this.provider.close();
	}

	private void handleHealth(HttpExchange exchange) throws IOException {
		if (!requireMethod(exchange, "GET")) {
			return;
		}

		JsonObject json = new JsonObject();
		json.addProperty("status", "ok");
		json.addProperty("provider", this.provider.name());
		sendJson(exchange, 200, json);
	}

	private void handleModels(HttpExchange exchange) throws IOException {
		if (!requireMethod(exchange, "GET") || !requireAuthorized(exchange)) {
			return;
		}

		try {
			JsonObject json = new JsonObject();
			JsonArray data = new JsonArray();

			for (String model : this.provider.models()) {
				JsonObject modelJson = new JsonObject();
				modelJson.addProperty("id", model);
				modelJson.addProperty("object", "model");
				data.add(modelJson);
			}

			json.addProperty("object", "list");
			json.add("data", data);
			sendJson(exchange, 200, json);
		} catch (Exception e) {
			sendError(exchange, 502, "Could not list bridge models: " + e.getMessage());
		}
	}

	private void handleChatCompletions(HttpExchange exchange) throws IOException {
		if (!requireMethod(exchange, "POST") || !requireAuthorized(exchange)) {
			return;
		}

		try {
			ChatCompletionRequest request = parseChatCompletionRequest(readBody(exchange.getRequestBody()));
			String content = this.provider.complete(request);
			JsonObject message = new JsonObject();
			message.addProperty("role", "assistant");
			message.addProperty("content", content);

			JsonObject choice = new JsonObject();
			choice.addProperty("index", 0);
			choice.add("message", message);
			choice.addProperty("finish_reason", "stop");

			JsonArray choices = new JsonArray();
			choices.add(choice);

			JsonObject response = new JsonObject();
			response.addProperty("id", "enigma-llm-bridge");
			response.addProperty("object", "chat.completion");
			response.add("choices", choices);
			sendJson(exchange, 200, response);
		} catch (IllegalArgumentException e) {
			sendError(exchange, 400, e.getMessage());
		} catch (Exception e) {
			sendError(exchange, 502, "Bridge provider failed: " + e.getMessage());
		}
	}

	static ChatCompletionRequest parseChatCompletionRequest(String body) {
		JsonObject json = JsonParser.parseString(body).getAsJsonObject();
		String model = requiredString(json, "model");
		JsonArray messagesJson = json.getAsJsonArray("messages");

		if (messagesJson == null || messagesJson.isEmpty()) {
			throw new IllegalArgumentException("chat completion request must contain messages");
		}

		List<ChatMessage> messages = new ArrayList<>();

		for (JsonElement element : messagesJson) {
			if (!element.isJsonObject()) {
				continue;
			}

			JsonObject message = element.getAsJsonObject();
			messages.add(new ChatMessage(optionalString(message, "role", "user"),
					optionalString(message, "content", "")));
		}

		if (messages.isEmpty()) {
			throw new IllegalArgumentException("chat completion request did not contain message objects");
		}

		return new ChatCompletionRequest(model, messages, optionalDouble(json, "temperature", 0.2));
	}

	private boolean requireMethod(HttpExchange exchange, String method) throws IOException {
		if (exchange.getRequestMethod().equals(method)) {
			return true;
		}

		exchange.getResponseHeaders().add("Allow", method);
		sendError(exchange, 405, "Method not allowed");
		return false;
	}

	private boolean requireAuthorized(HttpExchange exchange) throws IOException {
		if (this.authToken.isBlank()) {
			return true;
		}

		String authorization = exchange.getRequestHeaders().getFirst("Authorization");

		if (("Bearer " + this.authToken).equals(authorization)) {
			return true;
		}

		sendError(exchange, 401, "Missing or invalid bridge authorization token");
		return false;
	}

	private static String readBody(InputStream stream) throws IOException {
		return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
	}

	private static void sendJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);

		try (OutputStream stream = exchange.getResponseBody()) {
			stream.write(bytes);
		}
	}

	private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
		JsonObject error = new JsonObject();
		error.addProperty("error", message == null || message.isBlank() ? "Bridge request failed" : message);
		sendJson(exchange, status, error);
	}

	private static String requiredString(JsonObject json, String key) {
		String value = optionalString(json, key, "");

		if (value.isBlank()) {
			throw new IllegalArgumentException("chat completion request must contain " + key);
		}

		return value;
	}

	private static String optionalString(JsonObject json, String key, String fallback) {
		JsonElement value = json.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
				? value.getAsString()
				: fallback;
	}

	private static double optionalDouble(JsonObject json, String key, double fallback) {
		JsonElement value = json.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
				? value.getAsDouble()
				: fallback;
	}
}
