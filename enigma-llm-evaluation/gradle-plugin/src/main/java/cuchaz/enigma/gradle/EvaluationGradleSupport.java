package cuchaz.enigma.gradle;

import java.io.File;

import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;

final class EvaluationGradleSupport {
	static final String VERIFICATION_GROUP = "verification";

	private EvaluationGradleSupport() {
	}

	static String pythonExecutable(Project project) {
		return property(project, "codexPython", "python3");
	}

	static File evaluationScript(Project project, String name) {
		return project.file(project.getProjectDir() + "/evaluation/" + name);
	}

	static File frontierScoreOutputDir(Project project) {
		return fileProperty(project, "frontierScoreOut", buildFile(project, "llm-evaluation/frontier-scores"));
	}

	static File requiredJudgeFile(Project project, File scoreOut) {
		return fileProperty(project, "frontierJudgeRequired", scoreOut.getPath() + "/semantic_judge_required.jsonl");
	}

	static String property(Project project, String name, Object defaultValue) {
		Object value = project.findProperty(name);
		return (value != null ? value : defaultValue).toString();
	}

	static boolean booleanProperty(Project project, String name) {
		return project.hasProperty(name) && Boolean.parseBoolean(project.property(name).toString());
	}

	static File buildFile(Project project, String relativePath) {
		return project.getLayout().getBuildDirectory().file(relativePath).get().getAsFile();
	}

	static File fileProperty(Project project, String name, Object defaultValue) {
		return project.file(project.findProperty(name) != null ? project.findProperty(name) : defaultValue);
	}

	static File optionalFileProperty(Project project, String name) {
		return project.hasProperty(name) ? project.file(project.property(name)) : null;
	}

	static FileCollection mainRuntimeClasspath(Project project) {
		JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
		return java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME).getRuntimeClasspath();
	}
}
