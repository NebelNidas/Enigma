package cuchaz.enigma.gradle;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import org.gradle.api.GradleException;

/**
 * Minimal file downloader for the evaluation inputs (Minecraft client, mappings, Parchment, corpus jars).
 *
 * <p>Uses the JDK {@link HttpClient} directly rather than Fabric loom's download helper: loom's downloader
 * does not follow HTTP redirects, so hosts that 302 to a CDN (e.g. parchmentmc) could not be fetched and a
 * corpus could not be rebuilt from scratch. The JDK client follows redirects natively and has no build-tool
 * version coupling (newer loom would force a whole-build Gradle upgrade), so the plugin no longer depends on loom.
 */
final class DownloadSupport {
	private static final int MAX_RETRIES = 3;

	private DownloadSupport() {
	}

	static void download(String url, String sha1, File output) {
		IOException last = null;

		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			try {
				attempt(url, sha1, output);
				return;
			} catch (IOException e) {
				last = e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new GradleException("Interrupted downloading " + url + " to " + output, e);
			}
		}

		throw new GradleException("Failed to download " + url + " to " + output + " after " + MAX_RETRIES + " attempts", last);
	}

	private static void attempt(String url, String sha1, File output) throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMinutes(5))
				.GET()
				.build();
		Path target = output.toPath();

		if (target.getParent() != null) {
			Files.createDirectories(target.getParent());
		}

		HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));

		if (response.statusCode() != 200) {
			Files.deleteIfExists(target);
			throw new IOException("HTTP " + response.statusCode() + " for " + url);
		}

		if (sha1 != null && !sha1.isEmpty()) {
			String actual = sha1Of(target);

			if (!actual.equalsIgnoreCase(sha1)) {
				Files.deleteIfExists(target);
				throw new IOException("sha1 mismatch for " + url + ": expected " + sha1 + " got " + actual);
			}
		}
	}

	private static String sha1Of(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			digest.update(Files.readAllBytes(path));
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}
}
