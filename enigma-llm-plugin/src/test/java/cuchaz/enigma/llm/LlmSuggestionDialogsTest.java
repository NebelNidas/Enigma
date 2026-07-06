package cuchaz.enigma.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;

import javax.swing.JTable;

import org.junit.Test;

public class LlmSuggestionDialogsTest {
	@Test
	public void batchHeaderShowsFailuresInScrollableTextArea() {
		BatchSuggestionResult result = new BatchSuggestionResult(List.of(), List.of("a.b : I: invalid", "a.c : I: duplicate"));

		javax.swing.JComponent header = LlmSuggestionDialogs.batchHeader(result);

		assertTrue(header instanceof javax.swing.JPanel);
		assertTrue(LlmTestSupport.containsComponent((javax.swing.JPanel) header, javax.swing.JScrollPane.class));
	}

	@Test
	public void batchTableSelectionRespectsThresholdAndManualChanges() {
		BatchSuggestion low = new BatchSuggestion(
				new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I"),
				new LlmSuggestion("count", List.of(), 0.4, ""),
				false
		);
		BatchSuggestion high = new BatchSuggestion(
				new EntryKey(EntryKind.METHOD, "example/Foo", "b", "()V"),
				new LlmSuggestion("getCount", List.of(), 0.9, ""),
				false
		);
		BatchSuggestionTableModel model = new BatchSuggestionTableModel(List.of(low, high));

		model.selectAboveThreshold(0.75);

		assertThat(model.selectedRows().size(), equalTo(1));
		assertThat(model.selectedRows().get(0).suggestion().suggestedName(), equalTo("getCount"));
		assertThat(model.getValueAt(1, 3), equalTo("0.90"));

		model.setValueAt(false, 1, 0);

		assertTrue(model.selectedRows().isEmpty());

		model.setAllSelected(true);

		assertThat(model.selectedRows().size(), equalTo(2));

		model.setAllSelected(false);

		assertTrue(model.selectedRows().isEmpty());
	}

	@Test
	public void batchTableSelectAllKeepsHighestConfidenceDuplicateName() {
		BatchSuggestion weakerPi = new BatchSuggestion(
				new EntryKey(EntryKind.FIELD, "example/Foo", "b", "F"),
				new LlmSuggestion("pi", List.of(), 0.60, "wrongly guessed pi"),
				false
		);
		BatchSuggestion strongerPi = new BatchSuggestion(
				new EntryKey(EntryKind.FIELD, "example/Foo", "a", "F"),
				new LlmSuggestion("pi", List.of(), 0.95, "actual pi constant"),
				false
		);
		BatchSuggestion angle = new BatchSuggestion(
				new EntryKey(EntryKind.FIELD, "example/Foo", "c", "F"),
				new LlmSuggestion("angle", List.of(), 0.80, "angle constant"),
				false
		);
		BatchSuggestionTableModel model = new BatchSuggestionTableModel(List.of(weakerPi, strongerPi, angle));

		model.setAllSelected(true);

		assertThat(model.selectedRows().size(), equalTo(2));
		assertThat(model.getValueAt(0, 0), equalTo(false));
		assertThat(model.getValueAt(1, 0), equalTo(true));
		assertThat(model.getValueAt(2, 0), equalTo(true));
		assertThat(model.autoResolvedConflictCount(), equalTo(1));
	}

