package cuchaz.enigma.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cuchaz.enigma.api.view.ProjectView;

class LlmGraphContextBuilder {
	private static final int NEIGHBOR_CLASS_LIMIT = 8;
	private static final int MEMBER_LIMIT = 8;

	void appendContext(StringBuilder prompt, ProjectView project, LlmProjectIndex index, EntryKey key) {
		prompt.append("\nContext backend: graph\n");
		prompt.append("This context is assembled with a lightweight Johannes-style graph retrieval strategy over Enigma's live ASM index.\n");
		prompt.append("Use the root target first, then declaring class members, call/field-use neighbors, and already mapped names as supporting evidence.\n");

		index.ownerClass(key).ifPresent(owner -> {
			prompt.append("\n=== ROOT / DECLARING CLASS ===\n");
			LlmPromptBuilder.appendOwner(prompt, project, owner);
		});

		index.entry(key).ifPresent(entry -> appendTargetNeighbors(prompt, project, index, entry));
	}

	private static void appendTargetNeighbors(StringBuilder prompt, ProjectView project, LlmProjectIndex index, IndexedEntry entry) {
		if (entry instanceof IndexedMethod method) {
			appendMethodNeighbors(prompt, project, index, method);
		} else if (entry instanceof IndexedField field) {
			appendFieldNeighbors(prompt, project, index, field);
		} else if (entry instanceof IndexedParameter parameter) {
			index.entry(new EntryKey(EntryKind.METHOD, parameter.key().owner(), parameter.key().name(), parameter.key().descriptor()))
					.filter(IndexedMethod.class::isInstance)
					.map(IndexedMethod.class::cast)
					.ifPresent(method -> appendMethodNeighbors(prompt, project, index, method));
		} else if (entry instanceof IndexedClass clazz) {
			appendClassDependencies(prompt, project, index, clazz);
		}
	}

	private static void appendMethodNeighbors(StringBuilder prompt, ProjectView project, LlmProjectIndex index, IndexedMethod method) {
		Map<String, IndexedClass> neighborClasses = new LinkedHashMap<>();
		index.referencedClasses(method).forEach(clazz -> neighborClasses.putIfAbsent(clazz.key().owner(), clazz));

		appendClassDependencies(prompt, project, index, index.ownerClass(method.key()).orElse(null));
		appendMethods(prompt, project, "Incoming callers", index.callersOf(method));
		appendNeighborClasses(prompt, project, "Referenced classes from target method", neighborClasses.values().stream().toList());
	}

	private static void appendFieldNeighbors(StringBuilder prompt, ProjectView project, LlmProjectIndex index, IndexedField field) {
		List<IndexedMethod> accessors = index.fieldAccessorsOf(field);
		Map<String, IndexedClass> neighborClasses = new LinkedHashMap<>();
		accessors.stream()
				.flatMap(method -> index.referencedClasses(method).stream())
				.forEach(clazz -> neighborClasses.putIfAbsent(clazz.key().owner(), clazz));

		appendMethods(prompt, project, "Field accessors", accessors);
		appendNeighborClasses(prompt, project, "Classes referenced by field accessors", neighborClasses.values().stream().toList());
	}

	private static void appendClassDependencies(StringBuilder prompt, ProjectView project, LlmProjectIndex index, IndexedClass clazz) {
		if (clazz == null) {
			return;
		}

		Map<String, IndexedClass> dependencies = new LinkedHashMap<>();
		clazz.methods().stream()
				.flatMap(method -> index.referencedClasses(method).stream())
				.filter(dependency -> !dependency.key().owner().equals(clazz.key().owner()))
				.forEach(dependency -> dependencies.putIfAbsent(dependency.key().owner(), dependency));
		appendNeighborClasses(prompt, project, "Referenced classes from owner", dependencies.values().stream().toList());
		appendMethods(prompt, project, "Methods referencing class", index.methodsReferencingClass(clazz));
		appendFields(prompt, project, "Fields referencing class", index.fieldsReferencingClass(clazz));
	}

