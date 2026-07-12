package cuchaz.enigma.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Font;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

import org.junit.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
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
	public void apiSettingsOverrideRuntimeConfigForCurrentSession() {
		LlmGuiService service = new LlmGuiService(new LlmNameProposalPlugin());
		LlmConfig config = new LlmConfig("http://localhost:1234/v1", "laptop-model", "",
				Duration.ofSeconds(120), java.util.OptionalDouble.empty());

		service.setApiSettings(" http://192.168.178.120:1234/v1 ", " qwen2.5-coder-14b-q6k ");

		LlmConfig overridden = service.withRuntimeOverrides(config);

		assertThat(overridden.baseUrl(), equalTo("http://192.168.178.120:1234/v1"));
		assertThat(overridden.model(), equalTo("qwen2.5-coder-14b-q6k"));
		assertThat(overridden.timeout(), equalTo(Duration.ofSeconds(120)));
	}

	@Test
	public void apiSettingsModelChoicesOnlyKeepCurrentValue() {
		List<String> models = LlmGuiService.modelChoices(" my-custom-model ");

		assertThat(models, equalTo(List.of("my-custom-model")));
	}

	@Test
	public void apiSettingsModelDropdownShowsLoadedModelRows() {
		assertThat(LlmGuiService.modelDropdownRowCount(0), equalTo(1));
		assertThat(LlmGuiService.modelDropdownRowCount(4), equalTo(4));
		assertThat(LlmGuiService.modelDropdownRowCount(20), equalTo(12));
	}

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
		assertTrue(translationKeys.contains("llm.menu.clear_current_suggestion"));
		assertTrue(translationKeys.contains("llm.menu.batch_current_class"));
		assertFalse(translationKeys.contains("llm.menu.batch_project"));
	}

	@Test
	public void batchTargetsUseIndexLimitAndSkipMappedEntries() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		ClassNode node = LlmTestSupport.classNode("example/a");
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "a", "I", null, null));
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "b", "I", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "c", "()V", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "d", "()V", null, null));

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		plugin.setIndex(builder.build());

		EntryKey mappedField = new EntryKey(EntryKind.FIELD, "example/a", "a", "I");
		FakeProjectView project = new FakeProjectView(Map.of(mappedField, "itemCount"));
		LlmGuiService service = new LlmGuiService(plugin);

		List<EntryKey> targets = service.batchTargets(project, 3);

		assertThat(targets.size(), equalTo(3));
		assertFalse(targets.contains(mappedField));
		assertFalse(targets.stream().anyMatch(key -> key.name().startsWith("<")));
		assertThat(targets.get(0), equalTo(new EntryKey(EntryKind.CLASS, "example/a", "example/a", "")));
		assertThat(targets.get(1), equalTo(new EntryKey(EntryKind.FIELD, "example/a", "b", "I")));
		assertThat(targets.get(2), equalTo(new EntryKey(EntryKind.METHOD, "example/a", "c", "()V")));
	}

	@Test
	public void batchTargetsCanBeLimitedToActiveClass() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		ClassNode activeNode = LlmTestSupport.classNode("example/a");
		activeNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "a", "I", null, null));
		ClassNode otherNode = LlmTestSupport.classNode("example/Other");
		otherNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "b", "I", null, null));
		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(activeNode);
		builder.accept(otherNode);
		plugin.setIndex(builder.build());

		LlmGuiService service = new LlmGuiService(plugin);
		List<EntryKey> targets = service.batchTargets(new FakeProjectView(), 10, ClassEntryView.create("example/a"));

		assertThat(targets, equalTo(List.of(
				new EntryKey(EntryKind.CLASS, "example/a", "example/a", ""),
				new EntryKey(EntryKind.FIELD, "example/a", "a", "I")
		)));
	}

	@Test
	public void activeClassBatchTargetsCanIncludeAllClassEntries() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		ClassNode activeNode = LlmTestSupport.classNode("example/a");

		for (int index = 0; index < 120; index++) {
			activeNode.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "f" + index, "I", null, null));
		}

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(activeNode);
		plugin.setIndex(builder.build());

		LlmGuiService service = new LlmGuiService(plugin);
		List<EntryKey> targets = service.batchTargets(new FakeProjectView(), Integer.MAX_VALUE, ClassEntryView.create("example/a"));

		assertThat(targets.size(), equalTo(121));
	}

	@Test
	public void batchTargetsOnlySkipGenerallyReadableOriginalNames() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		ClassNode predicate = LlmTestSupport.classNode("example/k");
		predicate.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
		predicate.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "test", "(C)Z", null, null));
		predicate.methods.add(functionalInterfaceDefaultMethod("and", "(Lexample/k;)Lexample/k;"));
		predicate.methods.add(functionalInterfaceDefaultMethod("negate", "()Lexample/k;"));
		predicate.methods.add(functionalInterfaceDefaultMethod("or", "(Lexample/k;)Lexample/k;"));
		predicate.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "a", "()V", null, null));
		predicate.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "or", "()V", null, null));
		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(predicate);
		plugin.setIndex(builder.build());

		LlmGuiService service = new LlmGuiService(plugin);
		List<EntryKey> targets = service.batchTargets(new FakeProjectView(), Integer.MAX_VALUE, ClassEntryView.create("example/k"));

		assertThat(targets, equalTo(List.of(
				new EntryKey(EntryKind.CLASS, "example/k", "example/k", ""),
				new EntryKey(EntryKind.METHOD, "example/k", "a", "()V"),
				new EntryKey(EntryKind.METHOD, "example/k", "and", "(Lexample/k;)Lexample/k;"),
				new EntryKey(EntryKind.PARAMETER, "example/k", "and", "(Lexample/k;)Lexample/k;", 1, ""),
				new EntryKey(EntryKind.METHOD, "example/k", "or", "()V"),
				new EntryKey(EntryKind.METHOD, "example/k", "or", "(Lexample/k;)Lexample/k;"),
				new EntryKey(EntryKind.PARAMETER, "example/k", "or", "(Lexample/k;)Lexample/k;", 1, ""),
				new EntryKey(EntryKind.PARAMETER, "example/k", "test", "(C)Z", 1, "")
		)));
	}

	@Test
	public void readableOriginalNameDetectionDoesNotUseVowelsForMethodNames() {
		EntryKey sync = new EntryKey(EntryKind.METHOD, "example/Foo", "sync", "()V");
		EntryKey copy = new EntryKey(EntryKind.METHOD, "example/Foo", "copy", "()V");
		EntryKey src = new EntryKey(EntryKind.PARAMETER, "example/Foo", "copy", "(II)V", 1, "src");

		assertTrue(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), sync));
		assertTrue(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), copy));
		assertFalse(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), src));
	}

	@Test
	public void readableOriginalNameDetectionCoversMeaningfulFieldNames() {
		// UPPER_CASE constants (segmented or single-word) and readable lowerCamelCase fields are meaningful.
		assertTrue(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("MAX_VALUE")));
		assertTrue(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("DEFAULT_BUFFER_SIZE")));
		assertTrue(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("UTF_8")));
		assertTrue(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("LOGGER")));
		assertTrue(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("INSTANCE")));
		assertTrue(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("ID")));
		assertTrue(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("bufferSize")));
		assertTrue(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("utf8")));

		// Obfuscator (OpaqueNames "f"+digits), SRG output and degenerate shapes must stay renameable.
		assertFalse(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("a")));
		assertFalse(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("f789")));
		assertFalse(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("f_12345_a")));
		assertFalse(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("field_1234")));
		assertFalse(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("MAX_value")));
		assertFalse(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("A__B")));
		assertFalse(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("_LEADING")));
		assertFalse(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("arg0")));
		assertFalse(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), field("var0")));
	}

	private static EntryKey field(String name) {
		return new EntryKey(EntryKind.FIELD, "example/Foo", name, "I");
	}

	@Test
	public void readableOriginalNameDetectionRejectsObfuscatorClassTokens() {
		// The obfuscator emits "C"+digits class tokens (one letter + digits); real names carry >=2 letters.
		assertTrue(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), classKey("example/RotationAxis")));
		assertFalse(LlmBytecodePatterns.isPreservedOriginalName(LlmProjectIndex.empty(), classKey("example/C1234")));
	}

	private static EntryKey classKey(String internalName) {
		return new EntryKey(EntryKind.CLASS, internalName, internalName, "");
	}

	@Test
	public void readableTargetFormatsMappedMethodDescriptor() {
		EntryKey classKey = new EntryKey(EntryKind.CLASS, "a", "a", "");
		EntryKey methodKey = new EntryKey(EntryKind.METHOD, "a", "of", "(Lorg/joml/Vector3f;)La;");
		FakeProjectView project = new FakeProjectView(Map.of(classKey, "RotationAxis"));

		assertThat(LlmGuiService.readableTarget(project, methodKey), equalTo("RotationAxis.of(Vector3f): RotationAxis"));
	}

	private static MethodNode functionalInterfaceDefaultMethod(String name, String descriptor) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
		method.instructions = new InsnList();
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "example/k", "test", "(C)Z", true));
		return method;
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
	public void installStatusComponentsRegistersHiddenTaskAndCancelComponents() {
		LlmGuiService service = new LlmGuiService(new LlmNameProposalPlugin());
		FakeGuiView gui = new FakeGuiView(new FakeProjectView(), true);

		service.installStatusComponents(gui);

		assertThat(gui.statusComponents.size(), equalTo(2));
		assertFalse(gui.statusComponents.get(0).isVisible());
		assertTrue(gui.statusComponents.get(1) instanceof JButton);
		assertFalse(gui.statusComponents.get(1).isVisible());
	}

	@Test
	public void installStatusComponentsUsesCurrentUiFonts() {
		Font oldLabelFont = UIManager.getFont("Label.font");
		Font oldButtonFont = UIManager.getFont("Button.font");
		Font labelFont = new Font(Font.DIALOG, Font.BOLD, 23);
		Font buttonFont = new Font(Font.DIALOG, Font.PLAIN, 21);

		try {
			UIManager.put("Label.font", labelFont);
			UIManager.put("Button.font", buttonFont);
			LlmGuiService service = new LlmGuiService(new LlmNameProposalPlugin());
			FakeGuiView gui = new FakeGuiView(new FakeProjectView(), true);

			service.installStatusComponents(gui);

			assertThat(((JLabel) gui.statusComponents.get(0)).getFont(), equalTo(labelFont));
			assertThat(((JButton) gui.statusComponents.get(1)).getFont(), equalTo(buttonFont));
		} finally {
			UIManager.put("Label.font", oldLabelFont);
			UIManager.put("Button.font", oldButtonFont);
		}
	}

	@Test
	public void cancelCurrentRequestClearsBusyTokenAndCancelsRegisteredJob() {
		LlmGuiService service = new LlmGuiService(new LlmNameProposalPlugin());
		Object token = service.beginBusyToken();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<?> job = executor.submit(() -> {
			try {
				Thread.sleep(Duration.ofSeconds(30).toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		service.registerRunningJob(token, job, executor);

		assertTrue(service.cancelCurrentRequest(new FakeGuiView(new FakeProjectView(), true)));
		assertFalse(service.isBusy());
		assertTrue(job.isCancelled());
		assertFalse(service.cancelCurrentRequest(new FakeGuiView(new FakeProjectView(), true)));
	}

	@Test
	public void statusCancelButtonCancelsRegisteredJob() {
		LlmGuiService service = new LlmGuiService(new LlmNameProposalPlugin());
		FakeGuiView gui = new FakeGuiView(new FakeProjectView(), true);
		service.installStatusComponents(gui);
		Object token = service.beginBusyToken();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<?> job = executor.submit(() -> {
			try {
				Thread.sleep(Duration.ofSeconds(30).toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		service.registerRunningJob(token, job, executor);

		((JButton) gui.statusComponents.get(1)).doClick();

		assertFalse(service.isBusy());
		assertTrue(job.isCancelled());
	}

	@Test
	public void clearCachedSuggestionsRemovesGrayProposalsAndInvalidatesMappings() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin);
		FakeProjectView project = new FakeProjectView();
		EntryKey key = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");
		plugin.getSuggestions().put(key, new LlmSuggestion("count", List.of(), 0.8, ""));

		assertTrue(service.hasCachedSuggestions());
		assertTrue(service.clearCachedSuggestions(project));

		assertFalse(service.hasCachedSuggestions());
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));
		assertThat(project.invalidations, equalTo(1));
		assertFalse(service.clearCachedSuggestions(project));
	}

	@Test
	public void clearCurrentSuggestionOnlyRemovesActiveGrayProposal() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin);
		FakeProjectView project = new FakeProjectView();
		FakeGuiView gui = new FakeGuiView(project, true);
		EntryKey activeKey = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");
		EntryKey otherKey = new EntryKey(EntryKind.FIELD, "example/Foo", "b", "I");
		LlmSuggestion activeSuggestion = new LlmSuggestion("count", List.of(), 0.8, "");
		LlmSuggestion otherSuggestion = new LlmSuggestion("size", List.of(), 0.7, "");
		gui.cursorDeclaration = FieldEntryView.create("example/Foo", "a", "I");
		plugin.getSuggestions().put(activeKey, activeSuggestion);
		plugin.getSuggestions().put(otherKey, otherSuggestion);

		assertTrue(service.currentTargetHasCachedSuggestion(gui));
		assertTrue(service.clearCurrentSuggestion(project, activeKey));

		assertThat(plugin.getSuggestions().get(activeKey), equalTo(Optional.empty()));
		assertThat(plugin.getSuggestions().get(otherKey), equalTo(Optional.of(otherSuggestion)));
		assertThat(project.invalidations, equalTo(1));
		assertFalse(service.currentTargetHasCachedSuggestion(gui));
		assertFalse(service.clearCurrentSuggestion(project, activeKey));
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

		service.setAnalysisHints(Set.of(LlmAnalysisHint.FUNCTIONAL_INTERFACE));

		assertThat(service.loadConfig().analysisHints(), equalTo(Set.of(LlmAnalysisHint.FUNCTIONAL_INTERFACE)));
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
	public void batchSelectionConflictsRejectDuplicateSelectedMethodSignatures() {
		List<BatchSuggestion> selected = List.of(
				new BatchSuggestion(new EntryKey(EntryKind.METHOD, "example/Foo", "a", "(Lexample/Foo;)Lexample/Foo;"), new LlmSuggestion("combine", List.of(), 0.8, ""), true),
				new BatchSuggestion(new EntryKey(EntryKind.METHOD, "example/Foo", "b", "(Lexample/Foo;)Lexample/Foo;"), new LlmSuggestion("combine", List.of(), 0.7, ""), true)
		);

		List<String> conflicts = LlmGuiService.batchSelectionConflicts(selected);

		assertThat(conflicts.size(), equalTo(1));
		assertThat(conflicts.get(0), containsString("example/Foo.a(Lexample/Foo;)Lexample/Foo;"));
		assertThat(conflicts.get(0), containsString("example/Foo.b(Lexample/Foo;)Lexample/Foo;"));
		assertThat(conflicts.get(0), containsString("combine"));
	}

	@Test
	public void applySuggestionNormalizesTopLevelClassRenameAndClearsCacheOnSuccess() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin);
		FakeProjectView project = new FakeProjectView();
		FakeGuiView gui = new FakeGuiView(project, true);
		EntryKey key = new EntryKey(EntryKind.CLASS, "example/Foo", "example/Foo", "");
		LlmSuggestion suggestion = new LlmSuggestion("ItemCounter", List.of(), 0.92, "counts items");

		assertTrue(service.applySuggestion(gui, project, key, suggestion));

		assertThat(gui.lastRename, equalTo("example/ItemCounter"));
		assertThat(((ClassEntryView) gui.lastEntry).getFullName(), equalTo("example/Foo"));
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));
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
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));
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
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));
		assertThat(project.invalidations, equalTo(1));
	}

	@Test
	public void applySuggestionClearsCacheAndRefreshesWhenRenameIsRejected() {
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
		assertThat(project.invalidations, equalTo(1));
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
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));
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
