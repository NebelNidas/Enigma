package cuchaz.enigma.llm.bridge;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

public class BridgeServerTest {
	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	public void fakeProviderServesOpenAiCompatibleContract() throws Exception {
		try (BridgeServer server = new BridgeServer("127.0.0.1", 0, "", new FakeBridgeProvider())) {
			server.start();
			URI base = URI.create("http://127.0.0.1:" + server.port());

			HttpResponse<String> health = send(get(base.resolve("/healthz")));
			HttpResponse<String> models = send(get(base.resolve("/v1/models")));
			HttpResponse<String> completion = send(post(base.resolve("/v1/chat/completions"), """
					{"model":"fake:enigma-name-suggester","messages":[{"role":"user","content":"Target Type: FIELD\\nContext: static final"}],"temperature":0.2}
					"""));

			assertThat(health.statusCode(), equalTo(200));
			assertThat(health.body(), containsString("\"provider\":\"fake\""));
			assertThat(models.statusCode(), equalTo(200));
			assertThat(models.body(), containsString("\"id\":\"fake:enigma-name-suggester\""));
			assertThat(completion.statusCode(), equalTo(200));

			JsonObject response = JsonParser.parseString(completion.body()).getAsJsonObject();
			String content = response.getAsJsonArray("choices").get(0).getAsJsonObject()
					.getAsJsonObject("message").get("content").getAsString();
			JsonObject suggestion = JsonParser.parseString(content).getAsJsonObject();
			assertThat(suggestion.get("suggestedName").getAsString(), equalTo("BRIDGE_CONSTANT"));
		}
	}

	@Test
	public void v1EndpointsCanRequireBearerToken() throws Exception {
		try (BridgeServer server = new BridgeServer("127.0.0.1", 0, "secret", new FakeBridgeProvider())) {
			server.start();
			URI models = URI.create("http://127.0.0.1:" + server.port() + "/v1/models");

			HttpResponse<String> rejected = send(get(models));
			HttpResponse<String> accepted = send(HttpRequest.newBuilder(models)
					.header("Authorization", "Bearer secret")
					.GET()
					.build());

			assertThat(rejected.statusCode(), equalTo(401));
			assertThat(accepted.statusCode(), equalTo(200));
		}
	}

	@Test
	public void bridgeConfigReadsEnvironmentAndArgs() {
		BridgeConfig config = BridgeConfig.load(new String[] {
				"--provider", "codex",
				"--port", "9999",
				"--codex-models", "codex:gpt-5.4,codex:gpt-5-mini",
				"--codex-trace"
		}, Map.of(
				"ENIGMA_LLM_BRIDGE_HOST", "0.0.0.0",
				"ENIGMA_LLM_BRIDGE_AUTH_TOKEN", " token ",
				"ENIGMA_LLM_BRIDGE_CODEX_TIMEOUT_SECONDS", "12"
		)::get);

		assertThat(config.host(), equalTo("0.0.0.0"));
		assertThat(config.port(), equalTo(9999));
		assertThat(config.provider(), equalTo("codex"));
		assertThat(config.authToken(), equalTo("token"));
		assertThat(config.codexModels(), equalTo(List.of("codex:gpt-5.4", "codex:gpt-5-mini")));
		assertThat(config.codexTimeout(), equalTo(Duration.ofSeconds(12)));
		assertTrue(config.codexTrace());
	}

	@Test
	public void parsesChatCompletionRequest() {
		ChatCompletionRequest request = BridgeServer.parseChatCompletionRequest("""
				{"model":"fake:model","messages":[{"role":"system","content":"rules"},{"role":"user","content":"target"}],"temperature":0.1}
				""");

		assertThat(request.model(), equalTo("fake:model"));
		assertThat(request.messages().size(), equalTo(2));
		assertTrue(request.combinedPrompt().contains("system:\nrules"));
		assertTrue(request.combinedPrompt().contains("user:\ntarget"));
		assertThat(request.temperature(), equalTo(0.1));
	}

