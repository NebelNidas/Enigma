package cuchaz.enigma.llm.bridge;

import java.time.Duration;
import java.util.List;

final class CodexAppServerBridgeProvider implements BridgeProvider {
	private final List<String> command;
	private final List<String> models;
	private final Duration timeout;
	private final boolean trace;

	CodexAppServerBridgeProvider(List<String> command, List<String> models, Duration timeout) {
		this(command, models, timeout, false);
	}

	CodexAppServerBridgeProvider(List<String> command, List<String> models, Duration timeout, boolean trace) {
		this.command = List.copyOf(command);
		this.models = List.copyOf(models);
		this.timeout = timeout;
		this.trace = trace;
	}

	@Override
	public String name() {
		return "codex-app-server";
	}

	@Override
	public List<String> models() throws Exception {
		List<String> appServerModels;

		try {
			appServerModels = new CodexAppServerClient(this.command, this.timeout, this.trace).listModels();
		} catch (Exception e) {
			return this.models;
		}

		if (!appServerModels.isEmpty()) {
			return appServerModels;
		}

		return this.models;
	}

	@Override
	public String complete(ChatCompletionRequest request) throws Exception {
		String model = request.model().startsWith("codex:")
				? request.model().substring("codex:".length())
				: request.model();
		return new CodexAppServerClient(this.command, this.timeout, this.trace).complete(model, request.combinedPrompt());
	}
}
