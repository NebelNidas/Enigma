package cuchaz.enigma.llm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import cuchaz.enigma.api.DataInvalidationEvent;
import cuchaz.enigma.api.EnigmaPlugin;
import cuchaz.enigma.api.EnigmaPluginContext;
import cuchaz.enigma.api.Ordering;
import cuchaz.enigma.api.service.EnigmaService;
import cuchaz.enigma.api.service.EnigmaServiceFactory;
import cuchaz.enigma.api.service.EnigmaServiceType;
import cuchaz.enigma.api.service.GuiService;
import cuchaz.enigma.api.service.I18nService;
import cuchaz.enigma.api.service.JarIndexerService;
import cuchaz.enigma.api.service.NameProposalService;
import cuchaz.enigma.api.service.ProjectService;
import cuchaz.enigma.api.view.entry.ClassEntryView;
import cuchaz.enigma.api.view.entry.FieldEntryView;
import cuchaz.enigma.api.view.entry.LocalVariableEntryView;
import cuchaz.enigma.api.view.entry.MethodEntryView;
import cuchaz.enigma.classprovider.ClassProvider;
import cuchaz.enigma.translation.representation.MethodDescriptor;
import cuchaz.enigma.translation.representation.TypeDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

public class LlmNameProposalPluginTest {
	@Test
	public void registersExpectedServices() {
		FakePluginContext context = new FakePluginContext();

		new LlmNameProposalPlugin().init(context);

		assertTrue(context.has(JarIndexerService.TYPE, "llm_name_proposal:jar_indexer"));
		assertTrue(context.has(NameProposalService.TYPE, "llm_name_proposal:name_proposal"));
		assertTrue(context.has(GuiService.TYPE, "llm_name_proposal:gui"));
		assertTrue(context.has(ProjectService.TYPE, "llm_name_proposal:project"));
		assertTrue(context.has(I18nService.TYPE, "llm_name_proposal:i18n"));
	}

	@Test
	public void pluginIsDiscoverableThroughServiceLoader() {
		boolean found = ServiceLoader.load(EnigmaPlugin.class).stream()
				.anyMatch(provider -> provider.type().equals(LlmNameProposalPlugin.class));

		assertTrue(found);
	}

	@Test
	public void i18nServiceUsesNamespacedResourcePath() throws Exception {
		try (var stream = new LlmI18nService().getTranslationResource("en_us")) {
			assertThat(stream == null, equalTo(false));
			String translations = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(translations, containsString("\"llm.menu.root\""));
			assertThat(translations, containsString("\"llm.menu.suggest\""));
			assertThat(translations, containsString("\"llm.menu.batch_current_class\""));
			assertThat(translations, containsString("\"llm.menu.batch_project\""));
			assertThat(translations, containsString("\"llm.menu.context_backend\""));
			assertThat(translations, containsString("\"llm.menu.parallelism\""));
			assertThat(translations, containsString("\"llm.menu.root\": \"LLM\""));
			assertThat(translations, containsString("Suggest name"));
			assertThat(translations, containsString("Graph-based"));
			assertThat(translations, containsString("parallel requests"));
			assertThat(translations, containsString("No LLM model configured"));
			assertThat(translations, containsString("class, field, method, or parameter"));
			assertThat(translations, containsString("ENIGMA_LLM_BASE_URL defaults to http://localhost:1234/v1"));
			assertThat(translations, containsString("No confidence threshold configured"));
		}
	}

	@Test
	public void projectServiceRebindsProjectAndClearsState() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmProjectService service = new LlmProjectService(plugin);
		FakeProjectView project = new FakeProjectView();
		EntryKey key = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(classNode("example/Foo"));
		plugin.setIndex(builder.build());
		plugin.getSuggestions().put(key, new LlmSuggestion("count", List.of(), 0.8, ""));

		service.onProjectOpen(project);