	@Test
	public void codexAppServerClientUsesJsonRpcThreadTurnFlow() throws Exception {
		Path script = Files.createTempFile("fake-codex-app-server", ".sh");
		Files.writeString(script, """
				#!/usr/bin/env bash
				read initialize
				printf '%s\\n' '{"id":0,"result":{}}'
				read initialized
				read thread_start
				printf '%s\\n' '{"id":1,"result":{"thread":{"id":"thread-1"}}}'
				read turn_start
				printf '%s\\n' '{"method":"item/agentMessage/delta","params":{"delta":"{\\"suggestedName\\":\\"codexName\\","}}'
				printf '%s\\n' '{"method":"item/agentMessage/delta","params":{"delta":"\\"alternatives\\":[],\\"confidence\\":0.7,\\"reasoning\\":\\"from fake codex\\"}"}}'
				printf '%s\\n' '{"method":"turn/completed","params":{"turn":{"id":"turn-1"}}}'
				""", StandardCharsets.UTF_8);
		assertTrue(script.toFile().setExecutable(true));

		String response = new CodexAppServerClient(List.of(script.toString()), Duration.ofSeconds(2))
				.complete("gpt-test", "Name this field");

		assertThat(response, equalTo("{\"suggestedName\":\"codexName\",\"alternatives\":[],\"confidence\":0.7,\"reasoning\":\"from fake codex\"}"));
	}

	@Test
	public void codexAppServerClientListsModels() throws Exception {
		Path script = Files.createTempFile("fake-codex-model-list", ".sh");
		Files.writeString(script, """
				#!/usr/bin/env bash
				read initialize
				printf '%s\\n' '{"id":0,"result":{}}'
				read initialized
				read model_list
				printf '%s\\n' '{"id":1,"result":{"data":[{"id":"gpt-test"},{"model":"gpt-alt"}]}}'
				""", StandardCharsets.UTF_8);
		assertTrue(script.toFile().setExecutable(true));

		List<String> models = new CodexAppServerClient(List.of(script.toString()), Duration.ofSeconds(2))
				.listModels();

		assertThat(models, equalTo(List.of("codex:gpt-test", "codex:gpt-alt")));
	}

	@Test
	public void codexAppServerClientTimesOutAndStopsProcess() throws Exception {
		Path pidFile = Files.createTempFile("fake-codex-timeout", ".pid");
		Path script = Files.createTempFile("fake-codex-timeout", ".sh");
		Files.writeString(script, """
				#!/usr/bin/env bash
				echo $$ > "$1"
				read initialize
				while true; do sleep 1; done
				""", StandardCharsets.UTF_8);
		assertTrue(script.toFile().setExecutable(true));

		try {
			new CodexAppServerClient(List.of(script.toString(), pidFile.toString()), Duration.ofMillis(200))
					.listModels();
			fail("Expected codex app-server timeout");
		} catch (IOException e) {
			assertThat(e.getMessage(), containsString("timed out"));
		}

		long pid = Long.parseLong(Files.readString(pidFile).strip());
		Thread.sleep(250);
		assertTrue(ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive());
	}

	@Test
	public void codexProviderFallsBackToConfiguredModelsWhenListingFails() throws Exception {
		Path script = Files.createTempFile("fake-codex-model-failure", ".sh");
		Files.writeString(script, """
				#!/usr/bin/env bash
				exit 1
				""", StandardCharsets.UTF_8);
		assertTrue(script.toFile().setExecutable(true));

		CodexAppServerBridgeProvider provider = new CodexAppServerBridgeProvider(
				List.of(script.toString()),
				List.of("codex:fallback-model"),
				Duration.ofSeconds(2));

		assertThat(provider.models(), equalTo(List.of("codex:fallback-model")));
	}

	private HttpResponse<String> send(HttpRequest request) throws Exception {
		return this.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static HttpRequest get(URI uri) {
		return HttpRequest.newBuilder(uri).GET().build();
	}

	private static HttpRequest post(URI uri, String body) {
		return HttpRequest.newBuilder(uri)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
	}
}
