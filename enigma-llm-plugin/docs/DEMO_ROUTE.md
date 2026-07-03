# Demo Route

## Setup

Start LM Studio with the local model loaded:

```sh
env -u ELECTRON_RUN_AS_NODE -u ELECTRON_NO_ATTACH_CONSOLE lm-studio
lms server start --port 1234
lms load -y --gpu max --context-length 4096 --identifier qwen2.5-coder-1.5b-q4km qwen2.5-coder-1.5b-instruct
```

Start Enigma with the plugin:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 \
ENIGMA_LLM_MODEL=qwen2.5-coder-1.5b-q4km \
ENIGMA_LLM_BATCH_PARALLELISM=2 \
ENIGMA_LLM_CONTEXT_BACKEND=owner \
./gradlew :enigma-swing:run --args='--jar "/home/julian/Dev-Env/Digitalisierungskolleg/Minecraft 1.21.11.jar" --edit-all'
```

`ENIGMA_LLM_CONTEXT_BACKEND` sets the startup default. During the demo, use `LLM -> Context backend` to switch between `Simple` (`owner`) and `Graph-based` (`graph`) without restarting. Keep the same JAR, model, and target examples when comparing both modes.

## Walkthrough

1. Open the constants class used during manual testing.
2. Open `LLM` and verify the menu status shows the configured model and context backend.
3. Request a single field suggestion with `Suggest name...` and apply `pi`.
4. Request another single suggestion with alternatives and choose an alternative from the dropdown.
5. Use `LLM -> Batch parallelism` to select a conservative local-model concurrency, e.g. 2 parallel requests.
6. Run current-editor batch suggestion preview with `Batch current class...`; this processes all unmapped targets in the active class.
7. Run a small whole-project batch suggestion preview with `Batch project...`; this still asks for a target limit.
8. Resize the batch dialog, inspect failures, use Select all / Clear all, and apply only safe rows.
9. Save mappings and verify only accepted names were written.
10. Switch `LLM -> Context backend -> Graph-based`, repeat one single suggestion, and compare whether the extra callers/referenced-class context changes the suggestion.

## Talking Points

- The plugin never blocks Enigma's `NameProposalService` with LLM calls.
- Suggestions are cached so Enigma's normal proposed-name rendering can display them.
- Guardrails handle common small-model failures: invalid identifiers, duplicate mapped names, owner-prefixed member names, noisy alternatives, and retryable validation failures.
- Local LM Studio demonstrates provider independence; stronger OpenAI-compatible models can be swapped in by changing configuration.
- The `owner` backend is compact and Enigma-native; the `graph` backend reuses Johannes' RAG idea without adding SQLite to the plugin.