	@Test
	public void batchTableManualSelectionCanOverrideAutoDeselectedDuplicateName() {
		BatchSuggestion weakerPi = new BatchSuggestion(
				new EntryKey(EntryKind.FIELD, "example/Foo", "b", "F"),
				new LlmSuggestion("pi", List.of(), 0.60, "wrongly guessed pi"),
				false
		);
		BatchSuggestion strongerPi = new BatchSuggestion(
				new EntryKey(EntryKind.FIELD, "example/Foo", "a", "F"),
				new LlmSuggestion("pi", List.of(), 0.95, "actual pi constant"),
				false
		);
		BatchSuggestionTableModel model = new BatchSuggestionTableModel(List.of(weakerPi, strongerPi));

		model.setAllSelected(true);

		assertThat(model.getValueAt(0, 0), equalTo(false));
		assertThat(model.getValueAt(1, 0), equalTo(true));

		model.setValueAt(true, 0, 0);

		assertThat(model.getValueAt(0, 0), equalTo(true));
		assertThat(model.getValueAt(1, 0), equalTo(true));
		assertThat(model.autoResolvedConflictCount(), equalTo(0));
		assertThat(LlmSuggestionDialogs.batchValidationMessages(model.selectedRows()).size(), equalTo(1));
	}

	@Test
	public void batchTableThresholdSelectionKeepsHighestConfidenceDuplicateName() {
		BatchSuggestion weakerPi = new BatchSuggestion(
				new EntryKey(EntryKind.FIELD, "example/Foo", "b", "F"),
				new LlmSuggestion("pi", List.of(), 0.80, "wrongly guessed pi"),
				false
		);
		BatchSuggestion strongerPi = new BatchSuggestion(
				new EntryKey(EntryKind.FIELD, "example/Foo", "a", "F"),
				new LlmSuggestion("pi", List.of(), 0.95, "actual pi constant"),
				false
		);
		BatchSuggestionTableModel model = new BatchSuggestionTableModel(List.of(weakerPi, strongerPi));

		model.selectAboveThreshold(0.75);

		assertThat(model.selectedRows().size(), equalTo(1));
		assertThat(model.selectedRows().get(0).key().name(), equalTo("a"));
	}

	@Test
	public void batchTableCanChooseAlternativeSuggestion() {
		BatchSuggestion row = new BatchSuggestion(
				new EntryKey(EntryKind.CLASS, "example/Foo", "example/Foo", ""),
				new LlmSuggestion("GeometryUtils", List.of("MathConstants", "AngleConstants"),
						0.85, "geometry constants"),
				true
		);
		BatchSuggestionTableModel model = new BatchSuggestionTableModel(List.of(row));

		assertTrue(model.isCellEditable(0, 2));
		assertThat(model.suggestionChoices(0), equalTo(List.of("GeometryUtils", "MathConstants", "AngleConstants")));

		model.setValueAt("MathConstants", 0, 2);

		BatchSuggestion selected = model.selectedRows().get(0);
		assertThat(selected.suggestion().suggestedName(), equalTo("MathConstants"));
		assertThat(selected.suggestion().alternatives(), equalTo(List.of("GeometryUtils", "AngleConstants")));
		assertThat(model.getValueAt(0, 2), equalTo("MathConstants"));

		model.setValueAt("NotAProvidedChoice", 0, 2);

		assertThat(model.selectedRows().get(0).suggestion().suggestedName(), equalTo("MathConstants"));
	}

	@Test
	public void batchTableShowsFullCellValueAsTooltip() {
		String reasoning = "long reasoning that would normally be clipped in the preview table";
		BatchSuggestion row = new BatchSuggestion(
				new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I"),
				new LlmSuggestion("count", List.of(), 0.8, reasoning),
				true
		);
		JTable table = LlmSuggestionDialogs.batchTable(new BatchSuggestionTableModel(List.of(row)));
		Rectangle cell = table.getCellRect(0, 6, true);
		MouseEvent event = new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0,
				cell.x + 1, cell.y + 1, 0, false);

