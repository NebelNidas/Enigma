package cuchaz.enigma.llm.bridge;

final class BridgeProviders {
	private BridgeProviders() {
	}

	static BridgeProvider create(BridgeConfig config) {
		return switch (config.provider().toLowerCase(java.util.Locale.ROOT)) {
		case "fake", "test" -> new FakeBridgeProvider();
		case "codex", "codex-app-server" -> new CodexAppServerBridgeProvider(config.codexCommand(),
				config.codexModels(), config.codexTimeout(), config.codexTrace());
		default -> throw new IllegalArgumentException("Unknown bridge provider: " + config.provider());
		};
	}
}
