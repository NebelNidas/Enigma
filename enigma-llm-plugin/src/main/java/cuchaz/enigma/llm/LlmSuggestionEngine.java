package cuchaz.enigma.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import cuchaz.enigma.api.view.ProjectView;
import cuchaz.enigma.api.view.RenameValidationResult;
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

			LlmContextBackend resolvedBackend = LlmPromptBuilder.resolveBackend(key, this.plugin.getIndex(), config.contextBackend());
			String prompt = this.promptBuilder.build(key, project, this.plugin.getIndex(), config.contextBackend(), config.analysisHints());
			return requestSuggestion(config, project, key, resolvedBackend, prompt);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (ProjectChangedException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Code-inclusive variant: identical to {@link #requestSuggestion(LlmConfig, ProjectView, EntryKey)}
	 * except the (already normalized) decompiled body of the target method is appended to the prompt. The
	 * metadata prefix is unchanged, so this arm differs from the metadata-only arm only by the added code.
	 */
	LlmSuggestion requestSuggestion(LlmConfig config, ProjectView project, EntryKey key, String codeSection) {
		try {
			if (!this.plugin.isProjectCurrent(project)) {
				throw new ProjectChangedException();
			}

			LlmContextBackend resolvedBackend = LlmPromptBuilder.resolveBackend(key, this.plugin.getIndex(), config.contextBackend());
			String prompt = this.promptBuilder.build(key, project, this.plugin.getIndex(), config.contextBackend(),
					config.analysisHints(), List.of(), codeSection);
			return requestSuggestion(config, project, key, resolvedBackend, prompt);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (ProjectChangedException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private LlmSuggestion requestBatchSuggestion(LlmConfig config, ProjectView project, EntryKey key,
			List<BatchSuggestion> batchSuggestions) {
		try {
			if (!this.plugin.isProjectCurrent(project)) {
				throw new ProjectChangedException();
			}

			LlmContextBackend resolvedBackend = LlmPromptBuilder.resolveBackend(key, this.plugin.getIndex(), config.contextBackend());
			String prompt = this.promptBuilder.build(key, project, this.plugin.getIndex(),
					config.contextBackend(), config.analysisHints(), batchSuggestions);
			return requestSuggestion(config, project, key, resolvedBackend, prompt);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (ProjectChangedException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private LlmSuggestion requestSuggestion(LlmConfig config, ProjectView project, EntryKey key,
			LlmContextBackend resolvedBackend, String prompt) throws IOException, InterruptedException {
		LlmSuggestion suggestion = null;
		String retryReason = null;

		for (int attempt = 0; attempt < 2; attempt++) {
			String attemptPrompt = attempt == 0 ? prompt : repairPrompt(prompt, retryReason);
			suggestion = normalizeSuggestion(key, this.plugin.getIndex(), this.suggestionRequester.suggestName(config, key.kind(), key.displayName(), attemptPrompt));
			suggestion = stripOwnerRedundancy(project, this.plugin.getIndex(), key, suggestion);

			if (!this.plugin.isProjectCurrent(project)) {
				throw new ProjectChangedException();
			}

			retryReason = validationFailure(project, this.plugin.getIndex(), key, suggestion).orElse(null);

			if (retryReason == null) {
				retryReason = cachedSuggestionFailure(config, project, this.plugin.getIndex(), key, suggestion).orElse(null);
			}

			if (retryReason == null) {
				suggestion = filterAlternatives(project, this.plugin.getIndex(), key, suggestion);
				suggestion = suggestion.withContextBackend(config.contextBackend(), resolvedBackend);
				removeWeakerCachedDuplicates(key, suggestion);
				this.plugin.getSuggestions().put(key, suggestion);
				return suggestion;
			}
		}

		this.plugin.getSuggestions().remove(key);
		throw new IllegalArgumentException(retryReason == null ? "LLM suggestion failed validation" : retryReason);
	}

	BatchSuggestionResult requestBatch(LlmConfig config, ProjectView project, List<EntryKey> targets) {
		return requestBatch(config, project, targets, _progress -> {
		});
	}

	BatchSuggestionResult requestBatch(LlmConfig config, ProjectView project, List<EntryKey> targets,
			Consumer<BatchProgress> progressListener) {
		return requestBatch(config, project, targets, Set.of(), progressListener);
	}

	BatchSuggestionResult requestBatch(LlmConfig config, ProjectView project, List<EntryKey> targets,
			Set<EntryKey> preservedNameDecisionTargets, Consumer<BatchProgress> progressListener) {
		List<BatchSuggestion> suggestions = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		double threshold = config.batchPreselectThreshold().orElse(1.1);
		int parallelism = Math.min(Math.max(1, config.batchParallelism()), Math.max(1, targets.size()));
		ExecutorService executor = Executors.newFixedThreadPool(parallelism);
		CompletionService<IndexedBatchTargetResult> completionService = new ExecutorCompletionService<>(executor);
		List<BatchTargetResult> results = new ArrayList<>();
		List<BatchSuggestion> completedSuggestions = Collections.synchronizedList(new ArrayList<>());

		try {
			for (int i = 0; i < targets.size(); i++) {
				int index = i;
				EntryKey key = targets.get(i);
				results.add(null);
				completionService.submit(() -> new IndexedBatchTargetResult(index,
						requestBatchTarget(config, project, key, threshold, completedSuggestions,
								preservedNameDecisionTargets.contains(key))));
			}

			for (int completed = 1; completed <= targets.size(); completed++) {
				IndexedBatchTargetResult indexedResult = completionService.take().get();
				results.set(indexedResult.index(), indexedResult.result());
				progressListener.accept(new BatchProgress(completed, targets.size()));
			}

			for (BatchTargetResult result : results) {
				if (result == null) {
					continue;
				}

				if (result.suggestion != null) {
					suggestions.add(result.suggestion);
				} else if (result.skipped != null) {
					skipped.add(result.skipped);
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

		return new BatchSuggestionResult(suggestions, failures, skipped);
	}

	private BatchTargetResult requestBatchTarget(LlmConfig config, ProjectView project, EntryKey key, double threshold,
			List<BatchSuggestion> completedSuggestions, boolean askLlmBeforeRenamingPreservedName) {
		if (Thread.currentThread().isInterrupted()) {
			throw new RuntimeException(new InterruptedException("Batch LLM suggestion request was interrupted"));
		}

		try {
			List<BatchSuggestion> batchSnapshot = snapshotBatchSuggestions(completedSuggestions);

			if (askLlmBeforeRenamingPreservedName && !requestPreservedNameDecision(config, project, key, batchSnapshot).rename()) {
				return BatchTargetResult.skipped(key.displayName() + ": kept original name");
			}

			LlmSuggestion suggestion = requestBatchSuggestion(config, project, key, batchSnapshot);
			BatchSuggestion batchSuggestion = new BatchSuggestion(key, suggestion, suggestion.confidence() >= threshold);
			completedSuggestions.add(batchSuggestion);
			return BatchTargetResult.suggestion(batchSuggestion);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (IOException e) {
			return BatchTargetResult.failure(key.displayName() + ": " + userFacingMessage(e));
		} catch (RuntimeException e) {
			if (wasInterrupted(e) || wasProjectChanged(e)) {
				throw e;
			}

			return BatchTargetResult.failure(key.displayName() + ": " + userFacingMessage(e));
		}
	}

	PreservedNameDecision requestPreservedNameDecision(LlmConfig config, ProjectView project, EntryKey key) {
		try {
			return requestPreservedNameDecision(config, project, key, List.of());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (ProjectChangedException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private PreservedNameDecision requestPreservedNameDecision(LlmConfig config, ProjectView project, EntryKey key,
			List<BatchSuggestion> batchSuggestions) throws IOException, InterruptedException {
		String context = this.promptBuilder.build(key, project, this.plugin.getIndex(),
				config.contextBackend(), config.analysisHints(), batchSuggestions);
		String prompt = LlmPromptBuilder.truncateForContext("""
				The target has an original Java name that may already be intentional rather than obfuscated.
				Decide whether this batch should rename it.
				Respond only with JSON in this exact shape:
				{"reasoning":"short reason","alternatives":[],"suggestedName":"KEEP","confidence":0.0}
				Set suggestedName to exactly KEEP or RENAME.
				Choose KEEP when the original name is already precise Java API naming.
				Choose RENAME only when the bytecode context strongly suggests the original name is misleading or obfuscated.

				Target:
				%s

				Context:
				%s
				""".formatted(key.displayName(), context));
		LlmSuggestion decision = this.suggestionRequester.suggestName(config, EntryKind.CLASS,
				"preserved original-name batch decision", prompt);

		if (!this.plugin.isProjectCurrent(project)) {
			throw new ProjectChangedException();
		}

		boolean rename = "RENAME".equalsIgnoreCase(decision.suggestedName());
		return new PreservedNameDecision(key, rename, decision.reasoning());
	}

	private static List<BatchSuggestion> snapshotBatchSuggestions(List<BatchSuggestion> completedSuggestions) {
		synchronized (completedSuggestions) {
			return List.copyOf(completedSuggestions);
		}
	}

	private Optional<String> validationFailure(ProjectView project, LlmProjectIndex index, EntryKey key, LlmSuggestion suggestion) {
		String suggestedName = suggestion.suggestedName();

		if (!this.validator.isValid(key, suggestedName, LlmPromptBuilder.isStaticFinalField(key, index))) {
			return Optional.of("Invalid Java identifier suggested for " + key.kind() + ": " + suggestedName);
		}

		Optional<String> renameFailure = enigmaRenameFailure(project, key, suggestedName);

		if (renameFailure.isPresent()) {
			return renameFailure;
		}

		if (isOwnerPrefixedMemberName(project, key, suggestedName)) {
			return Optional.of("Suggested name repeats the owner class name instead of describing the " + key.kind() + ": " + suggestedName);
		}

		return Optional.empty();
	}

	private static Optional<String> enigmaRenameFailure(ProjectView project, EntryKey key, String suggestedName) {
		return EntryKey.toEntryView(key)
				.map(entry -> {
					RenameValidationResult result = project.validateRename(entry, LlmRenameApplier.normalizeRename(entry, suggestedName));

					if (result.valid()) {
						return Optional.<String>empty();
					}

					return Optional.of(result.messages().stream()
							.findFirst()
							.orElse("Rename failed validation for " + key.kind() + ": " + suggestedName));
				})
				.orElseGet(() -> Optional.of("Cannot validate rename target: " + key.displayName()));
	}

	private LlmSuggestion filterAlternatives(ProjectView project, LlmProjectIndex index, EntryKey key, LlmSuggestion suggestion) {
		List<String> alternatives = suggestion.alternatives().stream()
				.map(alternative -> new LlmSuggestion(alternative, List.of(), suggestion.confidence(), "",
						suggestion.configuredBackend(), suggestion.resolvedBackend()))
				.filter(alternative -> validationFailure(project, index, key, alternative).isEmpty())
				.map(LlmSuggestion::suggestedName)
				.toList();

		return new LlmSuggestion(suggestion.suggestedName(), alternatives, suggestion.confidence(), suggestion.reasoning(),
				suggestion.configuredBackend(), suggestion.resolvedBackend());
	}

	// Evaluation-only escape hatch (read once at class load, like LlmPromptBuilder's MAX_PROMPT_CHARS):
	// when ENIGMA_LLM_DISABLE_SUGGESTION_CACHE is set, the whole-jar duplicate-name dedup/tie-break is
	// switched off so the round-trip benchmark can measure recovery with every target scored independently
	// (a cache-off control). Unset by default, so normal product runs are unchanged.
	private static final boolean SUGGESTION_CACHE_DISABLED =
			!System.getenv().getOrDefault("ENIGMA_LLM_DISABLE_SUGGESTION_CACHE", "").isBlank();

	private Optional<String> cachedSuggestionFailure(LlmConfig config, ProjectView project, LlmProjectIndex index,
			EntryKey key, LlmSuggestion suggestion) throws IOException, InterruptedException {
		if (SUGGESTION_CACHE_DISABLED) {
			return Optional.empty();
		}

		String conflictKey = cachedSuggestionConflictKey(key, suggestion);

		if (conflictKey.isBlank()) {
			return Optional.empty();
		}

		for (Map.Entry<EntryKey, LlmSuggestion> entry : this.plugin.getSuggestions().entries()) {
			if (entry.getKey().equals(key)
					|| !cachedSuggestionConflictKey(entry.getKey(), entry.getValue()).equals(conflictKey)) {
				continue;
			}

			CachedSuggestionConflict conflict = new CachedSuggestionConflict(entry.getKey(), entry.getValue());

			if (entry.getValue().confidence() > suggestion.confidence()) {
				return Optional.of(cachedSuggestionConflictMessage(conflict, suggestion));
			}

			if (Double.compare(entry.getValue().confidence(), suggestion.confidence()) == 0) {
				TieBreakerChoice choice;

				try {
					choice = judgeCachedDuplicate(config, project, index, key, suggestion, conflict);
				} catch (IOException e) {
					choice = TieBreakerChoice.CURRENT;
				}

				if (choice == TieBreakerChoice.EXISTING) {
					return Optional.of("Suggested name is already preferred by an equal-score LLM comparison for "
							+ conflict.key().displayName() + ": " + suggestion.suggestedName());
				}
			}
		}

		return Optional.empty();
	}

	private TieBreakerChoice judgeCachedDuplicate(LlmConfig config, ProjectView project, LlmProjectIndex index,
			EntryKey key, LlmSuggestion suggestion, CachedSuggestionConflict conflict) throws IOException, InterruptedException {
		String prompt = duplicateTieBreakerPrompt(config, project, index, key, suggestion, conflict);
		LlmSuggestion decision = this.suggestionRequester.suggestName(config, EntryKind.CLASS,
				"duplicate LLM suggestion tie-break", prompt);

		if (!this.plugin.isProjectCurrent(project)) {
			throw new ProjectChangedException();
		}

		return "EXISTING".equalsIgnoreCase(decision.suggestedName()) ? TieBreakerChoice.EXISTING : TieBreakerChoice.CURRENT;
	}

	private String duplicateTieBreakerPrompt(LlmConfig config, ProjectView project, LlmProjectIndex index,
			EntryKey key, LlmSuggestion suggestion, CachedSuggestionConflict conflict) {
		return LlmPromptBuilder.truncateForContext("""
				Two cached LLM name suggestions conflict. The name is only a gray suggestion, not a manually applied mapping.
				Choose which target should keep the suggested name "%s".
				Respond only with JSON in this exact shape:
				{"reasoning":"why this target is a better match","alternatives":[],"suggestedName":"CURRENT","confidence":0.0}
				Set suggestedName to exactly CURRENT or EXISTING.
				Prefer the target whose bytecode context most directly supports the name.

				Current target:
				%s
				Current proposed reasoning: %s
				Current model score: %.2f
				Current context:
				%s

				Existing gray suggestion:
				%s
				Existing proposed reasoning: %s
				Existing model score: %.2f
				Existing context:
				%s
				""".formatted(
				suggestion.suggestedName(),
				key.displayName(),
				suggestion.reasoning(),
				suggestion.confidence(),
				this.promptBuilder.build(key, project, index, config.contextBackend(), config.analysisHints()),
				conflict.key().displayName(),
				conflict.suggestion().reasoning(),
				conflict.suggestion().confidence(),
				this.promptBuilder.build(conflict.key(), project, index, config.contextBackend(), config.analysisHints())));
	}

	private static String cachedSuggestionConflictMessage(CachedSuggestionConflict conflict, LlmSuggestion suggestion) {
		return "Suggested name is already held by a higher model-score LLM suggestion for "
				+ conflict.key().displayName() + ": " + suggestion.suggestedName();
	}

	private void removeWeakerCachedDuplicates(EntryKey key, LlmSuggestion suggestion) {
		String conflictKey = cachedSuggestionConflictKey(key, suggestion);

		if (conflictKey.isBlank()) {
			return;
		}

		for (Map.Entry<EntryKey, LlmSuggestion> entry : this.plugin.getSuggestions().entries()) {
			if (!entry.getKey().equals(key)
					&& entry.getValue().confidence() <= suggestion.confidence()
					&& cachedSuggestionConflictKey(entry.getKey(), entry.getValue()).equals(conflictKey)) {
				this.plugin.getSuggestions().remove(entry.getKey());
			}
		}
	}

	private static String cachedSuggestionConflictKey(EntryKey key, LlmSuggestion suggestion) {
		return LlmRenameApplier.batchSelectionConflictKey(new BatchSuggestion(key, suggestion, true));
	}

	static LlmSuggestion normalizeSuggestion(EntryKey key, LlmSuggestion suggestion) {
		return normalizeSuggestion(key, LlmProjectIndex.empty(), suggestion);
	}

	static LlmSuggestion normalizeSuggestion(EntryKey key, LlmProjectIndex index, LlmSuggestion suggestion) {
		String normalizedName = normalizeSuggestionName(key, index, suggestion.suggestedName());
		List<String> normalizedAlternatives = suggestion.alternatives().stream()
				.map(alternative -> normalizeSuggestionName(key, index, alternative))
				.toList();

		return new LlmSuggestion(normalizedName, normalizedAlternatives, suggestion.confidence(), suggestion.reasoning(),
				suggestion.configuredBackend(), suggestion.resolvedBackend());
	}

	static String normalizeSuggestionName(EntryKey key, String suggestedName) {
		return normalizeSuggestionName(key, LlmProjectIndex.empty(), suggestedName);
	}

	static String normalizeSuggestionName(EntryKey key, LlmProjectIndex index, String suggestedName) {
		String normalized = suggestedName == null ? "" : suggestedName.strip();

		if (key.kind() == EntryKind.CLASS) {
			return normalized;
		}

		normalized = afterLast(normalized, "::");
		normalized = afterLast(normalized, "#");
		normalized = afterLast(normalized, ".");

		if (LlmPromptBuilder.isStaticFinalField(key, index)) {
			return normalized;
		}

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

	private static boolean isOwnerPrefixedMemberName(ProjectView project, EntryKey key, String suggestedName) {
		String ownerPrefix = ownerNamePrefix(project, key);

		return !ownerPrefix.isBlank()
				&& suggestedName.length() > ownerPrefix.length()
				&& suggestedName.startsWith(ownerPrefix);
	}

	private static String ownerNamePrefix(ProjectView project, EntryKey key) {
		if (key.kind() == EntryKind.CLASS) {
			return "";
		}

		return EntryKey.toEntryView(new EntryKey(EntryKind.CLASS, key.owner(), key.owner(), ""))
				.map(project::deobfuscate)
				.map(EntryView::getName)
				.map(LlmSuggestionEngine::lowerCaseFirstCodePoint)
				.orElse("");
	}

	// Minimum length of the residual token after stripping the owner-class prefix for the strip to be
	// considered safe. Real Yarn word-tokens (health, offset, position) clear this easily; obfuscation
	// leftovers such as the trailing "B" in "geometryConstantsB" do not, so those keep falling through
	// to reject/retry, which lets the model produce a genuinely descriptive name instead.
	private static final int MIN_STRIPPED_TOKEN_LENGTH = 3;

	// Yarn member names do not repeat their owner class name (e.g. PlayerEntity.playerEntityHealth ->
	// PlayerEntity.health). When the model emits an owner-prefixed name whose residual token is a real
	// word, strip the redundant prefix deterministically instead of paying for a repair round-trip.
	// Anything shorter than MIN_STRIPPED_TOKEN_LENGTH, blank, or not a valid identifier is left intact
	// so validationFailure() still rejects it and the retry can propose a better name.
	private LlmSuggestion stripOwnerRedundancy(ProjectView project, LlmProjectIndex index, EntryKey key,
			LlmSuggestion suggestion) {
		String primary = stripRedundantOwnerPrefix(project, index, key, suggestion.suggestedName());
		List<String> alternatives = suggestion.alternatives().stream()
				.map(alternative -> stripRedundantOwnerPrefix(project, index, key, alternative))
				.toList();

		if (primary.equals(suggestion.suggestedName()) && alternatives.equals(suggestion.alternatives())) {
			return suggestion;
		}

		return new LlmSuggestion(primary, alternatives, suggestion.confidence(), suggestion.reasoning(),
				suggestion.configuredBackend(), suggestion.resolvedBackend());
	}

	private String stripRedundantOwnerPrefix(ProjectView project, LlmProjectIndex index, EntryKey key,
			String suggestedName) {
		if (!isOwnerPrefixedMemberName(project, key, suggestedName)) {
			return suggestedName;
		}

		String ownerPrefix = ownerNamePrefix(project, key);
		String remainder = lowerCaseFirstCodePoint(suggestedName.substring(ownerPrefix.length()));

		if (remainder.length() < MIN_STRIPPED_TOKEN_LENGTH
				|| !this.validator.isValid(key, remainder, LlmPromptBuilder.isStaticFinalField(key, index))) {
			return suggestedName;
		}

		return remainder;
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

	record BatchProgress(int completed, int total) {
	}

	record PreservedNameDecision(EntryKey key, boolean rename, String reasoning) {
		PreservedNameDecision {
			reasoning = reasoning == null ? "" : reasoning.strip();
		}
	}

	private record CachedSuggestionConflict(EntryKey key, LlmSuggestion suggestion) {
	}

	private enum TieBreakerChoice {
		CURRENT,
		EXISTING
	}

	private record IndexedBatchTargetResult(int index, BatchTargetResult result) {
	}

	private record BatchTargetResult(BatchSuggestion suggestion, String failure, String skipped) {
		static BatchTargetResult suggestion(BatchSuggestion suggestion) {
			return new BatchTargetResult(suggestion, null, null);
		}

		static BatchTargetResult failure(String failure) {
			return new BatchTargetResult(null, failure, null);
		}

		static BatchTargetResult skipped(String skipped) {
			return new BatchTargetResult(null, null, skipped);
		}
	}

	static class ProjectChangedException extends RuntimeException {
		ProjectChangedException() {
			super("Project changed before the LLM suggestion completed");
		}
	}
}
