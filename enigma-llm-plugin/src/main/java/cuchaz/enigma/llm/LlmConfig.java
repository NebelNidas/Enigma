package cuchaz.enigma.llm;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;

record LlmConfig(String baseUrl, String model, String apiKey, Duration timeout,
		OptionalDouble batchPreselectThreshold, int batchParallelism, LlmContextBackend contextBackend,
		String promptExtension, Set<LlmAnalysisHint> analysisHints) {
	private static final LlmContextBackend DEFAULT_CONTEXT_BACKEND = LlmContextBackend.AUTO;
	private static final Set<LlmAnalysisHint> DEFAULT_ANALYSIS_HINTS = LlmAnalysisHint.conservative();

	LlmConfig {
		baseUrl = baseUrl == null ? "" : baseUrl.strip();
		model = model == null ? "" : model.strip();
		apiKey = apiKey == null ? "" : apiKey.strip();
		promptExtension = promptExtension == null ? "" : promptExtension.strip();
		analysisHints = analysisHints == null ? Set.of() : Set.copyOf(analysisHints);
	}

	LlmConfig(String baseUrl, String model, String apiKey, Duration timeout, OptionalDouble batchPreselectThreshold) {
		this(baseUrl, model, apiKey, timeout, batchPreselectThreshold, 1, LlmContextBackend.OWNER, "", Set.of());
	}

	LlmConfig(String baseUrl, String model, String apiKey, Duration timeout, OptionalDouble batchPreselectThreshold, int batchParallelism) {
		this(baseUrl, model, apiKey, timeout, batchPreselectThreshold, batchParallelism, LlmContextBackend.OWNER, "", Set.of());
	}

	LlmConfig(String baseUrl, String model, String apiKey, Duration timeout, OptionalDouble batchPreselectThreshold, int batchParallelism, LlmContextBackend contextBackend) {
		this(baseUrl, model, apiKey, timeout, batchPreselectThreshold, batchParallelism, contextBackend, "", Set.of());
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
				LlmContextBackend.from(first(env, property, "ENIGMA_LLM_CONTEXT_BACKEND", "enigma.llm.contextBackend", DEFAULT_CONTEXT_BACKEND.configValue()), DEFAULT_CONTEXT_BACKEND),
				first(env, property, "ENIGMA_LLM_PROMPT_EXTENSION", "enigma.llm.promptExtension", ""),
				LlmAnalysisHint.from(first(env, property, "ENIGMA_LLM_ANALYSIS_HINTS", "enigma.llm.analysisHints", "conservative"), DEFAULT_ANALYSIS_HINTS)
		);
	}

	boolean isConfigured() {
		return this.baseUrl != null && !this.baseUrl.isBlank() && this.model != null && !this.model.isBlank();
	}

	LlmConfig withContextBackend(LlmContextBackend contextBackend) {
		return new LlmConfig(this.baseUrl, this.model, this.apiKey, this.timeout, this.batchPreselectThreshold, this.batchParallelism, contextBackend, this.promptExtension, this.analysisHints);
	}

	LlmConfig withBaseUrl(String baseUrl) {
		return new LlmConfig(baseUrl, this.model, this.apiKey, this.timeout, this.batchPreselectThreshold, this.batchParallelism, this.contextBackend, this.promptExtension, this.analysisHints);
	}

	LlmConfig withModel(String model) {
		return new LlmConfig(this.baseUrl, model, this.apiKey, this.timeout, this.batchPreselectThreshold, this.batchParallelism, this.contextBackend, this.promptExtension, this.analysisHints);
	}

	LlmConfig withBatchParallelism(int batchParallelism) {
		return new LlmConfig(this.baseUrl, this.model, this.apiKey, this.timeout, this.batchPreselectThreshold, parseBoundedPositiveInt(Integer.toString(batchParallelism), this.batchParallelism, 8), this.contextBackend, this.promptExtension, this.analysisHints);
	}

	LlmConfig withTimeoutSeconds(int timeoutSeconds) {
		return new LlmConfig(this.baseUrl, this.model, this.apiKey, Duration.ofSeconds(parsePositiveLong(Integer.toString(timeoutSeconds), this.timeout.toSeconds())), this.batchPreselectThreshold, this.batchParallelism, this.contextBackend, this.promptExtension, this.analysisHints);
	}

	LlmConfig withPromptExtension(String promptExtension) {
		return new LlmConfig(this.baseUrl, this.model, this.apiKey, this.timeout, this.batchPreselectThreshold, this.batchParallelism, this.contextBackend, promptExtension == null ? "" : promptExtension.strip(), this.analysisHints);
	}

	LlmConfig withAnalysisHints(Set<LlmAnalysisHint> analysisHints) {
		return new LlmConfig(this.baseUrl, this.model, this.apiKey, this.timeout, this.batchPreselectThreshold, this.batchParallelism, this.contextBackend, this.promptExtension, analysisHints);
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

enum LlmAnalysisHint {
	FUNCTIONAL_INTERFACE,
	CONSTANTS_CLASS,
	RELATED_CONSTANTS,
	ACCESSORS;

	static Set<LlmAnalysisHint> conservative() {
		return Set.of(FUNCTIONAL_INTERFACE, CONSTANTS_CLASS, RELATED_CONSTANTS);
	}

	static Set<LlmAnalysisHint> all() {
		return EnumSet.allOf(LlmAnalysisHint.class);
	}

	static Set<LlmAnalysisHint> from(String value, Set<LlmAnalysisHint> fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}

		String normalized = value.strip().toLowerCase(Locale.ROOT);

		if (normalized.equals("off") || normalized.equals("none") || normalized.equals("false")) {
			return Set.of();
		}

		if (normalized.equals("conservative") || normalized.equals("default")) {
			return conservative();
		}

		if (normalized.equals("all") || normalized.equals("detailed")) {
			return all();
		}

		EnumSet<LlmAnalysisHint> hints = EnumSet.noneOf(LlmAnalysisHint.class);

		for (String part : normalized.split("[,; ]+")) {
			switch (part) {
			case "functional", "functional_interface", "functional-interface", "predicate" -> hints.add(FUNCTIONAL_INTERFACE);
			case "constants", "constant", "constants_class", "constants-class" -> hints.add(CONSTANTS_CLASS);
			case "related_constants", "related-constants", "constant_families", "constant-families",
					"families", "siblings" -> hints.add(RELATED_CONSTANTS);
			case "accessors", "accessor", "getter", "setter" -> hints.add(ACCESSORS);
			case "" -> {
			}
			default -> {
				return fallback;
			}
			}
		}

		return Set.copyOf(hints);
	}

	String configValue() {
		return switch (this) {
		case FUNCTIONAL_INTERFACE -> "functional_interface";
		case CONSTANTS_CLASS -> "constants_class";
		case RELATED_CONSTANTS -> "related_constants";
		case ACCESSORS -> "accessors";
		};
	}
}

enum LlmContextBackend {
	AUTO,
	OWNER,
	GRAPH;

	static LlmContextBackend from(String value) {
		return from(value, OWNER);
	}

	static LlmContextBackend from(String value, LlmContextBackend fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}

		return switch (value.strip().toLowerCase(Locale.ROOT)) {
		case "auto" -> AUTO;
		case "graph", "rag", "johannes" -> GRAPH;
		case "owner", "simple" -> OWNER;
		default -> fallback;
		};
	}

	String configValue() {
		return switch (this) {
		case AUTO -> "auto";
		case OWNER -> "owner";
		case GRAPH -> "graph";
		};
	}
}
