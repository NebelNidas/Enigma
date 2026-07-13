package cuchaz.enigma.gradle;

import java.io.File;
import java.net.URISyntaxException;

import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.download.DownloadBuilder;
import net.fabricmc.loom.util.download.DownloadException;
import org.gradle.api.GradleException;

final class DownloadSupport {
	private DownloadSupport() {
	}

	static void download(String url, String sha1, File output) {
		try {
			DownloadBuilder download = Download.create(url).maxRetries(3);

			if (sha1 != null && !sha1.isEmpty()) {
				download.sha1(sha1);
			}

			download.downloadPath(output.toPath());
		} catch (DownloadException | URISyntaxException e) {
			throw new GradleException("Failed to download " + url + " to " + output, e);
		}
	}
}
