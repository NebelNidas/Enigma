package cuchaz.enigma.llm;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import cuchaz.enigma.api.DataInvalidationEvent;
import cuchaz.enigma.api.I18n;
import cuchaz.enigma.api.service.GuiService;
import cuchaz.enigma.api.view.GuiView;
import cuchaz.enigma.api.view.ProjectView;
import cuchaz.enigma.api.view.entry.ClassEntryView;
import cuchaz.enigma.api.view.entry.EntryReferenceView;
import cuchaz.enigma.api.view.entry.EntryView;

public class LlmGuiService implements GuiService {
	private static final int DEFAULT_BATCH_LIMIT = 25;
	private static final int MAX_BATCH_LIMIT = 100;

	private final LlmNameProposalPlugin plugin;
	private final LlmSuggestionEngine suggestionEngine;
	private final LlmRenameApplier renameApplier;
	private final JLabel taskStatusLabel = new JLabel();
	private final Object busyLock = new Object();
	private Object busyToken;
	private volatile LlmContextBackend selectedContextBackend;
	private volatile Integer selectedBatchParallelism;

	public LlmGuiService(LlmNameProposalPlugin plugin) {
		this(plugin, new LlmSuggestionEngine(plugin));
	}

	LlmGuiService(LlmNameProposalPlugin plugin, LlmSuggestionEngine.SuggestionRequester suggestionRequester) {
		this(plugin, new LlmSuggestionEngine(plugin, suggestionRequester));
	}

	private LlmGuiService(LlmNameProposalPlugin plugin, LlmSuggestionEngine suggestionEngine) {
		this.plugin = plugin;
		this.suggestionEngine = suggestionEngine;
		this.renameApplier = new LlmRenameApplier(plugin);
	}

	@Override
	public void onStart(GuiView gui) {
		LlmMenu.install(gui.getFrame().getJMenuBar(), LlmMenu.create(
				() -> suggestCurrent(gui),
				() -> suggestBatch(gui, BatchScope.PROJECT),
				() -> suggestBatch(gui, BatchScope.CURRENT_CLASS),
				() -> !isBusy() && currentTarget(gui).isPresent(),
				() -> !isBusy() && gui.getProject() != null && !this.plugin.getIndex().isEmpty(),
				() -> !isBusy() && gui.getProject() != null && gui.getActiveClass() != null && !this.plugin.getIndex().isEmpty(),
				this::loadConfig,
				this::setContextBackend,
				this::setBatchParallelism
		));
		this.taskStatusLabel.setVisible(false);
		gui.addStatusComponent(this.taskStatusLabel);
	}

