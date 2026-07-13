package cuchaz.enigma.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
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
				.sorted(methodComparator())
				.toList();
	}

	List<IndexedMethod> fieldAccessorsOf(IndexedField target) {
		String fieldTarget = target.key().owner() + "." + target.key().name() + " : " + target.key().descriptor();
		return this.classes.values().stream()
				.flatMap(owner -> owner.methods().stream())
				.filter(method -> method.fieldUses().contains(fieldTarget))
				.sorted(methodComparator())
				.toList();
	}

	List<IndexedClass> referencedClasses(IndexedMethod method) {
		return referencedClassNames(method).stream()
				.distinct()
				.map(this.classes::get)
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	List<IndexedMethod> methodsReferencingClass(IndexedClass target) {
		String targetOwner = target.key().owner();
		return this.classes.values().stream()
				.flatMap(owner -> owner.methods().stream())
				.filter(method -> !method.key().owner().equals(targetOwner))
				.filter(method -> descriptorReferences(method.key().descriptor(), targetOwner)
						|| method.calls().stream().anyMatch(call -> ownerBeforeDot(call).equals(targetOwner))
						|| method.fieldUses().stream().anyMatch(field -> ownerBeforeDot(field).equals(targetOwner) || descriptorReferences(descriptorAfterColon(field), targetOwner)))
				.sorted(methodComparator())
				.toList();
	}

	List<IndexedField> fieldsReferencingClass(IndexedClass target) {
		String targetOwner = target.key().owner();
		return this.classes.values().stream()
				.flatMap(owner -> owner.fields().stream())
				.filter(field -> !field.key().owner().equals(targetOwner))
				.filter(field -> descriptorReferences(field.key().descriptor(), targetOwner))
				.sorted(fieldComparator())
				.toList();
	}

	static Comparator<IndexedClass> classComparator() {
		return Comparator.comparing(clazz -> clazz.key().owner());
	}

	static Comparator<IndexedField> fieldComparator() {
		return Comparator.comparing((IndexedField field) -> field.key().owner())
				.thenComparing(field -> field.key().name())
				.thenComparing(field -> field.key().descriptor());
	}

	static Comparator<IndexedMethod> methodComparator() {
		return Comparator.comparing((IndexedMethod method) -> method.key().owner())
				.thenComparing(method -> method.key().name())
				.thenComparing(method -> method.key().descriptor());
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

	private static boolean descriptorReferences(String descriptor, String owner) {
		return descriptor.contains("L" + owner + ";");
	}

	private static String descriptorAfterColon(String fieldReference) {
		int colon = fieldReference.indexOf(" : ");
		return colon >= 0 ? fieldReference.substring(colon + 3) : "";
	}

	static class Builder {
		private final Map<String, IndexedClass> classes = new LinkedHashMap<>();
		private final List<IndexedEntry> entries = new ArrayList<>();

		void accept(ClassNode classNode) {
			List<IndexedMethod> methods = new ArrayList<>();
			EntryKey classKey = new EntryKey(EntryKind.CLASS, classNode.name, classNode.name, "");
			Map<String, MethodNode> rawMethods = rawMethods(classNode.methods);

			for (MethodNode method : classNode.methods) {
				IndexedMethod indexed = indexMethod(classNode.name, method, rawMethods);
				methods.add(indexed);

				if (!method.name.equals("<init>") && !method.name.equals("<clinit>")) {
					this.entries.add(indexed);
				}

				this.entries.addAll(indexed.parameters());
			}

			List<IndexedField> fields = new ArrayList<>();
			Map<EntryKey, List<String>> fieldInitializers = staticFieldInitializers(classNode.name, classNode.methods, rawMethods);

			for (FieldNode field : classNode.fields) {
				EntryKey key = new EntryKey(EntryKind.FIELD, classNode.name, field.name, field.desc);
				IndexedField indexed = new IndexedField(key, field.access, constantValue(field.value),
						fieldInitializers.getOrDefault(key, List.of()));
				fields.add(indexed);
				this.entries.add(indexed);
			}

			IndexedClass indexedClass = new IndexedClass(classKey, classNode.access, classNode.superName,
					List.copyOf(classNode.interfaces), List.copyOf(fields), List.copyOf(methods));
			this.classes.put(classNode.name, indexedClass);
			this.entries.add(indexedClass);
		}

		LlmProjectIndex build() {
			this.entries.sort(Comparator.comparing(entry -> entry.key().displayName()));
			return new LlmProjectIndex(this.classes, this.entries);
		}

		private static IndexedMethod indexMethod(String owner, MethodNode method, Map<String, MethodNode> rawMethods) {
			List<String> calls = new ArrayList<>();
			List<String> fieldUses = new ArrayList<>();
			List<String> fieldReads = new ArrayList<>();
			List<String> fieldWrites = new ArrayList<>();
			List<String> literals = new ArrayList<>();
			List<String> dynamicCalls = new ArrayList<>();
			List<IndexedParameter> parameters = indexParameters(owner, method);

			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call) {
					calls.add(call.owner + "." + call.name + call.desc);
				} else if (instruction instanceof FieldInsnNode field) {
					String fieldReference = field.owner + "." + field.name + " : " + field.desc;
					fieldUses.add(fieldReference);

					if (isFieldWrite(instruction.getOpcode())) {
						fieldWrites.add(fieldReference);
					} else {
						fieldReads.add(fieldReference);
					}
				} else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
					dynamicCalls.add(invokeDynamicSummary(owner, dynamic, rawMethods));
				} else {
					literalValue(instruction).ifPresent(literals::add);
				}

				if (calls.size() + fieldUses.size() + literals.size() + dynamicCalls.size() >= 36) {
					break;
				}
			}

			return new IndexedMethod(new EntryKey(EntryKind.METHOD, owner, method.name, method.desc),
					method.access, List.copyOf(calls), List.copyOf(fieldUses), List.copyOf(fieldReads),
					List.copyOf(fieldWrites), List.copyOf(literals), List.copyOf(dynamicCalls), List.copyOf(parameters));
		}

		private static boolean isFieldWrite(int opcode) {
			return opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
		}

		private static Optional<String> literalValue(AbstractInsnNode instruction) {
			if (instruction instanceof LdcInsnNode ldc) {
				return Optional.of("literal " + formatLiteral(ldc.cst));
			}

			int opcode = instruction.getOpcode();

			return switch (opcode) {
			case Opcodes.ICONST_M1 -> Optional.of("literal -1");
			case Opcodes.ICONST_0, Opcodes.LCONST_0, Opcodes.FCONST_0, Opcodes.DCONST_0 -> Optional.of("literal 0");
			case Opcodes.ICONST_1, Opcodes.LCONST_1, Opcodes.FCONST_1, Opcodes.DCONST_1 -> Optional.of("literal 1");
			case Opcodes.ICONST_2, Opcodes.FCONST_2 -> Optional.of("literal 2");
			case Opcodes.ICONST_3 -> Optional.of("literal 3");
			case Opcodes.ICONST_4 -> Optional.of("literal 4");
			case Opcodes.ICONST_5 -> Optional.of("literal 5");
			case Opcodes.BIPUSH, Opcodes.SIPUSH -> Optional.of("literal " + ((IntInsnNode) instruction).operand);
			default -> Optional.empty();
			};
		}

		private static String formatLiteral(Object value) {
			if (value instanceof String string) {
				return '"' + shortenLiteral(string.replace("\n", "\\n").replace("\r", "\\r"), 80) + '"';
			}

			if (value instanceof Type type) {
				return type.getDescriptor();
			}

			return String.valueOf(value);
		}

		private static String shortenLiteral(String value, int maxLength) {
			return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
		}

		private static Map<String, MethodNode> rawMethods(List<MethodNode> methods) {
			Map<String, MethodNode> rawMethods = new HashMap<>();

			for (MethodNode method : methods) {
				rawMethods.put(method.name + method.desc, method);
			}

			return rawMethods;
		}

		private static Map<EntryKey, List<String>> staticFieldInitializers(String owner, List<MethodNode> methods,
				Map<String, MethodNode> rawMethods) {
			Map<EntryKey, List<String>> initializers = new LinkedHashMap<>();

			methods.stream()
					.filter(method -> method.name.equals("<clinit>"))
					.findFirst()
					.ifPresent(clinit -> collectStaticFieldInitializers(owner, clinit, rawMethods, initializers));

			return initializers;
		}

		private static void collectStaticFieldInitializers(String owner, MethodNode clinit,
				Map<String, MethodNode> rawMethods, Map<EntryKey, List<String>> initializers) {
			List<String> recent = new ArrayList<>();

			for (AbstractInsnNode instruction : clinit.instructions) {
				if (instruction instanceof InvokeDynamicInsnNode dynamic) {
					recent.add(invokeDynamicSummary(owner, dynamic, rawMethods));
				} else if (instruction instanceof MethodInsnNode call) {
					recent.add(methodCallSummary(call));
				} else if (instruction instanceof FieldInsnNode field && instruction.getOpcode() == Opcodes.PUTSTATIC
						&& field.owner.equals(owner)) {
					EntryKey key = new EntryKey(EntryKind.FIELD, field.owner, field.name, field.desc);
					initializers.put(key, List.copyOf(recent.stream().limit(6).toList()));
					recent.clear();
				}

				if (recent.size() > 8) {
					recent.remove(0);
				}
			}
		}

		private static String invokeDynamicSummary(String owner, InvokeDynamicInsnNode dynamic,
				Map<String, MethodNode> rawMethods) {
			List<String> parts = new ArrayList<>();
			parts.add("invokedynamic " + dynamic.name + dynamic.desc);

			for (Object argument : dynamic.bsmArgs) {
				if (argument instanceof Handle handle) {
					parts.add("impl " + handle.getOwner() + "." + handle.getName() + handle.getDesc());

					if (handle.getOwner().equals(owner)) {
						MethodNode implementation = rawMethods.get(handle.getName() + handle.getDesc());
						String summary = implementationSummary(implementation);

						if (!summary.isBlank()) {
							parts.add(summary);
						}
					}
				}
			}

			return String.join("; ", parts);
		}

		private static String implementationSummary(MethodNode method) {
			if (method == null) {
				return "";
			}

			List<String> calls = new ArrayList<>();

			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && !call.name.startsWith("<")) {
					String summary = "impl calls " + methodCallSummary(call);

					if (hasRecentOpcode(instruction, Opcodes.FNEG, 6) || hasRecentOpcode(instruction, Opcodes.DNEG, 6)
							|| hasRecentOpcode(instruction, Opcodes.INEG, 6) || hasRecentOpcode(instruction, Opcodes.LNEG, 6)) {
						summary += " with negated argument";
					}

					calls.add(summary);
				}

				if (calls.size() >= 4) {
					break;
				}
			}

			return String.join("; ", calls);
		}

		private static boolean hasRecentOpcode(AbstractInsnNode instruction, int opcode, int limit) {
			AbstractInsnNode previous = instruction.getPrevious();

			for (int i = 0; previous != null && i < limit; i++) {
				if (previous.getOpcode() == opcode) {
					return true;
				}

				previous = previous.getPrevious();
			}

			return false;
		}

		private static String methodCallSummary(MethodInsnNode call) {
			return call.owner + "." + call.name + call.desc;
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

record IndexedField(EntryKey key, int access, Optional<String> constantValue, List<String> initializerHints) implements IndexedEntry {
}

record IndexedMethod(EntryKey key, int access, List<String> calls, List<String> fieldUses, List<String> fieldReads,
		List<String> fieldWrites, List<String> literals, List<String> dynamicCalls, List<IndexedParameter> parameters) implements IndexedEntry {
}

record IndexedParameter(EntryKey key, int access, String descriptor) implements IndexedEntry {
}
