package cuchaz.enigma.llm;

import java.util.List;

record BatchSuggestionResult(List<BatchSuggestion> suggestions, List<String> failures) {
	BatchSuggestionResult {
		suggestions = List.copyOf(suggestions);
		failures = List.copyOf(failures);
	}
}
