package cuchaz.enigma.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

class LlmSuggestionCache {
	private final Map<EntryKey, LlmSuggestion> suggestions = new LinkedHashMap<>();

	synchronized Optional<LlmSuggestion> get(EntryKey key) {
		return Optional.ofNullable(this.suggestions.get(key));
	}

	synchronized void put(EntryKey key, LlmSuggestion suggestion) {
		this.suggestions.put(key, suggestion);
	}

	synchronized void remove(EntryKey key) {
		this.suggestions.remove(key);
	}

	synchronized void removeIf(Predicate<EntryKey> predicate) {
		this.suggestions.keySet().removeIf(predicate);
	}

	synchronized void clear() {
		this.suggestions.clear();
	}
}
