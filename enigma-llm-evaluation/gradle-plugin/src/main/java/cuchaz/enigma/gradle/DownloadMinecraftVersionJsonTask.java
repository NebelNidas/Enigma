package cuchaz.enigma.gradle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class DownloadMinecraftVersionJsonTask extends DefaultTask {
	@InputFile
	public abstract RegularFileProperty getManifest();

	@Input
	public abstract Property<String> getMinecraftVersion();

	@OutputFile
	public abstract RegularFileProperty getOutput();

	@TaskAction
	public void run() {
		for (JsonElement element : GradleJson.readObject(getManifest().get().getAsFile()).getAsJsonArray("versions")) {
			JsonObject version = element.getAsJsonObject();

			if (getMinecraftVersion().get().equals(version.get("id").getAsString())) {
				DownloadSupport.download(version.get("url").getAsString(), null, getOutput().get().getAsFile());
				return;
			}
		}

		throw new GradleException("Minecraft version " + getMinecraftVersion().get() + " not found in Mojang version manifest");
	}
}