		assertThat(table.getToolTipText(event), equalTo(reasoning));
	}

	@Test
	public void batchTableShowsResolvedAutoContextBackend() {
		BatchSuggestion row = new BatchSuggestion(
				new EntryKey(EntryKind.CLASS, "example/CharPredicate", "example/CharPredicate", ""),
				new LlmSuggestion("CharPredicate", List.of(), 0.8, "functional interface",
						LlmContextBackend.AUTO, LlmContextBackend.GRAPH),
				true
		);
		BatchSuggestionTableModel model = new BatchSuggestionTableModel(List.of(row));

		assertThat(model.getColumnName(4), containsString("context"));
		assertThat(model.getValueAt(0, 4), equalTo("Auto -> Graph-based"));
	}

	@Test
	public void batchValidationMessagesRejectSelectedDuplicateFieldNames() {
		List<BatchSuggestion> selected = List.of(
				new BatchSuggestion(new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I"),
						new LlmSuggestion("angle", List.of(), 0.8, ""), true),
				new BatchSuggestion(new EntryKey(EntryKind.FIELD, "example/Foo", "b", "I"),
						new LlmSuggestion("angle", List.of(), 0.7, ""), true)
		);

		List<String> messages = LlmSuggestionDialogs.batchValidationMessages(selected);

		assertThat(messages.size(), equalTo(1));
		assertThat(messages.get(0), containsString("both map to angle"));
	}

	@Test
	public void batchTableFormatsConfidenceWithStableLocale() {
		Locale previousLocale = Locale.getDefault();

		try {
			Locale.setDefault(Locale.GERMANY);
			BatchSuggestion row = new BatchSuggestion(
					new EntryKey(EntryKind.METHOD, "example/Foo", "b", "()V"),
					new LlmSuggestion("getCount", List.of(), 0.875, ""),
					false
			);
			BatchSuggestionTableModel model = new BatchSuggestionTableModel(List.of(row));

			assertThat(model.getValueAt(0, 3), equalTo("0.88"));
		} finally {
			Locale.setDefault(previousLocale);
		}
	}

	@Test
	public void singleSuggestionMessageIncludesStableFormattedDetails() {
		LlmSuggestion suggestion = new LlmSuggestion("itemCount", List.of("count", "size"), 0.875, "stores the item count");

		String message = LlmSuggestionDialogs.singleSuggestionMessage(suggestion);

		assertThat(message, containsString(": itemCount"));
		assertThat(message, containsString(": count, size"));
		assertThat(message, containsString(": 0.88"));
		assertThat(message, containsString(": stores the item count"));
	}

	@Test
	public void singleSuggestionChoicesPutPrimarySuggestionFirst() {
		LlmSuggestion suggestion = new LlmSuggestion("GeometryUtils", List.of("MathConstants", "GeometryUtils", "AngleConstants"), 0.85, "geometry constants");

		assertThat(LlmSuggestionDialogs.singleSuggestionChoices(suggestion), equalTo(List.of("GeometryUtils", "MathConstants", "AngleConstants")));
	}

	@Test
	public void singleSuggestionNameSelectorUsesDropdownOnlyForMultipleChoices() {
		javax.swing.JComponent single = LlmSuggestionDialogs.singleSuggestionNameSelector(List.of("GeometryUtils"));
		javax.swing.JComponent multiple = LlmSuggestionDialogs.singleSuggestionNameSelector(List.of("GeometryUtils", "MathConstants"));

		assertTrue(single instanceof javax.swing.JLabel);
		assertThat(LlmSuggestionDialogs.selectedSuggestionName(single), equalTo("GeometryUtils"));
		assertTrue(multiple instanceof javax.swing.JComboBox);
		assertThat(LlmSuggestionDialogs.selectedSuggestionName(multiple), equalTo("GeometryUtils"));
	}

	@Test
	public void singleSuggestionPanelShowsResolvedAutoContextBackend() {
		LlmSuggestion suggestion = new LlmSuggestion("CharPredicate", List.of(), 0.8, "functional interface",
				LlmContextBackend.AUTO, LlmContextBackend.GRAPH);

		javax.swing.JPanel panel = LlmSuggestionDialogs.singleSuggestionPanel(suggestion,
				LlmSuggestionDialogs.singleSuggestionNameSelector(List.of("CharPredicate")));

		assertTrue(LlmTestSupport.containsText(panel, "Auto -> Graph-based"));
	}
}
