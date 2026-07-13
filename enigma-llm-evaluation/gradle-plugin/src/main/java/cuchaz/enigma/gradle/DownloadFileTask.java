package cuchaz.enigma.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class DownloadFileTask extends DefaultTask {
	@Input
	public abstract Property<String> getUrl();

	@Input
	@Optional
	public abstract Property<String> getSha1();

	@OutputFile
	public abstract RegularFileProperty getOutput();

	@TaskAction
	public void run() {
		DownloadSupport.download(getUrl().get(), getSha1().getOrElse(""), getOutput().get().getAsFile());
	}
}
