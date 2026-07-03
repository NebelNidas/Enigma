package cuchaz.enigma.api.view.entry;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.NonExtendable
public interface EntryReferenceView {
	EntryView getEntry();

	/**
	 * Returns the entry that should be renamed for this reference.
	 *
	 * <p>For most references this is the referenced entry itself. Constructor
	 * references resolve to their containing class, because constructors do not
	 * have independent deobfuscated names.</p>
	 */
	EntryView getNameableEntry();
}
