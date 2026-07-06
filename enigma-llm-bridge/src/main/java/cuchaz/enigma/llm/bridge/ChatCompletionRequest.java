package cuchaz.enigma.llm.bridge;

import java.util.List;

record ChatCompletionRequest(String model, List<ChatMessage> messages, double temperature) {
	ChatCompletionRequest {
		model = model == null ? "" : model.strip();
		messages = messages == null ? List.of() : List.copyOf(messages);
	}

	String combinedPrompt() {
		StringBuilder prompt = new StringBuilder();

		for (ChatMessage message : this.messages) {
			if (prompt.length() > 0) {
				prompt.append("\n\n");
			}

			prompt.append(message.role()).append(":\n").append(message.content());
		}

		return prompt.toString();
	}
}

record ChatMessage(String role, String content) {
	ChatMessage {
		role = role == null || role.isBlank() ? "user" : role.strip();
		content = content == null ? "" : content;
	}
}
