package cuchaz.enigma.llm.bridge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class CodexAppServerClient {
	private static final Gson GSON = new Gson();

	private final List<String> command;
	private final Duration timeout;
	private final boolean trace;

	CodexAppServerClient(List<String> command, Duration timeout) {
		this(command, timeout, false);
	}

	CodexAppServerClient(List<String> command, Duration timeout, boolean trace) {
		this.command = command == null || command.isEmpty() ? List.of("codex") : List.copyOf(command);
		this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(180) : timeout;
		this.trace = trace;
	}

	String complete(String model, String prompt) throws IOException, InterruptedException {
		try {
			return withAppServer((writer, reader) -> completeInAppServer(model, prompt, writer, reader));
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();

			if (cause instanceof IOException ioException) {
				throw ioException;
			}

			if (cause instanceof InterruptedException interruptedException) {
				throw interruptedException;
			}

			throw new IOException("codex app-server request failed", cause);
		}
	}

	List<String> listModels() throws IOException, InterruptedException {
		try {
			return withAppServer(this::listModelsInAppServer);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();

			if (cause instanceof IOException ioException) {
				throw ioException;
			}

			if (cause instanceof InterruptedException interruptedException) {
				throw interruptedException;
			}

			throw new IOException("codex app-server model listing failed", cause);
		}
	}

	private String completeInAppServer(String model, String prompt, BufferedWriter writer, BufferedReader reader) throws IOException {
		send(writer, request(1, "thread/start", object(
				"model", model,
				"approvalPolicy", "never",
				"developerInstructions", "Answer the user's request directly. Do not edit files or run commands."
		)));
		String threadId = readThreadId(reader);
		send(writer, request(2, "turn/start", object(
				"threadId", threadId,
				"approvalPolicy", "never",
				"input", List.of(object("type", "text", "text", prompt))
		)));
		return readAssistantText(reader);
	}

	private List<String> listModelsInAppServer(BufferedWriter writer, BufferedReader reader) throws IOException {
		send(writer, request(1, "model/list", object("includeHidden", false, "limit", 200)));
		return readModelIds(reader);
	}

	private <T> T withAppServer(AppServerOperation<T> operation) throws IOException, InterruptedException, ExecutionException {
		AtomicReference<Process> processReference = new AtomicReference<>();
		ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			return new Thread(runnable, "enigma-codex-app-server-client");
		});
		Future<T> future = executor.submit(() -> {
			List<String> processCommand = new ArrayList<>(this.command);
			processCommand.add("app-server");

			Process process = new ProcessBuilder(processCommand)
					.redirectError(ProcessBuilder.Redirect.INHERIT)
					.start();
			processReference.set(process);

			try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
					BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				send(writer, request(0, "initialize", object("clientInfo", object(
						"name", "enigma_llm_bridge",
						"title", "Enigma LLM Bridge",
						"version", "0.1.0"
				))));
				readResponse(reader, 0, "initialize");
				send(writer, notification("initialized", new JsonObject()));
				return operation.run(writer, reader);
			} finally {
				stopProcess(process);
			}
		});

		try {
			return future.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			Process process = processReference.get();

			if (process != null) {
				process.destroyForcibly();
				process.waitFor(2, TimeUnit.SECONDS);
			}

			future.cancel(true);
			throw new IOException("codex app-server request timed out after " + this.timeout.toSeconds() + " seconds", e);
		} finally {
			executor.shutdownNow();
		}
	}

	private String readThreadId(BufferedReader reader) throws IOException {
		while (true) {
			JsonObject message = readMessage(reader);

			if (message == null) {
				break;
			}

			throwIfError(message, "thread/start");

			if (message.has("id") && message.get("id").getAsInt() == 1 && message.has("result")) {
				String threadId = findString(message.get("result"), "id", "threadId");

				if (!threadId.isBlank()) {
					return threadId;
				}
			}
		}

		throw new IOException("codex app-server did not return a thread id");
	}

	private String readAssistantText(BufferedReader reader) throws IOException {
		StringBuilder text = new StringBuilder();

		while (true) {
			JsonObject message = readMessage(reader);

			if (message == null) {
				break;
			}

			throwIfError(message, "turn/start");
			String method = message.has("method") ? message.get("method").getAsString() : "";

			if (method.contains("agentMessage")) {
				text.append(findString(message.get("params"), "delta", "text", "content"));
			}

			if ("turn/failed".equals(method)) {
				throw new IOException("codex app-server turn failed: " + message);
			}

			if ("turn/completed".equals(method)) {
				break;
			}
		}

		if (text.isEmpty()) {
			throw new IOException("codex app-server did not return assistant text");
		}

		return text.toString();
	}

	private List<String> readModelIds(BufferedReader reader) throws IOException {
		while (true) {
			JsonObject message = readMessage(reader);

			if (message == null) {
				break;
			}

			throwIfError(message, "model/list");

			if (message.has("id") && message.get("id").getAsInt() == 1 && message.has("result")) {
				JsonObject result = message.getAsJsonObject("result");
				List<String> models = new ArrayList<>();

				if (result != null && result.has("data") && result.get("data").isJsonArray()) {
					for (JsonElement element : result.getAsJsonArray("data")) {
						String id = findString(element, "id", "model");

						if (!id.isBlank()) {
							models.add("codex:" + id);
						}
					}
				}

				return models;
			}
		}

		throw new IOException("codex app-server did not return models");
	}

	private JsonObject readResponse(BufferedReader reader, int id, String description) throws IOException {
		while (true) {
			JsonObject message = readMessage(reader);

			if (message == null) {
				break;
			}

			throwIfError(message, description);

			if (message.has("id") && message.get("id").getAsInt() == id && message.has("result")) {
				return message.getAsJsonObject("result");
			}
		}

		throw new IOException("codex app-server did not return " + description + " response");
	}

	private JsonObject readMessage(BufferedReader reader) throws IOException {
		String line = reader.readLine();

		if (line == null) {
			return null;
		}

		if (this.trace) {
			System.err.println("[codex app-server] " + line);
		}

		return parseLine(line);
	}

	private static void throwIfError(JsonObject message, String description) throws IOException {
		if (message.has("error")) {
			throw new IOException("codex app-server " + description + " failed: " + message.get("error"));
		}
	}

	private static void stopProcess(Process process) throws InterruptedException {
		process.destroy();

		if (!process.waitFor(2, TimeUnit.SECONDS)) {
			process.destroyForcibly();
		}
	}

	@FunctionalInterface
	private interface AppServerOperation<T> {
		T run(BufferedWriter writer, BufferedReader reader) throws Exception;
	}

	private static JsonObject parseLine(String line) throws IOException {
		try {
			return JsonParser.parseString(line).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new IOException("Invalid codex app-server JSON-RPC line", e);
		}
	}

	private static void send(BufferedWriter writer, JsonObject message) throws IOException {
		writer.write(GSON.toJson(message));
		writer.newLine();
		writer.flush();
	}

	private static JsonObject request(int id, String method, JsonObject params) {
		JsonObject request = notification(method, params);
		request.addProperty("id", id);
		return request;
	}

	private static JsonObject notification(String method, JsonObject params) {
		JsonObject notification = new JsonObject();
		notification.addProperty("method", method);
		notification.add("params", params);
		return notification;
	}

	private static JsonObject object(Object... entries) {
		JsonObject object = new JsonObject();

		for (int i = 0; i + 1 < entries.length; i += 2) {
			String key = entries[i].toString();
			Object value = entries[i + 1];

			if (value instanceof String string) {
				object.addProperty(key, string);
			} else if (value instanceof Number number) {
				object.addProperty(key, number);
			} else if (value instanceof Boolean bool) {
				object.addProperty(key, bool);
			} else if (value instanceof JsonElement json) {
				object.add(key, json);
			} else if (value instanceof List<?> list) {
				object.add(key, GSON.toJsonTree(list));
			} else if (value != null) {
				object.add(key, GSON.toJsonTree(value));
			}
		}

		return object;
	}

	private static String findString(JsonElement element, String... keys) {
		if (element == null || element.isJsonNull()) {
			return "";
		}

		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			return element.getAsString();
		}

		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();

			for (String key : keys) {
				JsonElement value = object.get(key);

				if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
					return value.getAsString();
				}
			}

			for (JsonElement value : object.asMap().values()) {
				String found = findString(value, keys);

				if (!found.isBlank()) {
					return found;
				}
			}
		}

		if (element.isJsonArray()) {
			for (JsonElement value : element.getAsJsonArray()) {
				String found = findString(value, keys);

				if (!found.isBlank()) {
					return found;
				}
			}
		}

		return "";
	}
}
