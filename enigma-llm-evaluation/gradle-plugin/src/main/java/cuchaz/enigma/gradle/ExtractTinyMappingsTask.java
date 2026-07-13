package cuchaz.enigma.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class ExtractTinyMappingsTask extends DefaultTask {
	@InputFile
	public abstract RegularFileProperty getInput();

	@OutputFile
	public abstract RegularFileProperty getOutput();

	@TaskAction
	public void run() {
		File inputFile = getInput().get().getAsFile();
		File outputFile = getOutput().get().getAsFile();

		if (!outputFile.getParentFile().mkdirs() && !outputFile.getParentFile().isDirectory()) {
			throw new GradleException("Failed to create " + outputFile.getParentFile());
		}

		try (ZipFile zip = new ZipFile(inputFile)) {
			ZipEntry entry = zip.getEntry("mappings/mappings.tiny");

			if (entry == null) {
				throw new GradleException("No mappings/mappings.tiny in " + inputFile);
			}

			try (InputStream stream = zip.getInputStream(entry)) {
				Files.copy(stream, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new GradleException("Failed to extract mappings/mappings.tiny from " + inputFile, e);
		}
	}
}
