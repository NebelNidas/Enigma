package cuchaz.enigma.llm;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import javax.swing.ButtonGroup;
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

	static JMenu create(Runnable suggestAction, Runnable batchProjectAction, Runnable batchCurrentClassAction, BooleanSupplier canSuggest, BooleanSupplier canBatchProject, BooleanSupplier canBatchCurrentClass, Supplier<LlmConfig> configSupplier, Consumer<LlmContextBackend> contextBackendAction, IntConsumer batchParallelismAction) {
		JMenu menu = new JMenu(I18n.translate("llm.menu.root"));
		JMenuItem statusItem = new JMenuItem();
		JMenuItem suggestItem = new JMenuItem(I18n.translate("llm.menu.suggest"));
		JMenuItem batchProjectItem = new JMenuItem(I18n.translate("llm.menu.batch_project"));
		JMenuItem batchCurrentClassItem = new JMenuItem(I18n.translate("llm.menu.batch_current_class"));
		JMenu backendMenu = new JMenu(I18n.translate("llm.menu.context_backend"));
		JMenu parallelismMenu = new JMenu(I18n.translate("llm.menu.parallelism"));
		JRadioButtonMenuItem ownerBackendItem = new JRadioButtonMenuItem(backendName(LlmContextBackend.OWNER));
		JRadioButtonMenuItem graphBackendItem = new JRadioButtonMenuItem(backendName(LlmContextBackend.GRAPH));
		ButtonGroup backendGroup = new ButtonGroup();
		ButtonGroup parallelismGroup = new ButtonGroup();
		JRadioButtonMenuItem[] parallelismItems = new JRadioButtonMenuItem[8];

		statusItem.setEnabled(false);
		suggestItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK));
		batchProjectItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
		suggestItem.addActionListener(_event -> suggestAction.run());
		batchProjectItem.addActionListener(_event -> batchProjectAction.run());
		batchCurrentClassItem.addActionListener(_event -> batchCurrentClassAction.run());
		ownerBackendItem.addActionListener(_event -> contextBackendAction.accept(LlmContextBackend.OWNER));
		graphBackendItem.addActionListener(_event -> contextBackendAction.accept(LlmContextBackend.GRAPH));
		backendGroup.add(ownerBackendItem);
		backendGroup.add(graphBackendItem);
		backendMenu.add(ownerBackendItem);
		backendMenu.add(graphBackendItem);

		for (int parallelism = 1; parallelism <= parallelismItems.length; parallelism++) {
			int value = parallelism;
			JRadioButtonMenuItem item = new JRadioButtonMenuItem(I18n.translate("llm.menu.parallelism_value", value));
			item.addActionListener(_event -> batchParallelismAction.accept(value));
			parallelismGroup.add(item);
			parallelismMenu.add(item);
			parallelismItems[parallelism - 1] = item;
		}

		menu.addMenuListener(new MenuListener() {
			@Override
			public void menuSelected(MenuEvent event) {
				LlmConfig config = configSupplier.get();
				statusItem.setText(statusText(config));
				suggestItem.setEnabled(canSuggest.getAsBoolean());
				batchProjectItem.setEnabled(canBatchProject.getAsBoolean());
				batchCurrentClassItem.setEnabled(canBatchCurrentClass.getAsBoolean());
				ownerBackendItem.setSelected(config.contextBackend() == LlmContextBackend.OWNER);
				graphBackendItem.setSelected(config.contextBackend() == LlmContextBackend.GRAPH);
				parallelismItems[Math.max(1, Math.min(parallelismItems.length, config.batchParallelism())) - 1].setSelected(true);
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
		menu.add(backendMenu);
		menu.add(parallelismMenu);
		menu.addSeparator();
		menu.add(suggestItem);
		menu.add(batchCurrentClassItem);
		menu.add(batchProjectItem);
		return menu;
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
		case OWNER -> translator.translate("llm.backend.owner");
		case GRAPH -> translator.translate("llm.backend.graph");
		};
	}

	@FunctionalInterface
	interface Translator {
		String translate(String key, Object... args);
	}
}
