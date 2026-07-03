package cuchaz.enigma.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
		AtomicInteger selectedParallelism = new AtomicInteger();
		LlmConfig graphConfig = new LlmConfig("http://localhost:1234/v1", "qwen-local", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty(), 1, LlmContextBackend.GRAPH);
		JMenu menu = LlmMenu.create(
				() -> {
				},
				() -> {
				},
				() -> {
				},
				() -> true,
				() -> true,
				() -> true,
				() -> graphConfig,
				selectedBackend::set,
				selectedParallelism::set
		);

		for (MenuListener listener : menu.getMenuListeners()) {
			listener.menuSelected(new MenuEvent(menu));
		}

		JMenu backendMenu = (JMenu) menu.getMenuComponent(2);
		JMenuItem simpleItem = backendMenu.getItem(0);
		JMenuItem graphItem = backendMenu.getItem(1);

		assertThat(simpleItem.isSelected(), equalTo(false));
		assertThat(graphItem.isSelected(), equalTo(true));

		simpleItem.doClick();

		assertThat(selectedBackend.get(), equalTo(LlmContextBackend.OWNER));
	}

	@Test
	public void parallelismSubmenuReflectsConfigAndUpdatesSelection() {
		AtomicReference<LlmContextBackend> selectedBackend = new AtomicReference<>();
		AtomicInteger selectedParallelism = new AtomicInteger();
		LlmConfig config = new LlmConfig("http://localhost:1234/v1", "qwen-local", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty(), 4, LlmContextBackend.OWNER);
		JMenu menu = LlmMenu.create(
				() -> {
				},
				() -> {
				},
				() -> {
				},
				() -> true,
				() -> true,
				() -> true,
				() -> config,
				selectedBackend::set,
				selectedParallelism::set
		);

		for (MenuListener listener : menu.getMenuListeners()) {
			listener.menuSelected(new MenuEvent(menu));
		}

		JMenu parallelismMenu = (JMenu) menu.getMenuComponent(3);
		assertThat(parallelismMenu.getItem(3).isSelected(), equalTo(true));

		parallelismMenu.getItem(7).doClick();

		assertThat(selectedBackend.get(), equalTo(null));
		assertThat(selectedParallelism.get(), equalTo(8));
	}
}
