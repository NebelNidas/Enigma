package cuchaz.enigma.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class LlmProjectIndex {
	private static final LlmProjectIndex EMPTY = new LlmProjectIndex(Map.of(), List.of());

	private final Map<String, IndexedClass> classes;
	private final List<IndexedEntry> entries;

	private LlmProjectIndex(Map<String, IndexedClass> classes, List<IndexedEntry> entries) {
		this.classes = Map.copyOf(classes);
		this.entries = List.copyOf(entries);
	}

	static LlmProjectIndex empty() {
		return EMPTY;
	}

	static Builder builder() {
		return new Builder();
	}

	boolean isEmpty() {
		return this.entries.isEmpty();
	}

	List<IndexedEntry> entries() {
		return this.entries;
	}

	Optional<IndexedEntry> entry(EntryKey key) {
		return this.entries.stream().filter(entry -> entry.key().equals(key)).findFirst();
	}

	Optional<IndexedClass> ownerClass(EntryKey key) {
		return Optional.ofNullable(this.classes.get(key.owner()));
	}

	Optional<IndexedClass> classByName(String className) {
		return Optional.ofNullable(this.classes.get(className));
	}

	List<IndexedMethod> callersOf(IndexedMethod target) {
		String callTarget = target.key().owner() + "." + target.key().name() + target.key().descriptor();
		return this.classes.values().stream()
				.flatMap(owner -> owner.methods().stream())
				.filter(method -> method.calls().contains(callTarget))
				.toList();
	}

	List<IndexedMethod> fieldAccessorsOf(IndexedField target) {
		String fieldTarget = target.key().owner() + "." + target.key().name() + " : " + target.key().descriptor();
		return this.classes.values().stream()
				.flatMap(owner -> owner.methods().stream())
				.filter(method -> method.fieldUses().contains(fieldTarget))
				.toList();
	}

	List<IndexedClass> referencedClasses(IndexedMethod method) {
		return referencedClassNames(method).stream()
				.distinct()
				.map(this.classes::get)
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	private static List<String> referencedClassNames(IndexedMethod method) {
		List<String> names = new ArrayList<>();
		method.calls().forEach(call -> names.add(ownerBeforeDot(call)));
		method.fieldUses().forEach(field -> names.add(ownerBeforeDot(field)));
		return names;
	}

	private static String ownerBeforeDot(String reference) {
		int dot = reference.indexOf('.');
		return dot >= 0 ? reference.substring(0, dot) : reference;
	}

	static class Builder {
		private final Map<String, IndexedClass> classes = new LinkedHashMap<>();
		private final List<IndexedEntry> entries = new ArrayList<>();

		void accept(ClassNode classNode) {
			List<IndexedField> fields = new ArrayList<>();
			List<IndexedMethod> methods = new ArrayList<>();
			EntryKey classKey = new EntryKey(EntryKind.CLASS, classNode.name, classNode.name, "");

			for (FieldNode field : classNode.fields) {
				EntryKey key = new EntryKey(EntryKind.FIELD, classNode.name, field.name, field.desc);
				IndexedField indexed = new IndexedField(key, field.access, constantValue(field.value));
				fields.add(indexed);
				this.entries.add(indexed);
			}

			for (MethodNode method : classNode.methods) {
				IndexedMethod indexed = indexMethod(classNode.name, method);
				methods.add(indexed);

				if (!method.name.equals("<init>") && !method.name.equals("<clinit>")) {
					this.entries.add(indexed);
				}

				this.entries.addAll(indexed.parameters());
			}

			IndexedClass indexedClass = new IndexedClass(classKey, classNode.access, classNode.superName, List.copyOf(classNode.interfaces), List.copyOf(fields), List.copyOf(methods));
			this.classes.put(classNode.name, indexedClass);
			this.entries.add(indexedClass);
		}

		LlmProjectIndex build() {
			this.entries.sort(Comparator.comparing(entry -> entry.key().displayName()));
			return new LlmProjectIndex(this.classes, this.entries);
		}

		private static IndexedMethod indexMethod(String owner, MethodNode method) {
			List<String> calls = new ArrayList<>();
			List<String> fieldUses = new ArrayList<>();
			List<IndexedParameter> parameters = indexParameters(owner, method);

			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call) {
					calls.add(call.owner + "." + call.name + call.desc);
				} else if (instruction instanceof FieldInsnNode field) {
					fieldUses.add(field.owner + "." + field.name + " : " + field.desc);
				}

				if (calls.size() + fieldUses.size() >= 24) {
					break;
				}
			}

			return new IndexedMethod(new EntryKey(EntryKind.METHOD, owner, method.name, method.desc), method.access, List.copyOf(calls), List.copyOf(fieldUses), List.copyOf(parameters));
		}

		private static Optional<String> constantValue(Object value) {
			return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
		}

		private static List<IndexedParameter> indexParameters(String owner, MethodNode method) {
			if (method.name.equals("<clinit>")) {
				return List.of();
			}

			Map<Integer, String> argumentDescriptors = argumentDescriptors(method.access, method.desc);
			Map<Integer, String> localNames = new HashMap<>();

			if (method.localVariables != null) {
				method.localVariables.stream()
						.filter(local -> argumentDescriptors.containsKey(local.index))
						.forEach(local -> localNames.putIfAbsent(local.index, local.name));
			}

			return argumentDescriptors.keySet().stream()
					.sorted()
					.map(slot -> indexedParameter(owner, method, slot, argumentDescriptors.get(slot), localNames.getOrDefault(slot, "")))
					.toList();
		}

		private static Map<Integer, String> argumentDescriptors(int access, String descriptor) {
			int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
			Map<Integer, String> descriptors = new LinkedHashMap<>();

			for (Type type : Type.getArgumentTypes(descriptor)) {
				descriptors.put(slot, type.getDescriptor());
				slot += type.getSize();
			}

			return descriptors;
		}

		private static IndexedParameter indexedParameter(String owner, MethodNode method, int slot, String descriptor, String localName) {
			return new IndexedParameter(new EntryKey(EntryKind.PARAMETER, owner, method.name, method.desc, slot, localName), method.access, descriptor);
		}
	}

	static String accessText(int access) {
		List<String> parts = new ArrayList<>();

		if ((access & Opcodes.ACC_PUBLIC) != 0) {
			parts.add("public");
		} else if ((access & Opcodes.ACC_PROTECTED) != 0) {
			parts.add("protected");
		} else if ((access & Opcodes.ACC_PRIVATE) != 0) {
			parts.add("private");
		}

		if ((access & Opcodes.ACC_STATIC) != 0) {
			parts.add("static");
		}

		if ((access & Opcodes.ACC_FINAL) != 0) {
			parts.add("final");
		}

		if ((access & Opcodes.ACC_ABSTRACT) != 0) {
			parts.add("abstract");
		}

		if ((access & Opcodes.ACC_INTERFACE) != 0) {
			parts.add("interface");
		} else if ((access & Opcodes.ACC_ENUM) != 0) {
			parts.add("enum");
		} else if ((access & Opcodes.ACC_RECORD) != 0) {
			parts.add("record");
		}

		return parts.isEmpty() ? "package-private" : String.join(" ", parts);
	}
}

sealed interface IndexedEntry permits IndexedClass, IndexedField, IndexedMethod, IndexedParameter {
	EntryKey key();
	int access();
}

record IndexedClass(EntryKey key, int access, String superName, List<String> interfaces, List<IndexedField> fields, List<IndexedMethod> methods) implements IndexedEntry {
}

record IndexedField(EntryKey key, int access, Optional<String> constantValue) implements IndexedEntry {
}

record IndexedMethod(EntryKey key, int access, List<String> calls, List<String> fieldUses, List<IndexedParameter> parameters) implements IndexedEntry {
}

record IndexedParameter(EntryKey key, int access, String descriptor) implements IndexedEntry {
}
