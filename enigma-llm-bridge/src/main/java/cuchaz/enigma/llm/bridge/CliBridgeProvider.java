package cuchaz.enigma.llm.bridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Generic provider that shells out to a vendor CLI (grok / claude / codex) for a single-turn,
 * headless completion. Used for the local-vs-commercial benchmark so the SAME prompt the local
 * LM-Studio models receive is answered by the subscription CLIs at their flagship tier.
 *
 * <p>The requested model is routed by a {@code vendor:modelId} prefix, e.g. {@code grok:grok-build},
 * {@code claude:opus}, {@code codex:gpt-5.5}. The combined prompt (which already instructs the model
 * to emit the {@code suggestedName/alternatives/confidence/reasoning} JSON) is passed verbatim; the
 * provider then extracts the last balanced JSON object containing {@code suggestedName} from stdout,
 * so agentic prose around the answer is tolerated. No extra instructions are injected, keeping the
 * comparison against the local models fair.
 */
final class CliBridgeProvider implements BridgeProvider {
	private final List<String> grokCommand;
	private final List<String> claudeCommand;
	private final List<String> codexCommand;
	private final List<String> models;
	private final Duration timeout;
	private final boolean trace;

	CliBridgeProvider(List<String> grokCommand, List<String> claudeCommand, List<String> codexCommand,
			List<String> models, Duration timeout, boolean trace) {
		this.grokCommand = List.copyOf(grokCommand);
		this.claudeCommand = List.copyOf(claudeCommand);
		this.codexCommand = List.copyOf(codexCommand);
		this.models = List.copyOf(models);
		this.timeout = timeout;
		this.trace = trace;
	}

	@Override
	public String name() {
		return "cli";
	}

	@Override
	public List<String> models() {
		return this.models;
	}

	@Override
	public String complete(ChatCompletionRequest request) throws Exception {
		String model = request.model();
		String vendor;
		String modelId;
		int colon = model.indexOf(':');

		if (colon < 0) {
			throw new IllegalArgumentException("cli provider expects a 'vendor:modelId' model, got: " + model);
		}

		vendor = model.substring(0, colon).toLowerCase(Locale.ROOT);
		modelId = model.substring(colon + 1).strip();
		String prompt = request.combinedPrompt();
		List<String> command = buildCommand(vendor, modelId, prompt);
		String stdout = run(command);
		String json = extractSuggestionJson(stdout);

		if (json == null) {
			throw new IllegalStateException("cli provider (" + vendor + ":" + modelId
					+ ") produced no suggestedName JSON; stdout head: "
					+ stdout.substring(0, Math.min(stdout.length(), 400)));
		}

		return json;
	}

	private List<String> buildCommand(String vendor, String modelId, String prompt) {
		return switch (vendor) {
		case "grok" -> concat(this.grokCommand, List.of("--disable-web-search", "-m", modelId, "-p", prompt));
		// claude accepts a model alias ("opus") or a full id; -p is single-turn headless.
		case "claude" -> concat(this.claudeCommand, List.of("-p", prompt, "--model", modelId));
		// codex: global flags (incl. -m) precede the exec subcommand.
		case "codex" -> concat(this.codexCommand, List.of("-m", modelId, "exec", "--skip-git-repo-check", prompt));
		default -> throw new IllegalArgumentException("cli provider: unknown vendor '" + vendor + "'");
		};
	}

	private String run(List<String> command) throws Exception {
		if (this.trace) {
			System.err.println("[cli] exec: " + command.subList(0, Math.min(command.size(), command.size() - 1)));
		}

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(false);
		Process process = builder.start();
		process.getOutputStream().close();
		byte[] out = readAll(process.getInputStream());
		byte[] err = readAll(process.getErrorStream());

		if (!process.waitFor(this.timeout.toSeconds(), TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("cli provider timed out after " + this.timeout.toSeconds() + "s: " + command.get(0));
		}

		String stdout = new String(out, StandardCharsets.UTF_8);

		if (this.trace && err.length > 0) {
			System.err.println("[cli] stderr: " + new String(err, StandardCharsets.UTF_8));
		}

		return stdout;
	}

	private static byte[] readAll(InputStream stream) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int read;

		while ((read = stream.read(chunk)) != -1) {
			buffer.write(chunk, 0, read);
		}

		return buffer.toByteArray();
	}

	/**
	 * Returns the last balanced {@code {...}} substring that contains {@code "suggestedName"}, or
	 * {@code null} if none is present. Scanning from the last opening brace backwards makes the final
	 * answer win when the CLI echoes the prompt (which itself may mention the key) earlier in stdout.
	 */
	static String extractSuggestionJson(String text) {
		if (text == null) {
			return null;
		}

		List<Integer> opens = new ArrayList<>();

		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '{') {
				opens.add(i);
			}
		}

		for (int idx = opens.size() - 1; idx >= 0; idx--) {
			int start = opens.get(idx);
			int depth = 0;
			boolean inString = false;
			boolean escape = false;

			for (int e = start; e < text.length(); e++) {
				char c = text.charAt(e);

				if (escape) {
					escape = false;
					continue;
				}

				if (c == '\\') {
					escape = true;
					continue;
				}

				if (c == '"') {
					inString = !inString;
					continue;
				}

				if (inString) {
					continue;
				}

				if (c == '{') {
					depth++;
				} else if (c == '}') {
					depth--;

					if (depth == 0) {
						String candidate = text.substring(start, e + 1);

						if (candidate.contains("\"suggestedName\"")) {
							return candidate;
						}

						break;
					}
				}
			}
		}

		return null;
	}

	private static List<String> concat(List<String> base, List<String> extra) {
		List<String> combined = new ArrayList<>(base.size() + extra.size());
		combined.addAll(base);
		combined.addAll(extra);
		return combined;
	}
}
