package cuchaz.enigma.api.view;

import java.awt.Component;

import javax.swing.JEditorPane;
import javax.swing.JFrame;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import cuchaz.enigma.api.view.entry.EntryReferenceView;
import cuchaz.enigma.api.view.entry.EntryView;
import cuchaz.enigma.api.view.entry.ClassEntryView;

@ApiStatus.NonExtendable
public interface GuiView {
	@Nullable
	ProjectView getProject();

	@Nullable
	EntryReferenceView getCursorReference();

	@Nullable
	EntryView getCursorDeclaration();

	@Nullable
	ClassEntryView getActiveClass();

	JFrame getFrame();

	float getScale();

	boolean isDarkTheme();

	JEditorPane createEditorPane();

	/**
	 * Adds a non-modal status component to the main status bar.
	 */
	void addStatusComponent(Component component);

	/**
	 * Removes a previously added status component from the main status bar.
	 */
	void removeStatusComponent(Component component);

	/**
	 * Applies a validated deobfuscated name through Enigma's normal rename path.
	 *
	 * @return whether the rename passed validation and was applied
	 */
	boolean applyRename(EntryView entry, String newName);
}
