package cuchaz.enigma.llm;

import java.util.List;
import java.util.Objects;

record LlmSuggestion(String suggestedName, List<String> alternatives, double confidence, String reasoning) {
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
}
