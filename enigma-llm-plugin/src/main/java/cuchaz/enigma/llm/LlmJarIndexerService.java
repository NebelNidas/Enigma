package cuchaz.enigma.llm;

import java.util.Set;

import org.objectweb.asm.tree.ClassNode;

import cuchaz.enigma.api.service.JarIndexerService;
import cuchaz.enigma.api.view.index.JarIndexView;
import cuchaz.enigma.classprovider.ClassProvider;

public class LlmJarIndexerService implements JarIndexerService {
	private final LlmNameProposalPlugin plugin;

	public LlmJarIndexerService(LlmNameProposalPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public void acceptJar(Set<String> scope, ClassProvider classProvider, JarIndexView jarIndex) {
		LlmProjectIndex.Builder builder = LlmProjectIndex.builder();

		for (String className : scope) {
			ClassNode node;

			try {
				node = classProvider.get(className);
			} catch (RuntimeException ignored) {
				continue;
			}

			if (node != null) {
				builder.accept(node);
			}
		}

		this.plugin.setIndex(builder.build());
	}
}
