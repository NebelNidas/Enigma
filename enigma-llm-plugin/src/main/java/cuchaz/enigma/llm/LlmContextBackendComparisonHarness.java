package cuchaz.enigma.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import cuchaz.enigma.api.DataInvalidationEvent;
import cuchaz.enigma.api.DataInvalidationListener;
import cuchaz.enigma.api.view.ProjectView;
import cuchaz.enigma.api.view.entry.ClassEntryView;
import cuchaz.enigma.api.view.entry.EntryView;
import cuchaz.enigma.api.view.entry.FieldEntryView;
import cuchaz.enigma.api.view.entry.LocalVariableEntryView;
import cuchaz.enigma.api.view.entry.MethodEntryView;
import cuchaz.enigma.api.view.index.JarIndexView;

public final class LlmContextBackendComparisonHarness {
	private static final Gson GSON = new Gson();
	private static final List<LlmContextBackend> EXPLICIT_BACKENDS = List.of(LlmContextBackend.OWNER, LlmContextBackend.GRAPH);

	private LlmContextBackendComparisonHarness() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("Usage: LlmContextBackendComparisonHarness <results.jsonl>");
			System.exit(2);
		}

		LlmConfig config = LlmConfig.load();

		if (!config.isConfigured()) {
			throw new IllegalStateException("Set ENIGMA_LLM_MODEL before running backend comparison");
		}

		List<ComparisonResult> results = run(config, Path.of(args[0]));
		printSummary(results);
	}

	static List<ComparisonResult> run(LlmConfig config, Path resultsPath) throws IOException, InterruptedException {
		ComparisonFixture fixture = comparisonFixture();
		OpenAiCompatibleClient client = new OpenAiCompatibleClient(config);
		LlmPromptBuilder promptBuilder = new LlmPromptBuilder();
		StringBuilder jsonl = new StringBuilder();
		java.util.ArrayList<ComparisonResult> results = new java.util.ArrayList<>();

		Files.createDirectories(resultsPath.toAbsolutePath().getParent());

		for (ComparisonCase testCase : fixture.cases()) {
			for (LlmContextBackend backend : EXPLICIT_BACKENDS) {
				String prompt = promptBuilder.build(testCase.key(), new EvaluationProjectView(testCase.mappings()), fixture.index(), backend);
				long startNanos = System.nanoTime();
				ComparisonResult result;

				try {
					LlmSuggestion suggestion = client.suggestName(testCase.key().kind(), testCase.key().displayName(), prompt);
					result = ComparisonResult.success(config.model(), backend, testCase, suggestion, Duration.ofNanos(System.nanoTime() - startNanos), prompt.length());
				} catch (IOException | RuntimeException e) {
					result = ComparisonResult.failure(config.model(), backend, testCase, e, Duration.ofNanos(System.nanoTime() - startNanos), prompt.length());
				}

				results.add(result);
				jsonl.append(result.toJson()).append('\n');
			}
		}

		Files.writeString(resultsPath, jsonl.toString(), StandardCharsets.UTF_8);
		return List.copyOf(results);
	}

	private static void printSummary(List<ComparisonResult> results) {
		for (LlmContextBackend backend : EXPLICIT_BACKENDS) {
			List<ComparisonResult> backendResults = results.stream()
					.filter(result -> result.backend == backend)
					.toList();
			long accepted = backendResults.stream().filter(result -> result.accepted).count();
			long exact = backendResults.stream().filter(result -> result.exact).count();
			long usable = backendResults.stream().filter(result -> result.usable).count();
			long failed = backendResults.stream().filter(result -> !result.accepted).count();
			double averageLatency = backendResults.stream().mapToLong(result -> result.latencyMillis).average().orElse(0.0);

			System.out.printf(Locale.ROOT, "%s backend: total=%d accepted=%d exact=%d usable=%d failed=%d avgLatencyMs=%.1f%n",
					backend.name().toLowerCase(Locale.ROOT), backendResults.size(), accepted, exact, usable, failed, averageLatency);
		}
	}

	static ComparisonFixture comparisonFixture() {
		ClassNode counter = classNode("example/Counter");
		counter.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "a", "I", null, null));
		MethodNode getCount = method(Opcodes.ACC_PUBLIC, "a", "()I");
		getCount.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "example/Counter", "a", "I"));
		counter.methods.add(getCount);
		MethodNode setCount = method(Opcodes.ACC_PUBLIC, "b", "(I)V");
		setCount.localVariables.add(new LocalVariableNode("this", "Lexample/Counter;", null, null, null, 0));
		setCount.localVariables.add(new LocalVariableNode("p_1_", "I", null, null, null, 1));
		setCount.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, "example/Counter", "a", "I"));
		counter.methods.add(setCount);

		ClassNode hud = classNode("example/HudRenderer");
		MethodNode renderCount = method(Opcodes.ACC_PUBLIC, "c", "(Lexample/Counter;)V");
		renderCount.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "example/Counter", "a", "()I", false));
		hud.methods.add(renderCount);

		ClassNode itemStack = classNode("example/ItemStack");
		itemStack.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "a", "Lexample/Item;", null, null));
		itemStack.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "b", "I", null, null));
		itemStack.methods.add(method(Opcodes.ACC_PUBLIC, "a", "()Lexample/Item;"));
		MethodNode itemGetCount = method(Opcodes.ACC_PUBLIC, "b", "()I");
		itemGetCount.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "example/ItemStack", "b", "I"));
		itemStack.methods.add(itemGetCount);
		MethodNode itemSetCount = method(Opcodes.ACC_PUBLIC, "c", "(I)V");
		itemSetCount.localVariables.add(new LocalVariableNode("this", "Lexample/ItemStack;", null, null, null, 0));
		itemSetCount.localVariables.add(new LocalVariableNode("p_1_", "I", null, null, null, 1));
		itemSetCount.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, "example/ItemStack", "b", "I"));
		itemStack.methods.add(itemSetCount);

		ClassNode tooltip = classNode("example/TooltipRenderer");
		MethodNode renderStackCount = method(Opcodes.ACC_PUBLIC, "d", "(Lexample/ItemStack;)V");
		renderStackCount.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "example/ItemStack", "b", "()I", false));
		tooltip.methods.add(renderStackCount);

		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();
		builder.accept(counter);
		builder.accept(hud);
		builder.accept(itemStack);
		builder.accept(tooltip);
		LlmProjectIndex index = builder.build();

		EntryKey counterField = new EntryKey(EntryKind.FIELD, "example/Counter", "a", "I");
		EntryKey counterGetter = new EntryKey(EntryKind.METHOD, "example/Counter", "a", "()I");
		EntryKey counterSetter = new EntryKey(EntryKind.METHOD, "example/Counter", "b", "(I)V");
		EntryKey counterSetterParam = new EntryKey(EntryKind.PARAMETER, "example/Counter", "b", "(I)V", 1, "p_1_");
		EntryKey renderCountMethod = new EntryKey(EntryKind.METHOD, "example/HudRenderer", "c", "(Lexample/Counter;)V");
		EntryKey itemCountField = new EntryKey(EntryKind.FIELD, "example/ItemStack", "b", "I");
		EntryKey itemGetter = new EntryKey(EntryKind.METHOD, "example/ItemStack", "b", "()I");
		EntryKey itemSetter = new EntryKey(EntryKind.METHOD, "example/ItemStack", "c", "(I)V");
		EntryKey itemSetterParam = new EntryKey(EntryKind.PARAMETER, "example/ItemStack", "c", "(I)V", 1, "p_1_");
		EntryKey tooltipMethod = new EntryKey(EntryKind.METHOD, "example/TooltipRenderer", "d", "(Lexample/ItemStack;)V");

		List<ComparisonCase> cases = List.of(
				new ComparisonCase("counter-getter-from-caller", counterGetter, "getCount", Set.of("getCount", "count"), Map.of(counterField, "count", counterSetter, "setCount", renderCountMethod, "renderCount")),
				new ComparisonCase("counter-field-from-accessors", counterField, "count", Set.of("count"), Map.of(counterGetter, "getCount", counterSetter, "setCount", renderCountMethod, "renderCount")),
				new ComparisonCase("counter-setter-parameter", counterSetterParam, "count", Set.of("count", "newCount"), Map.of(counterField, "count", counterSetter, "setCount")),
				new ComparisonCase("item-count-getter-from-tooltip", itemGetter, "getCount", Set.of("getCount", "count"), Map.of(itemCountField, "count", itemSetter, "setCount", tooltipMethod, "renderStackCount")),
				new ComparisonCase("item-count-field-from-accessors", itemCountField, "count", Set.of("count", "stackSize", "itemCount"), Map.of(itemGetter, "getCount", itemSetter, "setCount", tooltipMethod, "renderStackCount")),
				new ComparisonCase("item-setter-parameter", itemSetterParam, "count", Set.of("count", "newCount"), Map.of(itemCountField, "count", itemSetter, "setCount"))
		);

		return new ComparisonFixture(index, cases);
	}

	private static ClassNode classNode(String name) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = name;
		node.superName = "java/lang/Object";
		return node;
	}

	private static MethodNode method(int access, String name, String descriptor) {
		MethodNode method = new MethodNode(access, name, descriptor, null, null);
		method.instructions = new InsnList();
		return method;
	}

	record ComparisonFixture(LlmProjectIndex index, List<ComparisonCase> cases) {
	}

	record ComparisonCase(String id, EntryKey key, String expected, Set<String> acceptable, Map<EntryKey, String> mappings) {
	}

	record ComparisonResult(String model, LlmContextBackend backend, String id, EntryKind kind, String targetName, String expected, String suggestedName, List<String> alternatives, double confidence, String reasoning, boolean accepted, boolean exact, boolean usable, long latencyMillis, int promptChars, String errorCategory, String error) {
		static ComparisonResult success(String model, LlmContextBackend backend, ComparisonCase testCase, LlmSuggestion suggestion, Duration latency, int promptChars) {
			boolean exact = testCase.expected.equals(suggestion.suggestedName());
			boolean usable = testCase.acceptable.contains(suggestion.suggestedName()) || suggestion.alternatives().stream().anyMatch(testCase.acceptable::contains);
			return new ComparisonResult(model, backend, testCase.id, testCase.key.kind(), testCase.key.displayName(), testCase.expected, suggestion.suggestedName(), suggestion.alternatives(), suggestion.confidence(), suggestion.reasoning(), true, exact, usable, latency.toMillis(), promptChars, "", "");
		}

		static ComparisonResult failure(String model, LlmContextBackend backend, ComparisonCase testCase, Exception error, Duration latency, int promptChars) {
			String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
			return new ComparisonResult(model, backend, testCase.id, testCase.key.kind(), testCase.key.displayName(), testCase.expected, "", List.of(), 0.0, "", false, false, false, latency.toMillis(), promptChars, LlmEvaluationHarness.errorCategory(error), message);
		}

		String toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("model", this.model);
			json.addProperty("backend", this.backend.name().toLowerCase(Locale.ROOT));
			json.addProperty("id", this.id);
			json.addProperty("kind", this.kind.name());
			json.addProperty("targetName", this.targetName);
			json.addProperty("expected", this.expected);
			json.addProperty("suggestedName", this.suggestedName);
			JsonArray alternativesJson = new JsonArray();
			this.alternatives.forEach(alternativesJson::add);
			json.add("alternatives", alternativesJson);
			json.addProperty("confidence", this.confidence);
			json.addProperty("reasoning", this.reasoning);
			json.addProperty("accepted", this.accepted);
			json.addProperty("exact", this.exact);
			json.addProperty("usable", this.usable);
			json.addProperty("latencyMillis", this.latencyMillis);
			json.addProperty("promptChars", this.promptChars);
			json.addProperty("errorCategory", this.errorCategory);
			json.addProperty("error", this.error);
			return GSON.toJson(json);
		}
	}

	static final class EvaluationProjectView implements ProjectView {
		private final Map<EntryKey, String> mappedNames;

		EvaluationProjectView(Map<EntryKey, String> mappedNames) {
			this.mappedNames = Map.copyOf(mappedNames);
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T extends EntryView> T deobfuscate(T entry) {
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
		public <T extends EntryView> T obfuscate(T entry) {
			return entry;
		}

		@Override
		public void registerForInverseMappings() {
		}

		@Override
		public JarIndexView getJarIndex() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Collection<String> getProjectClasses() {
			return List.of();
		}

		@Override
		public Collection<String> getProjectAndLibraryClasses() {
			return List.of();
		}

		@Override
		public @Nullable ClassNode getBytecode(String className) {
			return null;
		}

		@Override
		public void addDataInvalidationListener(DataInvalidationListener listener) {
		}

		@Override
		public void invalidateData(@Nullable Collection<String> classes, DataInvalidationEvent.InvalidationType type) {
		}
	}
}
