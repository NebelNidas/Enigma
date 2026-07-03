package cuchaz.enigma.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cuchaz.enigma.api.DataInvalidationEvent;
import cuchaz.enigma.api.view.GuiView;
import cuchaz.enigma.api.view.ProjectView;
import cuchaz.enigma.api.view.entry.ClassEntryView;
import cuchaz.enigma.api.view.entry.EntryView;

class LlmRenameApplier {
	private final LlmNameProposalPlugin plugin;

	LlmRenameApplier(LlmNameProposalPlugin plugin) {
		this.plugin = plugin;
	}

	boolean applySuggestion(GuiView gui, ProjectView project, EntryKey key, LlmSuggestion suggestion) {
		return EntryKey.toEntryView(key).map(entry -> {
			String rename = normalizeRename(entry, suggestion.suggestedName());

			if (gui.applyRename(entry, rename)) {
				this.plugin.getSuggestions().put(key, suggestion);
				this.plugin.markInternalRefreshInvalidation();
				project.invalidateData(DataInvalidationEvent.InvalidationType.MAPPINGS);
				return true;
			}

			this.plugin.getSuggestions().remove(key);
			return false;
		}).orElse(false);
	}

	static List<String> batchSelectionConflicts(List<BatchSuggestion> selectedRows) {
		Map<String, BatchSuggestion> seen = new LinkedHashMap<>();
		List<String> conflicts = new ArrayList<>();

		for (BatchSuggestion row : selectedRows) {
			String key = batchSelectionConflictKey(row);

			if (key.isBlank()) {
				continue;
			}

			BatchSuggestion previous = seen.putIfAbsent(key, row);

			if (previous != null) {
				conflicts.add(previous.key().displayName() + " and " + row.key().displayName() + " both map to " + row.suggestion().suggestedName());
			}
		}

		return conflicts;
	}

	static String normalizeRename(EntryView entry, String suggestedName) {
		if (entry instanceof ClassEntryView classEntry && classEntry.getParent() == null && !suggestedName.contains("/")) {
			String fullName = classEntry.getFullName();
			int packageEnd = fullName.lastIndexOf('/');

			if (packageEnd >= 0) {
				return fullName.substring(0, packageEnd + 1) + suggestedName;
			}
		}

		return suggestedName;
	}

	private static String batchSelectionConflictKey(BatchSuggestion row) {
		EntryKey key = row.key();

		return switch (key.kind()) {
		case FIELD -> "FIELD|" + key.owner() + "|" + row.suggestion().suggestedName();
		case PARAMETER -> "PARAMETER|" + key.owner() + "|" + key.name() + "|" + key.descriptor() + "|" + row.suggestion().suggestedName();
		case CLASS, METHOD -> "";
		};
	}
}
