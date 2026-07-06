package cuchaz.enigma.llm;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import cuchaz.enigma.api.I18n;

class LlmMenu {
	private LlmMenu() {
	}

	static JMenu create(Runnable suggestAction, Runnable batchProjectAction, Runnable batchCurrentClassAction,
			Runnable cancelAction, Runnable clearSuggestionsAction, Runnable editApiSettingsAction,
			Runnable editPromptExtensionAction, BooleanSupplier canSuggest,
			BooleanSupplier canBatchProject, BooleanSupplier canBatchCurrentClass, BooleanSupplier canCancel,
			BooleanSupplier canClearSuggestions, Supplier<LlmConfig> configSupplier,
			Consumer<LlmContextBackend> contextBackendAction, IntConsumer batchParallelismAction,
			IntConsumer timeoutAction, Consumer<Set<LlmAnalysisHint>> analysisHintsAction) {
		JMenu menu = new JMenu(I18n.translate("llm.menu.root"));
		JMenuItem statusItem = new JMenuItem();
		JMenuItem cancelItem = new JMenuItem(I18n.translate("llm.menu.cancel"));
		JMenuItem clearSuggestionsItem = new JMenuItem(I18n.translate("llm.menu.clear_suggestions"));
		JMenuItem editApiSettingsItem = new JMenuItem(I18n.translate("llm.menu.api_settings"));
		JMenuItem editPromptExtensionItem = new JMenuItem(I18n.translate("llm.menu.prompt_extension"));
		JMenuItem suggestItem = new JMenuItem(I18n.translate("llm.menu.suggest"));
		JMenuItem batchProjectItem = new JMenuItem(I18n.translate("llm.menu.batch_project"));
		JMenuItem batchCurrentClassItem = new JMenuItem(I18n.translate("llm.menu.batch_current_class"));
		JMenu backendMenu = new JMenu(I18n.translate("llm.menu.context_backend"));
		JMenu analysisHintsMenu = new JMenu(I18n.translate("llm.menu.analysis_hints"));
		JMenu analysisHintPresetsMenu = new JMenu(I18n.translate("llm.menu.analysis_hints_presets"));
		JMenu parallelismMenu = new JMenu(I18n.translate("llm.menu.parallelism"));
		JMenu timeoutMenu = new JMenu(I18n.translate("llm.menu.timeout"));
		JRadioButtonMenuItem autoBackendItem = new JRadioButtonMenuItem(backendName(LlmContextBackend.AUTO));
		JRadioButtonMenuItem ownerBackendItem = new JRadioButtonMenuItem(backendName(LlmContextBackend.OWNER));
		JRadioButtonMenuItem graphBackendItem = new JRadioButtonMenuItem(backendName(LlmContextBackend.GRAPH));
		ButtonGroup backendGroup = new ButtonGroup();
		ButtonGroup analysisHintPresetGroup = new ButtonGroup();
		ButtonGroup parallelismGroup = new ButtonGroup();
		ButtonGroup timeoutGroup = new ButtonGroup();
		JRadioButtonMenuItem hintsOffItem = new JRadioButtonMenuItem(I18n.translate("llm.analysis_hints.off"));
		JRadioButtonMenuItem hintsConservativeItem = new JRadioButtonMenuItem(I18n.translate("llm.analysis_hints.conservative"));
		JRadioButtonMenuItem hintsAllItem = new JRadioButtonMenuItem(I18n.translate("llm.analysis_hints.all"));
		JCheckBoxMenuItem functionalInterfaceHintItem = new JCheckBoxMenuItem(I18n.translate("llm.analysis_hint.functional_interface"));
		JCheckBoxMenuItem constantsClassHintItem = new JCheckBoxMenuItem(I18n.translate("llm.analysis_hint.constants_class"));
		JCheckBoxMenuItem relatedConstantsHintItem = new JCheckBoxMenuItem(I18n.translate("llm.analysis_hint.related_constants"));
		JCheckBoxMenuItem accessorsHintItem = new JCheckBoxMenuItem(I18n.translate("llm.analysis_hint.accessors"));
		JRadioButtonMenuItem[] parallelismItems = new JRadioButtonMenuItem[8];
		int[] timeoutValues = { 60, 120, 300, 600 };
		JRadioButtonMenuItem[] timeoutItems = new JRadioButtonMenuItem[timeoutValues.length];

		statusItem.setEnabled(false);
		suggestItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK));
		batchProjectItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
		suggestItem.addActionListener(_event -> suggestAction.run());
		batchProjectItem.addActionListener(_event -> batchProjectAction.run());
		batchCurrentClassItem.addActionListener(_event -> batchCurrentClassAction.run());
		cancelItem.addActionListener(_event -> cancelAction.run());
		clearSuggestionsItem.addActionListener(_event -> clearSuggestionsAction.run());
		editApiSettingsItem.addActionListener(_event -> editApiSettingsAction.run());
		editPromptExtensionItem.addActionListener(_event -> editPromptExtensionAction.run());
		autoBackendItem.addActionListener(_event -> contextBackendAction.accept(LlmContextBackend.AUTO));
		ownerBackendItem.addActionListener(_event -> contextBackendAction.accept(LlmContextBackend.OWNER));
		graphBackendItem.addActionListener(_event -> contextBackendAction.accept(LlmContextBackend.GRAPH));
		hintsOffItem.addActionListener(_event -> analysisHintsAction.accept(Set.of()));
		hintsConservativeItem.addActionListener(_event -> analysisHintsAction.accept(LlmAnalysisHint.conservative()));
		hintsAllItem.addActionListener(_event -> analysisHintsAction.accept(LlmAnalysisHint.all()));
		functionalInterfaceHintItem.addActionListener(_event -> analysisHintsAction.accept(toggledHints(configSupplier.get().analysisHints(), LlmAnalysisHint.FUNCTIONAL_INTERFACE, functionalInterfaceHintItem.isSelected())));
		constantsClassHintItem.addActionListener(_event -> analysisHintsAction.accept(toggledHints(configSupplier.get().analysisHints(), LlmAnalysisHint.CONSTANTS_CLASS, constantsClassHintItem.isSelected())));
		relatedConstantsHintItem.addActionListener(_event -> analysisHintsAction.accept(toggledHints(configSupplier.get().analysisHints(), LlmAnalysisHint.RELATED_CONSTANTS, relatedConstantsHintItem.isSelected())));
		accessorsHintItem.addActionListener(_event -> analysisHintsAction.accept(toggledHints(configSupplier.get().analysisHints(), LlmAnalysisHint.ACCESSORS, accessorsHintItem.isSelected())));
		backendGroup.add(autoBackendItem);
		backendGroup.add(ownerBackendItem);
		backendGroup.add(graphBackendItem);
		backendMenu.add(autoBackendItem);
		backendMenu.add(ownerBackendItem);
		backendMenu.add(graphBackendItem);
		analysisHintPresetGroup.add(hintsOffItem);
		analysisHintPresetGroup.add(hintsConservativeItem);
		analysisHintPresetGroup.add(hintsAllItem);
		analysisHintPresetsMenu.add(hintsOffItem);
		analysisHintPresetsMenu.add(hintsConservativeItem);
		analysisHintPresetsMenu.add(hintsAllItem);
		analysisHintsMenu.add(analysisHintPresetsMenu);
		analysisHintsMenu.addSeparator();
		analysisHintsMenu.add(functionalInterfaceHintItem);
		analysisHintsMenu.add(constantsClassHintItem);
		analysisHintsMenu.add(relatedConstantsHintItem);
		analysisHintsMenu.add(accessorsHintItem);

		for (int parallelism = 1; parallelism <= parallelismItems.length; parallelism++) {
			int value = parallelism;
			JRadioButtonMenuItem item = new JRadioButtonMenuItem(I18n.translate("llm.menu.parallelism_value", value));
			item.addActionListener(_event -> batchParallelismAction.accept(value));
			parallelismGroup.add(item);
			parallelismMenu.add(item);
			parallelismItems[parallelism - 1] = item;
		}

		for (int i = 0; i < timeoutValues.length; i++) {
			int value = timeoutValues[i];
			JRadioButtonMenuItem item = new JRadioButtonMenuItem(I18n.translate("llm.menu.timeout_value", value));
			item.addActionListener(_event -> timeoutAction.accept(value));
			timeoutGroup.add(item);
			timeoutMenu.add(item);
			timeoutItems[i] = item;
		}

		menu.addMenuListener(new MenuListener() {
			@Override
			public void menuSelected(MenuEvent event) {
				LlmConfig config = configSupplier.get();
				statusItem.setText(statusText(config));
				suggestItem.setEnabled(canSuggest.getAsBoolean());
				batchProjectItem.setEnabled(canBatchProject.getAsBoolean());
				batchCurrentClassItem.setEnabled(canBatchCurrentClass.getAsBoolean());
				cancelItem.setEnabled(canCancel.getAsBoolean());
				clearSuggestionsItem.setEnabled(canClearSuggestions.getAsBoolean());
				autoBackendItem.setSelected(config.contextBackend() == LlmContextBackend.AUTO);
				ownerBackendItem.setSelected(config.contextBackend() == LlmContextBackend.OWNER);
				graphBackendItem.setSelected(config.contextBackend() == LlmContextBackend.GRAPH);
				hintsOffItem.setSelected(config.analysisHints().isEmpty());
				hintsConservativeItem.setSelected(config.analysisHints().equals(LlmAnalysisHint.conservative()));
				hintsAllItem.setSelected(config.analysisHints().equals(LlmAnalysisHint.all()));
				functionalInterfaceHintItem.setSelected(config.analysisHints().contains(LlmAnalysisHint.FUNCTIONAL_INTERFACE));
				constantsClassHintItem.setSelected(config.analysisHints().contains(LlmAnalysisHint.CONSTANTS_CLASS));
				relatedConstantsHintItem.setSelected(config.analysisHints().contains(LlmAnalysisHint.RELATED_CONSTANTS));
				accessorsHintItem.setSelected(config.analysisHints().contains(LlmAnalysisHint.ACCESSORS));
				parallelismItems[Math.max(1, Math.min(parallelismItems.length, config.batchParallelism())) - 1].setSelected(true);

				for (int i = 0; i < timeoutValues.length; i++) {
					timeoutItems[i].setSelected(config.timeout().toSeconds() == timeoutValues[i]);
				}
			}

			@Override
			public void menuDeselected(MenuEvent event) {
			}

			@Override
			public void menuCanceled(MenuEvent event) {
			}
		});

		menu.add(statusItem);
		menu.addSeparator();
		menu.add(cancelItem);
		menu.add(clearSuggestionsItem);
		menu.addSeparator();
		menu.add(backendMenu);
		menu.add(analysisHintsMenu);
		menu.add(timeoutMenu);
		menu.add(parallelismMenu);
		menu.add(editApiSettingsItem);
		menu.add(editPromptExtensionItem);
		menu.addSeparator();
		menu.add(suggestItem);
		menu.add(batchCurrentClassItem);
		menu.add(batchProjectItem);
		return menu;
	}

	private static Set<LlmAnalysisHint> toggledHints(Set<LlmAnalysisHint> current, LlmAnalysisHint hint, boolean enabled) {
		java.util.EnumSet<LlmAnalysisHint> hints = current.isEmpty()
				? java.util.EnumSet.noneOf(LlmAnalysisHint.class)
				: java.util.EnumSet.copyOf(current);

		if (enabled) {
			hints.add(hint);
		} else {
			hints.remove(hint);
		}

		return Set.copyOf(hints);
	}

	static void install(JMenuBar menuBar, JMenu menu) {
		int insertIndex = Math.max(0, menuBar.getMenuCount() - 1);
		menuBar.add(menu, insertIndex);
	}

	static String statusText(LlmConfig config) {
		return statusText(config, I18n::translate);
	}

	static String statusText(LlmConfig config, Translator translator) {
		if (!config.isConfigured()) {
			return translator.translate("llm.menu.status_unconfigured");
		}

		return translator.translate("llm.menu.status_configured", config.model(), backendName(config.contextBackend(), translator));
	}

	static String backendName(LlmContextBackend backend) {
		return backendName(backend, I18n::translate);
	}

	private static String backendName(LlmContextBackend backend, Translator translator) {
		return switch (backend) {
		case AUTO -> translator.translate("llm.backend.auto");
		case OWNER -> translator.translate("llm.backend.owner");
		case GRAPH -> translator.translate("llm.backend.graph");
		};
	}

	@FunctionalInterface
	interface Translator {
		String translate(String key, Object... args);
	}
}
