package cuchaz.enigma.gradle;

import java.util.Set;

import org.gradle.api.Project;
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;

final class PublicationControlTasks {
	private static final Set<String> PUBLISH_TASKS = Set.of(
			"publish",
			"publishToMavenLocal",
			"publishAllPublicationsToMavenLocalRepository");

	private PublicationControlTasks() {
	}

	static void register(Project project) {
		project.getTasks().withType(PublishToMavenRepository.class).configureEach(task -> task.setEnabled(false));
		project.getTasks().withType(PublishToMavenLocal.class).configureEach(task -> task.setEnabled(false));
		project.getTasks().matching(task -> PUBLISH_TASKS.contains(task.getName()))
				.configureEach(task -> task.setEnabled(false));
	}
}
