package cuchaz.enigma.llm.bridge;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

final class FakeBridgeProvider implements BridgeProvider {
	@Override
	public String name() {
		return "fake";
	}

	@Override
	public List<String> models() {
		return List.of("fake:enigma-name-suggester");
	}

	@Override
	public String complete(ChatCompletionRequest request) {
		JsonObject suggestion = new JsonObject();
		JsonArray alternatives = new JsonArray();
		alternatives.add("bridgeName");
		suggestion.addProperty("reasoning", "Fake bridge response for OpenAI-compatible contract tests.");
		suggestion.add("alternatives", alternatives);
		suggestion.addProperty("suggestedName", suggestedName(request));
		suggestion.addProperty("confidence", 0.5);
		return suggestion.toString();
	}

	private static String suggestedName(ChatCompletionRequest request) {
		String prompt = request.combinedPrompt().toLowerCase(java.util.Locale.ROOT);

		if (prompt.contains("target type: class") || prompt.contains("target kind: class")) {
			return "BridgeNamedClass";
		}

		if (prompt.contains("static final")) {
			return "BRIDGE_CONSTANT";
		}

		return "bridgeName";
	}
}
