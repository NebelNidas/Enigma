package cuchaz.enigma.gradle;

import static cuchaz.enigma.gradle.EvaluationGradleSupport.VERIFICATION_GROUP;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.booleanProperty;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.buildFile;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.evaluationScript;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.fileProperty;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.mainRuntimeClasspath;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.optionalFileProperty;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.property;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.pythonExecutable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.gradle.api.Project;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.JavaExec;

final class PromptBatchTasks {
	private static final String PROMPT_PREP_MARKER = ".codex-prompt-prep-owned";
	private static final List<PromptArm> PROMPT_ARMS = List.of(
			new PromptArm("M", false, null),
			new PromptArm("MC", true, null),
			new PromptArm("MPP", true, "sterile"));

	private PromptBatchTasks() {
	}

	static void register(Project project) {
		registerPromptDumpTasks(project);
		registerPromptBatchPreparation(project);
		registerCodexPromptBatch(project);
		registerFablePromptBatch(project);
		registerCodexPromptBatchScoring(project);
		registerCodexPromptPairingVerification(project);
	}

	private static File promptDumpRoot(Project project) {
		return fileProperty(project, "codexPromptDumpRoot", buildFile(project, "llm-evaluation/codex-prompt-dumps"));
	}

	private static File promptBatchRoot(Project project) {
		return fileProperty(project, "codexPromptBatchRoot", buildFile(project, "llm-evaluation/prompt-batches"));
	}

	private static boolean containsCodexModelOutputs(File dir) {
		if (!dir.exists()) {
			return false;
		}

		try (Stream<Path> stream = Files.walk(dir.toPath())) {
			return stream
					.filter(Files::isRegularFile)
					.filter(path -> {
						String fileName = path.getFileName().toString();
						return fileName.endsWith(".json") || fileName.endsWith(".jsonl");
					})
					.anyMatch(PromptBatchTasks::containsModelOutputMarker);
		} catch (IOException e) {
			throw new RuntimeException("Failed to inspect " + dir, e);
		}
	}

