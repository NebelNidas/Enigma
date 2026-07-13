package cuchaz.enigma.gradle;

import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class DownloadMinecraftAssetTask extends DefaultTask {
	@InputFile
	public abstract RegularFileProperty getVersionJson();

	@Input
	public abstract Property<String> getDownloadKey();

	@OutputFile
	public abstract RegularFileProperty getOutput();

	@TaskAction
	public void run() {
		JsonObject downloads = GradleJson.readObject(getVersionJson().get().getAsFile()).getAsJsonObject("downloads");
		JsonObject download = downloads.getAsJsonObject(getDownloadKey().get());

		if (download == null) {
			throw new GradleException("Minecraft version JSON has no downloads." + getDownloadKey().get());
		}

		DownloadSupport.download(download.get("url").getAsString(), download.get("sha1").getAsString(), getOutput().get().getAsFile());
	}
}
