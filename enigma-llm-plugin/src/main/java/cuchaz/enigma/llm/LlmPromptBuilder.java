package cuchaz.enigma.llm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import cuchaz.enigma.api.view.ProjectView;
import cuchaz.enigma.api.view.entry.EntryView;

class LlmPromptBuilder {
	private static final int MAX_PROMPT_CHARS = 8000;
	private static final String RESPONSE_INSTRUCTIONS_MARKER = "\nRespond with JSON only:\n";

	String build(EntryKey key, ProjectView project, LlmProjectIndex index, LlmContextBackend backend) {
		return build(key, project, index, backend, Set.of());
	}

	String build(EntryKey key, ProjectView project, LlmProjectIndex index, LlmContextBackend backend,
			Set<LlmAnalysisHint> analysisHints) {
		return build(key, project, index, backend, analysisHints, List.of());
	}

	String build(EntryKey key, ProjectView project, LlmProjectIndex index, LlmContextBackend backend,
			Set<LlmAnalysisHint> analysisHints, List<BatchSuggestion> batchSuggestions) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("Target kind: ").append(key.kind()).append('\n');
		prompt.append("Target obfuscated name: ").append(key.displayName()).append('\n');
		appendTargetDetails(prompt, key, index);

		appendMapping(prompt, project, "Target mapped name", key);
		appendUnavailableNames(prompt, project, index, key);

		index.entry(key).ifPresent(entry -> prompt.append("Target access: ").append(LlmProjectIndex.accessText(entry.access())).append('\n'));
		appendAnalysisHints(prompt, index, key, analysisHints);
		appendBatchSuggestions(prompt, key, batchSuggestions);
		appendContext(prompt, project, index, key, backend);