	private static boolean containsModelOutputMarker(Path path) {
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			String line;

			while ((line = reader.readLine()) != null) {
				if (line.contains("\"attempted\":true") || line.contains("\"requestedModel\"")
						|| (line.contains("\"ok\"") && line.contains("\"suggestedName\""))) {
					return true;
				}
			}

			return false;
		} catch (IOException e) {
			throw new RuntimeException("Failed to inspect " + path, e);
		}
	}

	private static void resetGeneratedDir(File dir, String label, File buildEvalDir,
			boolean deleteModelOutputsAllowed, boolean overwriteUnmarkedAllowed) {
		try {
			File target = dir.getCanonicalFile();
			boolean underBuildEval = target.getPath().equals(buildEvalDir.getPath())
					|| target.getPath().startsWith(buildEvalDir.getPath() + File.separator);
			File marker = new File(target, PROMPT_PREP_MARKER);

			if (target.exists()) {
				if (containsCodexModelOutputs(target) && !deleteModelOutputsAllowed) {
					throw new IllegalStateException("Refusing to delete " + label + " at " + target
							+ ": it appears to contain Codex model outputs. Move them, choose another output directory, "
							+ "or pass -PcodexPromptDeleteModelOutputs=true if this is intentional.");
				}

				if (!underBuildEval && !marker.exists() && !overwriteUnmarkedAllowed) {
					throw new IllegalStateException("Refusing to delete unmarked " + label + " outside build/llm-evaluation: "
							+ target + ". Use a fresh directory or pass -PcodexPromptOverwriteUnmarked=true after checking its contents.");
				}

				deleteRecursively(target.toPath());
			}

			Files.createDirectories(target.toPath());
			Files.writeString(marker.toPath(), "Owned by prepareCodexPromptBatches; safe to regenerate prompt dumps/batches here.\n");
		} catch (IOException e) {
			throw new RuntimeException("Failed to reset " + label + " at " + dir, e);
		}
	}

	private static void deleteRecursively(Path root) throws IOException {
		try (Stream<Path> stream = Files.walk(root)) {
			for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}

	private static void configurePromptDump(Project project, JavaExec task, File dumpDir, File resultsDir,
			boolean includeCode, String codeControl) {
		File buildEvalDir = buildFile(project, "llm-evaluation");
		boolean deleteModelOutputsAllowed = project.hasProperty("codexPromptDeleteModelOutputs");
		boolean overwriteUnmarkedAllowed = project.hasProperty("codexPromptOverwriteUnmarked");
		task.getOutputs().dir(dumpDir);
		task.getOutputs().dir(resultsDir);
		task.doFirst(t -> {
			resetGeneratedDir(dumpDir, "prompt dump directory", buildEvalDir, deleteModelOutputsAllowed, overwriteUnmarkedAllowed);
			resetGeneratedDir(resultsDir, "structural prompt-dump result directory", buildEvalDir, deleteModelOutputsAllowed, overwriteUnmarkedAllowed);
		});
		task.environment("ENIGMA_LLM_MODEL", "");
		task.environment("ENIGMA_LLM_DUMP_PROMPTS", dumpDir.getPath());

		if (includeCode) {
			task.environment("ENIGMA_LLM_BENCH_CODE", "true");
		}

		if (codeControl != null) {
			task.environment("ENIGMA_LLM_BENCH_CODE_CONTROL", codeControl);
		}

		if (project.hasProperty("maxPromptChars")) {
			task.environment("ENIGMA_LLM_MAX_PROMPT_CHARS", project.property("maxPromptChars"));
		}
	}

	private static void registerPromptDumpTasks(Project project) {
		for (PromptArm arm : PROMPT_ARMS) {
			project.getTasks().register("dumpCodexObscurePrompts" + arm.name, JavaExec.class, task -> {
				task.setGroup(VERIFICATION_GROUP);
				task.setDescription("Dumps offline obscure-corpus prompts for the Codex " + arm.name + " arm.");
				task.dependsOn("obfuscateCorpus");
				task.setClasspath(mainRuntimeClasspath(project));
				task.getMainClass().set("cuchaz.enigma.llm.LlmObfuscationBenchmarkHarness");
				File dumpDir = new File(promptDumpRoot(project), "obscure_" + arm.name);
				File resultsDir = new File(promptDumpRoot(project), "obscure_" + arm.name + "_structural");
				task.args(buildFile(project, "llm-evaluation/obfuscated").getPath(), resultsDir.getPath());
				configurePromptDump(project, task, dumpDir, resultsDir, arm.includeCode, arm.codeControl);
			});

			project.getTasks().register("dumpCodexMinecraftPrompts" + arm.name, JavaExec.class, task -> {
				task.setGroup(VERIFICATION_GROUP);
				task.setDescription("Dumps offline Minecraft prompts for the Codex " + arm.name + " arm.");
				task.dependsOn("buildMinecraftCorpus");
				task.setClasspath(mainRuntimeClasspath(project));
				task.getMainClass().set("cuchaz.enigma.llm.LlmObfuscationBenchmarkHarness");
				String profile = property(project, "mcProfile", "union").toLowerCase(Locale.ROOT);

				if (!List.of("yarn", "mojmap", "union").contains(profile)) {
					throw new IllegalArgumentException("mcProfile must be one of yarn, mojmap, union; got " + profile);
				}

				File dumpDir = new File(promptDumpRoot(project), "mc_" + arm.name);
				File resultsDir = new File(promptDumpRoot(project), "mc_" + arm.name + "_structural");
				task.args(buildFile(project, "llm-evaluation/minecraft/" + profile).getPath(), resultsDir.getPath());
				configurePromptDump(project, task, dumpDir, resultsDir, arm.includeCode, arm.codeControl);
			});
		}
	}

	private static void registerPromptBatchPreparation(Project project) {
		project.getTasks().register("prepareCodexPromptBatches", Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Builds paired M/M+C/M++ prompt batches for Codex/Fable replay from offline benchmark dumps.");
			task.dependsOn(
					"dumpCodexObscurePromptsM", "dumpCodexObscurePromptsMC", "dumpCodexObscurePromptsMPP",
					"dumpCodexMinecraftPromptsM", "dumpCodexMinecraftPromptsMC", "dumpCodexMinecraftPromptsMPP");

			File batchRoot = promptBatchRoot(project);
			File dumpRoot = promptDumpRoot(project);
			File buildEvalDir = buildFile(project, "llm-evaluation");
			boolean deleteModelOutputsAllowed = project.hasProperty("codexPromptDeleteModelOutputs");
			boolean overwriteUnmarkedAllowed = project.hasProperty("codexPromptOverwriteUnmarked");
			task.getOutputs().dir(batchRoot);
			task.doFirst(t -> resetGeneratedDir(batchRoot, "filtered prompt batch directory", buildEvalDir,
					deleteModelOutputsAllowed, overwriteUnmarkedAllowed));
			task.commandLine(
					pythonExecutable(project),
					evaluationScript(project, "prepare_codex_prompt_batches.py").getPath(),
					"--out", batchRoot.getPath(),
					"--obscure-arm", "M=" + new File(dumpRoot, "obscure_M").getPath(),
					"--obscure-arm", "MC=" + new File(dumpRoot, "obscure_MC").getPath(),
					"--obscure-arm", "MPP=" + new File(dumpRoot, "obscure_MPP").getPath(),
					"--mc-arm", "M=" + new File(dumpRoot, "mc_M").getPath(),
					"--mc-arm", "MC=" + new File(dumpRoot, "mc_MC").getPath(),
					"--mc-arm", "MPP=" + new File(dumpRoot, "mc_MPP").getPath());
		});
	}

	private static void registerCodexPromptBatch(Project project) {
		project.getTasks().register("runCodexPromptBatch", Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Runs dumped prompt JSONL batches through codex exec in a resumable, auditable layout.");

			if (!project.hasProperty("codexPromptRoot")) {
				task.dependsOn("prepareCodexPromptBatches");
			}

			File promptRoot = fileProperty(project, "codexPromptRoot", buildFile(project, "llm-evaluation/prompt-batches"));
			File out = fileProperty(project, "codexOut", buildFile(project, "llm-evaluation/codex-prompt-batch"));

			task.commandLine(
					pythonExecutable(project),
					evaluationScript(project, "run_codex_prompt_batch.py").getPath(),
					"--prompt-root", promptRoot.getPath(),
					"--out", out.getPath(),
					"--model", property(project, "codexModel", "gpt-5.6-sol"),
					"--effort", property(project, "codexEffort", "high"),
					"--datasets", property(project, "codexDatasets", "obscure=batch,mc=mc_batch"),
					"--arms", property(project, "codexArms", "M,MC,MPP"),
					"--tracks", property(project, "codexTracks", "realistic"),
					"--kinds", property(project, "codexKinds", "METHOD"),
					"--parallel", property(project, "codexParallel", "2"),
					"--limit", property(project, "codexLimit", "0"),
					"--timeout-seconds", property(project, "codexTimeoutSeconds", "420"));

			if (project.hasProperty("codexNeutralCwd")) {
				task.args("--neutral-cwd", optionalFileProperty(project, "codexNeutralCwd").getPath());
			}

			if (project.hasProperty("codexSchema")) {
				task.args("--schema", optionalFileProperty(project, "codexSchema").getPath());
			}

			if (booleanProperty(project, "codexDryRun")) {
				task.args("--dry-run");
			}
		});
	}

	private static void registerFablePromptBatch(Project project) {
		project.getTasks().register("runFablePromptBatch", Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Runs dumped prompt JSONL batches through the Claude/Fable CLI in a resumable, auditable layout.");

			if (!project.hasProperty("fablePromptRoot")) {
				task.dependsOn("prepareCodexPromptBatches");
			}

			File promptRoot = fileProperty(project, "fablePromptRoot", buildFile(project, "llm-evaluation/prompt-batches"));
			File out = fileProperty(project, "fableOut", buildFile(project, "llm-evaluation/fable-prompt-batch"));

			task.commandLine(
					pythonExecutable(project),
					evaluationScript(project, "run_fable_prompt_batch.py").getPath(),
					"--prompt-root", promptRoot.getPath(),
					"--out", out.getPath(),
					"--model", property(project, "fableModel", "fable"),
					"--effort", property(project, "fableEffort", "high"),
					"--datasets", property(project, "fableDatasets", "obscure=batch,mc=mc_batch"),
					"--arms", property(project, "fableArms", "M,MC,MPP"),
					"--tracks", property(project, "fableTracks", "realistic"),
					"--kinds", property(project, "fableKinds", "METHOD"),
					"--parallel", property(project, "fableParallel", "5"),
					"--limit", property(project, "fableLimit", "0"),
					"--timeout-seconds", property(project, "fableTimeoutSeconds", "420"));

			if (project.hasProperty("fableNeutralCwd")) {
				task.args("--neutral-cwd", optionalFileProperty(project, "fableNeutralCwd").getPath());
			}

			if (project.hasProperty("fableDisallowedTools")) {
				task.args("--disallowed-tools", project.property("fableDisallowedTools").toString());
			}

			if (booleanProperty(project, "fableRetryFailed")) {
				task.args("--retry-failed");
			}

			if (booleanProperty(project, "fableAllowLegacyResume")) {
				task.args("--allow-legacy-resume");
			}

			if (booleanProperty(project, "fableDryRun")) {
				task.args("--dry-run");
			}
		});
	}

	private static void registerCodexPromptBatchScoring(Project project) {
		project.getTasks().register("scoreCodexPromptBatch", Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Scores runCodexPromptBatch output directories with exact/normalized/usable and latency summaries.");

			File out = fileProperty(project, "codexOut", buildFile(project, "llm-evaluation/codex-prompt-batch"));
			task.commandLine(
					pythonExecutable(project),
					evaluationScript(project, "score_prompt_batch.py").getPath(),
					out.getPath());
		});
	}

	private static void registerCodexPromptPairingVerification(Project project) {
		project.getTasks().register("verifyCodexPromptPairing", Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Verifies that M+C and M++ prompt batches share the same non-code context.");

			File promptRoot = fileProperty(project, "codexPromptRoot", buildFile(project, "llm-evaluation/prompt-batches"));
			File out = optionalFileProperty(project, "codexPromptPairingReport");

			task.commandLine(
					pythonExecutable(project),
					evaluationScript(project, "verify_prompt_pairing.py").getPath(),
					"--prompt-root", promptRoot.getPath());

			if (out != null) {
				task.args("--out", out.getPath());
			}
		});
	}

	private record PromptArm(String name, boolean includeCode, String codeControl) {
	}
}
