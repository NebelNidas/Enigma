package cuchaz.enigma.api.view;

import java.util.List;

/**
 * Result of validating a deobfuscated name through Enigma's normal rename rules.
 */
public record RenameValidationResult(boolean valid, List<String> messages) {
	public RenameValidationResult {
		messages = List.copyOf(messages);
	}

	public static RenameValidationResult ok() {
		return new RenameValidationResult(true, List.of());
	}

	public static RenameValidationResult invalid(String message) {
		return new RenameValidationResult(false, List.of(message));
	}
}
