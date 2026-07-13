package cuchaz.enigma.gradle;

import static cuchaz.enigma.gradle.EvaluationGradleSupport.VERIFICATION_GROUP;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.buildFile;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.fileProperty;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.mainRuntimeClasspath;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.property;

import java.io.File;
import java.util.Locale;
import java.util.Set;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;

final class HarnessJavaExecTasks {
	private static final Set<String> MINECRAFT_PROFILES = Set.of("yarn", "mojmap", "union");

	private HarnessJavaExecTasks() {
	}

	static void register(Project project) {
		registerBenchmarkObfuscation(project);
		registerBenchmarkMinecraftCorpus(project);
		registerRunEvaluation(project);
		registerCompareContextBackends(project);
		registerSummarizeEvaluation(project);
	}

	private static String rootRelativePath(Project project, Object value) {
		return value == null ? null : project.getRootProject().file(value.toString()).getPath();
	}

	private static void configureBenchmarkEnvironment(Project project, JavaExec task, String prefix) {
		if (project.hasProperty(prefix + "DumpPrompts")) {
			task.environment("ENIGMA_LLM_DUMP_PROMPTS", project.file(project.property(prefix + "DumpPrompts")).getPath());
		}

		if (project.hasProperty(prefix + "Code")) {
			task.environment("ENIGMA_LLM_BENCH_CODE", project.property(prefix + "Code"));
		}

		if (project.hasProperty(prefix + "CodeControl")) {
			task.environment("ENIGMA_LLM_BENCH_CODE_CONTROL", project.property(prefix + "CodeControl"));
		}

		if (project.hasProperty(prefix + "CodeControlSeed")) {
			task.environment("ENIGMA_LLM_BENCH_CODE_CONTROL_SEED", project.property(prefix + "CodeControlSeed"));
		}

		if (project.hasProperty("maxPromptChars")) {
			task.environment("ENIGMA_LLM_MAX_PROMPT_CHARS", project.property("maxPromptChars"));
		}
	}

	private static void registerBenchmarkObfuscation(Project project) {
		project.getTasks().register("benchmarkObfuscation", JavaExec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Phase A: drives the real suggestion engine over the obfuscated corpus and scores name recovery vs ground truth.");
			task.dependsOn("obfuscateCorpus");
			task.setClasspath(mainRuntimeClasspath(project));
			task.getMainClass().set("cuchaz.enigma.llm.LlmObfuscationBenchmarkHarness");

			File obfDir = fileProperty(project, "obfDir", buildFile(project, "llm-evaluation/obfuscated"));
			File resultsDir = fileProperty(project, "resultsDir", buildFile(project, "llm-evaluation/benchmark"));
			task.args(obfDir.getPath(), resultsDir.getPath());

			if (project.hasProperty("limit")) {
				task.environment("ENIGMA_LLM_BENCH_LIMIT", project.property("limit"));
			}
		});
	}

	private static void registerBenchmarkMinecraftCorpus(Project project) {
		project.getTasks().register("benchmarkMinecraftCorpus", JavaExec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Runs the LLM obfuscation benchmark over a downloaded/built Minecraft corpus profile.");
			task.dependsOn("buildMinecraftCorpus");
			task.setClasspath(mainRuntimeClasspath(project));
			task.getMainClass().set("cuchaz.enigma.llm.LlmObfuscationBenchmarkHarness");

			String profile = property(project, "mcProfile", "union").toLowerCase(Locale.ROOT);

			if (!MINECRAFT_PROFILES.contains(profile)) {
				throw new GradleException("mcProfile must be one of yarn, mojmap, union; got " + profile);
			}

			File obfDir = buildFile(project, "llm-evaluation/minecraft/" + profile);
			File resultsDir = fileProperty(project, "mcResultsDir", buildFile(project, "llm-evaluation/minecraft-benchmark/" + profile));
			task.args(obfDir.getPath(), resultsDir.getPath());

			if (project.hasProperty("limit")) {
				task.environment("ENIGMA_LLM_BENCH_LIMIT", project.property("limit"));
			}

			configureBenchmarkEnvironment(project, task, "mc");
		});
	}

	private static void registerRunEvaluation(Project project) {
		project.getTasks().register("runEvaluation", JavaExec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Runs the LLM name suggestion evaluation JSONL harness.");
			task.setClasspath(mainRuntimeClasspath(project));
			task.getMainClass().set("cuchaz.enigma.llm.LlmEvaluationHarness");
			task.args(
					rootRelativePath(project, project.findProperty("cases") != null
							? project.findProperty("cases")
							: project.getProjectDir() + "/evaluation/sample-cases.jsonl"),
					rootRelativePath(project, project.findProperty("out") != null
							? project.findProperty("out")
							: buildFile(project, "llm-evaluation/results.jsonl")));
		});
	}

	private static void registerCompareContextBackends(Project project) {
		project.getTasks().register("compareContextBackends", JavaExec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Runs a small owner-vs-graph context backend comparison against the configured LLM endpoint.");
			task.setClasspath(mainRuntimeClasspath(project));
			task.getMainClass().set("cuchaz.enigma.llm.LlmContextBackendComparisonHarness");
			task.args(rootRelativePath(project, project.findProperty("out") != null
					? project.findProperty("out")
					: buildFile(project, "llm-evaluation/context-backend-comparison.jsonl")));
		});
	}

	private static void registerSummarizeEvaluation(Project project) {
		project.getTasks().register("summarizeEvaluation", JavaExec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Summarizes an existing LLM evaluation JSONL result file.");
			task.setClasspath(mainRuntimeClasspath(project));
			task.getMainClass().set("cuchaz.enigma.llm.LlmEvaluationSummaryTool");
			task.args(rootRelativePath(project, project.findProperty("results") != null
					? project.findProperty("results")
					: buildFile(project, "llm-evaluation/results.jsonl")));
		});
	}
}
