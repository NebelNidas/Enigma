package cuchaz.enigma.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.swing.KeyStroke;

import org.junit.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import cuchaz.enigma.api.service.GuiService;
import cuchaz.enigma.api.view.entry.ClassEntryView;
import cuchaz.enigma.api.view.entry.FieldEntryView;
import cuchaz.enigma.api.view.entry.LocalVariableEntryView;
import cuchaz.enigma.api.view.entry.MethodEntryView;
import cuchaz.enigma.llm.LlmTestSupport.FakeEntryReferenceView;
import cuchaz.enigma.llm.LlmTestSupport.FakeGuiView;
import cuchaz.enigma.llm.LlmTestSupport.FakeProjectView;

public class LlmGuiServiceTest {
	@Test
	public void editorContextMenuOmitsProjectWideBatchAction() {
		LlmGuiService service = new LlmGuiService(new LlmNameProposalPlugin());
		FakeGuiView gui = new FakeGuiView(new FakeProjectView(), true);
		List<String> translationKeys = new ArrayList<>();

		service.addToEditorContextMenu(gui, new GuiService.MenuRegistrar() {
			@Override
			public void addSeparator() {
			}

			@Override
			public GuiService.MenuItemBuilder add(Supplier<String> translationKey) {
				translationKeys.add(translationKey.get());
				return new GuiService.MenuItemBuilder() {
					@Override
					public GuiService.MenuItemBuilder setAccelerator(KeyStroke accelerator) {
						return this;
					}

					@Override
					public GuiService.MenuItemBuilder setEnabledWhen(BooleanSupplier condition) {
						return this;
					}

					@Override
					public GuiService.MenuItemBuilder setAction(Runnable action) {
						return this;
					}
				};
			}
		});

		assertTrue(translationKeys.contains("llm.menu.suggest"));
		assertTrue(translationKeys.contains("llm.menu.batch_current_class"));
		assertFalse(translationKeys.contains("llm.menu.batch_project"));
	}

