package cuchaz.enigma.gradle;

import static cuchaz.enigma.gradle.EvaluationGradleSupport.VERIFICATION_GROUP;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.buildFile;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.fileProperty;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.mainRuntimeClasspath;
import static cuchaz.enigma.gradle.EvaluationGradleSupport.property;

import java.io.File;
import java.util.List;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;

final class EvaluationCorpusTasks {
	private static final List<CorpusJar> CORPUS = List.of(
			new CorpusJar(
					"gson-2.11.0.jar",
					"https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar",
					"527175ca6d81050b53bdd4c457a6d6e017626b0e"),
			new CorpusJar(
					"commons-lang3-3.14.0.jar",
					"https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar",
					"1ed471194b02f2c6cb734a0cd6f6f107c673afae"),
			new CorpusJar(
					"xz-1.9.jar",
					"https://repo1.maven.org/maven2/org/tukaani/xz/1.9/xz-1.9.jar",
					"1ea4bec1a921180164852c65006d928617bd2caf"));

	private EvaluationCorpusTasks() {
	}

	static void register(Project project) {
		Configuration intermediaryMappings = registerMappingConfiguration(project, "minecraftIntermediaryMappings");
		Configuration yarnMappings = registerMappingConfiguration(project, "minecraftYarnMappings");
		registerMappingDependencies(project);
		registerDownloadCorpus(project);
		registerObfuscateCorpus(project);
		registerPrepareMinecraftInputs(project, intermediaryMappings, yarnMappings);
		registerBuildMinecraftCorpus(project);
		registerBuildMinecraftFreshCorpus(project);
	}

	private static Configuration registerMappingConfiguration(Project project, String name) {
		return project.getConfigurations().create(name, configuration -> {
			configuration.setCanBeConsumed(false);
			configuration.setCanBeResolved(true);
			configuration.setTransitive(false);
		});
	}

	private static void registerMappingDependencies(Project project) {
		String minecraftVersion = property(project, "mcVersion", "1.21.11");
		String yarnMappingsVersion = property(project, "mcYarnVersion", minecraftVersion + "+build.6");
		project.getDependencies().add("minecraftIntermediaryMappings", "net.fabricmc:intermediary:" + minecraftVersion + ":v2");
		project.getDependencies().add("minecraftYarnMappings", "net.fabricmc:yarn:" + yarnMappingsVersion + ":v2");
	}

