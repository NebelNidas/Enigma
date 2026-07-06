package cuchaz.enigma.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import org.junit.Test;

public class LlmMenuTest {
	@Test
	public void statusShowsConfigurationState() {
		LlmConfig missingModel = new LlmConfig("http://localhost:1234/v1", "", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());
		LlmConfig configured = new LlmConfig("http://localhost:1234/v1", "qwen-local", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty(), 1, LlmContextBackend.GRAPH);
		LlmMenu.Translator translator = (key, args) -> switch (key) {
		case "llm.menu.status_unconfigured" -> "No LLM model configured";
		case "llm.menu.status_configured" -> args[0] + " · " + args[1] + " context";
		case "llm.backend.graph" -> "Graph-based";
		default -> key;
		};

		assertThat(LlmMenu.statusText(missingModel, translator), equalTo("No LLM model configured"));
		assertThat(LlmMenu.statusText(configured, translator), equalTo("qwen-local · Graph-based context"));
	}

	@Test
	public void installsBeforeRightmostHelpMenu() {
		javax.swing.JMenuBar menuBar = new javax.swing.JMenuBar();
		menuBar.add(new javax.swing.JMenu("File"));
		menuBar.add(new javax.swing.JMenu("Help"));

		LlmMenu.install(menuBar, new javax.swing.JMenu("LLM"));

		assertThat(menuBar.getMenu(0).getText(), equalTo("File"));
		assertThat(menuBar.getMenu(1).getText(), equalTo("LLM"));
		assertThat(menuBar.getMenu(2).getText(), equalTo("Help"));
	}

	@Test
	public void contextBackendSubmenuReflectsConfigAndUpdatesSelection() {
		AtomicReference<LlmContextBackend> selectedBackend = new AtomicReference<>();
		AtomicReference<Set<LlmAnalysisHint>> selectedHints = new AtomicReference<>();
		AtomicInteger selectedParallelism = new AtomicInteger();
		AtomicInteger selectedTimeout = new AtomicInteger();
		LlmConfig graphConfig = new LlmConfig("http://localhost:1234/v1", "qwen-local", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty(), 1, LlmContextBackend.GRAPH);
		JMenu menu = createMenu(() -> graphConfig, selectedBackend, selectedHints, selectedParallelism, selectedTimeout, () -> false);

		for (MenuListener listener : menu.getMenuListeners()) {
			listener.menuSelected(new MenuEvent(menu));
		}

		JMenu backendMenu = (JMenu) menu.getMenuComponent(5);
		JMenuItem autoItem = backendMenu.getItem(0);
		JMenuItem simpleItem = backendMenu.getItem(1);
		JMenuItem graphItem = backendMenu.getItem(2);

		assertThat(autoItem.isSelected(), equalTo(false));
		assertThat(simpleItem.isSelected(), equalTo(false));
		assertThat(graphItem.isSelected(), equalTo(true));

		autoItem.doClick();

		assertThat(selectedBackend.get(), equalTo(LlmContextBackend.AUTO));

		simpleItem.doClick();

		assertThat(selectedBackend.get(), equalTo(LlmContextBackend.OWNER));
	}

	@Test
	public void parallelismSubmenuReflectsConfigAndUpdatesSelection() {
		AtomicReference<LlmContextBackend> selectedBackend = new AtomicReference<>();
		AtomicReference<Set<LlmAnalysisHint>> selectedHints = new AtomicReference<>();
		AtomicInteger selectedParallelism = new AtomicInteger();
		AtomicInteger selectedTimeout = new AtomicInteger();
		LlmConfig config = new LlmConfig("http://localhost:1234/v1", "qwen-local", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty(), 4, LlmContextBackend.OWNER);
		JMenu menu = createMenu(() -> config, selectedBackend, selectedHints, selectedParallelism, selectedTimeout, () -> false);

		for (MenuListener listener : menu.getMenuListeners()) {
			listener.menuSelected(new MenuEvent(menu));
		}

		JMenu parallelismMenu = (JMenu) menu.getMenuComponent(8);
		assertThat(parallelismMenu.getItem(3).isSelected(), equalTo(true));

		parallelismMenu.getItem(7).doClick();

		assertThat(selectedBackend.get(), equalTo(null));
		assertThat(selectedParallelism.get(), equalTo(8));
	}

