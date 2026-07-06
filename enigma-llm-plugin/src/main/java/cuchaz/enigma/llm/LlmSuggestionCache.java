package cuchaz.enigma.llm;

import java.util.LinkedHashMap;
import java.util.List;
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

	synchronized boolean isEmpty() {
		return this.suggestions.isEmpty();
	}

	synchronized List<Map.Entry<EntryKey, LlmSuggestion>> entries() {
		return this.suggestions.entrySet().stream()
				.map(entry -> Map.entry(entry.getKey(), entry.getValue()))
				.toList();
	}

	synchronized void clear() {
		this.suggestions.clear();
	}
}
