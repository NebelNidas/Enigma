package cuchaz.enigma.llm;

import java.time.Duration;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.Function;

record LlmConfig(String baseUrl, String model, String apiKey, Duration timeout, OptionalDouble batchPreselectThreshold, int batchParallelism, LlmContextBackend contextBackend) {
	LlmConfig(String baseUrl, String model, String apiKey, Duration timeout, OptionalDouble batchPreselectThreshold) {
		this(baseUrl, model, apiKey, timeout, batchPreselectThreshold, 1, LlmContextBackend.OWNER);
	}

	LlmConfig(String baseUrl, String model, String apiKey, Duration timeout, OptionalDouble batchPreselectThreshold, int batchParallelism) {
		this(baseUrl, model, apiKey, timeout, batchPreselectThreshold, batchParallelism, LlmContextBackend.OWNER);
	}

	static LlmConfig load() {
		return load(System::getenv, System::getProperty);
	}

	static LlmConfig load(Function<String, String> env, Function<String, String> property) {
		return new LlmConfig(
				first(env, property, "ENIGMA_LLM_BASE_URL", "enigma.llm.baseUrl", "http://localhost:1234/v1"),
				first(env, property, "ENIGMA_LLM_MODEL", "enigma.llm.model", ""),
				first(env, property, "ENIGMA_LLM_API_KEY", "enigma.llm.apiKey", ""),
				Duration.ofSeconds(parsePositiveLong(first(env, property, "ENIGMA_LLM_TIMEOUT_SECONDS", "enigma.llm.timeoutSeconds", "120"), 120)),
				parseOptionalDouble(first(env, property, "ENIGMA_LLM_AUTO_APPLY_THRESHOLD", "enigma.llm.autoApplyThreshold", "")),
				parseBoundedPositiveInt(first(env, property, "ENIGMA_LLM_BATCH_PARALLELISM", "enigma.llm.batchParallelism", "2"), 2, 8),
				LlmContextBackend.from(first(env, property, "ENIGMA_LLM_CONTEXT_BACKEND", "enigma.llm.contextBackend", "owner"))
		);
	}

	boolean isConfigured() {
		return this.baseUrl != null && !this.baseUrl.isBlank() && this.model != null && !this.model.isBlank();
	}

	LlmConfig withContextBackend(LlmContextBackend contextBackend) {
		return new LlmConfig(this.baseUrl, this.model, this.apiKey, this.timeout, this.batchPreselectThreshold, this.batchParallelism, contextBackend);
	}

	LlmConfig withBatchParallelism(int batchParallelism) {
		return new LlmConfig(this.baseUrl, this.model, this.apiKey, this.timeout, this.batchPreselectThreshold, parseBoundedPositiveInt(Integer.toString(batchParallelism), this.batchParallelism, 8), this.contextBackend);
	}

	private static String first(Function<String, String> envSource, Function<String, String> propertySource, String env, String property, String fallback) {
		String value = envSource.apply(env);

		if (value != null && !value.isBlank()) {
			return value.strip();
		}

		value = propertySource.apply(property);
		return value == null || value.isBlank() ? fallback : value.strip();
	}

	private static long parsePositiveLong(String value, long fallback) {
		try {
			long parsed = Long.parseLong(value.strip());
			return parsed > 0 ? parsed : fallback;
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static int parseBoundedPositiveInt(String value, int fallback, int max) {
		try {
			int parsed = Integer.parseInt(value.strip());
			return parsed > 0 ? Math.min(parsed, max) : fallback;
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static OptionalDouble parseOptionalDouble(String value) {
		if (value == null || value.isBlank()) {
			return OptionalDouble.empty();
		}

		try {
			double parsed = Double.parseDouble(value.strip());
			return parsed >= 0.0 && parsed <= 1.0 ? OptionalDouble.of(parsed) : OptionalDouble.empty();
		} catch (NumberFormatException ignored) {
			return OptionalDouble.empty();
		}
	}
}

enum LlmContextBackend {
	OWNER,
	GRAPH;

	static LlmContextBackend from(String value) {
		if (value == null || value.isBlank()) {
			return OWNER;
		}

		return switch (value.strip().toLowerCase(Locale.ROOT)) {
		case "graph", "rag", "johannes" -> GRAPH;
		default -> OWNER;
		};
	}
}