	@Override
	public void addToEditorContextMenu(GuiView gui, MenuRegistrar registrar) {
		registrar.addSeparator();
		registrar.add("llm.menu.suggest")
				.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK))
				.setEnabledWhen(() -> !isBusy() && currentTarget(gui).isPresent())
				.setAction(() -> suggestCurrent(gui));
		registrar.add("llm.menu.batch_current_class")
				.setEnabledWhen(() -> !isBusy() && gui.getProject() != null && gui.getActiveClass() != null && !this.plugin.getIndex().isEmpty())
				.setAction(() -> suggestBatch(gui, BatchScope.CURRENT_CLASS));
	}

	private void suggestCurrent(GuiView gui) {
		Optional<EntryView> target = currentTarget(gui);

		if (target.isEmpty()) {
			showMessage(gui, "llm.dialog.no_target");
			return;
		}

		ProjectView project = gui.getProject();

		if (project == null) {
			showMessage(gui, "llm.dialog.no_project");
			return;
		}

		LlmConfig config = loadConfig();

		if (!config.isConfigured()) {
			showMessage(gui, "llm.dialog.no_config");
			return;
		}

		EntryKey.fromEntryView(target.get()).ifPresentOrElse(key -> {
			Object busyToken = setBusy(gui, "llm.dialog.suggesting");
			if (busyToken == null) {
				return;
			}

			CompletableFuture.supplyAsync(() -> requestSuggestion(config, project, key))
					.whenComplete((suggestion, error) -> SwingUtilities.invokeLater(() -> {
						if (!this.plugin.isProjectCurrent(project) || gui.getProject() != project) {
							clearBusy(gui, busyToken);
							return;
						}

						clearBusy(gui, busyToken);

						if (error != null) {
							showError(gui, error);
						} else {
							this.plugin.markInternalRefreshInvalidation();
							project.invalidateData(DataInvalidationEvent.InvalidationType.MAPPINGS);
							showSingleSuggestion(gui, project, key, suggestion);
						}
					}));
		}, () -> showMessage(gui, "llm.dialog.no_target"));
	}

	private void suggestBatch(GuiView gui, BatchScope scope) {
		ProjectView project = gui.getProject();

		if (project == null) {
			showMessage(gui, "llm.dialog.no_project");
			return;
		}

		LlmConfig config = loadConfig();

		if (!config.isConfigured()) {
			showMessage(gui, "llm.dialog.no_config");
			return;
		}

		int limit = scope == BatchScope.PROJECT ? askBatchLimit(gui) : Integer.MAX_VALUE;

		if (limit <= 0) {
			return;
		}

		List<EntryKey> targets = batchTargets(gui, project, limit, scope);

		if (targets.isEmpty()) {
			showMessage(gui, "llm.dialog.no_suggestions");
			return;
		}

		Object busyToken = setBusy(gui, "llm.dialog.batch");
		if (busyToken == null) {
			return;
		}

		CompletableFuture.supplyAsync(() -> requestBatch(config, project, targets))
				.whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
					if (!this.plugin.isProjectCurrent(project) || gui.getProject() != project) {
						clearBusy(gui, busyToken);
						return;
					}

					clearBusy(gui, busyToken);

					if (error != null) {
						showError(gui, error);
					} else {
						this.plugin.markInternalRefreshInvalidation();
						project.invalidateData(DataInvalidationEvent.InvalidationType.MAPPINGS);
						showBatchSuggestions(gui, project, config, result);
					}
				}));
	}

	LlmSuggestion requestSuggestion(LlmConfig config, ProjectView project, EntryKey key) {
		return this.suggestionEngine.requestSuggestion(config, project, key);
	}

	LlmConfig loadConfig() {
		LlmConfig config = LlmConfig.load();
		LlmContextBackend selectedBackend = this.selectedContextBackend;

		if (selectedBackend == null) {
			return withSelectedBatchParallelism(config);
		}

		return withSelectedBatchParallelism(config.withContextBackend(selectedBackend));
	}

	void setContextBackend(LlmContextBackend contextBackend) {
		this.selectedContextBackend = contextBackend;
	}

	void setBatchParallelism(int batchParallelism) {
		this.selectedBatchParallelism = batchParallelism;
	}

	private LlmConfig withSelectedBatchParallelism(LlmConfig config) {
		Integer selectedParallelism = this.selectedBatchParallelism;
		return selectedParallelism == null ? config : config.withBatchParallelism(selectedParallelism);
	}

	BatchSuggestionResult requestBatch(LlmConfig config, ProjectView project, List<EntryKey> targets) {
		return this.suggestionEngine.requestBatch(config, project, targets);
	}

	List<EntryKey> batchTargets(ProjectView project, int limit) {
		return batchTargets(project, limit, null);
	}

	List<EntryKey> batchTargets(ProjectView project, int limit, ClassEntryView activeClass) {
		return this.plugin.getIndex().entries().stream()
				.map(IndexedEntry::key)
				.filter(key -> key.kind() != EntryKind.METHOD || !key.name().startsWith("<"))
				.filter(key -> activeClass == null || key.owner().equals(activeClass.getFullName()))
				.filter(key -> isUnmapped(project, key))
				.limit(limit)
				.toList();
	}

	private List<EntryKey> batchTargets(GuiView gui, ProjectView project, int limit, BatchScope scope) {
		return switch (scope) {
		case PROJECT -> batchTargets(project, limit);
		case CURRENT_CLASS -> {
			ClassEntryView activeClass = gui.getActiveClass();

			if (activeClass == null) {
				yield List.of();
			}

			yield batchTargets(project, limit, activeClass);
		}
		};
	}

	private void showSingleSuggestion(GuiView gui, ProjectView project, EntryKey key, LlmSuggestion suggestion) {
		String selectedName = LlmSuggestionDialogs.showSingleSuggestion(gui, suggestion);

		if (!selectedName.isBlank()) {
			applySuggestion(gui, project, key, new LlmSuggestion(selectedName, suggestion.alternatives(), suggestion.confidence(), suggestion.reasoning()));
		}
	}

	private void showBatchSuggestions(GuiView gui, ProjectView project, LlmConfig config, BatchSuggestionResult result) {
		if (result.suggestions().isEmpty()) {
			showMessage(gui, "llm.dialog.no_suggestions", LlmSuggestionDialogs.failureSummary(result.failures()));
			return;
		}

		List<BatchSuggestion> selectedRows = LlmSuggestionDialogs.showBatchPreview(gui, config, result);

		if (selectedRows.isEmpty()) {
			return;
		}

		List<String> conflicts = batchSelectionConflicts(selectedRows);

		if (!conflicts.isEmpty()) {
			showMessage(gui, "llm.dialog.batch_conflicts", String.join("\n", conflicts));
			return;
		}

		for (BatchSuggestion row : selectedRows) {
			applySuggestion(gui, project, row.key(), row.suggestion());
		}
	}

	static List<String> batchSelectionConflicts(List<BatchSuggestion> selectedRows) {
		return LlmRenameApplier.batchSelectionConflicts(selectedRows);
	}

	boolean applySuggestion(GuiView gui, ProjectView project, EntryKey key, LlmSuggestion suggestion) {
		return this.renameApplier.applySuggestion(gui, project, key, suggestion);
	}

	static Optional<EntryView> currentTarget(GuiView gui) {
		EntryReferenceView reference = gui.getCursorReference();

		if (reference != null) {
			EntryView entry = reference.getNameableEntry();
			Optional<EntryKey> key = EntryKey.fromEntryView(entry);

			if (key.isPresent()) {
				return Optional.of(entry);
			}
		}

		EntryView declaration = gui.getCursorDeclaration();
		return declaration == null ? Optional.empty() : EntryKey.fromEntryView(declaration).map(ignored -> declaration);
	}

	private static boolean isUnmapped(ProjectView project, EntryKey key) {
		return key.hasUnchangedName(project);
	}

	static String normalizeRename(EntryView entry, String suggestedName) {
		return LlmRenameApplier.normalizeRename(entry, suggestedName);
	}

	private static int askBatchLimit(GuiView gui) {
		return parseBatchLimit(JOptionPane.showInputDialog(gui.getFrame(), I18n.translate("llm.dialog.batch_limit", MAX_BATCH_LIMIT), Integer.toString(DEFAULT_BATCH_LIMIT)));
	}

	static int parseBatchLimit(String value) {
		if (value == null) {
			return 0;
		}

		String trimmed = value.strip();

		if (trimmed.isBlank()) {
			return 0;
		}

		try {
			return Math.min(MAX_BATCH_LIMIT, Math.max(0, Integer.parseInt(trimmed)));
		} catch (NumberFormatException ignored) {
			return DEFAULT_BATCH_LIMIT;
		}
	}

	private static void showMessage(GuiView gui, String translationKey) {
		JOptionPane.showMessageDialog(gui.getFrame(), I18n.translate(translationKey), I18n.translate("llm.dialog.title"), JOptionPane.INFORMATION_MESSAGE);
	}

	private static void showMessage(GuiView gui, String translationKey, String detail) {
		String message = I18n.translate(translationKey);

		if (detail != null && !detail.isBlank()) {
			message += "\n\n" + detail;
		}

		JOptionPane.showMessageDialog(gui.getFrame(), message, I18n.translate("llm.dialog.title"), JOptionPane.INFORMATION_MESSAGE);
	}

	private static void showError(GuiView gui, Throwable error) {
		JOptionPane.showMessageDialog(gui.getFrame(), LlmSuggestionEngine.userFacingMessage(error), I18n.translate("llm.dialog.error.title"), JOptionPane.ERROR_MESSAGE);
	}

	Object beginBusyToken() {
		Object token = new Object();

		synchronized (this.busyLock) {
			if (this.busyToken != null) {
				return null;
			}

			this.busyToken = token;
		}

		return token;
	}

	boolean isBusy() {
		synchronized (this.busyLock) {
			return this.busyToken != null;
		}
	}

	boolean completeBusyToken(Object token) {
		if (token == null) {
			return false;
		}

		synchronized (this.busyLock) {
			if (this.busyToken != token) {
				return false;
			}

			this.busyToken = null;
			return true;
		}
	}

	private Object setBusy(GuiView gui, String translationKey) {
		Object token = beginBusyToken();

		if (token == null) {
			this.taskStatusLabel.setText(I18n.translate("llm.dialog.busy"));
			this.taskStatusLabel.setVisible(true);
			return null;
		}

		this.taskStatusLabel.setText(I18n.translate(translationKey));
		this.taskStatusLabel.setVisible(true);
		this.taskStatusLabel.revalidate();
		this.taskStatusLabel.repaint();
		return token;
	}

	private void clearBusy(GuiView gui, Object token) {
		if (!completeBusyToken(token)) {
			return;
		}

		this.taskStatusLabel.setText("");
		this.taskStatusLabel.setVisible(false);
		this.taskStatusLabel.revalidate();
		this.taskStatusLabel.repaint();
	}

	private enum BatchScope {
		PROJECT,
		CURRENT_CLASS
	}
}