	@Test
	public void timeoutSubmenuReflectsConfigAndUpdatesSelection() {
		AtomicReference<LlmContextBackend> selectedBackend = new AtomicReference<>();
		AtomicReference<Set<LlmAnalysisHint>> selectedHints = new AtomicReference<>();
		AtomicInteger selectedParallelism = new AtomicInteger();
		AtomicInteger selectedTimeout = new AtomicInteger();
		LlmConfig config = new LlmConfig("http://localhost:1234/v1", "qwen-local", "", Duration.ofSeconds(300), java.util.OptionalDouble.empty(), 4, LlmContextBackend.OWNER);
		JMenu menu = createMenu(() -> config, selectedBackend, selectedHints, selectedParallelism, selectedTimeout, () -> false);

		for (MenuListener listener : menu.getMenuListeners()) {
			listener.menuSelected(new MenuEvent(menu));
		}

		JMenu timeoutMenu = (JMenu) menu.getMenuComponent(7);
		assertThat(timeoutMenu.getItem(2).isSelected(), equalTo(true));

		timeoutMenu.getItem(3).doClick();

		assertThat(selectedBackend.get(), equalTo(null));
		assertThat(selectedParallelism.get(), equalTo(0));
		assertThat(selectedTimeout.get(), equalTo(600));
	}

	@Test
	public void analysisHintsSubmenuReflectsConfigAndUpdatesSelection() {
		AtomicReference<LlmContextBackend> selectedBackend = new AtomicReference<>();
		AtomicReference<Set<LlmAnalysisHint>> selectedHints = new AtomicReference<>();
		AtomicInteger selectedParallelism = new AtomicInteger();
		AtomicInteger selectedTimeout = new AtomicInteger();
		LlmConfig config = new LlmConfig("http://localhost:1234/v1", "qwen-local", "",
				Duration.ofSeconds(5), java.util.OptionalDouble.empty(), 4, LlmContextBackend.OWNER,
				"", Set.of(LlmAnalysisHint.FUNCTIONAL_INTERFACE));
		JMenu menu = createMenu(() -> config, selectedBackend, selectedHints, selectedParallelism, selectedTimeout, () -> false);

		for (MenuListener listener : menu.getMenuListeners()) {
			listener.menuSelected(new MenuEvent(menu));
		}

		JMenu hintsMenu = (JMenu) menu.getMenuComponent(6);
		JMenu presetsMenu = (JMenu) hintsMenu.getItem(0);
		JMenuItem offItem = presetsMenu.getItem(0);
		JMenuItem functionalItem = hintsMenu.getItem(2);
		JMenuItem constantsItem = hintsMenu.getItem(3);
		JMenuItem relatedConstantsItem = hintsMenu.getItem(4);

		assertThat(functionalItem.isSelected(), equalTo(true));
		assertThat(constantsItem.isSelected(), equalTo(false));
		assertThat(relatedConstantsItem.isSelected(), equalTo(false));

		constantsItem.doClick();

		assertThat(selectedHints.get(), equalTo(Set.of(LlmAnalysisHint.FUNCTIONAL_INTERFACE, LlmAnalysisHint.CONSTANTS_CLASS)));

		relatedConstantsItem.doClick();

		assertThat(selectedHints.get(), equalTo(Set.of(LlmAnalysisHint.FUNCTIONAL_INTERFACE, LlmAnalysisHint.RELATED_CONSTANTS)));

		offItem.doClick();

		assertThat(selectedHints.get(), equalTo(Set.of()));
	}

