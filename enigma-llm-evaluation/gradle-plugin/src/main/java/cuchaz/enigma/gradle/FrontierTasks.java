package cuchaz.enigma.gradle;

import static cuchaz.enigma.gradle.EvaluationGradleSupport.VERIFICATION_GROUP;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.booleanProperty;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.buildFile;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.evaluationScript;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.fileProperty;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.frontierScoreOutputDir;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.optionalFileProperty;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.property;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.pythonExecutable;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.requiredJudgeFile;

import java.io.File;

import org.gradle.api.Project;
import org.gradle.api.tasks.Exec;

final class FrontierTasks {
	private FrontierTasks() {
	}

	static void register(Project project) {
		registerScoreFrontierRuns(project);
		registerCodexJudge(project);
		registerGrokJudge(project);
		registerJudgeComparison(project);
		registerJudgeSummary(project);
	}

	private static void registerScoreFrontierRuns(Project project) {
		project.getTasks().register("scoreFrontierRuns", Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Scores paired Fable/Sol M/M+C/M++ frontier output directories and prepares semantic-judge candidates.");

			File fableOut = fileProperty(project, "frontierFableOut", buildFile(project, "llm-evaluation/fable-prompt-batch"));
			File solOut = fileProperty(project, "frontierSolOut", buildFile(project, "llm-evaluation/codex-prompt-batch"));
			File scoreOut = frontierScoreOutputDir(project);
			File promptRoot = optionalFileProperty(project, "frontierPromptRoot");

			task.commandLine(
					pythonExecutable(project),
					evaluationScript(project, "score_frontier_runs.py").getPath(),
					"--fable-out", fableOut.getPath(),
					"--sol-out", solOut.getPath(),
					"--out", scoreOut.getPath(),
					"--datasets", property(project, "frontierDatasets", "obscure=batch,mc=mc_batch"),
					"--tracks", property(project, "frontierTracks", "realistic"),
					"--kinds", property(project, "frontierKinds", "METHOD"));

			if (promptRoot != null) {
				task.args("--prompt-root", promptRoot.getPath());
			}
		});
	}

	private static void registerCodexJudge(Project project) {
		project.getTasks().register("runFrontierSemanticJudge", Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Runs the GPT/Codex semantic-judge v2 rubric over frontier residual candidates.");

			File scoreOut = frontierScoreOutputDir(project);
			File required = requiredJudgeFile(project, scoreOut);

			task.commandLine(
					pythonExecutable(project),
					evaluationScript(project, "run_frontier_semantic_judge.py").getPath(),
					"--required", required.getPath(),
					"--out", scoreOut.getPath(),
					"--model", property(project, "frontierJudgeModel", "gpt-5.5"),
					"--effort", property(project, "frontierJudgeEffort", "high"),
					"--batch-size", property(project, "frontierJudgeBatchSize", "8"),
					"--limit", property(project, "frontierJudgeLimit", "0"),
					"--timeout-seconds", property(project, "frontierJudgeTimeoutSeconds", "600"));

			if (project.hasProperty("frontierJudgeNeutralCwd")) {
				task.args("--neutral-cwd", optionalFileProperty(project, "frontierJudgeNeutralCwd").getPath());
			}

			if (project.hasProperty("frontierJudgeSchema")) {
				task.args("--schema", optionalFileProperty(project, "frontierJudgeSchema").getPath());
			}

			if (booleanProperty(project, "frontierJudgeOverwrite")) {
				task.args("--overwrite");
			}

			if (booleanProperty(project, "frontierJudgeDryRun")) {
				task.args("--dry-run");
			}
		});
	}

	private static void registerGrokJudge(Project project) {
		project.getTasks().register("runFrontierGrokSemanticJudge", Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Runs the Grok semantic-judge v2 rubric over frontier residual candidates.");

			File scoreOut = frontierScoreOutputDir(project);
			File required = requiredJudgeFile(project, scoreOut);

			task.commandLine(
					pythonExecutable(project),
					evaluationScript(project, "run_frontier_grok_semantic_judge.py").getPath(),
					"--required", required.getPath(),
					"--out", scoreOut.getPath(),
					"--model", property(project, "frontierGrokJudgeModel", "grok-build"),
					"--effort", property(project, "frontierGrokJudgeEffort", "default"),
					"--batch-size", property(project, "frontierGrokJudgeBatchSize", "8"),
					"--limit", property(project, "frontierGrokJudgeLimit", "0"),
					"--timeout-seconds", property(project, "frontierGrokJudgeTimeoutSeconds", "600"),
					"--retries", property(project, "frontierGrokJudgeRetries", "3"));

			if (project.hasProperty("frontierGrokJudgeNeutralCwd")) {
				task.args("--neutral-cwd", optionalFileProperty(project, "frontierGrokJudgeNeutralCwd").getPath());
			}

			if (booleanProperty(project, "frontierGrokJudgeOverwrite")) {
				task.args("--overwrite");
			}

			if (booleanProperty(project, "frontierGrokJudgeDryRun")) {
				task.args("--dry-run");
			}
		});
	}

	private static void registerJudgeComparison(Project project) {
		project.getTasks().register("compareFrontierSemanticJudges", Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Compares two frontier semantic-judge verdict files by judge_id.");

			File scoreOut = frontierScoreOutputDir(project);
			File left = fileProperty(project, "frontierJudgeLeft", scoreOut.getPath() + "/semantic_judge_codex_gpt-5.5_high.json");
			File right = fileProperty(project, "frontierJudgeRight", scoreOut.getPath() + "/semantic_judge_codex_gpt-5.6-sol_high.json.partial");
			File out = fileProperty(project, "frontierJudgeComparisonOut", scoreOut.getPath() + "/semantic_judge_gpt55_vs_sol_high.txt");

			task.commandLine(
					pythonExecutable(project),
					evaluationScript(project, "compare_frontier_semantic_judges.py").getPath(),
					"--left", left.getPath(),
					"--right", right.getPath(),
					"--left-label", property(project, "frontierJudgeLeftLabel", "gpt-5.5-high"),
					"--right-label", property(project, "frontierJudgeRightLabel", "gpt-5.6-sol-high"),
					"--out", out.getPath());
		});
	}

	private static void registerJudgeSummary(Project project) {
		project.getTasks().register("summarizeFrontierSemanticJudge", Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Summarizes semantic-judge v2 residual verdicts without live model calls.");

			File scoreOut = frontierScoreOutputDir(project);
			File required = requiredJudgeFile(project, scoreOut);
			File potentialCells = fileProperty(project, "frontierJudgePotentialCells",
					scoreOut.getPath() + "/semantic_judge_potential_residual_cells.jsonl");
			File judge = fileProperty(project, "frontierJudgeResults", scoreOut.getPath() + "/semantic_judge_codex_gpt-5.5_high.json");
			File out = fileProperty(project, "frontierJudgeSummaryOut", scoreOut.getPath() + "/semantic_judge_gpt55_summary.txt");
			File jsonOut = fileProperty(project, "frontierJudgeSummaryJson", scoreOut.getPath() + "/semantic_judge_gpt55_summary.json");

			task.getInputs().files(required, judge, potentialCells);
			task.getOutputs().files(out, jsonOut);
			task.commandLine(
					pythonExecutable(project),
					evaluationScript(project, "summarize_frontier_semantic_judge.py").getPath(),
					"--required", required.getPath(),
					"--judge", judge.getPath(),
					"--potential-cells", potentialCells.getPath(),
					"--out", out.getPath(),
					"--json-out", jsonOut.getPath());

			if (booleanProperty(project, "frontierJudgeSummaryOverwrite")) {
				task.args("--overwrite");
			}
		});
	}
}
