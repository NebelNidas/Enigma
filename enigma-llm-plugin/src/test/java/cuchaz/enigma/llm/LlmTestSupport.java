package cuchaz.enigma.llm;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.swing.JEditorPane;
import javax.swing.JFrame;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import cuchaz.enigma.api.DataInvalidationEvent;
import cuchaz.enigma.api.DataInvalidationListener;
import cuchaz.enigma.api.view.GuiView;
import cuchaz.enigma.api.view.ProjectView;
import cuchaz.enigma.api.view.entry.ClassEntryView;
import cuchaz.enigma.api.view.entry.EntryReferenceView;
import cuchaz.enigma.api.view.entry.EntryView;
import cuchaz.enigma.api.view.entry.FieldEntryView;
import cuchaz.enigma.api.view.entry.LocalVariableEntryView;
import cuchaz.enigma.api.view.entry.MethodEntryView;
import cuchaz.enigma.api.view.index.JarIndexView;

final class LlmTestSupport {
	private LlmTestSupport() {
	}

	static ClassNode classNode(String name) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V17;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = name;
		node.superName = "java/lang/Object";
		return node;
	}

	static boolean containsComponent(java.awt.Container container, Class<?> type) {
		for (java.awt.Component component : container.getComponents()) {
			if (type.isInstance(component)) {
				return true;
			}

			if (component instanceof java.awt.Container child && containsComponent(child, type)) {
				return true;
			}
		}

		return false;
	}

	static class FakeProjectView implements ProjectView {
		private final Map<EntryKey, String> mappedNames;
		private final List<DataInvalidationListener> listeners = new ArrayList<>();
		int invalidations;

		FakeProjectView() {
			this(Map.of());
		}

		FakeProjectView(Map<EntryKey, String> mappedNames) {
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
		public void addDataInvalidationListener(DataInvalidationListener listener) {
			this.listeners.add(listener);
		}

		@Override
		public void invalidateData(java.util.Collection<String> classes, DataInvalidationEvent.InvalidationType type) {
			this.invalidations++;
			DataInvalidationEvent event = new DataInvalidationEvent() {
				@Override
				public java.util.Collection<String> getClasses() {
					return classes;
				}

				@Override
				public DataInvalidationEvent.InvalidationType getType() {
					return type;
				}
			};
			this.listeners.forEach(listener -> listener.onDataInvalidated(event));
		}
	}

	static class FakeGuiView implements GuiView {
		private final ProjectView project;
		private final boolean applyResult;
		EntryReferenceView cursorReference;
		EntryView cursorDeclaration;
		ClassEntryView activeClass;
		EntryView lastEntry;
		String lastRename;
		Component statusComponent;

		FakeGuiView(ProjectView project, boolean applyResult) {
			this.project = project;
			this.applyResult = applyResult;
		}

		@Override
		public ProjectView getProject() {
			return this.project;
		}

		@Override
		public EntryReferenceView getCursorReference() {
			return this.cursorReference;
		}

		@Override
		public EntryView getCursorDeclaration() {
			return this.cursorDeclaration;
		}

		@Override
		public ClassEntryView getActiveClass() {
			return this.activeClass;
		}

		@Override
		public JFrame getFrame() {
			return null;
		}

		@Override
		public float getScale() {
			return 1.0F;
		}

		@Override
		public boolean isDarkTheme() {
			return false;
		}

		@Override
		public JEditorPane createEditorPane() {
			return null;
		}

		@Override
		public void addStatusComponent(Component component) {
			this.statusComponent = component;
		}

		@Override
		public void removeStatusComponent(Component component) {
			if (this.statusComponent == component) {
				this.statusComponent = null;
			}
		}

		@Override
		public boolean applyRename(EntryView entry, String newName) {
			this.lastEntry = entry;
			this.lastRename = newName;
			return this.applyResult;
		}
	}

	record FakeEntryReferenceView(EntryView entry) implements EntryReferenceView {
		@Override
		public EntryView getEntry() {
			return this.entry;
		}

		@Override
		public EntryView getNameableEntry() {
			return this.entry;
		}
	}
}
