package cuchaz.enigma.llm;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import cuchaz.enigma.api.view.ProjectView;
import cuchaz.enigma.api.view.entry.EntryView;

class LlmPromptBuilder {
	String build(EntryKey key, ProjectView project, LlmProjectIndex index, LlmContextBackend backend) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("Target kind: ").append(key.kind()).append('\n');
		prompt.append("Target obfuscated name: ").append(key.displayName()).append('\n');
		appendTargetDetails(prompt, key, index);

		appendMapping(prompt, project, "Target mapped name", key);
		appendUnavailableNames(prompt, project, index, key);

		index.entry(key).ifPresent(entry -> prompt.append("Target access: ").append(LlmProjectIndex.accessText(entry.access())).append('\n'));
		appendContext(prompt, project, index, key, backend);

		prompt.append("\nRespond with JSON only:\n");
		prompt.append("{\"reasoning\":\"short explanation\",\"alternatives\":[\"NameA\",\"NameB\"],\"suggestedName\":\"BestName\",\"confidence\":0.0}\n");
		prompt.append("Use PascalCase for classes and camelCase for fields, methods, and parameters. Top-level classes may be simple names or JVM internal names with package separators. Avoid generic names such as Manager, Handler, Helper, Data, or Utils unless the context proves that exact role.\n");
		prompt.append("For fields, methods, and parameters, use clean lowerCamelCase only. Never use underscores, screaming-case, or mixed constant-style names such as CONSTANT_A or cONSTANT_A.\n");
		prompt.append("For fields, methods, and parameters, do not prefix the name with the owner class name or append the obfuscated member name to the owner class name.\n");
		prompt.append("Names marked as mapped in the context are already assigned; do not reuse an existing mapped member name for a different member.\n");
		return prompt.toString();
	}

	String build(EntryKey key, ProjectView project, LlmProjectIndex index) {
		return build(key, project, index, LlmContextBackend.OWNER);
	}

	private static void appendContext(StringBuilder prompt, ProjectView project, LlmProjectIndex index, EntryKey key, LlmContextBackend backend) {
		if (backend == LlmContextBackend.GRAPH) {
			new LlmGraphContextBuilder().appendContext(prompt, project, index, key);
		} else {
			index.ownerClass(key).ifPresent(owner -> appendOwner(prompt, project, owner));
		}
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
			prompt.append("Target naming rule: return one lower camelCase field name without package separators.\n");
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

			if (!method.fieldUses().isEmpty()) {
				prompt.append("  fields: ").append(String.join(", ", method.fieldUses().stream().limit(8).toList())).append('\n');
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
}
