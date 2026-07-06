package cuchaz.enigma.llm;

import java.util.List;
import java.util.Objects;

record LlmSuggestion(String suggestedName, List<String> alternatives, double confidence, String reasoning,
		LlmContextBackend configuredBackend, LlmContextBackend resolvedBackend) {
	LlmSuggestion(String suggestedName, List<String> alternatives, double confidence, String reasoning) {
		this(suggestedName, alternatives, confidence, reasoning, null, null);
	}

	LlmSuggestion {
		suggestedName = suggestedName == null ? "" : suggestedName.strip();
		String finalSuggestedName = suggestedName;
		alternatives = alternatives == null ? List.of() : alternatives.stream()
				.filter(Objects::nonNull)
				.map(String::strip)
				.filter(alternative -> !alternative.isEmpty())
				.filter(alternative -> !alternative.equals(finalSuggestedName))
				.distinct()
				.toList();
		confidence = Double.isFinite(confidence) ? Math.max(0.0, Math.min(1.0, confidence)) : 0.0;
		reasoning = reasoning == null ? "" : reasoning.strip();
	}

	String alternativesText() {
		return String.join(", ", this.alternatives);
	}

	LlmSuggestion withContextBackend(LlmContextBackend configuredBackend, LlmContextBackend resolvedBackend) {
		return new LlmSuggestion(this.suggestedName, this.alternatives, this.confidence, this.reasoning,
				configuredBackend, resolvedBackend);
	}

	String contextBackendText() {
		if (this.resolvedBackend == null) {
			return "";
		}

		if (this.configuredBackend == LlmContextBackend.AUTO) {
			return backendDisplayName(LlmContextBackend.AUTO) + " -> " + backendDisplayName(this.resolvedBackend);
		}

		return backendDisplayName(this.resolvedBackend);
	}

	private static String backendDisplayName(LlmContextBackend backend) {
		return switch (backend) {
		case AUTO -> "Auto";
		case OWNER -> "Simple";
		case GRAPH -> "Graph-based";
		};
	}
}
