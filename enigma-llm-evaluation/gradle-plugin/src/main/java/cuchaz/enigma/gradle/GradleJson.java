package cuchaz.enigma.gradle;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.GradleException;

final class GradleJson {
	private GradleJson() {
	}

	static JsonObject readObject(File file) {
		try (Reader reader = Files.newBufferedReader(file.toPath())) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		} catch (IOException e) {
			throw new GradleException("Failed to read JSON from " + file, e);
		}
	}
}
