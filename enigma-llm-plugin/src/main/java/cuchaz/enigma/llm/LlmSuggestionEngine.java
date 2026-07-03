package cuchaz.enigma.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import cuchaz.enigma.api.view.ProjectView;
import cuchaz.enigma.api.view.entry.EntryView;

class LlmSuggestionEngine {
	private final LlmNameProposalPlugin plugin;
	private final LlmPromptBuilder promptBuilder = new LlmPromptBuilder();
	private final LlmNameValidator validator = new LlmNameValidator();
	private final SuggestionRequester suggestionRequester;

	LlmSuggestionEngine(LlmNameProposalPlugin plugin) {
		this(plugin, (config, kind, targetName, prompt) -> new OpenAiCompatibleClient(config).suggestName(kind, targetName, prompt));
	}

	LlmSuggestionEngine(LlmNameProposalPlugin plugin, SuggestionRequester suggestionRequester) {
		this.plugin = plugin;
		this.suggestionRequester = suggestionRequester;
	}

	LlmSuggestion requestSuggestion(LlmConfig config, ProjectView project, EntryKey key) {
		try {
			if (!this.plugin.isProjectCurrent(project)) {
				throw new ProjectChangedException();
			}

			String prompt = this.promptBuilder.build(key, project, this.plugin.getIndex(), config.contextBackend());
			LlmSuggestion suggestion = null;
			String retryReason = null;

			for (int attempt = 0; attempt < 2; attempt++) {
				String attemptPrompt = attempt == 0 ? prompt : repairPrompt(prompt, retryReason);
				suggestion = normalizeSuggestion(key, this.suggestionRequester.suggestName(config, key.kind(), key.displayName(), attemptPrompt));

				if (!this.plugin.isProjectCurrent(project)) {
					throw new ProjectChangedException();
				}

				retryReason = validationFailure(project, this.plugin.getIndex(), key, suggestion.suggestedName()).orElse(null);

				if (retryReason == null) {
					suggestion = filterAlternatives(project, this.plugin.getIndex(), key, suggestion);
					this.plugin.getSuggestions().put(key, suggestion);
					return suggestion;
				}
			}

			this.plugin.getSuggestions().remove(key);
			throw new IllegalArgumentException(retryReason == null ? "LLM suggestion failed validation" : retryReason);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (ProjectChangedException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	BatchSuggestionResult requestBatch(LlmConfig config, ProjectView project, List<EntryKey> targets) {
		List<BatchSuggestion> suggestions = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		double threshold = config.batchPreselectThreshold().orElse(1.1);
		int parallelism = Math.min(Math.max(1, config.batchParallelism()), Math.max(1, targets.size()));
		ExecutorService executor = Executors.newFixedThreadPool(parallelism);
		List<Future<BatchTargetResult>> futures = new ArrayList<>();

		try {
			for (EntryKey key : targets) {
				futures.add(executor.submit(() -> requestBatchTarget(config, project, key, threshold)));
			}

			for (Future<BatchTargetResult> future : futures) {
				BatchTargetResult result = future.get();

				if (result.suggestion != null) {
					suggestions.add(result.suggestion);
				} else {
					failures.add(result.failure);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			executor.shutdownNow();
			throw new RuntimeException(e);
		} catch (ExecutionException e) {
			executor.shutdownNow();
			Throwable cause = e.getCause();

			if (wasInterrupted(cause)) {
				Thread.currentThread().interrupt();
			}

			throw cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
		} finally {
			executor.shutdownNow();
		}

		return new BatchSuggestionResult(suggestions, failures);
	}

	private BatchTargetResult requestBatchTarget(LlmConfig config, ProjectView project, EntryKey key, double threshold) {
		if (Thread.currentThread().isInterrupted()) {
			throw new RuntimeException(new InterruptedException("Batch LLM suggestion request was interrupted"));
		}

		try {
			LlmSuggestion suggestion = requestSuggestion(config, project, key);
			return BatchTargetResult.suggestion(new BatchSuggestion(key, suggestion, suggestion.confidence() >= threshold));
		} catch (RuntimeException e) {
			if (wasInterrupted(e) || wasProjectChanged(e)) {
				throw e;
			}

			return BatchTargetResult.failure(key.displayName() + ": " + userFacingMessage(e));
		}
	}

	private Optional<String> validationFailure(ProjectView project, LlmProjectIndex index, EntryKey key, String suggestedName) {
		if (!this.validator.isValid(key, suggestedName)) {
			return Optional.of("Invalid Java identifier suggested for " + key.kind() + ": " + suggestedName);
		}

		if (isDuplicateMemberName(project, index, key, suggestedName)) {
			return Optional.of("Suggested name is already used by another " + key.kind() + " in this context: " + suggestedName);
		}

		if (isOwnerPrefixedMemberName(project, key, suggestedName)) {
			return Optional.of("Suggested name repeats the owner class name instead of describing the " + key.kind() + ": " + suggestedName);
		}

		return Optional.empty();
	}

	private LlmSuggestion filterAlternatives(ProjectView project, LlmProjectIndex index, EntryKey key, LlmSuggestion suggestion) {
		List<String> alternatives = suggestion.alternatives().stream()
				.filter(alternative -> validationFailure(project, index, key, alternative).isEmpty())
				.toList();

		return new LlmSuggestion(suggestion.suggestedName(), alternatives, suggestion.confidence(), suggestion.reasoning());
	}

	static LlmSuggestion normalizeSuggestion(EntryKey key, LlmSuggestion suggestion) {
		String normalizedName = normalizeSuggestionName(key, suggestion.suggestedName());
		List<String> normalizedAlternatives = suggestion.alternatives().stream()
				.map(alternative -> normalizeSuggestionName(key, alternative))
				.toList();

		return new LlmSuggestion(normalizedName, normalizedAlternatives, suggestion.confidence(), suggestion.reasoning());
	}

	static String normalizeSuggestionName(EntryKey key, String suggestedName) {
		String normalized = suggestedName == null ? "" : suggestedName.strip();

		if (key.kind() == EntryKind.CLASS) {
			return normalized;
		}

		normalized = afterLast(normalized, "::");
		normalized = afterLast(normalized, "#");
		normalized = afterLast(normalized, ".");

		return lowerCaseFirstCodePoint(normalized);
	}

	static String userFacingMessage(Throwable error) {
		Throwable cause = error;

		while (cause.getCause() != null) {
			cause = cause.getCause();
		}

		return cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
	}

	private static String repairPrompt(String originalPrompt, String retryReason) {
		return originalPrompt
				+ "\nPrevious suggestion was rejected: " + retryReason + "\n"
				+ "Generate a different suggestion now. Do not repeat any rejected, unchanged, invalid, or already-used name. Respond with JSON only.\n";
	}

	private static boolean isDuplicateMemberName(ProjectView project, LlmProjectIndex index, EntryKey target, String suggestedName) {
		return switch (target.kind()) {
		case FIELD -> index.ownerClass(target).stream()
				.flatMap(owner -> owner.fields().stream())
				.filter(field -> !field.key().equals(target))
				.anyMatch(field -> mappedName(project, field.key()).filter(suggestedName::equals).isPresent());
		case PARAMETER -> index.entry(target).stream()
				.filter(IndexedParameter.class::isInstance)
				.map(IndexedParameter.class::cast)
				.anyMatch(parameter -> index.ownerClass(target).stream()
						.flatMap(owner -> owner.methods().stream())
						.filter(method -> method.key().name().equals(target.name()) && method.key().descriptor().equals(target.descriptor()))
						.flatMap(method -> method.parameters().stream())
						.filter(otherParameter -> !otherParameter.key().equals(parameter.key()))
						.anyMatch(otherParameter -> mappedName(project, otherParameter.key()).filter(suggestedName::equals).isPresent()));
		case CLASS, METHOD -> false;
		};
	}

	private static boolean isOwnerPrefixedMemberName(ProjectView project, EntryKey key, String suggestedName) {
		if (key.kind() == EntryKind.CLASS) {
			return false;
		}

		String ownerPrefix = EntryKey.toEntryView(new EntryKey(EntryKind.CLASS, key.owner(), key.owner(), ""))
				.map(project::deobfuscate)
				.map(EntryView::getName)
				.map(LlmSuggestionEngine::lowerCaseFirstCodePoint)
				.orElse("");

		return !ownerPrefix.isBlank()
				&& suggestedName.length() > ownerPrefix.length()
				&& suggestedName.startsWith(ownerPrefix);
	}

	private static Optional<String> mappedName(ProjectView project, EntryKey key) {
		return EntryKey.toEntryView(key)
				.map(project::deobfuscate)
				.map(entry -> key.kind() == EntryKind.CLASS ? entry.getFullName() : entry.getName());
	}

	private static boolean wasInterrupted(Throwable error) {
		Throwable cause = error;

		while (cause != null) {
			if (cause instanceof InterruptedException) {
				return true;
			}

			cause = cause.getCause();
		}

		return false;
	}

	private static boolean wasProjectChanged(Throwable error) {
		Throwable cause = error;

		while (cause != null) {
			if (cause instanceof ProjectChangedException) {
				return true;
			}

			cause = cause.getCause();
		}

		return false;
	}

	private static String afterLast(String value, String separator) {
		int index = value.lastIndexOf(separator);
		return index >= 0 ? value.substring(index + separator.length()).strip() : value;
	}

	private static String lowerCaseFirstCodePoint(String value) {
		if (value.isEmpty()) {
			return value;
		}

		if (value.codePoints().allMatch(Character::isUpperCase)) {
			return value.toLowerCase(Locale.ROOT);
		}

		int first = value.codePointAt(0);
		int lower = Character.toLowerCase(first);

		if (first == lower) {
			return value;
		}

		return new StringBuilder()
				.appendCodePoint(lower)
				.append(value.substring(Character.charCount(first)))
				.toString();
	}

	@FunctionalInterface
	interface SuggestionRequester {
		LlmSuggestion suggestName(LlmConfig config, EntryKind kind, String targetName, String prompt) throws IOException, InterruptedException;
	}

	private record BatchTargetResult(BatchSuggestion suggestion, String failure) {
		static BatchTargetResult suggestion(BatchSuggestion suggestion) {
			return new BatchTargetResult(suggestion, null);
		}

		static BatchTargetResult failure(String failure) {
			return new BatchTargetResult(null, failure);
		}
	}

	static class ProjectChangedException extends RuntimeException {
		ProjectChangedException() {
			super("Project changed before the LLM suggestion completed");
		}
	}
}
