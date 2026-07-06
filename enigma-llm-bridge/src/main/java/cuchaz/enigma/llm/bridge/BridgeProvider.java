package cuchaz.enigma.llm.bridge;

import java.util.List;

interface BridgeProvider extends AutoCloseable {
	String name();

	List<String> models() throws Exception;

	String complete(ChatCompletionRequest request) throws Exception;

	@Override
	default void close() throws Exception {
	}
}
