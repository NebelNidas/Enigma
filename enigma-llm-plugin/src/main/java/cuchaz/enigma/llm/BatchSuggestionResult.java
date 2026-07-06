package cuchaz.enigma.llm;

import java.util.List;

record BatchSuggestionResult(List<BatchSuggestion> suggestions, List<String> failures, List<String> skipped) {
	BatchSuggestionResult(List<BatchSuggestion> suggestions, List<String> failures) {
		this(suggestions, failures, List.of());
	}

	BatchSuggestionResult {
		suggestions = List.copyOf(suggestions);
		failures = List.copyOf(failures);
		skipped = List.copyOf(skipped);
	}
}
