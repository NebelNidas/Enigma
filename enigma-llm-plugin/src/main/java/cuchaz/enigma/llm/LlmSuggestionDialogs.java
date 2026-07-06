package cuchaz.enigma.llm;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableCellEditor;

import cuchaz.enigma.api.I18n;
import cuchaz.enigma.api.view.GuiView;

class LlmSuggestionDialogs {
	private LlmSuggestionDialogs() {
	}

	static SingleSuggestionChoice showSingleSuggestion(GuiView gui, LlmSuggestion suggestion) {
		List<String> choices = singleSuggestionChoices(suggestion);
		JComponent nameSelector = singleSuggestionNameSelector(choices);
		JPanel panel = singleSuggestionPanel(suggestion, nameSelector);
		String[] options = {
				I18n.translate("llm.dialog.apply"),
				I18n.translate("llm.dialog.keep_suggestion"),
				I18n.translate("llm.dialog.dismiss")
		};
		int result = JOptionPane.showOptionDialog(gui.getFrame(), panel, I18n.translate("llm.dialog.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);

		return switch (result) {
		case 0 -> new SingleSuggestionChoice(SingleSuggestionAction.APPLY, selectedSuggestionName(nameSelector));
		case 2 -> new SingleSuggestionChoice(SingleSuggestionAction.DISMISS, "");
		default -> new SingleSuggestionChoice(SingleSuggestionAction.KEEP, "");
		};
	}

	static List<String> singleSuggestionChoices(LlmSuggestion suggestion) {
		List<String> choices = new ArrayList<>();
		choices.add(suggestion.suggestedName());
		choices.addAll(suggestion.alternatives());
		return choices.stream().distinct().toList();
	}

	static JComponent singleSuggestionNameSelector(List<String> choices) {
		if (choices.size() <= 1) {
			return new JLabel(choices.isEmpty() ? "" : choices.get(0));
		}

		return new JComboBox<>(choices.toArray(String[]::new));
	}

	static String selectedSuggestionName(JComponent nameSelector) {
		if (nameSelector instanceof JComboBox<?> comboBox && comboBox.getSelectedItem() instanceof String selectedName) {
			return selectedName;
		}

		return nameSelector instanceof JLabel label ? label.getText() : "";
	}

	static JPanel singleSuggestionPanel(LlmSuggestion suggestion, JComponent nameSelector) {
		JPanel panel = new JPanel(new BorderLayout(0, 8));
		JPanel fields = new JPanel(new java.awt.GridLayout(0, 1, 0, 6));
		fields.add(new JLabel(I18n.translate("llm.dialog.suggestion")));
		fields.add(nameSelector);
		fields.add(new JLabel(String.format(Locale.ROOT, "%s: %.2f", I18n.translate("llm.dialog.confidence"), suggestion.confidence())));

		if (!suggestion.contextBackendText().isBlank()) {
			fields.add(new JLabel(I18n.translate("llm.dialog.context_backend") + ": " + suggestion.contextBackendText()));
		}

		panel.add(fields, BorderLayout.NORTH);

		if (!suggestion.reasoning().isBlank()) {
			panel.add(new JLabel("<html><body style='width: 420px'>" + escapeHtml(I18n.translate("llm.dialog.reasoning")) + ": " + escapeHtml(suggestion.reasoning()) + "</body></html>"), BorderLayout.CENTER);
		}

		return panel;
	}

	static String singleSuggestionMessage(LlmSuggestion suggestion) {
		return String.format(Locale.ROOT, "%s: %s%n%s: %s%n%s: %.2f%n%n%s: %s",
				I18n.translate("llm.dialog.suggestion"),
				suggestion.suggestedName(),
				I18n.translate("llm.dialog.alternatives"),
				suggestion.alternativesText(),
				I18n.translate("llm.dialog.confidence"),
				suggestion.confidence(),
				I18n.translate("llm.dialog.reasoning"),
				suggestion.reasoning()
		);
	}

	static JComponent batchHeader(BatchSuggestionResult result) {
		JPanel header = new JPanel(new BorderLayout(0, 4));
		header.add(new JLabel(I18n.translate("llm.dialog.batch_summary", result.suggestions().size(),
				result.failures().size(), result.skipped().size())), BorderLayout.NORTH);

		List<String> details = new ArrayList<>();
		details.addAll(result.failures());
		details.addAll(result.skipped());

		if (!details.isEmpty()) {
			JTextArea failures = new JTextArea(failureSummary(details));
			failures.setEditable(false);
			failures.setLineWrap(false);
			failures.setRows(Math.min(4, details.size() + 1));
			JScrollPane failureScroll = new JScrollPane(failures);
			failureScroll.setPreferredSize(new Dimension(900, 90));
			header.add(failureScroll, BorderLayout.CENTER);
		}

		return header;
	}

	static List<BatchSuggestion> showBatchPreview(GuiView gui, LlmConfig config, BatchSuggestionResult result) {
		BatchSuggestionTableModel model = new BatchSuggestionTableModel(result.suggestions());
		JTable table = batchTable(model);

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.setPreferredSize(new Dimension(900, 360));
		JLabel thresholdStatus = new JLabel(I18n.translate("llm.dialog.threshold_status"));

		if (config.batchPreselectThreshold().isPresent()) {
			double threshold = config.batchPreselectThreshold().getAsDouble();
			thresholdStatus.setText(I18n.translate("llm.dialog.threshold_status_configured", threshold));
			model.selectAboveThreshold(threshold);
		}

		JPanel panel = new JPanel(new BorderLayout(0, 8));
		JPanel footer = new JPanel(new BorderLayout(8, 0));
		JPanel statusPanel = new JPanel(new java.awt.GridLayout(0, 1, 0, 2));
		JLabel validationStatus = new JLabel();
		JLabel autoResolutionStatus = new JLabel();
		JPanel selectionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		JButton selectAll = new JButton(I18n.translate("llm.dialog.select_all"));
		JButton clearAll = new JButton(I18n.translate("llm.dialog.clear_all"));
		selectAll.addActionListener(_event -> model.setAllSelected(true));
		clearAll.addActionListener(_event -> model.setAllSelected(false));
		statusPanel.add(thresholdStatus);
		statusPanel.add(validationStatus);
		statusPanel.add(autoResolutionStatus);
		selectionButtons.add(selectAll);
		selectionButtons.add(clearAll);
		footer.add(statusPanel, BorderLayout.WEST);
		footer.add(selectionButtons, BorderLayout.EAST);

		panel.add(batchHeader(result), BorderLayout.NORTH);
		panel.add(scrollPane, BorderLayout.CENTER);
		panel.add(footer, BorderLayout.SOUTH);

		return showResizableBatchDialog(gui, panel, () -> batchValidationMessages(model.selectedRows()),
				validationStatus, () -> autoResolutionMessage(model), autoResolutionStatus,
				model::addTableModelListener) ? model.selectedRows() : List.of();
	}

	static JTable batchTable(BatchSuggestionTableModel model) {
		JTable table = new JTable(model) {
			@Override
			public String getToolTipText(MouseEvent event) {
				Point point = event.getPoint();
				int row = rowAtPoint(point);
				int column = columnAtPoint(point);

				if (row < 0 || column < 0) {
					return null;
				}

				Object value = getValueAt(row, column);
				String text = value == null ? "" : value.toString();
				return text.isBlank() ? null : text;
			}
		};
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setFillsViewportHeight(true);
		table.getColumnModel().getColumn(0).setPreferredWidth(55);
		table.getColumnModel().getColumn(1).setPreferredWidth(260);
		table.getColumnModel().getColumn(2).setPreferredWidth(150);
		table.getColumnModel().getColumn(3).setPreferredWidth(90);
		table.getColumnModel().getColumn(4).setPreferredWidth(145);
		table.getColumnModel().getColumn(5).setPreferredWidth(220);
		table.getColumnModel().getColumn(6).setPreferredWidth(420);
		table.getColumnModel().getColumn(2).setCellEditor(new BatchSuggestionChoiceEditor(model));
		return table;
	}

	static boolean showResizableBatchDialog(GuiView gui, JComponent panel) {
		return showResizableBatchDialog(gui, panel, List::of, new JLabel(), () -> "", new JLabel(), _listener -> {
		});
	}

	static boolean showResizableBatchDialog(GuiView gui, JComponent panel, Supplier<List<String>> validationMessages,
			JLabel validationStatus, Supplier<String> autoResolutionMessage, JLabel autoResolutionStatus,
			java.util.function.Consumer<TableModelListener> validationListenerRegistrar) {
		boolean[] accepted = { false };
		JDialog dialog = new JDialog(gui.getFrame(), I18n.translate("llm.dialog.title"), true);
		JButton ok = new JButton(I18n.translate("llm.dialog.apply"));
		JButton cancel = new JButton(I18n.translate("llm.dialog.close"));
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JPanel content = new JPanel(new BorderLayout(0, 8));
		Runnable updateValidation = () -> {
			List<String> messages = validationMessages.get();
			boolean valid = messages.isEmpty();
			ok.setEnabled(valid);
			validationStatus.setText(valid ? " "
					: I18n.translate("llm.dialog.batch_conflict_status", messages.size()));
			validationStatus.setToolTipText(valid ? null : String.join("\n", messages));
			String autoResolution = autoResolutionMessage.get();
			autoResolutionStatus.setText(autoResolution.isBlank() ? " " : autoResolution);
		};

		ok.addActionListener(_event -> {
			updateValidation.run();

			if (!ok.isEnabled()) {
				return;
			}

			accepted[0] = true;
			dialog.dispose();
		});
		cancel.addActionListener(_event -> dialog.dispose());
		validationListenerRegistrar.accept(_event -> updateValidation.run());
		updateValidation.run();
		buttons.add(ok);
		buttons.add(cancel);
		content.add(panel, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.setMinimumSize(new Dimension(720, 360));
		dialog.setResizable(true);
		dialog.pack();
		dialog.setLocationRelativeTo(gui.getFrame());
		dialog.setVisible(true);
		return accepted[0];
	}

	static List<String> batchValidationMessages(List<BatchSuggestion> selectedRows) {
		return LlmRenameApplier.batchSelectionConflicts(selectedRows);
	}

	static String autoResolutionMessage(BatchSuggestionTableModel model) {
		int count = model.autoResolvedConflictCount();
		return count <= 0 ? "" : I18n.translate("llm.dialog.batch_auto_resolved", count);
	}

	static String failureSummary(List<String> failures) {
		if (failures.isEmpty()) {
			return "";
		}

		return I18n.translate("llm.dialog.failures") + "\n" + String.join("\n", failures);
	}

	private static String escapeHtml(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	record SingleSuggestionChoice(SingleSuggestionAction action, String selectedName) {
	}

	enum SingleSuggestionAction {
		APPLY,
		KEEP,
		DISMISS
	}

	private static class BatchSuggestionChoiceEditor extends AbstractCellEditor implements TableCellEditor {
		private final BatchSuggestionTableModel model;
		private JComboBox<String> comboBox = new JComboBox<>();

		BatchSuggestionChoiceEditor(BatchSuggestionTableModel model) {
			this.model = model;
		}

		@Override
		public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row,
				int column) {
			int modelRow = table.convertRowIndexToModel(row);
			this.comboBox = new JComboBox<>(this.model.suggestionChoices(modelRow).toArray(String[]::new));
			this.comboBox.setSelectedItem(value);
			return this.comboBox;
		}

		@Override
		public Object getCellEditorValue() {
			Object selected = this.comboBox.getSelectedItem();
			return selected instanceof String name ? name : "";
		}
	}
}
