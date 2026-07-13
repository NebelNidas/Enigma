package cuchaz.enigma.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public final class FrontierEvaluationPlugin implements Plugin<Project> {
	@Override
	public void apply(Project project) {
		PublicationControlTasks.register(project);
		EvaluationCorpusTasks.register(project);
		HarnessJavaExecTasks.register(project);
		PromptBatchTasks.register(project);
		FrontierTasks.register(project);
		EvaluationReportTasks.register(project);
		EvaluationShellTasks.register(project);
	}
}
