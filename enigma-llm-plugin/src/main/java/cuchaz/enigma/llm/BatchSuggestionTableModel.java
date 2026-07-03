package cuchaz.enigma.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.table.AbstractTableModel;

import cuchaz.enigma.api.I18n;

class BatchSuggestionTableModel extends AbstractTableModel {
	private final List<BatchSuggestion> rows;

	BatchSuggestionTableModel(List<BatchSuggestion> rows) {
		this.rows = new ArrayList<>(rows);
	}

	List<BatchSuggestion> selectedRows() {
		return this.rows.stream().filter(BatchSuggestion::selected).toList();
	}

	void selectAboveThreshold(double threshold) {
		for (int i = 0; i < this.rows.size(); i++) {
			BatchSuggestion row = this.rows.get(i);
			this.rows.set(i, new BatchSuggestion(row.key(), row.suggestion(), row.selected() || row.suggestion().confidence() >= threshold));
		}

		this.fireTableDataChanged();
	}

	void setAllSelected(boolean selected) {
		for (int i = 0; i < this.rows.size(); i++) {
			BatchSuggestion row = this.rows.get(i);
			this.rows.set(i, new BatchSuggestion(row.key(), row.suggestion(), selected));
		}

		this.fireTableDataChanged();
	}

	@Override
	public int getRowCount() {
		return this.rows.size();
	}

	@Override
	public int getColumnCount() {
		return 6;
	}

	@Override
	public String getColumnName(int column) {
		return switch (column) {
		case 0 -> I18n.translate("llm.table.apply");
		case 1 -> I18n.translate("llm.table.target");
		case 2 -> I18n.translate("llm.table.suggestion");
		case 3 -> I18n.translate("llm.table.confidence");
		case 4 -> I18n.translate("llm.table.alternatives");
		case 5 -> I18n.translate("llm.table.reasoning");
		default -> "";
		};
	}

	@Override
	public Class<?> getColumnClass(int columnIndex) {
		return columnIndex == 0 ? Boolean.class : String.class;
	}

	@Override
	public boolean isCellEditable(int rowIndex, int columnIndex) {
		return columnIndex == 0;
	}

	@Override
	public Object getValueAt(int rowIndex, int columnIndex) {
		BatchSuggestion row = this.rows.get(rowIndex);

		return switch (columnIndex) {
		case 0 -> row.selected();
		case 1 -> row.key().displayName();
		case 2 -> row.suggestion().suggestedName();
		case 3 -> String.format(Locale.ROOT, "%.2f", row.suggestion().confidence());
		case 4 -> row.suggestion().alternativesText();
		case 5 -> row.suggestion().reasoning();
		default -> "";
		};
	}

	@Override
	public void setValueAt(Object value, int rowIndex, int columnIndex) {
		if (columnIndex == 0 && value instanceof Boolean selected) {
			BatchSuggestion row = this.rows.get(rowIndex);
			this.rows.set(rowIndex, new BatchSuggestion(row.key(), row.suggestion(), selected));
			this.fireTableCellUpdated(rowIndex, columnIndex);
		}
	}
}
