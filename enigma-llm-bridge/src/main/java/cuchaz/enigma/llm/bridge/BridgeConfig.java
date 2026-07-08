package cuchaz.enigma.llm.bridge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

record BridgeConfig(String host, int port, String provider, String authToken,
		List<String> codexCommand, List<String> codexModels, Duration codexTimeout, boolean codexTrace,
		List<String> cliGrokCommand, List<String> cliClaudeCommand, List<String> cliCodexCommand,
		List<String> cliModels, Duration cliTimeout) {
	private static final String DEFAULT_HOST = "127.0.0.1";
	private static final int DEFAULT_PORT = 8787;

	BridgeConfig {
		host = host == null || host.isBlank() ? DEFAULT_HOST : host.strip();
		provider = provider == null || provider.isBlank() ? "fake" : provider.strip();
		authToken = authToken == null ? "" : authToken.strip();
		codexCommand = codexCommand == null || codexCommand.isEmpty() ? List.of("codex") : List.copyOf(codexCommand);
		codexModels = codexModels == null || codexModels.isEmpty() ? List.of("codex:gpt-5.4") : List.copyOf(codexModels);
		codexTimeout = codexTimeout == null || codexTimeout.isNegative() || codexTimeout.isZero() ? Duration.ofSeconds(180) : codexTimeout;
		cliGrokCommand = cliGrokCommand == null || cliGrokCommand.isEmpty() ? List.of("grok") : List.copyOf(cliGrokCommand);
		cliClaudeCommand = cliClaudeCommand == null || cliClaudeCommand.isEmpty() ? List.of("claude") : List.copyOf(cliClaudeCommand);
		cliCodexCommand = cliCodexCommand == null || cliCodexCommand.isEmpty() ? List.of("codex") : List.copyOf(cliCodexCommand);
		cliModels = cliModels == null || cliModels.isEmpty()
				? List.of("grok:grok-build", "codex:gpt-5.5", "claude:opus") : List.copyOf(cliModels);
		cliTimeout = cliTimeout == null || cliTimeout.isNegative() || cliTimeout.isZero() ? Duration.ofSeconds(300) : cliTimeout;
	}

	static BridgeConfig load(String[] args, Function<String, String> env) {
		String host = first(env, "ENIGMA_LLM_BRIDGE_HOST", DEFAULT_HOST);
		int port = parsePositiveInt(first(env, "ENIGMA_LLM_BRIDGE_PORT", Integer.toString(DEFAULT_PORT)), DEFAULT_PORT);
		String provider = first(env, "ENIGMA_LLM_BRIDGE_PROVIDER", "fake");
		String authToken = first(env, "ENIGMA_LLM_BRIDGE_AUTH_TOKEN", "");
		List<String> codexCommand = command(first(env, "ENIGMA_LLM_BRIDGE_CODEX_COMMAND", "codex"));
		List<String> codexModels = values(first(env, "ENIGMA_LLM_BRIDGE_CODEX_MODELS", "codex:gpt-5.4"));
		Duration codexTimeout = Duration.ofSeconds(parsePositiveInt(first(env, "ENIGMA_LLM_BRIDGE_CODEX_TIMEOUT_SECONDS", "180"), 180));
		boolean codexTrace = parseBoolean(first(env, "ENIGMA_LLM_BRIDGE_CODEX_TRACE", "false"));
		List<String> cliGrokCommand = command(first(env, "ENIGMA_LLM_BRIDGE_CLI_GROK_COMMAND", "grok"), "grok");
		List<String> cliClaudeCommand = command(first(env, "ENIGMA_LLM_BRIDGE_CLI_CLAUDE_COMMAND", "claude"), "claude");
		List<String> cliCodexCommand = command(first(env, "ENIGMA_LLM_BRIDGE_CLI_CODEX_COMMAND", "codex"), "codex");
		List<String> cliModels = values(first(env, "ENIGMA_LLM_BRIDGE_CLI_MODELS", "grok:grok-build,codex:gpt-5.5,claude:opus"));
		Duration cliTimeout = Duration.ofSeconds(parsePositiveInt(first(env, "ENIGMA_LLM_BRIDGE_CLI_TIMEOUT_SECONDS", "300"), 300));

		for (int i = 0; args != null && i < args.length; i++) {
			String arg = args[i];

			switch (arg) {
			case "--host" -> host = next(args, ++i, arg);
			case "--port" -> port = parsePositiveInt(next(args, ++i, arg), port);
			case "--provider" -> provider = next(args, ++i, arg);
			case "--auth-token" -> authToken = next(args, ++i, arg);
			case "--codex-command" -> codexCommand = command(next(args, ++i, arg));
			case "--codex-models" -> codexModels = values(next(args, ++i, arg));
			case "--codex-timeout-seconds" -> codexTimeout = Duration.ofSeconds(parsePositiveInt(next(args, ++i, arg), (int) codexTimeout.toSeconds()));
			case "--codex-trace" -> codexTrace = true;
			case "--cli-grok-command" -> cliGrokCommand = command(next(args, ++i, arg), "grok");
			case "--cli-claude-command" -> cliClaudeCommand = command(next(args, ++i, arg), "claude");
			case "--cli-codex-command" -> cliCodexCommand = command(next(args, ++i, arg), "codex");
			case "--cli-models" -> cliModels = values(next(args, ++i, arg));
			case "--cli-timeout-seconds" -> cliTimeout = Duration.ofSeconds(parsePositiveInt(next(args, ++i, arg), (int) cliTimeout.toSeconds()));
			default -> throw new IllegalArgumentException("Unknown bridge argument: " + arg);
			}
		}

		return new BridgeConfig(host, port, provider, authToken, codexCommand, codexModels, codexTimeout, codexTrace,
				cliGrokCommand, cliClaudeCommand, cliCodexCommand, cliModels, cliTimeout);
	}

	private static String first(Function<String, String> env, String key, String fallback) {
		String value = env.apply(key);
		return value == null || value.isBlank() ? fallback : value.strip();
	}

	private static String next(String[] args, int index, String option) {
		if (index >= args.length) {
			throw new IllegalArgumentException("Missing value for " + option);
		}

		return args[index];
	}

	private static int parsePositiveInt(String value, int fallback) {
		try {
			int parsed = Integer.parseInt(value.strip());
			return parsed > 0 ? parsed : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static boolean parseBoolean(String value) {
		return value != null && switch (value.strip().toLowerCase()) {
		case "1", "true", "yes", "on" -> true;
		default -> false;
		};
	}

	private static List<String> command(String value) {
		return command(value, "codex");
	}

	private static List<String> command(String value, String fallback) {
		List<String> parts = values(value);
		return parts.isEmpty() ? List.of(fallback) : parts;
	}

	private static List<String> values(String value) {
		List<String> values = new ArrayList<>();

		if (value == null || value.isBlank()) {
			return values;
		}

		for (String part : value.split("[, ]+")) {
			if (!part.isBlank()) {
				values.add(part.strip());
			}
		}

		return values;
	}
}
