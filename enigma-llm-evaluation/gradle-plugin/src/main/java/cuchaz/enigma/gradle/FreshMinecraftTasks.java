package cuchaz.enigma.gradle;

import static cuchaz.enigma.gradle.EvaluationGradleSupport.VERIFICATION_GROUP;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.buildFile;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.evaluationScript;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.fileProperty;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.property;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.pythonExecutable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.Project;
import org.gradle.api.tasks.Exec;

final class FreshMinecraftTasks {
	private FreshMinecraftTasks() {
	}

	static void register(Project project) {
		registerFreshMinecraftAggregate(project);
	}

	private static void registerFreshMinecraftAggregate(Project project) {
		project.getTasks().register("aggregateFreshMinecraftBenchmark", Exec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Aggregates fresh-Minecraft Fable/Sol benchmark runs without live model calls.");

			File root = fileProperty(project, "freshMcScratchpad", buildFile(project, "llm-evaluation/fresh-mc"));
			File out = fileProperty(project, "freshMcAggregateOut", new File(root, "fresh_mc_aggregate.txt"));
			List<String> runSpecs = splitSpecs(property(project, "freshMcRuns", defaultRuns(root)));
			List<String> profileSpecs = splitSpecs(property(project, "freshMcProfiles", defaultProfiles(root)));
			List<String> mcnemar = splitSpecs(property(project, "freshMcMcnemar", "fable-low,sol-low"));

			for (String spec : runSpecs) {
				task.getInputs().dir(specPath(project, spec));
			}

			for (String spec : profileSpecs) {
				task.getInputs().file(specPath(project, spec));
			}

			task.getOutputs().file(out);

			List<String> commandLine = new ArrayList<>();
			commandLine.add(pythonExecutable(project));
			commandLine.add(evaluationScript(project, "aggregate_fresh_mc.py").getPath());

			for (String spec : runSpecs) {
				commandLine.add("--run");
				commandLine.add(spec);
			}

			for (String spec : profileSpecs) {
				commandLine.add("--profile");
				commandLine.add(spec);
			}

			if (mcnemar.size() == 2) {
				commandLine.add("--mcnemar");
				commandLine.add(mcnemar.get(0));
				commandLine.add(mcnemar.get(1));
			}

			commandLine.add("--profile-for-mcnemar");
			commandLine.add(property(project, "freshMcMcnemarProfile", "union"));
			commandLine.add("--out");
			commandLine.add(out.getPath());
			task.commandLine(commandLine);
		});
	}

	private static String defaultRuns(File root) {
		return String.join(",",
				"fable-low=" + new File(root, "run-fable-low"),
				"fable-low=" + new File(root, "run-fable-low-mc"),
				"fable-low=" + new File(root, "run-fable-low-parammc"),
				"sol-low=" + new File(root, "run-sol-low"),
				"sol-low=" + new File(root, "run-sol-low-parammc"),
				"sol-medium=" + new File(root, "run-sol-medium"),
				"sol-medium=" + new File(root, "run-sol-medium-parammc"));
	}

	private static String defaultProfiles(File root) {
		String name = "minecraft-26.3-snapshot-3-groundtruth.jsonl";
		return String.join(",",
				"union=" + new File(root, "fresh-mc-out/union/" + name),
				"yarn=" + new File(root, "fresh-mc-out/yarn/" + name),
				"mojmap=" + new File(root, "fresh-mc-out/mojmap/" + name));
	}

	private static List<String> splitSpecs(String value) {
		List<String> specs = new ArrayList<>();

		for (String item : value.split(",")) {
			String spec = item.strip();

			if (!spec.isEmpty()) {
				specs.add(spec);
			}
		}

		return specs;
	}

	private static File specPath(Project project, String spec) {
		int split = spec.indexOf('=');
		String path = split >= 0 ? spec.substring(split + 1) : spec;
		return project.file(path);
	}
}
