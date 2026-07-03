package cuchaz.enigma.llm;

import java.util.Optional;

import cuchaz.enigma.api.service.NameProposalService;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.Entry;

public class LlmCachedNameProposalService implements NameProposalService {
	private final LlmNameProposalPlugin plugin;

	public LlmCachedNameProposalService(LlmNameProposalPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public Optional<String> proposeName(Entry<?> obfEntry, EntryRemapper remapper) {
		return EntryKey.fromEntry(obfEntry)
				.flatMap(key -> this.plugin.getSuggestions().get(key).map(suggestion -> proposedName(key, suggestion)));
	}

	private static String proposedName(EntryKey key, LlmSuggestion suggestion) {
		String name = suggestion.suggestedName();

		if (key.kind() == EntryKind.CLASS && !key.owner().contains("$") && !name.contains("/")) {
			int packageEnd = key.owner().lastIndexOf('/');

			if (packageEnd >= 0) {
				return key.owner().substring(0, packageEnd + 1) + name;
			}
		}

		return name;
	}
}
