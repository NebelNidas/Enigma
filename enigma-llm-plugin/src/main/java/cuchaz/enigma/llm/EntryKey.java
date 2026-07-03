package cuchaz.enigma.llm;

import java.util.Objects;
import java.util.Optional;

import cuchaz.enigma.api.view.ProjectView;
import cuchaz.enigma.api.view.entry.ClassEntryView;
import cuchaz.enigma.api.view.entry.EntryView;
import cuchaz.enigma.api.view.entry.FieldEntryView;
import cuchaz.enigma.api.view.entry.LocalVariableEntryView;
import cuchaz.enigma.api.view.entry.MethodEntryView;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

record EntryKey(EntryKind kind, String owner, String name, String descriptor, int localIndex, String localName) {
	EntryKey(EntryKind kind, String owner, String name, String descriptor) {
		this(kind, owner, name, descriptor, -1, "");
	}

	static Optional<EntryKey> fromEntryView(EntryView entry) {
		if (entry instanceof ClassEntryView classEntry) {
			return Optional.of(new EntryKey(EntryKind.CLASS, classEntry.getFullName(), classEntry.getFullName(), ""));
		} else if (entry instanceof FieldEntryView fieldEntry) {
			return Optional.of(new EntryKey(EntryKind.FIELD, fieldEntry.getParent().getFullName(), fieldEntry.getName(), fieldEntry.getDescriptor()));
		} else if (entry instanceof MethodEntryView methodEntry) {
			if ("<init>".equals(methodEntry.getName()) || "<clinit>".equals(methodEntry.getName())) {
				return Optional.empty();
			}

			return Optional.of(new EntryKey(EntryKind.METHOD, methodEntry.getParent().getFullName(), methodEntry.getName(), methodEntry.getDescriptor()));
		} else if (entry instanceof LocalVariableEntryView localEntry) {
			if (!localEntry.isArgument()) {
				return Optional.empty();
			}

			MethodEntryView method = localEntry.getParent();
			return Optional.of(new EntryKey(EntryKind.PARAMETER, method.getParent().getFullName(), method.getName(), method.getDescriptor(), localEntry.getIndex(), localEntry.getName()));
		}

		return Optional.empty();
	}

	static Optional<EntryKey> fromEntry(Entry<?> entry) {
		if (entry instanceof ClassEntry classEntry) {
			return Optional.of(new EntryKey(EntryKind.CLASS, classEntry.getFullName(), classEntry.getFullName(), ""));
		} else if (entry instanceof FieldEntry fieldEntry) {
			return Optional.of(new EntryKey(EntryKind.FIELD, fieldEntry.getParent().getFullName(), fieldEntry.getName(), fieldEntry.getDesc().toString()));
		} else if (entry instanceof MethodEntry methodEntry) {
			if (methodEntry.isConstructor()) {
				return Optional.empty();
			}

			return Optional.of(new EntryKey(EntryKind.METHOD, methodEntry.getParent().getFullName(), methodEntry.getName(), methodEntry.getDesc().toString()));
		} else if (entry instanceof LocalVariableEntry localEntry) {
			if (!localEntry.isArgument()) {
				return Optional.empty();
			}

			MethodEntry method = localEntry.getParent();
			return Optional.of(new EntryKey(EntryKind.PARAMETER, method.getParent().getFullName(), method.getName(), method.getDesc().toString(), localEntry.getIndex(), localEntry.getName()));
		}

		return Optional.empty();
	}

	static Optional<EntryView> toEntryView(EntryKey key) {
		return switch (key.kind()) {
		case CLASS -> Optional.of((EntryView) ClassEntryView.create(key.owner()));
		case FIELD -> Optional.of((EntryView) FieldEntryView.create(key.owner(), key.name(), key.descriptor()));
		case METHOD -> Optional.of((EntryView) MethodEntryView.create(key.owner(), key.name(), key.descriptor()));
		case PARAMETER -> Optional.of((EntryView) LocalVariableEntryView.create(MethodEntryView.create(key.owner(), key.name(), key.descriptor()), key.localIndex(), key.localName(), true));
		};
	}

	Optional<String> deobfuscatedName(ProjectView project) {
		return toEntryView(this).map(entry -> {
			EntryView deobfuscated = project.deobfuscate(entry);
			return this.kind == EntryKind.CLASS ? deobfuscated.getFullName() : deobfuscated.getName();
		});
	}

	boolean hasUnchangedName(ProjectView project) {
		return toEntryView(this).map(entry -> {
			EntryView deobfuscated = project.deobfuscate(entry);
			String obfuscatedName = this.kind == EntryKind.CLASS ? entry.getFullName() : entry.getName();
			String mappedName = this.kind == EntryKind.CLASS ? deobfuscated.getFullName() : deobfuscated.getName();
			return obfuscatedName.equals(mappedName);
		}).orElse(false);
	}

	String displayName() {
		return switch (this.kind) {
		case CLASS -> this.owner;
		case FIELD -> this.owner + "." + this.name + " : " + this.descriptor;
		case METHOD -> this.owner + "." + this.name + this.descriptor;
		case PARAMETER -> this.owner + "." + this.name + this.descriptor + " arg " + this.localIndex + " (" + this.localName + ")";
		};
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}

		if (!(other instanceof EntryKey key)) {
			return false;
		}

		return this.kind == key.kind
				&& this.localIndex == key.localIndex
				&& Objects.equals(this.owner, key.owner)
				&& Objects.equals(this.name, key.name)
				&& Objects.equals(this.descriptor, key.descriptor)
				&& (this.kind == EntryKind.PARAMETER || Objects.equals(this.localName, key.localName));
	}

	@Override
	public int hashCode() {
		if (this.kind == EntryKind.PARAMETER) {
			return Objects.hash(this.kind, this.owner, this.name, this.descriptor, this.localIndex);
		}

		return Objects.hash(this.kind, this.owner, this.name, this.descriptor, this.localIndex, this.localName);
	}
}
