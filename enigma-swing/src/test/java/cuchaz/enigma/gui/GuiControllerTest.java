package cuchaz.enigma.gui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.api.service.GuiService;
import cuchaz.enigma.api.service.JarIndexerService;
import cuchaz.enigma.api.service.NameProposalService;
import cuchaz.enigma.api.service.ProjectService;
import cuchaz.enigma.translation.mapping.EntryChange;
import cuchaz.enigma.translation.mapping.EntryUtil;
import cuchaz.enigma.translation.representation.MethodDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import cuchaz.enigma.utils.I18n;
import cuchaz.enigma.utils.validation.ValidationContext;

public class GuiControllerTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void enigmaLoadsCoreAndLlmTranslationsFromRuntimeClasspath() {
		Enigma enigma = Enigma.create();

		assertThat(I18n.translate("menu.file"), equalTo("File"));
		assertThat(I18n.translate("llm.menu.root"), equalTo("LLM"));
		assertTrue(I18n.getAvailableLanguages().contains("en_us"));
		assertThat(I18n.getLanguageName("en_us"), equalTo("English"));
		assertTrue(hasLlmService(enigma, JarIndexerService.TYPE));
		assertTrue(hasLlmService(enigma, NameProposalService.TYPE));
		assertTrue(hasLlmService(enigma, GuiService.TYPE));
		assertTrue(hasLlmService(enigma, ProjectService.TYPE));
	}

	@Test
	public void applyRenameRejectsInvalidNameWithoutMutatingMappings() throws Exception {
		EnigmaProject project = Enigma.create().openJar(createTestJar(), List.of(), ProgressListener.none());
		GuiController controller = new GuiController(null, null);
		controller.project = project;
		ClassEntry entry = new ClassEntry("a");

		assertFalse(controller.applyRename(entry, "not a valid class name"));

		assertThat(project.getMapper().getDeobfMapping(entry).targetName(), nullValue());
	}

	@Test
	public void applyRenameAppliesValidNameThroughMappingPath() throws Exception {
		EnigmaProject project = Enigma.create().openJar(createTestJar(), List.of(), ProgressListener.none());
		TestGuiController controller = new TestGuiController(project);
		ClassEntry entry = new ClassEntry("a");

		assertThat(controller.applyRename(entry, "RenamedClass"), equalTo(true));

		assertThat(project.getMapper().getDeobfMapping(entry).targetName(), equalTo("RenamedClass"));
	}

	@Test
	public void applyRenameAppliesParameterNameThroughMappingPath() throws Exception {
		EnigmaProject project = Enigma.create().openJar(createTestJar(), List.of(), ProgressListener.none());
		TestGuiController controller = new TestGuiController(project);
		MethodEntry method = new MethodEntry(new ClassEntry("a"), "m", new MethodDescriptor("(I)V"));
		LocalVariableEntry parameter = new LocalVariableEntry(method, 1, "p", true, null);

		assertThat(controller.applyRename(parameter, "count"), equalTo(true));

		assertThat(project.getMapper().getDeobfMapping(parameter).targetName(), equalTo("count"));
	}

	private Path createTestJar() throws IOException {
		Path root = this.temporaryFolder.getRoot().toPath();
		Path sourceDir = Files.createDirectories(root.resolve("src"));
		Path classesDir = Files.createDirectories(root.resolve("classes"));
		Path source = sourceDir.resolve("a.java");
		Files.writeString(source, "public class a { public void m(int p) {} }\n");

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull("Tests must run on a JDK, not a JRE", compiler);
		assertThat(compiler.run(null, null, null, "-d", classesDir.toString(), source.toString()), equalTo(0));

		Path jar = root.resolve("input.jar");

		try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
			output.putNextEntry(new JarEntry("a.class"));
			Files.copy(classesDir.resolve("a.class"), output);
			output.closeEntry();
		}

		return jar;
	}

	private static boolean hasLlmService(Enigma enigma, cuchaz.enigma.api.service.EnigmaServiceType<?> type) {
		return enigma.getServices().get(type).stream()
				.anyMatch(service -> service.getClass().getName().startsWith("cuchaz.enigma.llm."));
	}

	private static class TestGuiController extends GuiController {
		TestGuiController(EnigmaProject project) {
			super(null, null);
			this.project = project;
		}

		@Override
		public void applyChange(ValidationContext vc, EntryChange<?> change) {
			EntryUtil.applyChange(vc, this.project, this.project.getMapper(), change);
		}
	}
}