		assertThat(plugin.getProject(), equalTo(Optional.of(project)));
		assertFalse(plugin.getIndex().isEmpty());
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));

		plugin.getSuggestions().put(key, new LlmSuggestion("count", List.of(), 0.8, ""));
		service.onProjectClose(project);

		assertThat(plugin.getProject(), equalTo(Optional.empty()));
		assertTrue(plugin.getIndex().isEmpty());
		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));
	}

	@Test
	public void externalMappingInvalidationClearsCachedSuggestions() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmProjectService service = new LlmProjectService(plugin);
		FakeProjectView project = new FakeProjectView();
		EntryKey key = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");
		LlmSuggestion suggestion = new LlmSuggestion("count", List.of(), 0.8, "");

		service.onProjectOpen(project);
		plugin.getSuggestions().put(key, suggestion);

		project.invalidateData(DataInvalidationEvent.InvalidationType.MAPPINGS);

		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));

		plugin.getSuggestions().put(key, suggestion);
		plugin.markInternalRefreshInvalidation();
		project.invalidateData(DataInvalidationEvent.InvalidationType.MAPPINGS);

		assertThat(plugin.getSuggestions().get(key), equalTo(Optional.of(suggestion)));
	}

	@Test
	public void projectCurrentTracksObservedLifecycle() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		FakeProjectView project = new FakeProjectView();
		FakeProjectView otherProject = new FakeProjectView();

		assertTrue(plugin.isProjectCurrent(project));

		plugin.openProject(project);

		assertTrue(plugin.isProjectCurrent(project));
		assertFalse(plugin.isProjectCurrent(otherProject));

		plugin.closeProject();

		assertFalse(plugin.isProjectCurrent(project));
	}

	@Test
	public void jarIndexerBuildsInventorySkipsMissingBytecodeAndClearsSuggestions() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		EntryKey staleKey = new EntryKey(EntryKind.FIELD, "old/Foo", "a", "I");
		ClassNode node = classNode("example/Foo");
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "a", "I", null, null));

		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "b", "(I)V", null, null);
		method.localVariables.add(new LocalVariableNode("this", "Lexample/Foo;", null, null, null, 0));
		method.localVariables.add(new LocalVariableNode("p_1_", "I", null, null, null, 1));
		node.methods.add(method);
		plugin.getSuggestions().put(staleKey, new LlmSuggestion("oldName", List.of(), 0.9, ""));

		new LlmJarIndexerService(plugin).acceptJar(Set.of("example/Foo", "missing/Foo"), new FakeClassProvider(Map.of("example/Foo", node)), null);

		assertThat(plugin.getSuggestions().get(staleKey), equalTo(Optional.empty()));
		assertTrue(plugin.getIndex().entries().stream().map(IndexedEntry::key).anyMatch(key -> key.kind() == EntryKind.CLASS && key.owner().equals("example/Foo")));
		assertTrue(plugin.getIndex().entries().stream().map(IndexedEntry::key).anyMatch(key -> key.kind() == EntryKind.FIELD && key.name().equals("a")));
		assertTrue(plugin.getIndex().entries().stream().map(IndexedEntry::key).anyMatch(key -> key.kind() == EntryKind.METHOD && key.name().equals("b")));
		assertTrue(plugin.getIndex().entries().stream().map(IndexedEntry::key).anyMatch(key -> key.kind() == EntryKind.PARAMETER && key.localIndex() == 1));
		assertFalse(plugin.getIndex().entries().stream().map(IndexedEntry::key).anyMatch(key -> key.owner().equals("missing/Foo")));
	}

	@Test
	public void jarIndexerSkipsUnreadableBytecode() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		ClassNode node = classNode("example/Foo");

		new LlmJarIndexerService(plugin).acceptJar(Set.of("example/Foo", "broken/Foo"), new FakeClassProvider(Map.of("example/Foo", node)) {
			@Override
			public ClassNode get(String name) {
				if (name.equals("broken/Foo")) {
					throw new RuntimeException("cannot read class");
				}

				return super.get(name);
			}
		}, null);

		assertTrue(plugin.getIndex().entries().stream().map(IndexedEntry::key).anyMatch(key -> key.kind() == EntryKind.CLASS && key.owner().equals("example/Foo")));
		assertFalse(plugin.getIndex().entries().stream().map(IndexedEntry::key).anyMatch(key -> key.owner().equals("broken/Foo")));
	}

	@Test
	public void cachedNameProposalReturnsStoredSuggestion() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		NameProposalService service = new LlmCachedNameProposalService(plugin);
		ClassEntry owner = new ClassEntry("example/Foo");
		FieldEntry field = new FieldEntry(owner, "a", new TypeDescriptor("I"));
		EntryKey key = EntryKey.fromEntry(field).orElseThrow();

		assertThat(service.proposeName(field, null), equalTo(Optional.empty()));

		plugin.getSuggestions().put(key, new LlmSuggestion("count", List.of("size"), 0.82, "integer counter"));

		assertThat(service.proposeName(field, null), equalTo(Optional.of("count")));
	}

	@Test
	public void cachedNameProposalReturnsFullClassNameForQualifiedSuggestion() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		NameProposalService service = new LlmCachedNameProposalService(plugin);
		ClassEntry entry = new ClassEntry("example/Foo");
		EntryKey key = EntryKey.fromEntry(entry).orElseThrow();

		plugin.getSuggestions().put(key, new LlmSuggestion("net/minecraft/TextureManager", List.of(), 0.74, "qualified class"));

		assertThat(service.proposeName(entry, null), equalTo(Optional.of("net/minecraft/TextureManager")));
		assertThat(plugin.getSuggestions().get(key).map(LlmSuggestion::suggestedName), equalTo(Optional.of("net/minecraft/TextureManager")));
	}

	@Test
	public void cachedNameProposalKeepsTopLevelClassPackageForSimpleSuggestion() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		NameProposalService service = new LlmCachedNameProposalService(plugin);
		ClassEntry entry = new ClassEntry("example/Foo");
		EntryKey key = EntryKey.fromEntry(entry).orElseThrow();

		plugin.getSuggestions().put(key, new LlmSuggestion("TextureManager", List.of(), 0.74, "simple class"));

		assertThat(service.proposeName(entry, null), equalTo(Optional.of("example/TextureManager")));
		assertThat(plugin.getSuggestions().get(key).map(LlmSuggestion::suggestedName), equalTo(Optional.of("TextureManager")));
	}

	@Test
	public void cachedNameProposalKeepsInnerClassSuggestionSimple() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		NameProposalService service = new LlmCachedNameProposalService(plugin);
		ClassEntry entry = new ClassEntry("example/Foo$Bar");
		EntryKey key = EntryKey.fromEntry(entry).orElseThrow();

		plugin.getSuggestions().put(key, new LlmSuggestion("Part", List.of(), 0.74, "inner class"));

		assertThat(service.proposeName(entry, null), equalTo(Optional.of("Part")));
	}

	@Test
	public void cachedNameProposalReturnsStoredParameterSuggestion() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		NameProposalService service = new LlmCachedNameProposalService(plugin);
		MethodEntry method = new MethodEntry(new ClassEntry("example/Foo"), "a", new MethodDescriptor("(I)V"));
		LocalVariableEntry parameter = new LocalVariableEntry(method, 1, "p_1_", true, null);
		LocalVariableEntry enigmaParameterLookup = new LocalVariableEntry(method, 1, "", true, null);
		EntryKey key = EntryKey.fromEntry(parameter).orElseThrow();

		assertThat(service.proposeName(enigmaParameterLookup, null), equalTo(Optional.empty()));

		plugin.getSuggestions().put(key, new LlmSuggestion("count", List.of("amount"), 0.81, "method uses it as a count"));

		assertThat(service.proposeName(enigmaParameterLookup, null), equalTo(Optional.of("count")));
	}

	@Test
	public void classEntryKeysUseFullNamesForInnerClasses() {
		ClassEntry innerClass = new ClassEntry("example/Foo$Bar");
		EntryKey expected = new EntryKey(EntryKind.CLASS, "example/Foo$Bar", "example/Foo$Bar", "");

		assertThat(EntryKey.fromEntry(innerClass), equalTo(Optional.of(expected)));
		assertThat(EntryKey.fromEntryView(ClassEntryView.create("example/Foo$Bar")), equalTo(Optional.of(expected)));
	}

	@Test
	public void parameterEntryKeysUseParentMethodAndLocalSlot() {
		MethodEntry method = new MethodEntry(new ClassEntry("example/Foo"), "a", new MethodDescriptor("(ILjava/lang/String;)V"));
		LocalVariableEntry parameter = new LocalVariableEntry(method, 2, "p_2_", true, null);
		LocalVariableEntry sameSlotWithoutName = new LocalVariableEntry(method, 2, "", true, null);
		EntryKey expected = new EntryKey(EntryKind.PARAMETER, "example/Foo", "a", "(ILjava/lang/String;)V", 2, "p_2_");

		assertThat(EntryKey.fromEntry(parameter), equalTo(Optional.of(expected)));
		assertThat(EntryKey.fromEntry(sameSlotWithoutName), equalTo(Optional.of(expected)));
		assertThat(EntryKey.fromEntryView(LocalVariableEntryView.create(MethodEntryView.create("example/Foo", "a", "(ILjava/lang/String;)V"), 2, "p_2_", true)), equalTo(Optional.of(expected)));
	}

	@Test
	public void nonArgumentLocalVariablesAreNotProposalTargets() {
		MethodEntry method = new MethodEntry(new ClassEntry("example/Foo"), "a", new MethodDescriptor("(I)V"));
		LocalVariableEntry local = new LocalVariableEntry(method, 2, "tmp", false, null);

		assertThat(EntryKey.fromEntry(local), equalTo(Optional.empty()));
		assertThat(EntryKey.fromEntryView(LocalVariableEntryView.create(MethodEntryView.create("example/Foo", "a", "(I)V"), 2, "tmp", false)), equalTo(Optional.empty()));
	}

	@Test
	public void promptIncludesExplicitClassTargetDetails() {
		ClassNode node = classNode("example/Foo");
		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		LlmProjectIndex index = builder.build();

		String prompt = new LlmPromptBuilder().build(new EntryKey(EntryKind.CLASS, "example/Foo", "example/Foo", ""), new FakeProjectView(), index);

		assertThat(prompt, containsString("Target kind: CLASS"));
		assertThat(prompt, containsString("Target class: example/Foo"));
		assertThat(prompt, containsString("Target naming rule: return a simple class name or a JVM internal name with package separators."));
		assertThat(prompt, containsString("Class names must describe the actual role shown by members."));
		assertThat(prompt, containsString("prefer a conservative constants-style name such as AngleConstants or MathConstants"));
		assertThat(prompt, containsString("Do not infer rendering, texture, shader, model, registry, or manager roles unless members prove them."));
		assertThat(prompt, containsString("Owner class: example/Foo"));
		assertThat(prompt, containsString("Top-level classes may be simple names or JVM internal names"));
		assertThat(prompt, containsString("do not reuse an existing mapped member name for a different member"));
	}

	@Test
	public void promptIncludesInnerClassNamingRule() {
		String prompt = new LlmPromptBuilder().build(new EntryKey(EntryKind.CLASS, "example/Foo$Bar", "example/Foo$Bar", ""), new FakeProjectView(), LlmProjectIndex.empty());

		assertThat(prompt, containsString("Target kind: CLASS"));
		assertThat(prompt, containsString("Target class: example/Foo$Bar"));
		assertThat(prompt, containsString("Target naming rule: return only the simple inner class name without package separators."));
	}

	@Test
	public void projectIndexAndPromptIncludeBytecodeContext() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = "example/Foo";
		node.superName = "example/Base";
		node.interfaces.add("example/Counter");
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "a", "I", null, null));

		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "b", "()V", null, null);
		method.instructions = new InsnList();
		method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "example/Foo", "a", "I"));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "example/Foo", "c", "()V", false));
		node.methods.add(method);

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		LlmProjectIndex index = builder.build();
		EntryKey key = new EntryKey(EntryKind.METHOD, "example/Foo", "b", "()V");
		String prompt = new LlmPromptBuilder().build(key, new FakeProjectView(), index);

		assertThat(prompt, containsString("Target kind: METHOD"));
		assertThat(prompt, containsString("Target owner: example/Foo"));
		assertThat(prompt, containsString("Target name: b"));
		assertThat(prompt, containsString("Target descriptor: ()V"));
		assertThat(prompt, containsString("Target naming rule: return one lower camelCase method name without package separators."));
		assertThat(prompt, containsString("Target access: public"));
		assertThat(prompt, containsString("Owner class: example/Foo"));
		assertThat(prompt, containsString("Owner access: public"));
		assertThat(prompt, containsString("Superclass: example/Base"));
		assertThat(prompt, containsString("Interfaces: example/Counter"));
		assertThat(prompt, containsString("- private a : I"));
		assertThat(prompt, containsString("fields: example/Foo.a : I"));
		assertThat(prompt, containsString("calls: example/Foo.c()V"));
	}

	@Test
	public void promptIncludesFieldConstantValues() {
		ClassNode node = classNode("example/Constants");
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "a", "F", null, Float.valueOf(3.1415927F)));
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "b", "F", null, Float.valueOf(57.29578F)));

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		LlmProjectIndex index = builder.build();

		String prompt = new LlmPromptBuilder().build(new EntryKey(EntryKind.FIELD, "example/Constants", "a", "F"), new FakeProjectView(), index);

		assertThat(prompt, containsString("Target constant value: 3.1415927"));
		assertThat(prompt, containsString("- public static final a : F = 3.1415927"));
		assertThat(prompt, containsString("- public static final b : F = 57.29578"));
	}

	@Test
	public void projectIndexSkipsConstructorsAsProposalTargets() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = "example/Foo";
		node.superName = "java/lang/Object";
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "a", "()V", null, null));

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		LlmProjectIndex index = builder.build();

		assertFalse(index.entries().stream().map(IndexedEntry::key).anyMatch(key -> key.name().startsWith("<")));
		assertTrue(index.entries().stream().map(IndexedEntry::key).anyMatch(key -> key.kind() == EntryKind.METHOD && key.name().equals("a")));
	}

	@Test
	public void projectIndexIncludesParametersFromLocalVariableTable() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = "example/Foo";
		node.superName = "java/lang/Object";

		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "a", "(IJ)V", null, null);
		method.localVariables.add(new LocalVariableNode("this", "Lexample/Foo;", null, null, null, 0));
		method.localVariables.add(new LocalVariableNode("p_1_", "I", null, null, null, 1));
		method.localVariables.add(new LocalVariableNode("p_2_", "J", null, null, null, 2));
		method.localVariables.add(new LocalVariableNode("tmp", "I", null, null, null, 4));
		node.methods.add(method);

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		LlmProjectIndex index = builder.build();

		assertTrue(index.entries().stream().anyMatch(entry -> entry instanceof IndexedParameter parameter && parameter.key().localIndex() == 1 && parameter.key().localName().equals("p_1_") && parameter.descriptor().equals("I")));
		assertTrue(index.entries().stream().anyMatch(entry -> entry instanceof IndexedParameter parameter && parameter.key().localIndex() == 2 && parameter.key().localName().equals("p_2_") && parameter.descriptor().equals("J")));
		assertFalse(index.entries().stream().map(IndexedEntry::key).anyMatch(key -> key.kind() == EntryKind.PARAMETER && key.localIndex() == 0));
		assertFalse(index.entries().stream().map(IndexedEntry::key).anyMatch(key -> key.kind() == EntryKind.PARAMETER && key.localName().equals("tmp")));
	}

	@Test
	public void projectIndexInfersParametersWithoutLocalVariableTable() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = "example/Foo";
		node.superName = "java/lang/Object";
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "a", "(IJLjava/lang/String;)V", null, null));

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		LlmProjectIndex index = builder.build();

		assertTrue(index.entries().stream().anyMatch(entry -> entry instanceof IndexedParameter parameter && parameter.key().localIndex() == 1 && parameter.key().localName().isEmpty() && parameter.descriptor().equals("I")));
		assertTrue(index.entries().stream().anyMatch(entry -> entry instanceof IndexedParameter parameter && parameter.key().localIndex() == 2 && parameter.key().localName().isEmpty() && parameter.descriptor().equals("J")));
		assertTrue(index.entries().stream().anyMatch(entry -> entry instanceof IndexedParameter parameter && parameter.key().localIndex() == 4 && parameter.key().localName().isEmpty() && parameter.descriptor().equals("Ljava/lang/String;")));
		assertFalse(index.entries().stream().map(IndexedEntry::key).anyMatch(key -> key.kind() == EntryKind.PARAMETER && key.localIndex() == 0));
	}

	@Test
	public void projectIndexInfersStaticParametersFromSlotZero() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = "example/Foo";
		node.superName = "java/lang/Object";
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "a", "(IJ)V", null, null));

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		LlmProjectIndex index = builder.build();

		assertTrue(index.entries().stream().anyMatch(entry -> entry instanceof IndexedParameter parameter && parameter.key().localIndex() == 0 && parameter.key().localName().isEmpty() && parameter.descriptor().equals("I")));
		assertTrue(index.entries().stream().anyMatch(entry -> entry instanceof IndexedParameter parameter && parameter.key().localIndex() == 1 && parameter.key().localName().isEmpty() && parameter.descriptor().equals("J")));
		assertFalse(index.entries().stream().map(IndexedEntry::key).anyMatch(key -> key.kind() == EntryKind.PARAMETER && key.localIndex() == 3));
	}

	@Test
	public void promptHandlesMissingBytecodeContext() {
		EntryKey key = new EntryKey(EntryKind.FIELD, "missing/Foo", "a", "I");
		String prompt = new LlmPromptBuilder().build(key, new FakeProjectView(), LlmProjectIndex.empty());

		assertThat(prompt, containsString("Target kind: FIELD"));
		assertThat(prompt, containsString("Target obfuscated name: missing/Foo.a : I"));
		assertThat(prompt, containsString("Target owner: missing/Foo"));
		assertThat(prompt, containsString("Target name: a"));
		assertThat(prompt, containsString("Target descriptor: I"));
		assertThat(prompt, containsString("Target naming rule: return one lower camelCase field name without package separators."));
		assertThat(prompt, containsString("Target mapped name: (none)"));
		assertThat(prompt, not(containsString("Owner class:")));
	}

	@Test
	public void promptIncludesExistingParameterMappings() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = "example/Foo";
		node.superName = "java/lang/Object";

		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "a", "(I)V", null, null);
		method.localVariables.add(new LocalVariableNode("this", "Lexample/Foo;", null, null, null, 0));
		method.localVariables.add(new LocalVariableNode("p_1_", "I", null, null, null, 1));
		node.methods.add(method);

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		LlmProjectIndex index = builder.build();
		EntryKey parameterKey = new EntryKey(EntryKind.PARAMETER, "example/Foo", "a", "(I)V", 1, "p_1_");
		FakeProjectView project = new FakeProjectView(Map.of(parameterKey, "count"));

		String prompt = new LlmPromptBuilder().build(parameterKey, project, index);

		assertThat(prompt, containsString("Target kind: PARAMETER"));
		assertThat(prompt, containsString("Target owner: example/Foo"));
		assertThat(prompt, containsString("Target method: a(I)V"));
		assertThat(prompt, containsString("Target local slot: 1"));
		assertThat(prompt, containsString("Target local name: p_1_"));
		assertThat(prompt, containsString("Target naming rule: return one lower camelCase parameter name without package separators."));
		assertThat(prompt, containsString("Target parameter descriptor: I"));
		assertThat(prompt, containsString("Target mapped name: count"));
		assertThat(prompt, containsString("params: #1 p_1_ : I mapped: count"));
	}

	@Test
	public void promptIncludesExistingMappingsForOwnerAndMembers() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = "example/Foo";
		node.superName = "java/lang/Object";
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "a", "I", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "b", "()I", null, null));
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "c", "()V", null, null));

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		LlmProjectIndex index = builder.build();
		EntryKey classKey = new EntryKey(EntryKind.CLASS, "example/Foo", "example/Foo", "");
		EntryKey fieldKey = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");
		EntryKey methodKey = new EntryKey(EntryKind.METHOD, "example/Foo", "b", "()I");
		FakeProjectView project = new FakeProjectView(Map.of(
				classKey, "example/ItemCounter",
				fieldKey, "itemCount",
				methodKey, "getItemCount"
		));

		String prompt = new LlmPromptBuilder().build(new EntryKey(EntryKind.METHOD, "example/Foo", "c", "()V"), project, index);

		assertThat(prompt, containsString("Owner mapped name: example/ItemCounter"));
		assertThat(prompt, containsString("a : I mapped: itemCount"));
		assertThat(prompt, containsString("b()I mapped: getItemCount"));
		assertThat(prompt, containsString("Target mapped name: (none)"));
	}

	@Test
	public void promptListsUnavailableMappedSiblingFieldNames() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = "example/MathUtils";
		node.superName = "java/lang/Object";
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "a", "F", null, Float.valueOf(3.1415927F)));
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "b", "F", null, Float.valueOf(57.295776F)));
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "c", "F", null, Float.valueOf(0.017453292F)));
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "d", "F", null, Float.valueOf(1.0E-6F)));

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		LlmProjectIndex index = builder.build();
		FakeProjectView project = new FakeProjectView(Map.of(
				new EntryKey(EntryKind.FIELD, "example/MathUtils", "a", "F"), "pi",
				new EntryKey(EntryKind.FIELD, "example/MathUtils", "c", "F"), "angle",
				new EntryKey(EntryKind.FIELD, "example/MathUtils", "d", "F"), "epsilon"
		));

		String prompt = new LlmPromptBuilder().build(new EntryKey(EntryKind.FIELD, "example/MathUtils", "b", "F"), project, index);

		assertThat(prompt, containsString("Unavailable mapped names for this FIELD: pi, angle, epsilon"));
		assertThat(prompt, containsString("Do not use unavailable mapped names as suggestedName or alternatives."));
	}

	@Test
	public void graphPromptIncludesCallAndDependencyNeighbors() {
		ClassNode owner = classNode("example/Foo");
		MethodNode target = new MethodNode(Opcodes.ACC_PUBLIC, "a", "()V", null, null);
		target.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "example/Bar", "b", "()V", false));
		owner.methods.add(target);

		ClassNode caller = classNode("example/Caller");
		MethodNode callerMethod = new MethodNode(Opcodes.ACC_PUBLIC, "call", "()V", null, null);
		callerMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "example/Foo", "a", "()V", false));
		caller.methods.add(callerMethod);

		ClassNode dependency = classNode("example/Bar");
		dependency.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "value", "I", null, null));
		dependency.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "b", "()V", null, null));

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(owner);
		builder.accept(caller);
		builder.accept(dependency);
		LlmProjectIndex index = builder.build();

		String prompt = new LlmPromptBuilder().build(new EntryKey(EntryKind.METHOD, "example/Foo", "a", "()V"), new FakeProjectView(), index, LlmContextBackend.GRAPH);

		assertThat(prompt, containsString("Context backend: graph"));
		assertThat(prompt, containsString("Johannes-style graph retrieval"));
		assertThat(prompt, containsString("=== ROOT / DECLARING CLASS ==="));
		assertThat(prompt, containsString("=== Incoming callers ==="));
		assertThat(prompt, containsString("example/Caller.call()V"));
		assertThat(prompt, containsString("=== Referenced classes from target method ==="));
		assertThat(prompt, containsString("class example/Bar"));
		assertThat(prompt, containsString("field value : I"));
	}

	@Test
	public void contextBackendComparisonFixtureProducesDifferentPrompts() {
		LlmContextBackendComparisonHarness.ComparisonFixture fixture = LlmContextBackendComparisonHarness.comparisonFixture();
		LlmContextBackendComparisonHarness.ComparisonCase testCase = fixture.cases().get(0);
		LlmContextBackendComparisonHarness.EvaluationProjectView project = new LlmContextBackendComparisonHarness.EvaluationProjectView(testCase.mappings());

		String ownerPrompt = new LlmPromptBuilder().build(testCase.key(), project, fixture.index(), LlmContextBackend.OWNER);
		String graphPrompt = new LlmPromptBuilder().build(testCase.key(), project, fixture.index(), LlmContextBackend.GRAPH);

		assertThat(ownerPrompt, not(containsString("Context backend: graph")));
		assertThat(ownerPrompt, not(containsString("Incoming callers")));
		assertThat(graphPrompt, containsString("Context backend: graph"));
		assertThat(graphPrompt, containsString("Incoming callers"));
		assertThat(graphPrompt, containsString("example/HudRenderer.c(Lexample/Counter;)V mapped: renderCount"));
	}

	@Test
	public void memberCanRemainUnmappedWhenOwnerClassIsMapped() {
		EntryKey classKey = new EntryKey(EntryKind.CLASS, "example/Foo", "example/Foo", "");
		EntryKey fieldKey = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");
		EntryKey methodKey = new EntryKey(EntryKind.METHOD, "example/Foo", "b", "()V");
		FakeProjectView project = new FakeProjectView(Map.of(classKey, "example/ItemCounter"));

		assertFalse(classKey.hasUnchangedName(project));
		assertTrue(fieldKey.hasUnchangedName(project));
		assertTrue(methodKey.hasUnchangedName(project));
	}

	@Test
	public void parsesOpenAiCompatibleResponse() throws Exception {
		String body = """
				{"choices":[{"message":{"content":"{\\"reasoning\\":\\"reads count\\",\\"alternatives\\":[\\"count\\",\\"size\\"],\\"suggestedName\\":\\"getCount\\",\\"confidence\\":0.91}"}}]}
				""";

		LlmSuggestion suggestion = OpenAiCompatibleClient.parseSuggestion(body);

		assertThat(suggestion.suggestedName(), equalTo("getCount"));
		assertThat(suggestion.alternatives(), equalTo(List.of("count", "size")));
		assertThat(suggestion.confidence(), equalTo(0.91));
		assertThat(suggestion.reasoning(), equalTo("reads count"));
	}

	@Test
	public void parsesEvaluationCaseJsonl() {
		LlmEvaluationHarness.EvaluationCase testCase = LlmEvaluationHarness.parseCase("{\"id\":\"field-pi\",\"kind\":\"FIELD\",\"targetName\":\"b.a : F\",\"prompt\":\"Target field\",\"expected\":\"pi\",\"acceptable\":[\"piValue\"]}");

		assertThat(testCase.id(), equalTo("field-pi"));
		assertThat(testCase.kind(), equalTo(EntryKind.FIELD));
		assertThat(testCase.targetName(), equalTo("b.a : F"));
		assertThat(testCase.prompt(), equalTo("Target field"));
		assertThat(testCase.expected(), equalTo("pi"));
		assertTrue(testCase.acceptable().contains("pi"));
		assertTrue(testCase.acceptable().contains("piValue"));
	}

	@Test
	public void evaluationResultSerializesJsonl() {
		LlmEvaluationHarness.EvaluationCase testCase = new LlmEvaluationHarness.EvaluationCase("field-pi", EntryKind.FIELD, "b.a : F", "Target field", "pi", Set.of("pi", "piValue"));
		LlmSuggestion suggestion = new LlmSuggestion("piValue", List.of("pi"), 0.8, "near miss");

		String json = LlmEvaluationHarness.EvaluationResult.success("local-model", testCase, suggestion, Duration.ofMillis(42)).toJson();

		assertThat(json, containsString("\"model\":\"local-model\""));
		assertThat(json, containsString("\"id\":\"field-pi\""));
		assertThat(json, containsString("\"accepted\":true"));
		assertThat(json, containsString("\"exact\":false"));
		assertThat(json, containsString("\"usable\":true"));
		assertThat(json, containsString("\"latencyMillis\":42"));
	}

	@Test
	public void normalizesOpenAiCompatibleSuggestionTextFields() throws Exception {
		String body = """
				{"choices":[{"message":{"content":"{\\"reasoning\\":\\" reads count \\",\\"alternatives\\":[\\" count \\",\\"\\",\\" size \\"],\\"suggestedName\\":\\" getCount \\",\\"confidence\\":0.91}"}}]}
				""";

		LlmSuggestion suggestion = OpenAiCompatibleClient.parseSuggestion(body);

		assertThat(suggestion.suggestedName(), equalTo("getCount"));
		assertThat(suggestion.alternatives(), equalTo(List.of("count", "size")));
		assertThat(suggestion.reasoning(), equalTo("reads count"));
	}

	@Test
	public void normalizesDirectSuggestionValues() {
		LlmSuggestion suggestion = new LlmSuggestion(" itemCount ", java.util.Arrays.asList(" count ", null, "", " itemCount ", " size ", " count "), 1.7, " stores count ");

		assertThat(suggestion.suggestedName(), equalTo("itemCount"));
		assertThat(suggestion.alternatives(), equalTo(List.of("count", "size")));
		assertThat(suggestion.confidence(), equalTo(1.0));
		assertThat(suggestion.reasoning(), equalTo("stores count"));

		assertThat(new LlmSuggestion("itemCount", List.of(), Double.NaN, "").confidence(), equalTo(0.0));
	}

	@Test
	public void parsesFencedOpenAiCompatibleResponseAndClampsConfidence() throws Exception {
		String body = """
				{"choices":[{"message":{"content":"```json\\n{\\"reasoning\\":\\"reads count\\",\\"alternatives\\":[],\\"suggestedName\\":\\"getCount\\",\\"confidence\\":2.4}\\n```"}}]}
				""";

		LlmSuggestion suggestion = OpenAiCompatibleClient.parseSuggestion(body);

		assertThat(suggestion.suggestedName(), equalTo("getCount"));
		assertThat(suggestion.confidence(), equalTo(1.0));
	}

	@Test
	public void parsesNumericStringOpenAiCompatibleConfidence() throws Exception {
		String body = """
				{"choices":[{"message":{"content":"{\\"reasoning\\":\\"reads count\\",\\"alternatives\\":[],\\"suggestedName\\":\\"getCount\\",\\"confidence\\":\\"0.91\\"}"}}]}
				""";

		LlmSuggestion suggestion = OpenAiCompatibleClient.parseSuggestion(body);

		assertThat(suggestion.confidence(), equalTo(0.91));
	}

	@Test
	public void treatsNullOpenAiCompatibleOptionalFieldsAsAbsent() throws Exception {
		String body = """
				{"choices":[{"message":{"content":"{\\"reasoning\\":null,\\"alternatives\\":null,\\"suggestedName\\":\\"getCount\\",\\"confidence\\":null}"}}]}
				""";

		LlmSuggestion suggestion = OpenAiCompatibleClient.parseSuggestion(body);

		assertThat(suggestion.suggestedName(), equalTo("getCount"));
		assertThat(suggestion.alternatives(), equalTo(List.of()));
		assertThat(suggestion.confidence(), equalTo(0.0));
		assertThat(suggestion.reasoning(), equalTo(""));
	}

	@Test
	public void parsesProseWrappedOpenAiCompatibleSuggestionJson() throws Exception {
		String body = """
				{"choices":[{"message":{"content":"Here is the JSON:\\n{\\"reasoning\\":\\"uses {count} text\\",\\"alternatives\\":[],\\"suggestedName\\":\\"getCount\\",\\"confidence\\":0.91}\\nHope this helps."}}]}
				""";

		LlmSuggestion suggestion = OpenAiCompatibleClient.parseSuggestion(body);

		assertThat(suggestion.suggestedName(), equalTo("getCount"));
		assertThat(suggestion.reasoning(), equalTo("uses {count} text"));
	}

	@Test
	public void reportsMalformedOpenAiCompatibleResponse() {
		try {
			OpenAiCompatibleClient.parseSuggestion("{\"choices\":[{\"message\":{\"content\":\"not json\"}}]}");
		} catch (IOException e) {
			assertThat(e.getMessage(), containsString("not valid OpenAI-compatible suggestion JSON"));
			return;
		}

		throw new AssertionError("Expected IOException");
	}

	@Test
	public void reportsWrongTypedOpenAiCompatibleSuggestionFields() {
		try {
			OpenAiCompatibleClient.parseSuggestion("""
					{"choices":[{"message":{"content":"{\\"reasoning\\":\\"bad\\",\\"alternatives\\":[],\\"suggestedName\\":\\"count\\",\\"confidence\\":\\"high\\"}"}}]}
					""");
		} catch (IOException e) {
			assertThat(e.getMessage(), containsString("not valid OpenAI-compatible suggestion JSON"));
			return;
		}

		throw new AssertionError("Expected IOException");
	}

	@Test
	public void reportsNonStringOpenAiCompatibleAlternatives() {
		try {
			OpenAiCompatibleClient.parseSuggestion("""
					{"choices":[{"message":{"content":"{\\"reasoning\\":\\"bad\\",\\"alternatives\\":[123],\\"suggestedName\\":\\"count\\",\\"confidence\\":0.5}"}}]}
					""");
		} catch (IOException e) {
			assertThat(e.getMessage(), containsString("alternatives must be strings"));
			return;
		}

		throw new AssertionError("Expected IOException");
	}

	@Test
	public void reportsNonArrayOpenAiCompatibleAlternatives() {
		try {
			OpenAiCompatibleClient.parseSuggestion("""
					{"choices":[{"message":{"content":"{\\"reasoning\\":\\"bad\\",\\"alternatives\\":{\\"one\\":\\"count\\"},\\"suggestedName\\":\\"count\\",\\"confidence\\":0.7}"}}]}
					""");
		} catch (IOException e) {
			assertThat(e.getMessage(), containsString("alternatives must be an array"));
			return;
		}

		throw new AssertionError("Expected IOException");
	}

	@Test
	public void reportsNonStringOpenAiCompatibleSuggestedName() {
		try {
			OpenAiCompatibleClient.parseSuggestion("""
					{"choices":[{"message":{"content":"{\\"reasoning\\":\\"bad\\",\\"alternatives\\":[],\\"suggestedName\\":123,\\"confidence\\":0.7}"}}]}
					""");
		} catch (IOException e) {
			assertThat(e.getMessage(), containsString("did not contain suggestedName"));
			return;
		}

		throw new AssertionError("Expected IOException");
	}

	@Test
	public void loadsOpenAiCompatibleConfigFromEnvironmentStyleSources() {
		Map<String, String> env = Map.of(
				"ENIGMA_LLM_BASE_URL", " http://127.0.0.1:1234/v1 ",
				"ENIGMA_LLM_MODEL", " local-model ",
				"ENIGMA_LLM_API_KEY", " secret ",
				"ENIGMA_LLM_TIMEOUT_SECONDS", " 7 ",
				"ENIGMA_LLM_AUTO_APPLY_THRESHOLD", " 0.86 ",
				"ENIGMA_LLM_BATCH_PARALLELISM", " 4 ",
				"ENIGMA_LLM_CONTEXT_BACKEND", " graph "
		);

		LlmConfig config = LlmConfig.load(env::get, key -> "");

		assertThat(config.baseUrl(), equalTo("http://127.0.0.1:1234/v1"));
		assertThat(config.model(), equalTo("local-model"));
		assertThat(config.apiKey(), equalTo("secret"));
		assertThat(config.timeout(), equalTo(Duration.ofSeconds(7)));
		assertTrue(config.batchPreselectThreshold().isPresent());
		assertThat(config.batchPreselectThreshold().getAsDouble(), equalTo(0.86));
		assertThat(config.batchParallelism(), equalTo(4));
		assertThat(config.contextBackend(), equalTo(LlmContextBackend.GRAPH));
		assertTrue(config.isConfigured());
	}

	@Test
	public void ignoresInvalidOpenAiCompatibleConfigNumbers() {
		Map<String, String> env = Map.of(
				"ENIGMA_LLM_MODEL", "local-model",
				"ENIGMA_LLM_TIMEOUT_SECONDS", "-3",
				"ENIGMA_LLM_AUTO_APPLY_THRESHOLD", "1.5",
				"ENIGMA_LLM_BATCH_PARALLELISM", "-1",
				"ENIGMA_LLM_CONTEXT_BACKEND", "wat"
		);

		LlmConfig config = LlmConfig.load(env::get, key -> "");

		assertThat(config.timeout(), equalTo(Duration.ofSeconds(120)));
		assertFalse(config.batchPreselectThreshold().isPresent());
		assertThat(config.batchParallelism(), equalTo(2));
		assertThat(config.contextBackend(), equalTo(LlmContextBackend.OWNER));

		Map<String, String> properties = Map.of(
				"enigma.llm.timeoutSeconds", "not-a-number",
				"enigma.llm.autoApplyThreshold", "-0.1",
				"enigma.llm.batchParallelism", "99"
		);
		config = LlmConfig.load(key -> "", properties::get);

		assertThat(config.timeout(), equalTo(Duration.ofSeconds(120)));
		assertFalse(config.batchPreselectThreshold().isPresent());
		assertThat(config.batchParallelism(), equalTo(8));
	}

	@Test
	public void callsOpenAiCompatibleEndpoint() throws Exception {
		try (FakeOpenAiServer server = FakeOpenAiServer.start()) {
			LlmConfig config = new LlmConfig(server.baseUrl(), "test-model", "", java.time.Duration.ofSeconds(5), java.util.OptionalDouble.empty());
			LlmSuggestion suggestion = new OpenAiCompatibleClient(config).suggestName(EntryKind.FIELD, "a", "Target field: a");

			assertThat(suggestion.suggestedName(), equalTo("itemCount"));
			assertThat(server.lastRequestPath, equalTo("/v1/chat/completions"));
			assertThat(server.lastProtocol, equalTo("HTTP/1.1"));
			assertThat(server.lastAccept, equalTo("application/json"));
			assertThat(server.lastRequestBody, containsString("\"model\":\"test-model\""));
			assertThat(server.lastRequestBody, containsString("Target field: a"));
			assertThat(server.lastRequestBody, containsString("net/minecraft/TextureManager"));
		}
	}

	@Test
	public void callsExplicitOpenAiCompatibleChatCompletionsEndpoint() throws Exception {
		try (FakeOpenAiServer server = FakeOpenAiServer.start()) {
			LlmConfig config = new LlmConfig(server.chatCompletionsUrl(), "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());

			new OpenAiCompatibleClient(config).suggestName(EntryKind.FIELD, "a", "Target field: a");

			assertThat(server.lastRequestPath, equalTo("/v1/chat/completions"));
		}
	}

	@Test
	public void sendsOpenAiCompatibleAuthorizationHeaderWhenConfigured() throws Exception {
		try (FakeOpenAiServer server = FakeOpenAiServer.start()) {
			LlmConfig config = new LlmConfig(server.baseUrl(), "test-model", "secret-token", Duration.ofSeconds(5), java.util.OptionalDouble.empty());

			new OpenAiCompatibleClient(config).suggestName(EntryKind.FIELD, "a", "Target field: a");

			assertThat(server.lastAuthorization, equalTo("Bearer secret-token"));
		}
	}

	@Test
	public void requestSuggestionUsesDisplayNameForParameters() throws Exception {
		try (FakeOpenAiServer server = FakeOpenAiServer.start()) {
			LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
			LlmGuiService service = new LlmGuiService(plugin);
			FakeProjectView project = new FakeProjectView();
			LlmConfig config = new LlmConfig(server.baseUrl(), "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());
			EntryKey key = new EntryKey(EntryKind.PARAMETER, "example/Foo", "a", "(I)V", 1, "p_1_");

			service.requestSuggestion(config, project, key);

			assertThat(server.lastRequestBody, containsString("Target Type: PARAMETER"));
			assertThat(server.lastRequestBody, containsString("Obfuscated Name: example/Foo.a(I)V arg 1 (p_1_)"));
		}
	}

	@Test
	public void requestSuggestionUsesConfiguredGraphContextBackend() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		ClassNode node = classNode("example/Foo");
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "a", "()V", null, null));
		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		plugin.setIndex(builder.build());

		List<String> prompts = new ArrayList<>();
		LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> {
			prompts.add(prompt);
			return new LlmSuggestion("run", List.of(), 0.9, "valid method");
		});
		LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty(), 1, LlmContextBackend.GRAPH);

		service.requestSuggestion(config, new FakeProjectView(), new EntryKey(EntryKind.METHOD, "example/Foo", "a", "()V"));

		assertThat(prompts.get(0), containsString("Context backend: graph"));
	}

	@Test
	public void requestSuggestionNormalizesQualifiedMemberNamesBeforeValidation() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> new LlmSuggestion("example/Foo.itemCount", List.of(), 0.9, "qualified field"));
		FakeProjectView project = new FakeProjectView();
		LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());
		EntryKey key = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");

		LlmSuggestion suggestion = service.requestSuggestion(config, project, key);

		assertThat(suggestion.suggestedName(), equalTo("itemCount"));
		assertThat(plugin.getSuggestions().get(key).map(LlmSuggestion::suggestedName), equalTo(Optional.of("itemCount")));
	}

	@Test
	public void requestSuggestionNormalizesMemberNamesToLowerCamelCase() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> new LlmSuggestion("Pi", List.of("Pi", "Math.PI", "PiValue", "cONSTANT_A"), 0.9, "math constant"));
		FakeProjectView project = new FakeProjectView();
		LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());
		EntryKey key = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "F");

		LlmSuggestion suggestion = service.requestSuggestion(config, project, key);

		assertThat(suggestion.suggestedName(), equalTo("pi"));
		assertThat(suggestion.alternatives(), equalTo(List.of("piValue")));
		assertThat(plugin.getSuggestions().get(key).map(LlmSuggestion::suggestedName), equalTo(Optional.of("pi")));
	}

	@Test
	public void requestSuggestionRetriesDuplicateMappedFieldNames() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		ClassNode node = classNode("example/Geometry");
		EntryKey piKey = new EntryKey(EntryKind.FIELD, "example/Geometry", "a", "F");
		EntryKey targetKey = new EntryKey(EntryKind.FIELD, "example/Geometry", "b", "F");
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "a", "F", null, Float.valueOf(3.1415927F)));
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "b", "F", null, Float.valueOf(57.29578F)));
		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(node);
		plugin.setIndex(builder.build());

		int[] calls = { 0 };
		List<String> prompts = new ArrayList<>();
		LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> {
			calls[0]++;
			prompts.add(prompt);
			return calls[0] == 1
					? new LlmSuggestion("Pi", List.of(), 0.8, "copied neighboring mapped name")
					: new LlmSuggestion("DegreesPerRadian", List.of(), 0.7, "180 divided by pi");
		});
		FakeProjectView project = new FakeProjectView(Map.of(piKey, "pi"));
		LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());

		LlmSuggestion suggestion = service.requestSuggestion(config, project, targetKey);

		assertThat(calls[0], equalTo(2));
		assertThat(prompts.get(1), containsString("Previous suggestion was rejected: Suggested name is already used by another FIELD in this context: pi"));
		assertThat(suggestion.suggestedName(), equalTo("degreesPerRadian"));
		assertThat(plugin.getSuggestions().get(targetKey).map(LlmSuggestion::suggestedName), equalTo(Optional.of("degreesPerRadian")));
	}

	@Test
	public void requestSuggestionRejectsUnchangedMemberNamesAfterNormalization() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> new LlmSuggestion("example/Foo.a", List.of(), 0.9, "unchanged qualified field"));
		FakeProjectView project = new FakeProjectView();
		LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());
		EntryKey key = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");

		try {
			service.requestSuggestion(config, project, key);
		} catch (RuntimeException e) {
			assertThat(e.getMessage(), containsString("Invalid Java identifier suggested for FIELD: a"));
			assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));
			return;
		}

		throw new AssertionError("Expected RuntimeException");
	}

	@Test
	public void requestSuggestionRetriesOwnerPrefixedMemberNames() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		EntryKey classKey = new EntryKey(EntryKind.CLASS, "example/Foo", "example/Foo", "");
		EntryKey fieldKey = new EntryKey(EntryKind.FIELD, "example/Foo", "b", "F");
		int[] calls = { 0 };
		List<String> prompts = new ArrayList<>();
		LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> {
			calls[0]++;
			prompts.add(prompt);
			return calls[0] == 1
					? new LlmSuggestion("GeometryConstantsB", List.of(), 0.8, "owner plus obfuscated suffix")
					: new LlmSuggestion("DegreesPerRadian", List.of(), 0.7, "180 divided by pi");
		});
		FakeProjectView project = new FakeProjectView(Map.of(classKey, "GeometryConstants"));
		LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());

		LlmSuggestion suggestion = service.requestSuggestion(config, project, fieldKey);

		assertThat(calls[0], equalTo(2));
		assertThat(prompts.get(1), containsString("Suggested name repeats the owner class name instead of describing the FIELD: geometryConstantsB"));
		assertThat(suggestion.suggestedName(), equalTo("degreesPerRadian"));
		assertThat(plugin.getSuggestions().get(fieldKey).map(LlmSuggestion::suggestedName), equalTo(Optional.of("degreesPerRadian")));
	}

	@Test
	public void requestSuggestionRestoresInterruptStatusWhenInterrupted() {
		Thread.interrupted();

		try {
			LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
			LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> {
				throw new InterruptedException("cancelled");
			});
			FakeProjectView project = new FakeProjectView();
			LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());
			EntryKey key = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");

			try {
				service.requestSuggestion(config, project, key);
			} catch (RuntimeException e) {
				assertTrue(e.getCause() instanceof InterruptedException);
				assertTrue(Thread.currentThread().isInterrupted());
				return;
			}

			throw new AssertionError("Expected RuntimeException");
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void requestSuggestionDoesNotCacheWhenProjectChangesBeforeCompletion() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		FakeProjectView originalProject = new FakeProjectView();
		FakeProjectView replacementProject = new FakeProjectView();
		EntryKey key = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");
		LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> {
			plugin.openProject(replacementProject);
			return new LlmSuggestion("itemCount", List.of(), 0.9, "valid but stale");
		});
		LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());
		plugin.openProject(originalProject);

		try {
			service.requestSuggestion(config, originalProject, key);
		} catch (RuntimeException e) {
			assertThat(e.getMessage(), containsString("Project changed before the LLM suggestion completed"));
			assertThat(plugin.getSuggestions().get(key), equalTo(Optional.empty()));
			return;
		}

		throw new AssertionError("Expected RuntimeException");
	}

	@Test
	public void requestBatchStopsWhenInterrupted() {
		Thread.interrupted();

		try {
			int[] calls = { 0 };
			LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
			LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> {
				calls[0]++;
				throw new InterruptedException("cancelled");
			});
			FakeProjectView project = new FakeProjectView();
			LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());
			List<EntryKey> targets = List.of(
					new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I"),
					new EntryKey(EntryKind.FIELD, "example/Foo", "b", "I")
			);

			try {
				service.requestBatch(config, project, targets);
			} catch (RuntimeException e) {
				assertTrue(e.getCause() instanceof InterruptedException);
				assertTrue(calls[0] >= 1);
				assertTrue(Thread.currentThread().isInterrupted());
				return;
			}

			throw new AssertionError("Expected RuntimeException");
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	public void requestBatchContinuesAfterPerTargetFailures() {
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> {
			if (targetName.contains(".a")) {
				return new LlmSuggestion("class", List.of(), 0.95, "invalid keyword");
			}

			return new LlmSuggestion("itemCount", List.of("count"), 0.91, "valid field name");
		});
		FakeProjectView project = new FakeProjectView();
		LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.of(0.9));
		EntryKey invalid = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");
		EntryKey valid = new EntryKey(EntryKind.FIELD, "example/Foo", "b", "I");
		plugin.getSuggestions().put(invalid, new LlmSuggestion("oldCount", List.of(), 0.6, "stale cached suggestion"));

		BatchSuggestionResult result = service.requestBatch(config, project, List.of(invalid, valid));

		assertThat(result.failures().size(), equalTo(1));
		assertThat(result.failures().get(0), containsString("Invalid Java identifier suggested for FIELD: class"));
		assertThat(result.suggestions().size(), equalTo(1));
		assertThat(result.suggestions().get(0).key(), equalTo(valid));
		assertThat(result.suggestions().get(0).suggestion().suggestedName(), equalTo("itemCount"));
		assertTrue(result.suggestions().get(0).selected());
		assertThat(plugin.getSuggestions().get(invalid), equalTo(Optional.empty()));
		assertThat(plugin.getSuggestions().get(valid).map(LlmSuggestion::suggestedName), equalTo(Optional.of("itemCount")));
	}

	@Test
	public void requestBatchUsesConfiguredParallelismAndKeepsTargetOrder() {
		CountDownLatch entered = new CountDownLatch(2);
		LlmNameProposalPlugin plugin = new LlmNameProposalPlugin();
		LlmGuiService service = new LlmGuiService(plugin, (config, kind, targetName, prompt) -> {
			entered.countDown();

			if (!entered.await(1, TimeUnit.SECONDS)) {
				throw new AssertionError("batch request did not run in parallel");
			}

			return targetName.contains(".a")
					? new LlmSuggestion("alpha", List.of(), 0.8, "first")
					: new LlmSuggestion("beta", List.of(), 0.7, "second");
		});
		FakeProjectView project = new FakeProjectView();
		LlmConfig config = new LlmConfig("http://localhost:1/v1", "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty(), 2);
		EntryKey first = new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I");
		EntryKey second = new EntryKey(EntryKind.FIELD, "example/Foo", "b", "I");

		BatchSuggestionResult result = service.requestBatch(config, project, List.of(first, second));

		assertThat(result.failures().size(), equalTo(0));
		assertThat(result.suggestions().get(0).key(), equalTo(first));
		assertThat(result.suggestions().get(0).suggestion().suggestedName(), equalTo("alpha"));
		assertThat(result.suggestions().get(1).key(), equalTo(second));
		assertThat(result.suggestions().get(1).suggestion().suggestedName(), equalTo("beta"));
	}

	@Test
	public void reportsOpenAiCompatibleHttpFailures() throws Exception {
		try (FakeOpenAiServer server = FakeOpenAiServer.startFailure()) {
			LlmConfig config = new LlmConfig(server.baseUrl(), "test-model", "", Duration.ofSeconds(5), java.util.OptionalDouble.empty());

			try {
				new OpenAiCompatibleClient(config).suggestName(EntryKind.FIELD, "a", "Target field: a");
			} catch (IOException e) {
				assertThat(e.getMessage(), containsString("HTTP 500"));
				assertThat(e.getMessage(), containsString("model unavailable"));
				return;
			}
		}

		throw new AssertionError("Expected IOException");
	}

	@Test
	public void timesOutOpenAiCompatibleEndpoint() throws Exception {
		try (FakeOpenAiServer server = FakeOpenAiServer.startSlow()) {
			LlmConfig config = new LlmConfig(server.baseUrl(), "test-model", "", Duration.ofMillis(50), java.util.OptionalDouble.empty());

			try {
				new OpenAiCompatibleClient(config).suggestName(EntryKind.FIELD, "a", "Target field: a");
			} catch (HttpTimeoutException e) {
				return;
			}
		}

		throw new AssertionError("Expected HttpTimeoutException");
	}

	@Test
	public void validatesJavaIdentifiersByKind() {
		LlmNameValidator validator = new LlmNameValidator();

		assertTrue(validator.isValid(EntryKind.CLASS, "TextureManager"));
		assertTrue(validator.isValid(EntryKind.CLASS, "net/minecraft/TextureManager"));
		assertTrue(validator.isValid(EntryKind.METHOD, "renderBlock"));
		assertTrue(validator.isValid(EntryKind.FIELD, "radiansPerDegree"));
		assertTrue(validator.isValid(EntryKind.PARAMETER, "itemCount"));
		assertFalse(validator.isValid(EntryKind.CLASS, "textureManager"));
		assertFalse(validator.isValid(EntryKind.CLASS, "net/minecraft/textureManager"));
		assertFalse(validator.isValid(EntryKind.CLASS, "net/minecraft/TextureManager/"));
		assertFalse(validator.isValid(EntryKind.CLASS, "net//minecraft/TextureManager"));
		assertFalse(validator.isValid(EntryKind.CLASS, "net/minecraft/Texture$Manager"));
		assertFalse(validator.isValid(EntryKind.FIELD, "class"));
		assertFalse(validator.isValid(EntryKind.FIELD, "cONSTANT_A"));
		assertFalse(validator.isValid(EntryKind.FIELD, "constant_a"));
		assertFalse(validator.isValid(EntryKind.FIELD, "constantAB"));
		assertFalse(validator.isValid(EntryKind.FIELD, "net/minecraft/itemCount"));
		assertFalse(validator.isValid(EntryKind.METHOD, "render-block"));
	}

	@Test
	public void validatesClassIdentifiersWithEntryContext() {
		LlmNameValidator validator = new LlmNameValidator();

		assertTrue(validator.isValid(new EntryKey(EntryKind.CLASS, "example/Foo", "example/Foo", ""), "net/minecraft/TextureManager"));
		assertTrue(validator.isValid(new EntryKey(EntryKind.CLASS, "example/Foo$Bar", "example/Foo$Bar", ""), "Part"));
		assertFalse(validator.isValid(new EntryKey(EntryKind.CLASS, "example/Foo$Bar", "example/Foo$Bar", ""), "net/minecraft/Part"));
		assertFalse(validator.isValid(new EntryKey(EntryKind.CLASS, "example/Foo", "example/Foo", ""), "Foo"));
		assertFalse(validator.isValid(new EntryKey(EntryKind.FIELD, "example/Foo", "a", "I"), "a"));
		assertFalse(validator.isValid(new EntryKey(EntryKind.PARAMETER, "example/Foo", "b", "(I)V", 1, "p_1_"), "p_1_"));
	}

	private static class FakePluginContext implements EnigmaPluginContext {
		private final Map<EnigmaServiceType<?>, List<String>> ids = new HashMap<>();

		@Override
		public <T extends EnigmaService> void registerService(String id, EnigmaServiceType<T> serviceType, EnigmaServiceFactory<T> factory, Ordering... ordering) {
			this.ids.computeIfAbsent(serviceType, ignored -> new ArrayList<>()).add(id);
			assertThat(factory.create(), not(nullValue()));
		}

		@Override
		public void disableService(String id, EnigmaServiceType<?> serviceType) {
			throw new UnsupportedOperationException();
		}

		boolean has(EnigmaServiceType<?> type, String id) {
			return this.ids.getOrDefault(type, List.of()).contains(id);
		}
	}

	private static class FakeProjectView implements cuchaz.enigma.api.view.ProjectView {
		private final Map<EntryKey, String> mappedNames;
		private final List<cuchaz.enigma.api.DataInvalidationListener> listeners = new ArrayList<>();
		private int invalidations;

		FakeProjectView() {
			this(Map.of());
		}

		FakeProjectView(Map<EntryKey, String> mappedNames) {
			this.mappedNames = Map.copyOf(mappedNames);
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T extends cuchaz.enigma.api.view.entry.EntryView> T deobfuscate(T entry) {
			Optional<EntryKey> key = EntryKey.fromEntryView(entry);

			if (key.isPresent() && this.mappedNames.containsKey(key.get())) {
				String mappedName = this.mappedNames.get(key.get());

				return (T) switch (key.get().kind()) {
				case CLASS -> ClassEntryView.create(mappedName);
				case FIELD -> {
					FieldEntryView field = (FieldEntryView) entry;
					yield FieldEntryView.create(field.getParent().getFullName(), mappedName, field.getDescriptor());
				}
				case METHOD -> {
					MethodEntryView method = (MethodEntryView) entry;
					yield MethodEntryView.create(method.getParent().getFullName(), mappedName, method.getDescriptor());
				}
				case PARAMETER -> {
					LocalVariableEntryView local = (LocalVariableEntryView) entry;
					MethodEntryView method = local.getParent();
					yield LocalVariableEntryView.create(MethodEntryView.create(method.getParent().getFullName(), method.getName(), method.getDescriptor()), local.getIndex(), mappedName, local.isArgument());
				}
				};
			}

			return entry;
		}

		@Override
		public <T extends cuchaz.enigma.api.view.entry.EntryView> T obfuscate(T entry) {
			return entry;
		}

		@Override
		public void registerForInverseMappings() {
		}

		@Override
		public cuchaz.enigma.api.view.index.JarIndexView getJarIndex() {
			throw new UnsupportedOperationException();
		}

		@Override
		public java.util.Collection<String> getProjectClasses() {
			return List.of();
		}

		@Override
		public java.util.Collection<String> getProjectAndLibraryClasses() {
			return List.of();
		}

		@Override
		public ClassNode getBytecode(String className) {
			return null;
		}

		@Override
		public void addDataInvalidationListener(cuchaz.enigma.api.DataInvalidationListener listener) {
			this.listeners.add(listener);
		}

		@Override
		public void invalidateData(java.util.Collection<String> classes, cuchaz.enigma.api.DataInvalidationEvent.InvalidationType type) {
			this.invalidations++;
			cuchaz.enigma.api.DataInvalidationEvent event = new cuchaz.enigma.api.DataInvalidationEvent() {
				@Override
				public java.util.Collection<String> getClasses() {
					return classes;
				}

				@Override
				public cuchaz.enigma.api.DataInvalidationEvent.InvalidationType getType() {
					return type;
				}
			};
			this.listeners.forEach(listener -> listener.onDataInvalidated(event));
		}
	}

	private static ClassNode classNode(String name) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = name;
		node.superName = "java/lang/Object";
		return node;
	}

	private static class FakeClassProvider implements ClassProvider {
		private final Map<String, ClassNode> nodes;

		FakeClassProvider(Map<String, ClassNode> nodes) {
			this.nodes = Map.copyOf(nodes);
		}

		@Override
		public Collection<String> getClassNames() {
			return this.nodes.keySet();
		}

		@Override
		public ClassNode get(String name) {
			return this.nodes.get(name);
		}
	}

	private static class FakeOpenAiServer implements AutoCloseable {
		private final HttpServer server;
		private String lastRequestBody = "";
		private String lastRequestPath = "";
		private String lastAuthorization = "";
		private String lastAccept = "";
		private String lastProtocol = "";

		private FakeOpenAiServer(HttpServer server) {
			this.server = server;
		}

		static FakeOpenAiServer start() throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			FakeOpenAiServer fake = new FakeOpenAiServer(server);
			server.createContext("/v1/chat/completions", exchange -> {
				fake.lastRequestPath = exchange.getRequestURI().getPath();
				fake.lastProtocol = exchange.getProtocol();
				fake.lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
				fake.lastAccept = exchange.getRequestHeaders().getFirst("Accept");
				fake.lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				byte[] response = """
						{"choices":[{"message":{"content":"{\\"reasoning\\":\\"stores item count\\",\\"alternatives\\":[\\"count\\"],\\"suggestedName\\":\\"itemCount\\",\\"confidence\\":0.77}"}}]}
						""".getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, response.length);

				try (OutputStream output = exchange.getResponseBody()) {
					output.write(response);
				}
			});
			server.start();
			return fake;
		}

		static FakeOpenAiServer startFailure() throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			FakeOpenAiServer fake = new FakeOpenAiServer(server);
			server.createContext("/v1/chat/completions", exchange -> {
				fake.lastRequestPath = exchange.getRequestURI().getPath();
				fake.lastProtocol = exchange.getProtocol();
				fake.lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
				fake.lastAccept = exchange.getRequestHeaders().getFirst("Accept");
				fake.lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				byte[] response = "model unavailable".getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(500, response.length);

				try (OutputStream output = exchange.getResponseBody()) {
					output.write(response);
				}
			});
			server.start();
			return fake;
		}

		static FakeOpenAiServer startSlow() throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			FakeOpenAiServer fake = new FakeOpenAiServer(server);
			server.createContext("/v1/chat/completions", exchange -> {
				fake.lastRequestPath = exchange.getRequestURI().getPath();
				fake.lastProtocol = exchange.getProtocol();
				fake.lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
				fake.lastAccept = exchange.getRequestHeaders().getFirst("Accept");
				fake.lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				byte[] response = """
						{"choices":[{"message":{"content":"{\\"reasoning\\":\\"slow\\",\\"alternatives\\":[],\\"suggestedName\\":\\"itemCount\\",\\"confidence\\":0.77}"}}]}
						""".getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, response.length);

				try (OutputStream output = exchange.getResponseBody()) {
					output.write(response);
				}
			});
			server.start();
			return fake;
		}

		String baseUrl() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1";
		}

		String chatCompletionsUrl() {
			return baseUrl() + "/chat/completions";
		}

		@Override
		public void close() {
			this.server.stop(0);
		}
	}
}
