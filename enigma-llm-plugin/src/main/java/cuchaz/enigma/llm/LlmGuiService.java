package cuchaz.enigma.llm;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;

import org.objectweb.asm.Type;

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
	private static final int PRESERVED_NAME_DIALOG_WIDTH = 900;
	private static final int PRESERVED_NAME_DIALOG_MIN_HEIGHT = 360;

	private final LlmNameProposalPlugin plugin;
	private final LlmSuggestionEngine suggestionEngine;
	private final LlmRenameApplier renameApplier;
	private final JLabel taskStatusLabel = new JLabel();
	private final JButton taskCancelButton = new JButton();
	private final Object busyLock = new Object();
	private Object busyToken;
	private Future<?> runningJob;
	private ExecutorService runningJobExecutor;
	private volatile LlmContextBackend selectedContextBackend;
	private volatile String selectedBaseUrl;
	private volatile String selectedModel;
	private volatile Integer selectedBatchParallelism;
	private volatile Integer selectedTimeoutSeconds;
	private volatile String selectedPromptExtension;
	private volatile Set<LlmAnalysisHint> selectedAnalysisHints;

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
				() -> cancelCurrentRequest(gui),
				() -> clearCachedSuggestions(gui),
				() -> editApiSettings(gui),
				() -> editPromptExtension(gui),
				() -> !isBusy() && currentTarget(gui).isPresent(),
				() -> !isBusy() && gui.getProject() != null && !this.plugin.getIndex().isEmpty(),
				() -> !isBusy() && gui.getProject() != null && gui.getActiveClass() != null && !this.plugin.getIndex().isEmpty(),
				this::isBusy,
				() -> !isBusy() && gui.getProject() != null && hasCachedSuggestions(),
				this::loadConfig,
				this::setContextBackend,
				this::setBatchParallelism,
				this::setTimeoutSeconds,
				this::setAnalysisHints
		));
		installStatusComponents(gui);
	}

	void installStatusComponents(GuiView gui) {
		applyStatusComponentStyle();
		this.taskStatusLabel.setVisible(false);
		this.taskCancelButton.setText(I18n.translate("llm.dialog.cancel"));
		this.taskCancelButton.setVisible(false);

		for (ActionListener listener : this.taskCancelButton.getActionListeners()) {
			this.taskCancelButton.removeActionListener(listener);
		}

		this.taskCancelButton.addActionListener(_event -> cancelCurrentRequest(gui));
		gui.addStatusComponent(this.taskStatusLabel);
		gui.addStatusComponent(this.taskCancelButton);
	}

	private void applyStatusComponentStyle() {
		Font labelFont = UIManager.getFont("Label.font");
		Font buttonFont = UIManager.getFont("Button.font");

		if (labelFont != null) {
			this.taskStatusLabel.setFont(labelFont);
		}

		if (buttonFont != null) {
			this.taskCancelButton.setFont(buttonFont);
		}
	}

	@Override
	public void addToEditorContextMenu(GuiView gui, MenuRegistrar registrar) {
		registrar.addSeparator();
		registrar.add("llm.menu.suggest")
				.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK))
				.setEnabledWhen(() -> !isBusy() && currentTarget(gui).isPresent())
				.setAction(() -> suggestCurrent(gui));
		registrar.add("llm.menu.clear_current_suggestion")
				.setEnabledWhen(() -> !isBusy() && currentTargetHasCachedSuggestion(gui))
				.setAction(() -> clearCurrentSuggestion(gui));
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

			Future<LlmSuggestion> future = startLlmJob(busyToken, () -> requestSuggestion(config, project, key));
			CompletableFuture.supplyAsync(() -> awaitFuture(future))
					.whenComplete((suggestion, error) -> SwingUtilities.invokeLater(() -> {
						if (!this.plugin.isProjectCurrent(project) || gui.getProject() != project || !isBusyToken(busyToken)) {
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

		List<EntryKey> candidates = batchTargets(gui, project, limit, scope, true);
		List<EntryKey> preservedNameTargets = candidates.stream()
				.filter(key -> isPreservedBatchTarget(this.plugin.getIndex(), key))
				.toList();
		BatchPreservedNameSelection preservedNameSelection = preservedNameTargets.isEmpty()
				? BatchPreservedNameSelection.includeAll()
				: askPreservedNameAction(gui, project, preservedNameTargets);

		if (preservedNameSelection == null) {
			return;
		}

		List<EntryKey> immediateTargets = candidates.stream()
				.filter(key -> !preservedNameSelection.keepOriginal().contains(key))
				.filter(key -> !preservedNameSelection.askLlm().contains(key))
				.toList();

		if (immediateTargets.isEmpty() && preservedNameSelection.askLlm().isEmpty()) {
			showMessage(gui, "llm.dialog.no_suggestions");
			return;
		}

		if (!preservedNameSelection.askLlm().isEmpty()) {
			requestPreservedNameDecisions(gui, project, config, immediateTargets,
					new ArrayList<>(preservedNameSelection.askLlm()));
			return;
		}

		startBatchSuggestions(gui, project, config, immediateTargets);
	}

	private void startBatchSuggestions(GuiView gui, ProjectView project, LlmConfig config, List<EntryKey> targets) {
		if (targets.isEmpty()) {
			showMessage(gui, "llm.dialog.no_suggestions");
			return;
		}

		Object busyToken = setBusy(gui, "llm.dialog.batch");

		if (busyToken == null) {
			return;
		}

		Future<BatchSuggestionResult> future = startLlmJob(busyToken, () -> requestBatch(config, project, targets,
				progress -> updateBatchProgress(busyToken, progress)));
		CompletableFuture.supplyAsync(() -> awaitFuture(future))
				.whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
					if (!this.plugin.isProjectCurrent(project) || gui.getProject() != project || !isBusyToken(busyToken)) {
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

	private void requestPreservedNameDecisions(GuiView gui, ProjectView project, LlmConfig config,
			List<EntryKey> immediateTargets, List<EntryKey> decisionTargets) {
		Object busyToken = setBusy(gui, "llm.dialog.preserved_names_deciding");

		if (busyToken == null) {
			return;
		}

		Future<List<LlmSuggestionEngine.PreservedNameDecision>> future = startLlmJob(busyToken,
				() -> requestPreservedNameDecisions(config, project, decisionTargets));
		CompletableFuture.supplyAsync(() -> awaitFuture(future))
				.whenComplete((decisions, error) -> SwingUtilities.invokeLater(() -> {
					if (!this.plugin.isProjectCurrent(project) || gui.getProject() != project || !isBusyToken(busyToken)) {
						clearBusy(gui, busyToken);
						return;
					}

					clearBusy(gui, busyToken);

					if (error != null) {
						showError(gui, error);
						return;
					}

					List<EntryKey> confirmedTargets = showPreservedNameDecisionReview(gui, project, decisions);

					if (confirmedTargets == null) {
						return;
					}

					List<EntryKey> targets = new ArrayList<>(immediateTargets);
					targets.addAll(confirmedTargets);
					startBatchSuggestions(gui, project, config, targets);
				}));
	}

	LlmSuggestion requestSuggestion(LlmConfig config, ProjectView project, EntryKey key) {
		return this.suggestionEngine.requestSuggestion(config, project, key);
	}

	LlmConfig loadConfig() {
		LlmConfig config = LlmConfig.load();
		LlmContextBackend selectedBackend = this.selectedContextBackend;

		if (selectedBackend == null) {
			return withSelectedOverrides(config);
		}

		return withSelectedOverrides(config.withContextBackend(selectedBackend));
	}

	void setContextBackend(LlmContextBackend contextBackend) {
		this.selectedContextBackend = contextBackend;
	}

	void setBatchParallelism(int batchParallelism) {
		this.selectedBatchParallelism = batchParallelism;
	}

	void setTimeoutSeconds(int timeoutSeconds) {
		this.selectedTimeoutSeconds = timeoutSeconds;
	}

	void setAnalysisHints(Set<LlmAnalysisHint> analysisHints) {
		this.selectedAnalysisHints = analysisHints == null ? Set.of() : Set.copyOf(analysisHints);
	}

	private LlmConfig withSelectedOverrides(LlmConfig config) {
		String baseUrl = this.selectedBaseUrl;
		String model = this.selectedModel;
		Integer selectedParallelism = this.selectedBatchParallelism;
		Integer selectedTimeout = this.selectedTimeoutSeconds;
		String promptExtension = this.selectedPromptExtension;
		Set<LlmAnalysisHint> analysisHints = this.selectedAnalysisHints;
		LlmConfig selectedConfig = baseUrl == null ? config : config.withBaseUrl(baseUrl);
		selectedConfig = model == null ? selectedConfig : selectedConfig.withModel(model);
		selectedConfig = selectedParallelism == null ? selectedConfig : selectedConfig.withBatchParallelism(selectedParallelism);
		selectedConfig = selectedTimeout == null ? selectedConfig : selectedConfig.withTimeoutSeconds(selectedTimeout);
		selectedConfig = promptExtension == null ? selectedConfig : selectedConfig.withPromptExtension(promptExtension);
		return analysisHints == null ? selectedConfig : selectedConfig.withAnalysisHints(analysisHints);
	}

	private void editApiSettings(GuiView gui) {
		LlmConfig config = loadConfig();
		JTextField baseUrlField = new JTextField(config.baseUrl(), 48);
		JComboBox<String> modelComboBox = editableComboBox(config.model(), modelChoices(config.model()));
		JButton loadModelsButton = new JButton(I18n.translate("llm.dialog.api_load_models"));
		JLabel modelStatusLabel = new JLabel(" ");
		JPanel panel = new JPanel(new java.awt.GridLayout(0, 1, 0, 6));
		Runnable loadModels = () -> loadModels(config, baseUrlField.getText(), modelComboBox, modelStatusLabel, loadModelsButton);
		loadModelsButton.addActionListener(_event -> loadModels.run());
		baseUrlField.addActionListener(_event -> loadModels.run());
		baseUrlField.addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent event) {
				loadModels.run();
			}
		});
		panel.add(new JLabel(I18n.translate("llm.dialog.api_base_url")));
		panel.add(baseUrlField);
		panel.add(new JLabel(I18n.translate("llm.dialog.api_model")));
		panel.add(modelComboBox);
		panel.add(loadModelsButton);
		panel.add(modelStatusLabel);
		SwingUtilities.invokeLater(loadModels);

		int result = JOptionPane.showConfirmDialog(gui.getFrame(), panel,
				I18n.translate("llm.dialog.api_settings.title"), JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);

		if (result == JOptionPane.OK_OPTION) {
			setApiSettings(baseUrlField.getText(), comboBoxText(modelComboBox));
			showTransientStatus("llm.dialog.api_settings_saved");
		}
	}

	static List<String> modelChoices(String currentModel) {
		return choices(currentModel, List.of());
	}

	private static List<String> choices(String currentValue, List<String> presets) {
		LinkedHashSet<String> choices = new LinkedHashSet<>();
		String current = currentValue == null ? "" : currentValue.strip();

		if (!current.isBlank()) {
			choices.add(current);
		}

		choices.addAll(presets);
		return new ArrayList<>(choices);
	}

	private static JComboBox<String> editableComboBox(String selectedValue, List<String> choices) {
		JComboBox<String> comboBox = new JComboBox<>(choices.toArray(String[]::new));
		comboBox.setEditable(true);
		comboBox.setSelectedItem(selectedValue == null ? "" : selectedValue.strip());
		comboBox.setMaximumRowCount(modelDropdownRowCount(choices.size()));
		return comboBox;
	}

	static int modelDropdownRowCount(int choiceCount) {
		return Math.min(12, Math.max(choiceCount, 1));
	}

	private static String comboBoxText(JComboBox<String> comboBox) {
		Object value = comboBox.getEditor().getItem();
		return value == null ? "" : value.toString().strip();
	}

	private static void loadModels(LlmConfig config, String baseUrl, JComboBox<String> modelComboBox,
			JLabel statusLabel, JButton loadButton) {
		String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.strip();

		if (normalizedBaseUrl.isBlank()) {
			statusLabel.setText(I18n.translate("llm.dialog.api_models_no_endpoint"));
			return;
		}

		String selectedModel = comboBoxText(modelComboBox);
		statusLabel.setText(I18n.translate("llm.dialog.api_models_loading"));
		loadButton.setEnabled(false);

		CompletableFuture.supplyAsync(() -> {
			try {
				return new OpenAiCompatibleClient(config.withBaseUrl(normalizedBaseUrl)).listModels();
			} catch (IOException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}

				throw new RuntimeException(e);
			}
		}).whenComplete((models, error) -> SwingUtilities.invokeLater(() -> {
			loadButton.setEnabled(true);

			if (error != null) {
				statusLabel.setText(I18n.translate("llm.dialog.api_models_failed", LlmSuggestionEngine.userFacingMessage(error)));
				return;
			}

			List<String> choices = choices(selectedModel, models);
			modelComboBox.setModel(new javax.swing.DefaultComboBoxModel<>(choices.toArray(String[]::new)));
			modelComboBox.setMaximumRowCount(modelDropdownRowCount(choices.size()));
			modelComboBox.setSelectedItem(!selectedModel.isBlank() ? selectedModel : choices.stream().findFirst().orElse(""));
			statusLabel.setText(I18n.translate("llm.dialog.api_models_loaded", models.size()));
		}));
	}

	void setApiSettings(String baseUrl, String model) {
		this.selectedBaseUrl = baseUrl == null ? "" : baseUrl.strip();
		this.selectedModel = model == null ? "" : model.strip();
	}

	LlmConfig withRuntimeOverrides(LlmConfig config) {
		return withSelectedOverrides(config);
	}

	private void editPromptExtension(GuiView gui) {
		LlmConfig config = loadConfig();
		JTextArea textArea = new JTextArea(config.promptExtension(), 10, 60);
		textArea.setLineWrap(true);
		textArea.setWrapStyleWord(true);
		JScrollPane scrollPane = new JScrollPane(textArea);
		scrollPane.setPreferredSize(new Dimension(640, 240));
		int result = JOptionPane.showConfirmDialog(gui.getFrame(), scrollPane,
				I18n.translate("llm.dialog.prompt_extension.title"), JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);

		if (result == JOptionPane.OK_OPTION) {
			this.selectedPromptExtension = textArea.getText().strip();
			showTransientStatus("llm.dialog.prompt_extension_saved");
		}
	}

	BatchSuggestionResult requestBatch(LlmConfig config, ProjectView project, List<EntryKey> targets) {
		return this.suggestionEngine.requestBatch(config, project, targets);
	}

	BatchSuggestionResult requestBatch(LlmConfig config, ProjectView project, List<EntryKey> targets,
			Consumer<LlmSuggestionEngine.BatchProgress> progressListener) {
		return this.suggestionEngine.requestBatch(config, project, targets, progressListener);
	}

	BatchSuggestionResult requestBatch(LlmConfig config, ProjectView project, List<EntryKey> targets,
			Set<EntryKey> preservedNameDecisionTargets,
			Consumer<LlmSuggestionEngine.BatchProgress> progressListener) {
		return this.suggestionEngine.requestBatch(config, project, targets, preservedNameDecisionTargets, progressListener);
	}

	List<LlmSuggestionEngine.PreservedNameDecision> requestPreservedNameDecisions(LlmConfig config,
			ProjectView project, List<EntryKey> targets) {
		List<LlmSuggestionEngine.PreservedNameDecision> decisions = new ArrayList<>();

		for (EntryKey target : targets) {
			decisions.add(this.suggestionEngine.requestPreservedNameDecision(config, project, target));
		}

		return decisions;
	}

	List<EntryKey> batchTargets(ProjectView project, int limit) {
		return batchTargets(project, limit, null);
	}

	List<EntryKey> batchTargets(ProjectView project, int limit, ClassEntryView activeClass) {
		return batchTargets(project, limit, activeClass, false);
	}

	private List<EntryKey> batchTargets(ProjectView project, int limit, ClassEntryView activeClass,
			boolean includePreservedOriginalNames) {
		return this.plugin.getIndex().entries().stream()
				.map(IndexedEntry::key)
				.filter(key -> key.kind() != EntryKind.METHOD || !key.name().startsWith("<"))
				.filter(key -> activeClass == null || key.owner().equals(activeClass.getFullName()))
				.filter(key -> shouldBatchSuggest(project, this.plugin.getIndex(), key, includePreservedOriginalNames))
				.limit(limit)
				.toList();
	}

	private List<EntryKey> batchTargets(GuiView gui, ProjectView project, int limit, BatchScope scope) {
		return batchTargets(gui, project, limit, scope, false);
	}

	private List<EntryKey> batchTargets(GuiView gui, ProjectView project, int limit, BatchScope scope,
			boolean includePreservedOriginalNames) {
		return switch (scope) {
		case PROJECT -> batchTargets(project, limit, null, includePreservedOriginalNames);
		case CURRENT_CLASS -> {
			ClassEntryView activeClass = gui.getActiveClass();

			if (activeClass == null) {
				yield List.of();
			}

			yield batchTargets(project, limit, activeClass, includePreservedOriginalNames);
		}
		};
	}

	private void showSingleSuggestion(GuiView gui, ProjectView project, EntryKey key, LlmSuggestion suggestion) {
		LlmSuggestionDialogs.SingleSuggestionChoice choice = LlmSuggestionDialogs.showSingleSuggestion(gui, suggestion);

		switch (choice.action()) {
		case APPLY -> {
			if (!choice.selectedName().isBlank()) {
				applySuggestion(gui, project, key, new LlmSuggestion(choice.selectedName(),
						suggestion.alternatives(), suggestion.confidence(), suggestion.reasoning(),
						suggestion.configuredBackend(), suggestion.resolvedBackend()));
			}
		}
		case DISMISS -> {
			this.plugin.getSuggestions().remove(key);
			this.plugin.markInternalRefreshInvalidation();
			project.invalidateData(DataInvalidationEvent.InvalidationType.MAPPINGS);
		}
		case KEEP -> {
		}
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

	static boolean shouldBatchSuggest(ProjectView project, LlmProjectIndex index, EntryKey key) {
		return shouldBatchSuggest(project, index, key, false);
	}

	private static boolean shouldBatchSuggest(ProjectView project, LlmProjectIndex index, EntryKey key,
			boolean includePreservedOriginalNames) {
		return isUnmapped(project, key)
				&& (includePreservedOriginalNames || !isPreservedBatchTarget(index, key));
	}

	private static boolean isPreservedBatchTarget(LlmProjectIndex index, EntryKey key) {
		return LlmBytecodePatterns.isPreservedOriginalName(index, key)
				|| LlmBytecodePatterns.isPreservedMethodName(index, key)
				|| LlmBytecodePatterns.isParameterOfPreservedMethod(index, key);
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

	private static BatchPreservedNameSelection askPreservedNameAction(GuiView gui, ProjectView project,
			List<EntryKey> preservedTargets) {
		PreservedNameActionTableModel model = new PreservedNameActionTableModel(project, preservedTargets);
		JTable table = new JTable(model);
		table.setFillsViewportHeight(true);
		table.setRowHeight(Math.max(table.getRowHeight(), 30));
		table.getTableHeader().setReorderingAllowed(false);
		table.getColumnModel().getColumn(0).setPreferredWidth(460);

		TableCellRenderer radioRenderer = new RadioButtonTableCell();
		TableCellEditor radioEditor = new RadioButtonTableCell();

		for (int column = 1; column < 4; column++) {
			table.getColumnModel().getColumn(column).setPreferredWidth(110);
			table.getColumnModel().getColumn(column).setMaxWidth(140);
			table.getColumnModel().getColumn(column).setCellRenderer(radioRenderer);
			table.getColumnModel().getColumn(column).setCellEditor(radioEditor);
		}

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.setPreferredSize(new Dimension(860, Math.min(400, Math.max(190, preservedTargets.size() * 36 + 56))));

		JPanel panel = new JPanel(new java.awt.BorderLayout(0, 10));
		panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
		panel.add(wrappingLabel(I18n.translate("llm.dialog.preserved_names_message", preservedTargets.size())),
				java.awt.BorderLayout.NORTH);

		JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		JButton allKeep = new JButton(I18n.translate("llm.dialog.preserved_names_all_keep"));
		JButton allAsk = new JButton(I18n.translate("llm.dialog.preserved_names_all_ask"));
		JButton allRename = new JButton(I18n.translate("llm.dialog.preserved_names_all_rename"));
		allKeep.addActionListener(_event -> model.selectAll(PreservedNameAction.KEEP));
		allAsk.addActionListener(_event -> model.selectAll(PreservedNameAction.ASK_LLM));
		allRename.addActionListener(_event -> model.selectAll(PreservedNameAction.RENAME));
		controls.add(allKeep);
		controls.add(allAsk);
		controls.add(allRename);

		JPanel tablePanel = new JPanel(new java.awt.BorderLayout(0, 6));
		tablePanel.add(controls, java.awt.BorderLayout.NORTH);
		tablePanel.add(scrollPane, java.awt.BorderLayout.CENTER);
		panel.add(tablePanel, java.awt.BorderLayout.CENTER);

		if (!showResizableConfirmDialog(gui, I18n.translate("llm.dialog.preserved_names_title"), panel)) {
			return null;
		}

		Set<EntryKey> keepOriginal = new LinkedHashSet<>();
		Set<EntryKey> askLlm = new LinkedHashSet<>();

		for (EntryKey key : preservedTargets) {
			switch (model.action(key)) {
			case KEEP -> keepOriginal.add(key);
			case ASK_LLM -> askLlm.add(key);
			case RENAME -> {
			}
			}
		}

		return new BatchPreservedNameSelection(Set.copyOf(keepOriginal), Set.copyOf(askLlm));
	}

	private static boolean showResizableConfirmDialog(GuiView gui, String title, JPanel panel) {
		JDialog dialog = new JDialog(gui.getFrame(), title, true);
		JButton apply = new JButton(I18n.translate("llm.dialog.apply"));
		JButton cancel = new JButton(I18n.translate("llm.dialog.cancel"));
		boolean[] accepted = { false };
		apply.addActionListener(_event -> {
			accepted[0] = true;
			dialog.dispose();
		});
		cancel.addActionListener(_event -> dialog.dispose());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
		buttons.add(apply);
		buttons.add(cancel);

		JPanel content = new JPanel(new java.awt.BorderLayout(0, 12));
		content.add(panel, java.awt.BorderLayout.CENTER);
		content.add(buttons, java.awt.BorderLayout.SOUTH);
		dialog.setContentPane(content);
		dialog.setResizable(true);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(Math.min(dialog.getWidth(), 780), Math.min(dialog.getHeight(), 320)));
		dialog.setSize(Math.max(dialog.getWidth(), PRESERVED_NAME_DIALOG_WIDTH),
				Math.max(dialog.getHeight(), PRESERVED_NAME_DIALOG_MIN_HEIGHT));
		dialog.setLocationRelativeTo(gui.getFrame());
		dialog.setVisible(true);
		return accepted[0];
	}

	private static JTextArea wrappingLabel(String text) {
		JTextArea label = new JTextArea(text);
		label.setEditable(false);
		label.setFocusable(false);
		label.setLineWrap(true);
		label.setWrapStyleWord(true);
		label.setOpaque(false);
		label.setBorder(BorderFactory.createEmptyBorder());
		label.setFont(UIManager.getFont("Label.font"));
		return label;
	}

	private static List<EntryKey> showPreservedNameDecisionReview(GuiView gui, ProjectView project,
			List<LlmSuggestionEngine.PreservedNameDecision> decisions) {
		BatchPreservedNameDecisionTableModel model = new BatchPreservedNameDecisionTableModel(project, decisions);
		JTable table = new JTable(model);
		table.setFillsViewportHeight(true);
		table.setRowHeight(Math.max(table.getRowHeight(), 24));
		table.getColumnModel().getColumn(0).setPreferredWidth(260);
		table.getColumnModel().getColumn(1).setPreferredWidth(80);
		table.getColumnModel().getColumn(2).setPreferredWidth(360);
		table.getColumnModel().getColumn(3).setPreferredWidth(70);

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.setPreferredSize(new Dimension(820, Math.min(340, Math.max(120, decisions.size() * 32 + 44))));
		JPanel panel = new JPanel(new java.awt.BorderLayout(0, 8));
		panel.add(new JLabel(I18n.translate("llm.dialog.preserved_names_review_message")),
				java.awt.BorderLayout.NORTH);
		panel.add(scrollPane, java.awt.BorderLayout.CENTER);

		int result = JOptionPane.showConfirmDialog(gui.getFrame(), panel,
				I18n.translate("llm.dialog.preserved_names_review_title"),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		return result == JOptionPane.OK_OPTION ? model.renameTargets() : null;
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

	boolean isBusyToken(Object token) {
		synchronized (this.busyLock) {
			return this.busyToken == token;
		}
	}

	boolean completeBusyToken(Object token) {
		if (token == null) {
			return false;
		}

		ExecutorService executor;

		synchronized (this.busyLock) {
			if (this.busyToken != token) {
				return false;
			}

			this.busyToken = null;
			this.runningJob = null;
			executor = this.runningJobExecutor;
			this.runningJobExecutor = null;
		}

		if (executor != null) {
			executor.shutdownNow();
		}

		return true;
	}

	boolean cancelCurrentRequest(GuiView gui) {
		Future<?> job;
		ExecutorService executor;

		synchronized (this.busyLock) {
			if (this.busyToken == null) {
				return false;
			}

			this.busyToken = null;
			job = this.runningJob;
			this.runningJob = null;
			executor = this.runningJobExecutor;
			this.runningJobExecutor = null;
		}

		if (job != null) {
			job.cancel(true);
		}

		if (executor != null) {
			executor.shutdownNow();
		}

		showTransientStatus("llm.dialog.cancelled");
		return true;
	}

	private void clearCachedSuggestions(GuiView gui) {
		ProjectView project = gui.getProject();

		if (project == null) {
			showMessage(gui, "llm.dialog.no_project");
			return;
		}

		if (clearCachedSuggestions(project)) {
			showTransientStatus("llm.dialog.suggestions_cleared");
		}
	}

	boolean clearCachedSuggestions(ProjectView project) {
		if (project == null || !hasCachedSuggestions()) {
			return false;
		}

		this.plugin.getSuggestions().clear();
		this.plugin.markInternalRefreshInvalidation();
		project.invalidateData(DataInvalidationEvent.InvalidationType.MAPPINGS);
		return true;
	}

	boolean hasCachedSuggestions() {
		return !this.plugin.getSuggestions().isEmpty();
	}

	private void clearCurrentSuggestion(GuiView gui) {
		ProjectView project = gui.getProject();

		if (project == null) {
			showMessage(gui, "llm.dialog.no_project");
			return;
		}

		currentTarget(gui)
				.flatMap(EntryKey::fromEntryView)
				.ifPresent(key -> {
					if (clearCurrentSuggestion(project, key)) {
						showTransientStatus("llm.dialog.current_suggestion_cleared");
					}
				});
	}

	boolean clearCurrentSuggestion(ProjectView project, EntryKey key) {
		if (project == null || this.plugin.getSuggestions().get(key).isEmpty()) {
			return false;
		}

		this.plugin.getSuggestions().remove(key);
		this.plugin.markInternalRefreshInvalidation();
		project.invalidateData(DataInvalidationEvent.InvalidationType.MAPPINGS);
		return true;
	}

	boolean currentTargetHasCachedSuggestion(GuiView gui) {
		return currentTarget(gui)
				.flatMap(EntryKey::fromEntryView)
				.flatMap(key -> this.plugin.getSuggestions().get(key))
				.isPresent();
	}

	<T> Future<T> startLlmJob(Object token, Callable<T> task) {
		ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "enigma-llm-request");
			thread.setDaemon(true);
			return thread;
		});
		Future<T> future = executor.submit(task);
		registerRunningJob(token, future, executor);
		return future;
	}

	void registerRunningJob(Object token, Future<?> job, ExecutorService executor) {
		boolean accepted;

		synchronized (this.busyLock) {
			accepted = this.busyToken == token;

			if (accepted) {
				this.runningJob = job;
				this.runningJobExecutor = executor;
			}
		}

		if (!accepted) {
			job.cancel(true);
			executor.shutdownNow();
		}
	}

	static <T> T awaitFuture(Future<T> future) {
		try {
			return future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();

			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}

			throw new RuntimeException(cause);
		}
	}

	private Object setBusy(GuiView gui, String translationKey) {
		Object token = beginBusyToken();

		if (token == null) {
			this.taskStatusLabel.setText(I18n.translate("llm.dialog.busy"));
			this.taskStatusLabel.setVisible(true);
			this.taskCancelButton.setVisible(true);
			return null;
		}

		this.taskStatusLabel.setText(I18n.translate(translationKey));
		this.taskStatusLabel.setVisible(true);
		this.taskCancelButton.setVisible(true);
		this.taskStatusLabel.revalidate();
		this.taskStatusLabel.repaint();
		this.taskCancelButton.revalidate();
		this.taskCancelButton.repaint();
		return token;
	}

	private void updateBatchProgress(Object token, LlmSuggestionEngine.BatchProgress progress) {
		SwingUtilities.invokeLater(() -> {
			if (!isBusyToken(token)) {
				return;
			}

			this.taskStatusLabel.setText(I18n.translate("llm.dialog.batch_progress", progress.completed(), progress.total()));
			this.taskStatusLabel.setVisible(true);
			this.taskStatusLabel.revalidate();
			this.taskStatusLabel.repaint();
		});
	}

	private void clearBusy(GuiView gui, Object token) {
		if (!completeBusyToken(token)) {
			return;
		}

		this.taskStatusLabel.setText("");
		this.taskStatusLabel.setVisible(false);
		this.taskCancelButton.setVisible(false);
		this.taskStatusLabel.revalidate();
		this.taskStatusLabel.repaint();
		this.taskCancelButton.revalidate();
		this.taskCancelButton.repaint();
	}

	private void showTransientStatus(String translationKey) {
		this.taskStatusLabel.setText(I18n.translate(translationKey));
		this.taskStatusLabel.setVisible(true);
		this.taskCancelButton.setVisible(false);
		this.taskStatusLabel.revalidate();
		this.taskStatusLabel.repaint();
		this.taskCancelButton.revalidate();
		this.taskCancelButton.repaint();

		Timer timer = new Timer(2500, _event -> {
			if (isBusy()) {
				return;
			}

			this.taskStatusLabel.setText("");
			this.taskStatusLabel.setVisible(false);
			this.taskCancelButton.setVisible(false);
			this.taskStatusLabel.revalidate();
			this.taskStatusLabel.repaint();
			this.taskCancelButton.revalidate();
			this.taskCancelButton.repaint();
		});
		timer.setRepeats(false);
		timer.start();
	}

	static String readableTarget(ProjectView project, EntryKey key) {
		return switch (key.kind()) {
		case CLASS -> readableClassName(project, key.owner());
		case FIELD -> readableClassName(project, key.owner()) + "." + key.name() + ": " + readableType(project, key.descriptor());
		case METHOD -> readableClassName(project, key.owner()) + "." + key.name() + readableMethodDescriptor(project, key.descriptor());
		case PARAMETER -> readableClassName(project, key.owner()) + "." + key.name() + readableMethodDescriptor(project, key.descriptor())
				+ " arg " + key.localIndex() + (key.localName().isBlank() ? "" : " (" + key.localName() + ")");
		};
	}

	private static String readableMethodDescriptor(ProjectView project, String descriptor) {
		try {
			Type methodType = Type.getMethodType(descriptor);
			List<String> arguments = java.util.Arrays.stream(methodType.getArgumentTypes())
					.map(type -> readableType(project, type))
					.toList();
			return "(" + String.join(", ", arguments) + "): " + readableType(project, methodType.getReturnType());
		} catch (IllegalArgumentException ignored) {
			return descriptor;
		}
	}

	private static String readableType(ProjectView project, String descriptor) {
		try {
			return readableType(project, Type.getType(descriptor));
		} catch (IllegalArgumentException ignored) {
			return descriptor;
		}
	}

	private static String readableType(ProjectView project, Type type) {
		return switch (type.getSort()) {
		case Type.VOID -> "void";
		case Type.BOOLEAN -> "boolean";
		case Type.CHAR -> "char";
		case Type.BYTE -> "byte";
		case Type.SHORT -> "short";
		case Type.INT -> "int";
		case Type.FLOAT -> "float";
		case Type.LONG -> "long";
		case Type.DOUBLE -> "double";
		case Type.ARRAY -> readableType(project, type.getElementType()) + "[]".repeat(type.getDimensions());
		case Type.OBJECT -> readableClassName(project, type.getInternalName());
		default -> type.getDescriptor();
		};
	}

	private static String readableClassName(ProjectView project, String internalName) {
		return EntryKey.toEntryView(new EntryKey(EntryKind.CLASS, internalName, internalName, ""))
				.map(project::deobfuscate)
				.map(EntryView::getName)
				.map(LlmGuiService::simpleClassName)
				.orElseGet(() -> simpleClassName(internalName));
	}

	private static String simpleClassName(String internalName) {
		int separator = Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$'));
		return separator >= 0 ? internalName.substring(separator + 1) : internalName;
	}

	private record BatchPreservedNameSelection(Set<EntryKey> keepOriginal, Set<EntryKey> askLlm) {
		static BatchPreservedNameSelection includeAll() {
			return new BatchPreservedNameSelection(Set.of(), Set.of());
		}
	}

	private enum PreservedNameAction {
		KEEP,
		ASK_LLM,
		RENAME
	}

	private static class PreservedNameActionTableModel extends AbstractTableModel {
		private final ProjectView project;
		private final List<EntryKey> rows;
		private final List<PreservedNameAction> actions;

		PreservedNameActionTableModel(ProjectView project, List<EntryKey> rows) {
			this.project = project;
			this.rows = List.copyOf(rows);
			this.actions = new ArrayList<>();

			for (int i = 0; i < rows.size(); i++) {
				this.actions.add(PreservedNameAction.KEEP);
			}
		}

		PreservedNameAction action(EntryKey key) {
			int index = this.rows.indexOf(key);
			return index >= 0 ? this.actions.get(index) : PreservedNameAction.KEEP;
		}

		void selectAll(PreservedNameAction action) {
			for (int i = 0; i < this.actions.size(); i++) {
				this.actions.set(i, action);
			}

			this.fireTableDataChanged();
		}

		@Override
		public int getRowCount() {
			return this.rows.size();
		}

		@Override
		public int getColumnCount() {
			return 4;
		}

		@Override
		public String getColumnName(int column) {
			return switch (column) {
			case 0 -> I18n.translate("llm.table.target");
			case 1 -> I18n.translate("llm.dialog.preserved_names_keep_column");
			case 2 -> I18n.translate("llm.dialog.preserved_names_ask_column");
			case 3 -> I18n.translate("llm.dialog.preserved_names_rename_column");
			default -> "";
			};
		}

		@Override
		public Class<?> getColumnClass(int column) {
			return column == 0 ? String.class : Boolean.class;
		}

		@Override
		public boolean isCellEditable(int rowIndex, int columnIndex) {
			return columnIndex > 0;
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			return switch (columnIndex) {
			case 0 -> readableTarget(this.project, this.rows.get(rowIndex));
			case 1 -> this.actions.get(rowIndex) == PreservedNameAction.KEEP;
			case 2 -> this.actions.get(rowIndex) == PreservedNameAction.ASK_LLM;
			case 3 -> this.actions.get(rowIndex) == PreservedNameAction.RENAME;
			default -> false;
			};
		}

		@Override
		public void setValueAt(Object value, int rowIndex, int columnIndex) {
			if (!(value instanceof Boolean selected) || !selected || columnIndex <= 0) {
				return;
			}

			PreservedNameAction action = switch (columnIndex) {
			case 1 -> PreservedNameAction.KEEP;
			case 2 -> PreservedNameAction.ASK_LLM;
			case 3 -> PreservedNameAction.RENAME;
			default -> this.actions.get(rowIndex);
			};
			this.actions.set(rowIndex, action);
			this.fireTableRowsUpdated(rowIndex, rowIndex);
		}
	}

	private static class RadioButtonTableCell extends AbstractCellEditor implements TableCellRenderer, TableCellEditor {
		private final JRadioButton radioButton = new JRadioButton();

		RadioButtonTableCell() {
			this.radioButton.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
			this.radioButton.addActionListener(_event -> this.stopCellEditing());
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			this.configure(table, value, isSelected);
			return this.radioButton;
		}

		@Override
		public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
				int row, int column) {
			this.configure(table, value, true);
			return this.radioButton;
		}

		@Override
		public Object getCellEditorValue() {
			return this.radioButton.isSelected();
		}

		private void configure(JTable table, Object value, boolean isSelected) {
			this.radioButton.setSelected(Boolean.TRUE.equals(value));
			this.radioButton.setOpaque(true);
			this.radioButton.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
			this.radioButton.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
		}
	}

	private static class BatchPreservedNameDecisionTableModel extends AbstractTableModel {
		private final ProjectView project;
		private final List<LlmSuggestionEngine.PreservedNameDecision> rows;
		private final Set<EntryKey> renameTargets = new LinkedHashSet<>();

		BatchPreservedNameDecisionTableModel(ProjectView project,
				List<LlmSuggestionEngine.PreservedNameDecision> rows) {
			this.project = project;
			this.rows = List.copyOf(rows);
			rows.stream()
					.filter(LlmSuggestionEngine.PreservedNameDecision::rename)
					.map(LlmSuggestionEngine.PreservedNameDecision::key)
					.forEach(this.renameTargets::add);
		}

		List<EntryKey> renameTargets() {
			return this.rows.stream()
					.map(LlmSuggestionEngine.PreservedNameDecision::key)
					.filter(this.renameTargets::contains)
					.toList();
		}

		@Override
		public int getRowCount() {
			return this.rows.size();
		}

		@Override
		public int getColumnCount() {
			return 4;
		}

		@Override
		public String getColumnName(int column) {
			return switch (column) {
			case 0 -> I18n.translate("llm.table.target");
			case 1 -> I18n.translate("llm.dialog.preserved_names_decision_column");
			case 2 -> I18n.translate("llm.dialog.reasoning");
			case 3 -> I18n.translate("llm.dialog.preserved_names_rename_column");
			default -> "";
			};
		}

		@Override
		public Class<?> getColumnClass(int column) {
			return column == 3 ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int rowIndex, int columnIndex) {
			return columnIndex == 3;
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			LlmSuggestionEngine.PreservedNameDecision decision = this.rows.get(rowIndex);
			EntryKey key = decision.key();

			return switch (columnIndex) {
			case 0 -> readableTarget(this.project, key);
			case 1 -> decision.rename() ? "RENAME" : "KEEP";
			case 2 -> decision.reasoning();
			case 3 -> this.renameTargets.contains(key);
			default -> "";
			};
		}

		@Override
		public void setValueAt(Object value, int rowIndex, int columnIndex) {
			if (!(value instanceof Boolean selected)) {
				return;
			}

			EntryKey key = this.rows.get(rowIndex).key();

			if (columnIndex == 3) {
				if (selected) {
					this.renameTargets.add(key);
				} else {
					this.renameTargets.remove(key);
				}
			}

			this.fireTableRowsUpdated(rowIndex, rowIndex);
		}
	}

	private enum BatchScope {
		PROJECT,
		CURRENT_CLASS
	}
}