	private static void appendMethods(StringBuilder prompt, ProjectView project, String title, List<IndexedMethod> methods) {
		if (methods.isEmpty()) {
			return;
		}

		prompt.append("\n=== ").append(title).append(" ===\n");
		methods.stream().limit(MEMBER_LIMIT).forEach(method -> {
			prompt.append("- ")
					.append(method.key().owner())
					.append('.')
					.append(method.key().name())
					.append(method.key().descriptor());
			LlmPromptBuilder.appendInlineMapping(prompt, project, method.key());
			prompt.append('\n');

			if (!method.calls().isEmpty()) {
				prompt.append("  calls: ").append(String.join(", ", method.calls().stream().limit(4).toList())).append('\n');
			}

			if (!method.fieldReads().isEmpty()) {
				prompt.append("  field reads: ").append(String.join(", ", method.fieldReads().stream().limit(4).toList())).append('\n');
			}

			if (!method.fieldWrites().isEmpty()) {
				prompt.append("  field writes: ").append(String.join(", ", method.fieldWrites().stream().limit(4).toList())).append('\n');
			}

			if (!method.dynamicCalls().isEmpty()) {
				prompt.append("  invokedynamic: ").append(String.join(", ", method.dynamicCalls().stream().limit(2).toList())).append('\n');
			}

			if (!method.literals().isEmpty()) {
				prompt.append("  literals: ").append(String.join(", ", method.literals().stream().limit(4).toList())).append('\n');
			}
		});
	}

	private static void appendFields(StringBuilder prompt, ProjectView project, String title, List<IndexedField> fields) {
		if (fields.isEmpty()) {
			return;
		}

		prompt.append("\n=== ").append(title).append(" ===\n");
		fields.stream().limit(MEMBER_LIMIT).forEach(field -> {
			prompt.append("- ")
					.append(field.key().owner())
					.append('.')
					.append(field.key().name())
					.append(" : ")
					.append(field.key().descriptor());
			LlmPromptBuilder.appendInlineMapping(prompt, project, field.key());
			prompt.append('\n');
		});
	}

	private static void appendNeighborClasses(StringBuilder prompt, ProjectView project, String title, List<IndexedClass> classes) {
		List<IndexedClass> nonEmptyClasses = new ArrayList<>(classes);

		if (nonEmptyClasses.isEmpty()) {
			return;
		}

		prompt.append("\n=== ").append(title).append(" ===\n");
		nonEmptyClasses.stream().limit(NEIGHBOR_CLASS_LIMIT).forEach(clazz -> appendClassSummary(prompt, project, clazz));
	}

	private static void appendClassSummary(StringBuilder prompt, ProjectView project, IndexedClass clazz) {
		prompt.append("- class ").append(clazz.key().owner());
		LlmPromptBuilder.appendInlineMapping(prompt, project, clazz.key());
		prompt.append(" access: ").append(LlmProjectIndex.accessText(clazz.access())).append('\n');

		if (clazz.superName() != null) {
			prompt.append("  superclass: ").append(clazz.superName()).append('\n');
		}

		clazz.fields().stream().limit(MEMBER_LIMIT).forEach(field -> {
			prompt.append("  field ")
					.append(field.key().name())
					.append(" : ")
					.append(field.key().descriptor());
			field.constantValue().ifPresent(value -> prompt.append(" = ").append(value));
			LlmPromptBuilder.appendInlineMapping(prompt, project, field.key());
			prompt.append('\n');

			if (!field.initializerHints().isEmpty()) {
				prompt.append("    init: ")
						.append(String.join("; ", field.initializerHints().stream().limit(2).toList()))
						.append('\n');
			}
		});

		clazz.methods().stream()
				.filter(method -> !method.key().name().startsWith("<"))
				.limit(MEMBER_LIMIT)
				.forEach(method -> {
					prompt.append("  method ")
							.append(method.key().name())
							.append(method.key().descriptor());
					LlmPromptBuilder.appendInlineMapping(prompt, project, method.key());
					prompt.append('\n');

					if (!method.literals().isEmpty()) {
						prompt.append("    literals: ")
								.append(String.join(", ", method.literals().stream().limit(3).toList()))
								.append('\n');
					}
				});
	}
}
