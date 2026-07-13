package cuchaz.enigma.gradle;

import static cuchaz.enigma.gradle.EvaluationGradleSupport.VERIFICATION_GROUP;

import org.gradle.api.Project;
import org.gradle.api.tasks.Exec;

final class EvaluationShellTasks {
	private EvaluationShellTasks() {
	}

	static void register(Project project) {
		registerShellTask(project, "runObfuscationSweep", "evaluation/run-obfuscation-sweep.sh",
				"Runs the local-model obfuscation sweep generator; requires LM Studio/SSH model switching.");
		registerShellTask(project, "runBackendAblation", "evaluation/run-backend-ablation.sh",
				"Runs the forced owner/graph context backend ablation generator; requires the configured local model endpoint.");
		registerShellTask(project, "runCacheOffAblation", "evaluation/run-cacheoff.sh",
				"Runs the duplicate-name cache-off ablation generator; requires LM Studio locally.");
		registerShellTask(project, "runTemp02Stability", "evaluation/run-temp02-stability.sh",
				"Runs the temp=0.2 stability generator; requires LM Studio locally.");
		registerShellTask(project, "runQuantAblation", "evaluation/run-quant-ablation.sh",
				"Runs the qwen2.5-coder-14B quantization ablation generator; requires LM Studio/SSH model switching.");
		registerShellTask(project, "run7bSweep", "evaluation/7b-loop/run-7b-sweep.sh",
				"Runs the 7B DEV backend/hint grid generator; requires a loaded local 7B endpoint.");
		registerShellTask(project, "run7bPromptExtension", "evaluation/7b-loop/run-7b-ext.sh",
				"Runs the 7B prompt-extension DEV generator; requires a loaded local 7B endpoint.");
		registerShellTask(project, "run7bHoldout", "evaluation/7b-loop/run-7b-holdout.sh",
				"Runs the 7B xz holdout generator; requires a loaded local 7B endpoint.");
	}

	private static void registerShellTask(Project project, String taskName, String scriptPath, String taskDescription) {
		project.getTasks().register(taskName, Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription(taskDescription);
			task.setWorkingDir(project.getRootProject().getProjectDir());
			task.commandLine("bash", project.file(project.getProjectDir() + "/" + scriptPath).getPath());
		});
	}
}