		prompt.append("\nRespond with JSON only:\n");
		prompt.append("{\"reasoning\":\"short explanation\",\"alternatives\":[\"NameA\",\"NameB\"],\"suggestedName\":\"BestName\",\"confidence\":0.0}\n");
		prompt.append("Names marked as mapped in the context are already assigned; do not reuse an existing mapped member name for a different member.\n");
		prompt.append("When batch suggestions are shown, keep vocabulary, abbreviation style, unit order, and inverse pairs consistent with the other provisional suggestions unless this target clearly needs a different name.\n");
		return truncateForContext(prompt.toString());
	}

	String build(EntryKey key, ProjectView project, LlmProjectIndex index) {
		return build(key, project, index, LlmContextBackend.OWNER);
	}

	private static void appendContext(StringBuilder prompt, ProjectView project, LlmProjectIndex index, EntryKey key, LlmContextBackend backend) {
		LlmContextBackend resolvedBackend = resolveBackend(key, index, backend);

		if (backend == LlmContextBackend.AUTO) {
			prompt.append("\nContext backend: auto selected ")
					.append(resolvedBackend == LlmContextBackend.GRAPH ? "graph" : "simple")
					.append(" context\n");
		}

		if (resolvedBackend == LlmContextBackend.GRAPH) {
			new LlmGraphContextBuilder().appendContext(prompt, project, index, key);
		} else {
			index.ownerClass(key).ifPresent(owner -> appendOwner(prompt, project, owner));
		}
	}

	static LlmContextBackend resolveBackend(EntryKey key, LlmProjectIndex index, LlmContextBackend backend) {
		if (backend != LlmContextBackend.AUTO) {
			return backend;
		}

		return autoBackend(key, index);
	}

	static String truncateForContext(String prompt) {
		if (prompt.length() <= MAX_PROMPT_CHARS) {
			return prompt;
		}

		int markerIndex = prompt.indexOf(RESPONSE_INSTRUCTIONS_MARKER);

		if (markerIndex < 0) {
			return prompt.substring(0, MAX_PROMPT_CHARS)
					+ "\n\n[Context truncated to fit the configured local model context window.]\n";
		}

		String suffix = prompt.substring(markerIndex);
		String notice = "\n[Context truncated to fit the configured local model context window.]\n";
		int prefixBudget = Math.max(0, MAX_PROMPT_CHARS - suffix.length() - notice.length());
		return prompt.substring(0, Math.min(prefixBudget, markerIndex)) + notice + suffix;
	}

	private static LlmContextBackend autoBackend(EntryKey key, LlmProjectIndex index) {
		return switch (key.kind()) {
		case PARAMETER -> LlmContextBackend.GRAPH;
		case METHOD -> index.entry(key)
				.filter(IndexedMethod.class::isInstance)
				.map(IndexedMethod.class::cast)
				.filter(method -> !index.callersOf(method).isEmpty() || !index.referencedClasses(method).isEmpty())
				.map(_method -> LlmContextBackend.GRAPH)
				.orElse(LlmContextBackend.OWNER);
		case FIELD -> index.entry(key)
				.filter(IndexedField.class::isInstance)
				.map(IndexedField.class::cast)
				.map(field -> {
					if (isStaticFinal(field.access())) {
						return LlmContextBackend.OWNER;
					}

					return index.fieldAccessorsOf(field).isEmpty() ? LlmContextBackend.OWNER : LlmContextBackend.GRAPH;
				})
				.orElse(LlmContextBackend.OWNER);
		case CLASS -> index.ownerClass(key)
				.map(clazz -> {
					if (looksLikeConstantsClass(clazz)) {
						return LlmContextBackend.OWNER;
					}

					boolean compactInterface = (clazz.access() & Opcodes.ACC_INTERFACE) != 0
							&& clazz.fields().isEmpty()
							&& nonConstructorMethods(clazz).size() <= 6;
					boolean hasExternalReferences = !index.methodsReferencingClass(clazz).isEmpty()
							|| !index.fieldsReferencingClass(clazz).isEmpty();

					return compactInterface && hasExternalReferences ? LlmContextBackend.GRAPH : LlmContextBackend.OWNER;
				})
				.orElse(LlmContextBackend.OWNER);
		};
	}

	private static void appendTargetDetails(StringBuilder prompt, EntryKey key, LlmProjectIndex index) {
		switch (key.kind()) {
		case CLASS -> {
			prompt.append("Target class: ").append(key.owner()).append('\n');

			if (key.owner().contains("$")) {
				prompt.append("Target naming rule: return only the simple inner class name without package separators.\n");
			} else {
				prompt.append("Target naming rule: return a simple class name or a JVM internal name with package separators.\n");
			}

			prompt.append("Class names must describe the actual role shown by members. If the class mainly contains constants, prefer a conservative constants-style name such as AngleConstants or MathConstants. Do not infer rendering, texture, shader, model, registry, or manager roles unless members prove them.\n");
		}
		case FIELD -> {
			prompt.append("Target owner: ").append(key.owner()).append('\n');
			prompt.append("Target name: ").append(key.name()).append('\n');
			prompt.append("Target descriptor: ").append(key.descriptor()).append('\n');
			index.entry(key).filter(IndexedField.class::isInstance).map(IndexedField.class::cast)
					.flatMap(IndexedField::constantValue)
					.ifPresent(value -> prompt.append("Target constant value: ").append(value).append('\n'));

			if (isStaticFinalField(key, index)) {
				prompt.append("Target naming rule: return one UPPER_SNAKE_CASE constant field name without package separators.\n");
				prompt.append("For numeric constants, prefer established mathematical or programming constant names when the value clearly matches one, such as PI or EPSILON, instead of generic descriptions such as SMALL_NUMBER or CONSTANT_VALUE.\n");
			} else {
				prompt.append("Target naming rule: return one lower camelCase field name without package separators.\n");
			}

			prompt.append("Return only the bare field identifier. Do not include the owner class, a dot, a slash, or the unchanged obfuscated name.\n");
		}
		case METHOD -> {
			prompt.append("Target owner: ").append(key.owner()).append('\n');
			prompt.append("Target name: ").append(key.name()).append('\n');
			prompt.append("Target descriptor: ").append(key.descriptor()).append('\n');
			prompt.append("Target naming rule: return one lower camelCase method name without package separators.\n");
			prompt.append("Return only the bare method identifier. Do not include the owner class, a dot, a slash, descriptor text, or the unchanged obfuscated name.\n");
		}
		case PARAMETER -> {
			prompt.append("Target owner: ").append(key.owner()).append('\n');
			prompt.append("Target method: ").append(key.name()).append(key.descriptor()).append('\n');
			prompt.append("Target local slot: ").append(key.localIndex()).append('\n');
			prompt.append("Target local name: ").append(key.localName().isBlank() ? "(unknown)" : key.localName()).append('\n');
			prompt.append("Target naming rule: return one lower camelCase parameter name without package separators.\n");
			prompt.append("Return only the bare parameter identifier. Do not include the owner class, method name, a dot, a slash, descriptor text, or the unchanged obfuscated local name.\n");
			index.entry(key).filter(IndexedParameter.class::isInstance).map(IndexedParameter.class::cast)
					.ifPresent(parameter -> prompt.append("Target parameter descriptor: ").append(parameter.descriptor()).append('\n'));
		}
		}
	}

	private static void appendAnalysisHints(StringBuilder prompt, LlmProjectIndex index, EntryKey key,
			Set<LlmAnalysisHint> analysisHints) {
		if (analysisHints == null || analysisHints.isEmpty()) {
			return;
		}

		StringBuilder hints = new StringBuilder();
		index.ownerClass(key).ifPresent(owner -> {
			if (analysisHints.contains(LlmAnalysisHint.FUNCTIONAL_INTERFACE)) {
				appendFunctionalInterfaceHints(hints, owner, key);
			}

			if (analysisHints.contains(LlmAnalysisHint.CONSTANTS_CLASS)) {
				appendConstantsClassHints(hints, owner);
			}

			if (analysisHints.contains(LlmAnalysisHint.RELATED_CONSTANTS)) {
				appendRelatedConstantsHints(hints, owner, key);
			}

			if (analysisHints.contains(LlmAnalysisHint.ACCESSORS)) {
				appendAccessorHints(hints, owner, key);
			}
		});

		if (hints.isEmpty()) {
			return;
		}

		prompt.append("\nStatic analysis hints:\n");
		prompt.append("These are conservative bytecode-derived observations, not required names. Use them as supporting evidence only when they fit the target.\n");
		prompt.append(hints);
	}

	private static void appendBatchSuggestions(StringBuilder prompt, EntryKey key, List<BatchSuggestion> batchSuggestions) {
		if (batchSuggestions == null || batchSuggestions.isEmpty()) {
			return;
		}

		List<BatchSuggestion> relatedSuggestions = batchSuggestions.stream()
				.filter(suggestion -> !suggestion.key().equals(key))
				.filter(suggestion -> isRelatedBatchSuggestion(key, suggestion.key()))
				.limit(24)
				.toList();

		if (relatedSuggestions.isEmpty()) {
			return;
		}

		prompt.append("\nBatch suggestions already produced in this run:\n");
		prompt.append("These are provisional gray suggestions, not manually applied mappings. Use them to keep related names consistent, but do not blindly copy them.\n");

		for (BatchSuggestion batchSuggestion : relatedSuggestions) {
			prompt.append("- ")
					.append(batchSuggestion.key().kind())
					.append(' ')
					.append(batchSuggestion.key().displayName())
					.append(" -> ")
					.append(batchSuggestion.suggestion().suggestedName());

			if (!batchSuggestion.suggestion().reasoning().isBlank()) {
				prompt.append(" (")
						.append(shorten(batchSuggestion.suggestion().reasoning(), 120))
						.append(')');
			}

			prompt.append('\n');
		}
	}

	private static boolean isRelatedBatchSuggestion(EntryKey key, EntryKey suggestionKey) {
		if (!key.owner().equals(suggestionKey.owner())) {
			return false;
		}

		if (key.kind() == EntryKind.PARAMETER || suggestionKey.kind() == EntryKind.PARAMETER) {
			return key.kind() == EntryKind.PARAMETER
					&& suggestionKey.kind() == EntryKind.PARAMETER
					&& key.name().equals(suggestionKey.name())
					&& key.descriptor().equals(suggestionKey.descriptor());
		}

		return true;
	}

	private static String shorten(String value, int maxChars) {
		if (value.length() <= maxChars) {
			return value;
		}

		return value.substring(0, Math.max(0, maxChars - 3)).stripTrailing() + "...";
	}

	private static void appendFunctionalInterfaceHints(StringBuilder prompt, IndexedClass owner, EntryKey key) {
		LlmBytecodePatterns.FunctionalInterfacePattern pattern = LlmBytecodePatterns.functionalInterfacePattern(owner).orElse(null);

		if (pattern == null) {
			return;
		}

		IndexedMethod singleAbstractMethod = pattern.singleAbstractMethod();
		List<IndexedMethod> compositionalMethods = pattern.compositionalMethods();

		prompt.append("- Detected Java pattern: functional interface with one abstract method ")
				.append(singleAbstractMethod.key().name())
				.append(singleAbstractMethod.key().descriptor())
				.append(".\n");

		if (isSingleArgumentBooleanMethod(singleAbstractMethod.key().descriptor())) {
			prompt.append("- The abstract method takes one argument and returns boolean; this often indicates predicate or matcher semantics.\n");
		}

		if (!compositionalMethods.isEmpty()) {
			prompt.append("- Non-abstract methods reference the same interface or call the abstract method; this often indicates predicate combinators such as and/or/negate rather than a generic utility class.\n");
		}

		if (key.kind() == EntryKind.CLASS) {
			prompt.append("- For the class target, avoid generic names such as Utility when a functional-interface or predicate role is supported.\n");
		} else if (key.kind() == EntryKind.METHOD && key.name().equals(singleAbstractMethod.key().name())
				&& key.descriptor().equals(singleAbstractMethod.key().descriptor())) {
			prompt.append("- The target method is the single abstract method; prefer a name describing the predicate operation.\n");
		} else if (key.kind() == EntryKind.METHOD && compositionalMethods.stream().anyMatch(method -> method.key().equals(key))) {
			prompt.append("- The target method appears to be a functional-interface combinator; prefer concise combinator naming when the body supports it.\n");
		}
	}

	private static void appendConstantsClassHints(StringBuilder prompt, IndexedClass owner) {
		if (!looksLikeConstantsClass(owner)) {
			return;
		}

		prompt.append("- Detected Java pattern: constants holder with static final fields and no ordinary methods.\n");
		prompt.append("- Prefer conservative constants-style class and field names based on values and descriptors.\n");
	}

	private static boolean isSingleArgumentBooleanMethod(String descriptor) {
		try {
			Type methodType = Type.getMethodType(descriptor);
			return methodType.getReturnType().getSort() == Type.BOOLEAN
					&& methodType.getArgumentTypes().length == 1;
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	private static void appendRelatedConstantsHints(StringBuilder prompt, IndexedClass owner, EntryKey key) {
		List<IndexedField> staticFinalFields = owner.fields().stream()
				.filter(field -> isStaticFinal(field.access()))
				.toList();
		String descriptor = key.kind() == EntryKind.FIELD ? key.descriptor() : mostCommonDescriptor(staticFinalFields);

		if (descriptor.isBlank()) {
			return;
		}

		List<IndexedField> relatedFields = staticFinalFields.stream()
				.filter(field -> field.key().descriptor().equals(descriptor))
				.limit(16)
				.toList();

		if (relatedFields.size() < 3) {
			return;
		}

		prompt.append("- Detected Java pattern: related static final fields with the same descriptor: ");

		for (int i = 0; i < relatedFields.size(); i++) {
			if (i > 0) {
				prompt.append(", ");
			}

			prompt.append(relatedFields.get(i).key().name());
		}

		prompt.append(".\n");
		prompt.append("- Treat these fields as a family. Compare sibling initializers and helper methods before naming one field in isolation.\n");
		prompt.append("- If sibling fields call the same operation with different method names, arguments, signs, axes, units, directions, or inverse formulas, ");
		prompt.append("encode that distinction consistently in all related names.\n");
		prompt.append("- Avoid placeholder sequence names made by combining a guessed prefix with unchanged obfuscated field tokens ");
		prompt.append("unless the bytecode proves those tokens are semantic.\n");
	}

	private static String mostCommonDescriptor(List<IndexedField> fields) {
		return fields.stream()
				.collect(java.util.stream.Collectors.groupingBy(field -> field.key().descriptor(),
						java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()))
				.entrySet()
				.stream()
				.max(java.util.Map.Entry.comparingByValue())
				.map(java.util.Map.Entry::getKey)
				.orElse("");
	}

	private static void appendAccessorHints(StringBuilder prompt, IndexedClass owner, EntryKey key) {
		if (key.kind() != EntryKind.METHOD) {
			return;
		}

		owner.methods().stream()
				.filter(method -> method.key().name().equals(key.name()) && method.key().descriptor().equals(key.descriptor()))
				.findFirst()
				.filter(method -> method.calls().isEmpty() && method.fieldUses().size() == 1)
				.ifPresent(method -> prompt.append("- Target method only accesses one field (")
						.append(method.fieldUses().get(0))
						.append("); consider getter, setter, or accessor semantics if the descriptor fits.\n"));
	}

	static void appendOwner(StringBuilder prompt, ProjectView project, IndexedClass owner) {
		prompt.append("\nOwner class: ").append(owner.key().owner()).append('\n');
		appendMapping(prompt, project, "Owner mapped name", owner.key());
		prompt.append("Owner access: ").append(LlmProjectIndex.accessText(owner.access())).append('\n');

		if (owner.superName() != null) {
			prompt.append("Superclass: ").append(owner.superName()).append('\n');
		}

		if (!owner.interfaces().isEmpty()) {
			prompt.append("Interfaces: ").append(String.join(", ", owner.interfaces())).append('\n');
		}

		appendFields(prompt, project, owner.fields());
		appendMethods(prompt, project, owner.methods());
	}

	static void appendFields(StringBuilder prompt, ProjectView project, List<IndexedField> fields) {
		if (fields.isEmpty()) {
			return;
		}

		prompt.append("\nFields:\n");
		fields.stream().limit(40).forEach(field -> {
			prompt.append("- ")
					.append(LlmProjectIndex.accessText(field.access()))
					.append(' ')
					.append(field.key().name())
					.append(" : ")
					.append(field.key().descriptor());
			field.constantValue().ifPresent(value -> prompt.append(" = ").append(value));
			appendInlineMapping(prompt, project, field.key());
			prompt.append('\n');

			if (!field.initializerHints().isEmpty()) {
				prompt.append("  init: ")
						.append(String.join("; ", field.initializerHints().stream().limit(4).toList()))
						.append('\n');
			}
		});
	}

	static void appendMethods(StringBuilder prompt, ProjectView project, List<IndexedMethod> methods) {
		if (methods.isEmpty()) {
			return;
		}

		prompt.append("\nMethods:\n");
		methods.stream().limit(40).forEach(method -> {
			prompt.append("- ")
					.append(LlmProjectIndex.accessText(method.access()))
					.append(' ')
					.append(method.key().name())
					.append(method.key().descriptor());
			appendInlineMapping(prompt, project, method.key());
			prompt.append('\n');

			if (!method.calls().isEmpty()) {
				prompt.append("  calls: ").append(String.join(", ", method.calls().stream().limit(8).toList())).append('\n');
			}

			if (!method.fieldReads().isEmpty()) {
				prompt.append("  field reads: ").append(String.join(", ", method.fieldReads().stream().limit(8).toList())).append('\n');
			}

			if (!method.fieldWrites().isEmpty()) {
				prompt.append("  field writes: ").append(String.join(", ", method.fieldWrites().stream().limit(8).toList())).append('\n');
			}

			if (!method.dynamicCalls().isEmpty()) {
				prompt.append("  invokedynamic: ").append(String.join(", ", method.dynamicCalls().stream().limit(4).toList())).append('\n');
			}

			if (!method.literals().isEmpty()) {
				prompt.append("  literals: ").append(String.join(", ", method.literals().stream().limit(8).toList())).append('\n');
			}

			if (!method.parameters().isEmpty()) {
				prompt.append("  params: ");
				method.parameters().stream().limit(8).forEach(parameter -> {
					prompt.append('#')
							.append(parameter.key().localIndex())
							.append(' ')
							.append(parameter.key().localName())
							.append(" : ")
							.append(parameter.descriptor());
					appendInlineMapping(prompt, project, parameter.key());
					prompt.append("; ");
				});
				prompt.append('\n');
			}
		});
	}

	static void appendMapping(StringBuilder prompt, ProjectView project, String label, EntryKey key) {
		if (key.hasUnchangedName(project)) {
			prompt.append(label).append(": (none)\n");
		} else {
			key.deobfuscatedName(project).ifPresent(name -> prompt.append(label).append(": ").append(name).append('\n'));
		}
	}

	private static void appendUnavailableNames(StringBuilder prompt, ProjectView project, LlmProjectIndex index, EntryKey key) {
		Set<String> names = unavailableNames(project, index, key);

		if (!names.isEmpty()) {
			prompt.append("Unavailable mapped names for this ")
					.append(key.kind())
					.append(": ")
					.append(String.join(", ", names))
					.append('\n');
			prompt.append("Do not use unavailable mapped names as suggestedName or alternatives.\n");
		}
	}

	static Set<String> unavailableNames(ProjectView project, LlmProjectIndex index, EntryKey key) {
		Set<String> names = new LinkedHashSet<>();

		switch (key.kind()) {
		case FIELD -> index.ownerClass(key).stream()
				.flatMap(owner -> owner.fields().stream())
				.map(IndexedField::key)
				.filter(fieldKey -> !fieldKey.equals(key))
				.forEach(fieldKey -> mappedName(project, fieldKey).ifPresent(names::add));
		case PARAMETER -> index.ownerClass(key).stream()
				.flatMap(owner -> owner.methods().stream())
				.filter(method -> method.key().name().equals(key.name()) && method.key().descriptor().equals(key.descriptor()))
				.flatMap(method -> method.parameters().stream())
				.map(IndexedParameter::key)
				.filter(parameterKey -> !parameterKey.equals(key))
				.forEach(parameterKey -> mappedName(project, parameterKey).ifPresent(names::add));
		case CLASS, METHOD -> {
		}
		}

		return names;
	}

	static void appendInlineMapping(StringBuilder prompt, ProjectView project, EntryKey key) {
		if (!key.hasUnchangedName(project)) {
			key.deobfuscatedName(project).ifPresent(name -> prompt.append(" mapped: ").append(name));
		}
	}

	private static java.util.Optional<String> mappedName(ProjectView project, EntryKey key) {
		return EntryKey.toEntryView(key)
				.map(project::deobfuscate)
				.map(entry -> key.kind() == EntryKind.CLASS ? entry.getFullName() : entry.getName())
				.filter(name -> !name.equals(obfuscatedName(key)));
	}

	private static String obfuscatedName(EntryKey key) {
		return EntryKey.toEntryView(key)
				.map(EntryView::getName)
				.orElse(key.name());
	}

	static boolean isStaticFinalField(EntryKey key, LlmProjectIndex index) {
		return key.kind() == EntryKind.FIELD
				&& index.entry(key)
						.filter(IndexedField.class::isInstance)
						.map(IndexedField.class::cast)
						.map(field -> isStaticFinal(field.access()))
						.orElse(false);
	}

	private static boolean looksLikeConstantsClass(IndexedClass clazz) {
		return !clazz.fields().isEmpty()
				&& nonConstructorMethods(clazz).isEmpty()
				&& clazz.fields().stream().allMatch(field -> isStaticFinal(field.access()));
	}

	private static List<IndexedMethod> nonConstructorMethods(IndexedClass clazz) {
		return clazz.methods().stream()
				.filter(method -> !method.key().name().startsWith("<"))
				.toList();
	}

	private static boolean isStaticFinal(int access) {
		return (access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) != 0;
	}
}