	@Test
	public void conservativeAnalysisHintsPresetDoesNotSelectAllPreset() {
		AtomicReference<LlmContextBackend> selectedBackend = new AtomicReference<>();
		AtomicReference<Set<LlmAnalysisHint>> selectedHints = new AtomicReference<>();
		AtomicInteger selectedParallelism = new AtomicInteger();
		AtomicInteger selectedTimeout = new AtomicInteger();
		LlmConfig config = new LlmConfig("http://localhost:1234/v1", "qwen-local", "",
				Duration.ofSeconds(5), java.util.OptionalDouble.empty(), 4, LlmContextBackend.OWNER,
				"", LlmAnalysisHint.conservative());
		JMenu menu = createMenu(() -> config, selectedBackend, selectedHints, selectedParallelism, selectedTimeout, () -> false);

		for (MenuListener listener : menu.getMenuListeners()) {
			listener.menuSelected(new MenuEvent(menu));
		}

		JMenu hintsMenu = (JMenu) menu.getMenuComponent(6);
		JMenu presetsMenu = (JMenu) hintsMenu.getItem(0);

		assertThat(presetsMenu.getItem(1).isSelected(), equalTo(true));
		assertThat(presetsMenu.getItem(2).isSelected(), equalTo(false));
	}

	@Test
	public void clearSuggestionsItemReflectsAvailabilityAndRunsAction() {
		AtomicReference<LlmContextBackend> selectedBackend = new AtomicReference<>();
		AtomicReference<Set<LlmAnalysisHint>> selectedHints = new AtomicReference<>();
		AtomicInteger selectedParallelism = new AtomicInteger();
		AtomicInteger selectedTimeout = new AtomicInteger();
		AtomicBoolean editedApiSettings = new AtomicBoolean();
		AtomicBoolean editedPrompt = new AtomicBoolean();
		AtomicInteger clearCount = new AtomicInteger();
		LlmConfig config = new LlmConfig("http://localhost:1234/v1", "qwen-local", "",
				Duration.ofSeconds(5), java.util.OptionalDouble.empty(), 4, LlmContextBackend.OWNER);
		JMenu menu = LlmMenu.create(
				() -> {
				},
				() -> {
				},
				() -> {
				},
				() -> {
				},
				clearCount::incrementAndGet,
				() -> editedApiSettings.set(true),
				() -> editedPrompt.set(true),
				() -> true,
				() -> true,
				() -> true,
				() -> false,
				() -> true,
				() -> config,
				selectedBackend::set,
				selectedParallelism::set,
				selectedTimeout::set,
				selectedHints::set
		);

		for (MenuListener listener : menu.getMenuListeners()) {
			listener.menuSelected(new MenuEvent(menu));
		}

		JMenuItem clearSuggestionsItem = (JMenuItem) menu.getMenuComponent(3);

		assertThat(clearSuggestionsItem.isEnabled(), equalTo(true));

		clearSuggestionsItem.doClick();

		assertThat(clearCount.get(), equalTo(1));

		JMenuItem editApiSettingsItem = (JMenuItem) menu.getMenuComponent(9);
		editApiSettingsItem.doClick();

		assertThat(editedApiSettings.get(), equalTo(true));

		JMenuItem editPromptItem = (JMenuItem) menu.getMenuComponent(10);
		editPromptItem.doClick();

		assertThat(editedPrompt.get(), equalTo(true));
	}

	private static JMenu createMenu(java.util.function.Supplier<LlmConfig> configSupplier,
			AtomicReference<LlmContextBackend> selectedBackend, AtomicReference<Set<LlmAnalysisHint>> selectedHints,
			AtomicInteger selectedParallelism,
			AtomicInteger selectedTimeout, BooleanSupplier canClearSuggestions) {
		return LlmMenu.create(
				() -> {
				},
				() -> {
				},
				() -> {
				},
				() -> {
				},
				() -> {
				},
				() -> {
				},
				() -> {
				},
				() -> true,
				() -> true,
				() -> true,
				() -> false,
				canClearSuggestions,
				configSupplier,
				selectedBackend::set,
				selectedParallelism::set,
				selectedTimeout::set,
				selectedHints::set
		);
	}
}