	@Test
	public void batchTargetsUseIndexLimitAndSkipMappedEntries() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		ClassNode node = LlmTestSupport.classNode("example/Foo");
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "a", "I", null, null));
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "b", "I", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "c", "()V", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "d", "()V", null, null));

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		plugin.setIndex(builder.build());

		EntryKey mappedField = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");
		FakeProjectView project = new FakeProjectView(Map.of(mappedField, "itemCount"));
		LlmGuiService service = new LlmGuiService(plugin);

		List<EntryKey> targets = service.batchTargets(project, 3);

		assertThat(targets.size(), equalTo(3));
		assertFalse(targets.contains(mappedField));
		assertFalse(targets.stream().anyMatch(key -> key.name().startsWith("<")));
		assertThat(targets.get(0), equalTo(new EntryKey(EntryKind.CLASS, "example/Foo", "example/Foo", "")));
		assertThat(targets.get(1), equalTo(new EntryKey(EntryKind.FIELD, "example/Foo", "b", "I")));
		assertThat(targets.get(2), equalTo(new EntryKey(EntryKind.METHOD, "example/Foo", "c", "()V")));
	}

	@Test
	public void batchTargetsCanBeLimitedToActiveClass() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		ClassNode activeNode = LlmTestSupport.classNode("example/Active");
		activeNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "a", "I", null, null));
		ClassNode otherNode = LlmTestSupport.classNode("example/Other");
		otherNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "b", "I", null, null));
		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(activeNode);
		builder.accept(otherNode);
		plugin.setIndex(builder.build());

		LlmGuiService service = new LlmGuiService(plugin);
		List<EntryKey> targets = service.batchTargets(new FakeProjectView(), 10, ClassEntryView.create("example/Active"));

		assertThat(targets, equalTo(List.of(
				new EntryKey(EntryKind.CLASS, "example/Active", "example/Active", ""),
				new EntryKey(EntryKind.FIELD, "example/Active", "a", "I")
		)));
	}

	@Test
	public void activeClassBatchTargetsCanIncludeAllClassEntries() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		ClassNode activeNode = LlmTestSupport.classNode("example/Active");

		for (int index = 0; index < 120; index++) {
			activeNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "f" + index, "I", null, null));
		}

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(activeNode);
		plugin.setIndex(builder.build());

		LlmGuiService service = new LlmGuiService(plugin);
		List<EntryKey> targets = service.batchTargets(new FakeProjectView(), Integer.MAX_VALUE, ClassEntryView.create("example/Active"));

		assertThat(targets.size(), equalTo(121));
	}

	@Test
	public void currentTargetFallsBackToCursorDeclaration() {
		FakeGuiView gui = new FakeGuiView(new FakeProjectView(), true);
		MethodEntryView declaration = MethodEntryView.create("example/Foo", "getCount", "()I");
		gui.cursorDeclaration = declaration;

		assertThat(LlmGuiService.currentTarget(gui), equalTo(Optional.of(declaration)));
	}

	@Test
	public void currentTargetPrefersNameableReferenceOverDeclaration() {
		FakeGuiView gui = new FakeGuiView(new FakeProjectView(), true);
		FieldEntryView referenceTarget = FieldEntryView.create("example/Foo", "a", "I");
		MethodEntryView declaration = MethodEntryView.create("example/Foo", "getCount", "()I");
		gui.cursorReference = new FakeEntryReferenceView(referenceTarget);
		gui.cursorDeclaration = declaration;

		assertThat(LlmGuiService.currentTarget(gui), equalTo(Optional.of(referenceTarget)));
	}

	@Test
	public void parsesBatchLimitSafely() {
		assertThat(LlmGuiService.parseBatchLimit(null), equalTo(0));
		assertThat(LlmGuiService.parseBatchLimit(""), equalTo(0));
		assertThat(LlmGuiService.parseBatchLimit("-5"), equalTo(0));
		assertThat(LlmGuiService.parseBatchLimit("12"), equalTo(12));
		assertThat(LlmGuiService.parseBatchLimit(" 12 "), equalTo(12));
		assertThat(LlmGuiService.parseBatchLimit("10000"), equalTo(100));
		assertThat(LlmGuiService.parseBatchLimit("not-a-number"), equalTo(25));
	}

	@Test
	public void busyTokensRejectConcurrentRequests() {
		LlmGuiService service = new LlmGuiService(new LlmNameProposalPlugin());
		Object first = service.beginBusyToken();
		Object second = service.beginBusyToken();

		assertThat(second, equalTo(null));
		assertTrue(service.isBusy());
		assertTrue(service.completeBusyToken(first));
		assertFalse(service.isBusy());
		assertFalse(service.completeBusyToken(second));

		Object third = service.beginBusyToken();

		assertTrue(service.isBusy());
		assertTrue(service.completeBusyToken(third));
		assertFalse(service.isBusy());
	}

	@Test
	public void loadConfigUsesGuiSelectedContextBackend() {
		LlmGuiService service = new LlmGuiService(new LlmNameProposalPlugin());

		service.setContextBackend(LlmContextBackend.GRAPH);

		assertThat(service.loadConfig().contextBackend(), equalTo(LlmContextBackend.GRAPH));

		service.setContextBackend(LlmContextBackend.OWNER);

		assertThat(service.loadConfig().contextBackend(), equalTo(LlmContextBackend.OWNER));

		service.setBatchParallelism(7);

		assertThat(service.loadConfig().batchParallelism(), equalTo(7));
	}

	@Test
	public void batchSelectionConflictsRejectDuplicateSelectedFieldNames() {
		List<BatchSuggestion> selected = List.of(
				new BatchSuggestion(new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I"), new LlmSuggestion("rotation", List.of(), 0.8, ""), true),
				new BatchSuggestion(new EntryKey(EntryKind.FIELD, "example/Foo", "b", "I"), new LlmSuggestion("rotation", List.of(), 0.7, ""), true)
		);

		List<String> conflicts = LlmGuiService.batchSelectionConflicts(selected);

		assertThat(conflicts.size(), equalTo(1));
		assertThat(conflicts.get(0), containsString("example/Foo.a : I"));
		assertThat(conflicts.get(0), containsString("example/Foo.b : I"));
		assertThat(conflicts.get(0), containsString("rotation"));
	}

	@Test
	public void batchSelectionConflictsAllowOverloadedMethodNames() {
		List<BatchSuggestion> selected = List.of(
				new BatchSuggestion(new EntryKey(EntryKind.METHOD, "example/Foo", "a", "(I)V"), new LlmSuggestion("setRotation", List.of(), 0.8, ""), true),
				new BatchSuggestion(new EntryKey(EntryKind.METHOD, "example/Foo", "b", "(F)V"), new LlmSuggestion("setRotation", List.of(), 0.7, ""), true)
		);

		assertTrue(LlmGuiService.batchSelectionConflicts(selected).isEmpty());
	}

	@Test
	public void applySuggestionNormalizesTopLevelClassRenameAndCachesOnSuccess() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin);
		FakeProjectView project = new FakeProjectView();
		FakeGuiView gui = new FakeGuiView(project, true);
		EntryKey key = new EntryKey(EntryKind.CLASS, "example/Foo", "example/Foo", "");
		LlmSuggestion suggestion = new LlmSuggestion("ItemCounter", List.of(), 0.92, "counts items");

		assertTrue(service.applySuggestion(gui, project, key, suggestion));

		assertThat(gui.lastRename, equalTo("example/ItemCounter"));
		assertThat(((ClassEntryView) gui.lastEntry).getFullName(), equalTo("example/Foo"));
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.of(suggestion)));
		assertThat(project.invalidations, equalTo(1));
	}

	@Test
	public void applySuggestionKeepsQualifiedTopLevelClassRename() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin);
		FakeProjectView project = new FakeProjectView();
		FakeGuiView gui = new FakeGuiView(project, true);
		EntryKey key = new EntryKey(EntryKind.CLASS, "example/Foo", "example/Foo", "");
		LlmSuggestion suggestion = new LlmSuggestion("other/ItemCounter", List.of(), 0.72, "moves to named package");

		assertTrue(service.applySuggestion(gui, project, key, suggestion));

		assertThat(gui.lastRename, equalTo("other/ItemCounter"));
		assertThat(((ClassEntryView) gui.lastEntry).getFullName(), equalTo("example/Foo"));
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.of(suggestion)));
		assertThat(project.invalidations, equalTo(1));
	}

	@Test
	public void applySuggestionKeepsInnerClassRenameSimple() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin);
		FakeProjectView project = new FakeProjectView();
		FakeGuiView gui = new FakeGuiView(project, true);
		EntryKey key = new EntryKey(EntryKind.CLASS, "example/Foo$Bar", "example/Foo$Bar", "");
		LlmSuggestion suggestion = new LlmSuggestion("Part", List.of(), 0.78, "inner component");

		assertTrue(service.applySuggestion(gui, project, key, suggestion));

		assertThat(gui.lastRename, equalTo("Part"));
		assertThat(((ClassEntryView) gui.lastEntry).getFullName(), equalTo("example/Foo$Bar"));
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.of(suggestion)));
		assertThat(project.invalidations, equalTo(1));
	}

	@Test
	public void applySuggestionDoesNotCacheWhenRenameIsRejected() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin);
		FakeProjectView project = new FakeProjectView();
		FakeGuiView gui = new FakeGuiView(project, false);
		EntryKey key = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");
		LlmSuggestion suggestion = new LlmSuggestion("itemCount", List.of(), 0.7, "");
		plugin.getSuggestions().put(key, suggestion);

		assertFalse(service.applySuggestion(gui, project, key, suggestion));

		assertThat(gui.lastRename, equalTo("itemCount"));
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));
		assertThat(project.invalidations, equalTo(0));
	}

	@Test
	public void applySuggestionAppliesParameterRenameWithoutClassNormalization() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin);
		FakeProjectView project = new FakeProjectView();
		FakeGuiView gui = new FakeGuiView(project, true);
		EntryKey key = new EntryKey(EntryKind.PARAMETER, "example/Foo", "a", "(I)V", 1, "p_1_");
		LlmSuggestion suggestion = new LlmSuggestion("count", List.of(), 0.84, "");

		assertTrue(service.applySuggestion(gui, project, key, suggestion));

		LocalVariableEntryView parameter = (LocalVariableEntryView) gui.lastEntry;
		assertThat(gui.lastRename, equalTo("count"));
		assertThat(parameter.getParent().getParent().getFullName(), equalTo("example/Foo"));
		assertThat(parameter.getParent().getName(), equalTo("a"));
		assertThat(parameter.getIndex(), equalTo(1));
		assertTrue(parameter.isArgument());
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.of(suggestion)));
		assertThat(project.invalidations, equalTo(1));
	}

	@Test
	public void requestSuggestionRejectsQualifiedInnerClassName() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> new LlmSuggestion("net/minecraft/Part", List.of(), 0.8, "qualified inner class"));
		FakeProjectView project = new FakeProjectView();
		LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());
		EntryKey key = new EntryKey(EntryKind.CLASS, "example/Foo$Bar", "example/Foo$Bar", "");

		try {
			service.requestSuggestion(config, project, key);
		} catch (RuntimeException e) {
			assertThat(e.getMessage(), containsString("Invalid Java identifier suggested for CLASS"));
			assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));
			return;
		}

		throw new AssertionError("Expected RuntimeException");
	}
}