	private static void registerDownloadCorpus(Project project) {
		List<TaskProvider<DownloadFileTask>> downloads = CORPUS.stream()
				.map(jar -> {
					File output = new File(corpusDir(project), jar.name);
					return project.getTasks().register("downloadCorpus" + taskSuffix(jar.name), DownloadFileTask.class, task -> {
						task.setGroup(VERIFICATION_GROUP);
						task.setDescription("Downloads " + jar.name + " for the deobfuscation benchmark.");
						task.getUrl().set(jar.url);
						task.getSha1().set(jar.sha1);
						task.getOutput().set(output);
					});
				})
				.toList();

		project.getTasks().register("downloadCorpus", task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Downloads the OSS ground-truth corpus jars for the deobfuscation benchmark (git-ignored).");
			task.dependsOn(downloads);
		});
	}

	private static void registerObfuscateCorpus(Project project) {
		project.getTasks().register("obfuscateCorpus", JavaExec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Obfuscates the downloaded corpus jars with tiny-remapper and emits ground-truth jsonl.");
			task.dependsOn("downloadCorpus");
			task.setClasspath(mainRuntimeClasspath(project));
			task.getMainClass().set("cuchaz.enigma.llm.ObfuscateCorpusTool");
			task.args(corpusDir(project).getPath(), buildFile(project, "llm-evaluation/obfuscated").getPath());
		});
	}

	private static void registerPrepareMinecraftInputs(Project project, Configuration intermediaryMappings, Configuration yarnMappings) {
		String minecraftVersion = property(project, "mcVersion", "1.21.11");
		File inputDir = minecraftInputDir(project, minecraftVersion);
		File versionManifest = new File(inputDir, "version_manifest_v2.json");
		File versionJson = new File(inputDir, minecraftVersion + ".json");
		File clientJar = new File(inputDir, "minecraft-" + minecraftVersion + "-client.jar");
		File mojmapTxt = new File(inputDir, "mojmap-client-" + minecraftVersion + ".txt");
		File intermediaryTiny = new File(inputDir, "intermediary-" + minecraftVersion + ".tiny");
		File yarnTiny = new File(inputDir, "yarn-" + minecraftVersion + ".tiny");
		File parchmentFile = new File(inputDir, "parchment-" + minecraftVersion + ".zip");
		String parchmentVersion = property(project, "mcParchmentVersion", "2025.12.20");

		TaskProvider<DownloadFileTask> manifestTask = project.getTasks()
				.register("downloadMinecraftVersionManifest", DownloadFileTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Downloads the Mojang Minecraft version manifest.");
					task.getUrl().set("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
					task.getOutput().set(versionManifest);
				});
		TaskProvider<DownloadMinecraftVersionJsonTask> versionJsonTask = project.getTasks()
				.register("downloadMinecraftVersionJson", DownloadMinecraftVersionJsonTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Downloads the selected Minecraft version metadata JSON.");
					task.dependsOn(manifestTask);
					task.getManifest().set(versionManifest);
					task.getMinecraftVersion().set(minecraftVersion);
					task.getOutput().set(versionJson);
				});
		TaskProvider<DownloadMinecraftAssetTask> clientTask = project.getTasks()
				.register("downloadMinecraftClientJar", DownloadMinecraftAssetTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Downloads the Minecraft client jar for the benchmark.");
					task.dependsOn(versionJsonTask);
					task.getVersionJson().set(versionJson);
					task.getDownloadKey().set("client");
					task.getOutput().set(clientJar);
				});
		TaskProvider<DownloadMinecraftAssetTask> mojmapTask = project.getTasks()
				.register("downloadMinecraftMojmap", DownloadMinecraftAssetTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Downloads Mojang's client mappings for the benchmark.");
					task.dependsOn(versionJsonTask);
					task.getVersionJson().set(versionJson);
					task.getDownloadKey().set("client_mappings");
					task.getOutput().set(mojmapTxt);
				});
		TaskProvider<ExtractTinyMappingsTask> intermediaryTask = project.getTasks()
				.register("extractMinecraftIntermediaryTiny", ExtractTinyMappingsTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Extracts mappings/mappings.tiny from the Fabric Intermediary mapping artifact.");
					task.getInput().set(project.getLayout().file(project.provider(() -> singleResolvedFile(intermediaryMappings))));
					task.getOutput().set(intermediaryTiny);
				});
		TaskProvider<ExtractTinyMappingsTask> yarnTask = project.getTasks()
				.register("extractMinecraftYarnTiny", ExtractTinyMappingsTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Extracts mappings/mappings.tiny from the Fabric Yarn mapping artifact.");
					task.getInput().set(project.getLayout().file(project.provider(() -> singleResolvedFile(yarnMappings))));
					task.getOutput().set(yarnTiny);
				});
		TaskProvider<DownloadFileTask> parchmentTask = project.getTasks()
				.register("downloadMinecraftParchment", DownloadFileTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Downloads the Parchment mappings zip for the benchmark.");
					task.getUrl().set("https://maven.parchmentmc.org/org/parchmentmc/data/parchment-" + minecraftVersion + "/"
							+ parchmentVersion + "/parchment-" + minecraftVersion + "-" + parchmentVersion + ".zip");
					task.getOutput().set(parchmentFile);
				});

		project.getTasks().register("prepareMinecraftInputs", task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Downloads Minecraft client, Mojmap, Intermediary, Yarn and Parchment inputs for the Minecraft benchmark.");
			task.dependsOn(clientTask, mojmapTask, intermediaryTask, yarnTask, parchmentTask);
		});
	}

	private static void registerBuildMinecraftCorpus(Project project) {
		project.getTasks().register("buildMinecraftCorpus", JavaExec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Builds the Minecraft full-corpus benchmark from downloaded Mojang/Fabric/Parchment mappings.");
			task.dependsOn("prepareMinecraftInputs");
			task.setClasspath(mainRuntimeClasspath(project));
			task.getMainClass().set("cuchaz.enigma.llm.MinecraftCorpusTool");

			String minecraftVersion = property(project, "mcVersion", "1.21.11");
			File inputDir = minecraftInputDir(project, minecraftVersion);
			File output = fileProperty(project, "mcOut", buildFile(project, "llm-evaluation/minecraft"));
			File clientJar = minecraftInputFile(project, "mcJar", inputDir, "minecraft-" + minecraftVersion + "-client.jar");
			File intermediaryTiny = minecraftInputFile(project, "mcIntermediaryTiny", inputDir, "intermediary-" + minecraftVersion + ".tiny");
			File yarnTiny = minecraftInputFile(project, "mcYarnMappings", inputDir, "yarn-" + minecraftVersion + ".tiny");
			File mojmapTxt = minecraftInputFile(project, "mcMojmap", inputDir, "mojmap-client-" + minecraftVersion + ".txt");
			File parchmentFile = minecraftInputFile(project, "mcParchment", inputDir, "parchment-" + minecraftVersion + ".zip");

			task.getInputs().files(clientJar, intermediaryTiny, yarnTiny, mojmapTxt, parchmentFile);
			task.getOutputs().dir(output);
			task.args(
					clientJar.getPath(),
					intermediaryTiny.getPath(),
					yarnTiny.getPath(),
					mojmapTxt.getPath(),
					parchmentFile.getPath(),
					output.getPath(),
					property(project, "mcBase", "minecraft-" + minecraftVersion));
		});
	}

	/**
	 * The "fresh Minecraft" memorization-control corpus (post-cutoff classes). Modern Minecraft is shipped
	 * de-obfuscated, so {@link cuchaz.enigma.llm.MinecraftFreshCorpusTool} obfuscates the official client jar
	 * itself. Every input is downloaded by the task: the official client jar from Mojang, and the merged
	 * ({@code official/intermediary/named}) tiny plus the baseline intermediary tiny from RelativityMC's
	 * public codemc maven. No local RelativityMC build is required. Each path is overridable with a
	 * {@code -P} property.
	 */
	private static void registerBuildMinecraftFreshCorpus(Project project) {
		String version = property(project, "mcFreshVersion", "26.3-snapshot-3");
		// The new-class baseline is the last Minecraft version released on or before the LATER of the two
		// model cutoffs (GPT-5.6 Sol: 2026-02-16; Fable: 2026-01). 26.1-snapshot-7 (2026-02-11) is that
		// version; the next one, 26.1-snapshot-8 (2026-02-17), is already post-cutoff. Every class new since
		// snapshot-7 therefore first appeared strictly after both cutoffs, so a correct recovery cannot be
		// rote recall. This spans ~5 months of development (Feb 17 -> Jul 7), giving a broad, domain-diverse
		// target set (1219 new classes) rather than the graphics-heavy 26.2->26.3 slice (483). To widen the
		// safety margin above the cutoff, override -PmcFreshBaselineVersion (e.g. 26.1, released 2026-03-24).
		String baseline = property(project, "mcFreshBaselineVersion", "26.1-snapshot-7");
		String yarnVersion = property(project, "mcFreshYarnVersion", version + "+build.4");
		// The baseline diff MUST come from the same artifact type as the current mappings (both merged
		// yarn). The pure "intermediary" tiny omits non-obfuscated com/mojang/* classes (official ==
		// intermediary), while the merged tiny lists them; mixing the two would flag long-existing classes
		// like blaze3d/platform/GLX as "new" and pollute the memorization control. So both sides use
		// modern-yarn:*:mergedv2.
		String baselineYarnVersion = property(project, "mcFreshBaselineYarnVersion", baseline + "+build.5");

		project.getRepositories().maven(repo -> {
			repo.setName("RelativityMC");
			repo.setUrl(project.uri("https://repo.codemc.io/repository/relativitymc/"));
		});

		Configuration mergedYarn = registerMappingConfiguration(project, "minecraftFreshMergedYarn");
		Configuration baselineMerged = registerMappingConfiguration(project, "minecraftFreshBaselineMerged");
		project.getDependencies().add("minecraftFreshMergedYarn", "org.relativitymc:modern-yarn:" + yarnVersion + ":mergedv2");
		project.getDependencies().add("minecraftFreshBaselineMerged", "org.relativitymc:modern-yarn:" + baselineYarnVersion + ":mergedv2");

		File inputDir = buildFile(project, "llm-evaluation/minecraft-fresh-inputs/" + version);
		File versionManifest = new File(inputDir, "version_manifest_v2.json");
		File versionJson = new File(inputDir, version + ".json");
		File clientJar = new File(inputDir, "minecraft-" + version + "-client.jar");
		File mergedTiny = new File(inputDir, "merged-" + version + ".tiny");
		File baselineTiny = new File(inputDir, "merged-baseline-" + baseline + ".tiny");

		TaskProvider<DownloadFileTask> manifestTask = project.getTasks()
				.register("downloadMinecraftFreshVersionManifest", DownloadFileTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Downloads the Mojang version manifest for the fresh-MC benchmark.");
					task.getUrl().set("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
					task.getOutput().set(versionManifest);
				});
		TaskProvider<DownloadMinecraftVersionJsonTask> versionJsonTask = project.getTasks()
				.register("downloadMinecraftFreshVersionJson", DownloadMinecraftVersionJsonTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Downloads the fresh Minecraft version metadata JSON.");
					task.dependsOn(manifestTask);
					task.getManifest().set(versionManifest);
					task.getMinecraftVersion().set(version);
					task.getOutput().set(versionJson);
				});
		TaskProvider<DownloadMinecraftAssetTask> clientTask = project.getTasks()
				.register("downloadMinecraftFreshClientJar", DownloadMinecraftAssetTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Downloads the de-obfuscated fresh Minecraft client jar.");
					task.dependsOn(versionJsonTask);
					task.getVersionJson().set(versionJson);
					task.getDownloadKey().set("client");
					task.getOutput().set(clientJar);
				});
		TaskProvider<ExtractTinyMappingsTask> mergedTinyTask = project.getTasks()
				.register("extractMinecraftFreshMergedTiny", ExtractTinyMappingsTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Extracts the merged official/intermediary/named tiny from the RelativityMC modern-yarn artifact.");
					task.getInput().set(project.getLayout().file(project.provider(() -> singleResolvedFile(mergedYarn))));
					task.getOutput().set(mergedTiny);
				});
		TaskProvider<ExtractTinyMappingsTask> baselineTinyTask = project.getTasks()
				.register("extractMinecraftFreshBaselineTiny", ExtractTinyMappingsTask.class, task -> {
					task.setGroup(VERIFICATION_GROUP);
					task.setDescription("Extracts the baseline merged tiny (new-class diff) from the RelativityMC modern-yarn artifact.");
					task.getInput().set(project.getLayout().file(project.provider(() -> singleResolvedFile(baselineMerged))));
					task.getOutput().set(baselineTiny);
				});

		File officialJarArg = fileProperty(project, "mcFreshOfficialJar", clientJar);
		File mergedTinyArg = fileProperty(project, "mcFreshMergedTiny", mergedTiny);
		File baselineTinyArg = fileProperty(project, "mcFreshBaselineTiny", baselineTiny);
		File output = fileProperty(project, "mcFreshOut", buildFile(project, "llm-evaluation/minecraft-fresh"));

		project.getTasks().register("buildMinecraftFreshCorpus", JavaExec.class, task -> {
			task.setGroup(VERIFICATION_GROUP);
			task.setDescription("Builds the fresh-Minecraft memorization-control corpus (post-cutoff classes, locally obfuscated).");
			task.dependsOn(clientTask, mergedTinyTask, baselineTinyTask);
			task.setClasspath(mainRuntimeClasspath(project));
			task.getMainClass().set("cuchaz.enigma.llm.MinecraftFreshCorpusTool");
			task.getInputs().files(officialJarArg, mergedTinyArg, baselineTinyArg);
			task.getOutputs().dir(output);
			task.args(
					officialJarArg.getPath(),
					mergedTinyArg.getPath(),
					baselineTinyArg.getPath(),
					output.getPath(),
					property(project, "mcFreshBase", "minecraft-" + version));
		});
	}

	private static File corpusDir(Project project) {
		return project.file(project.getProjectDir() + "/evaluation/corpus");
	}

	private static File minecraftInputDir(Project project, String minecraftVersion) {
		return buildFile(project, "llm-evaluation/minecraft-inputs/" + minecraftVersion);
	}

	private static File minecraftInputFile(Project project, String propertyName, File inputDir, String defaultName) {
		return fileProperty(project, propertyName, new File(inputDir, defaultName));
	}

	private static File singleResolvedFile(Configuration configuration) {
		return configuration.getIncoming().getFiles().getSingleFile();
	}

	private static String taskSuffix(String fileName) {
		StringBuilder out = new StringBuilder();
		boolean capitalizeNext = true;

		for (char c : fileName.toCharArray()) {
			if (Character.isLetterOrDigit(c)) {
				out.append(capitalizeNext ? Character.toUpperCase(c) : c);
				capitalizeNext = false;
			} else {
				capitalizeNext = true;
			}
		}

		return out.toString();
	}

	private record CorpusJar(String name, String url, String sha1) {
	}
}
