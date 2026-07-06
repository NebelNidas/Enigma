package cuchaz.enigma.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.table.AbstractTableModel;

import cuchaz.enigma.api.I18n;

class BatchSuggestionTableModel extends AbstractTableModel {
	private final List<BatchSuggestion> rows;
	private int autoResolvedConflictCount;

	BatchSuggestionTableModel(List<BatchSuggestion> rows) {
		this.rows = new ArrayList<>(rows);
	}

	List<BatchSuggestion> selectedRows() {
		return this.rows.stream().filter(BatchSuggestion::selected).toList();
	}

	List<String> suggestionChoices(int rowIndex) {
		BatchSuggestion row = this.rows.get(rowIndex);
		List<String> choices = new ArrayList<>();
		choices.add(row.suggestion().suggestedName());
		choices.addAll(row.suggestion().alternatives());
		return choices.stream().distinct().toList();
	}

	int autoResolvedConflictCount() {
		return this.autoResolvedConflictCount;
	}

	void selectAboveThreshold(double threshold) {
		for (int i = 0; i < this.rows.size(); i++) {
			BatchSuggestion row = this.rows.get(i);
			boolean selected = row.selected() || row.suggestion().confidence() >= threshold;
			this.rows.set(i, new BatchSuggestion(row.key(), row.suggestion(), selected));
		}

		resolveSelectedConflictsByConfidence();
		this.fireTableDataChanged();
	}

	void setAllSelected(boolean selected) {
		for (int i = 0; i < this.rows.size(); i++) {
			BatchSuggestion row = this.rows.get(i);
			this.rows.set(i, new BatchSuggestion(row.key(), row.suggestion(), selected));
		}

		if (selected) {
			resolveSelectedConflictsByConfidence();
		} else {
			this.autoResolvedConflictCount = 0;
		}

		this.fireTableDataChanged();
	}

	@Override
	public int getRowCount() {
		return this.rows.size();
	}

	@Override
	public int getColumnCount() {
		return 7;
	}

	@Override
	public String getColumnName(int column) {
		return switch (column) {
		case 0 -> I18n.translate("llm.table.apply");
		case 1 -> I18n.translate("llm.table.target");
		case 2 -> I18n.translate("llm.table.suggestion");
		case 3 -> I18n.translate("llm.table.confidence");
		case 4 -> I18n.translate("llm.table.context_backend");
		case 5 -> I18n.translate("llm.table.alternatives");
		case 6 -> I18n.translate("llm.table.reasoning");
		default -> "";
		};
	}

	@Override
	public Class<?> getColumnClass(int columnIndex) {
		return columnIndex == 0 ? Boolean.class : String.class;
	}

	@Override
	public boolean isCellEditable(int rowIndex, int columnIndex) {
		return columnIndex == 0 || columnIndex == 2 && suggestionChoices(rowIndex).size() > 1;
	}

	@Override
	public Object getValueAt(int rowIndex, int columnIndex) {
		BatchSuggestion row = this.rows.get(rowIndex);

		return switch (columnIndex) {
		case 0 -> row.selected();
		case 1 -> row.key().displayName();
		case 2 -> row.suggestion().suggestedName();
		case 3 -> String.format(Locale.ROOT, "%.2f", row.suggestion().confidence());
		case 4 -> row.suggestion().contextBackendText();
		case 5 -> row.suggestion().alternativesText();
		case 6 -> row.suggestion().reasoning();
		default -> "";
		};
	}

	@Override
	public void setValueAt(Object value, int rowIndex, int columnIndex) {
		if (columnIndex == 0 && value instanceof Boolean selected) {
			BatchSuggestion row = this.rows.get(rowIndex);
			this.rows.set(rowIndex, new BatchSuggestion(row.key(), row.suggestion(), selected));
			this.autoResolvedConflictCount = 0;
			this.fireTableDataChanged();
		} else if (columnIndex == 2 && value instanceof String selectedName) {
			List<String> choices = suggestionChoices(rowIndex);

			if (!choices.contains(selectedName)) {
				return;
			}

			BatchSuggestion row = this.rows.get(rowIndex);
			List<String> alternatives = new ArrayList<>();
			alternatives.add(row.suggestion().suggestedName());
			alternatives.addAll(row.suggestion().alternatives());
			LlmSuggestion suggestion = new LlmSuggestion(selectedName, alternatives,
					row.suggestion().confidence(), row.suggestion().reasoning(),
					row.suggestion().configuredBackend(), row.suggestion().resolvedBackend());
			this.rows.set(rowIndex, new BatchSuggestion(row.key(), suggestion, row.selected()));
			this.autoResolvedConflictCount = 0;
			this.fireTableDataChanged();
		}
	}

	void resolveSelectedConflictsByConfidence() {
		Map<String, Integer> strongestRows = new LinkedHashMap<>();
		this.autoResolvedConflictCount = 0;

		for (int i = 0; i < this.rows.size(); i++) {
			BatchSuggestion row = this.rows.get(i);

			if (!row.selected()) {
				continue;
			}

			String conflictKey = LlmRenameApplier.batchSelectionConflictKey(row);

			if (conflictKey.isBlank()) {
				continue;
			}

			Integer previous = strongestRows.get(conflictKey);

			if (previous == null
					|| row.suggestion().confidence() > this.rows.get(previous).suggestion().confidence()) {
				strongestRows.put(conflictKey, i);
			}
		}

		for (int i = 0; i < this.rows.size(); i++) {
			BatchSuggestion row = this.rows.get(i);

			if (!row.selected()) {
				continue;
			}

			String conflictKey = LlmRenameApplier.batchSelectionConflictKey(row);

			if (!conflictKey.isBlank() && strongestRows.get(conflictKey) != i) {
				this.rows.set(i, new BatchSuggestion(row.key(), row.suggestion(), false));
				this.autoResolvedConflictCount++;
			}
		}
	}
}
