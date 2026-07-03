# Enigma LLM Name Proposal Plugin

Fabric-Enigma plugin that suggests names for obfuscated classes, fields, methods, and method parameters through an OpenAI-compatible chat-completions endpoint.

The plugin follows Fabric/Yarn Filament's Enigma plugin pattern:

- `JarIndexerService` builds a lightweight ASM index when a JAR is opened.
- `NameProposalService` only returns cached suggestions; it never calls the LLM.
- `GuiService` adds an `LLM` menu and editor context-menu actions.
- `ProjectService` clears project-local state on open/close.

Parameter suggestions are inferred from method descriptors, with local-variable table names used as extra context when available. Non-argument local variables are intentionally skipped for now because their identity and source-level usefulness are less stable.

## Run

From the `fabric-enigma` root:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
ENIGMA_LLM_BASE_URL=http://localhost:1234/v1 \
ENIGMA_LLM_MODEL=your-model-name \
./gradlew :enigma-swing:run
```

`enigma-swing` depends on this plugin at runtime, so the plugin is loaded automatically by Java `ServiceLoader`.

## Configuration

- `ENIGMA_LLM_BASE_URL`: OpenAI-compatible API base URL, default `http://localhost:1234/v1`
- `ENIGMA_LLM_MODEL`: model name, required before requesting suggestions
- `ENIGMA_LLM_API_KEY`: optional bearer token
- `ENIGMA_LLM_TIMEOUT_SECONDS`: request timeout, default `120`
- `ENIGMA_LLM_AUTO_APPLY_THRESHOLD`: optional confidence threshold for preselecting batch suggestions
- `ENIGMA_LLM_BATCH_PARALLELISM`: parallel requests for batch suggestions, default `2`, maximum `8`
- `ENIGMA_LLM_CONTEXT_BACKEND`: prompt context backend, default `owner`

System properties with matching lower-camel names are also supported, for example `-Denigma.llm.model=...`.

The OpenAI-compatible client uses HTTP/1.1 so local LM Studio servers work without HTTP/2 negotiation issues.

Context backend values:

- `owner`: current compact Enigma context. Includes the target, declaring class, fields, methods, constants, calls, field uses, parameters, and live mappings.
- `graph`: Johannes-inspired graph/RAG context over the same live ASM index. Starts with the target and declaring class, then adds call/field-use neighbors, incoming callers, field accessors, referenced classes, and mapped names.
- `rag` and `johannes` are accepted aliases for `graph`.

`ENIGMA_LLM_CONTEXT_BACKEND` sets the startup default. The `LLM -> Context backend` submenu can switch between `Simple` (`owner`) and `Graph-based` (`graph`) while Enigma is running.
`ENIGMA_LLM_BATCH_PARALLELISM` sets the startup default for batch request concurrency. The `LLM -> Batch parallelism` submenu can switch between 1 and 8 parallel requests while Enigma is running.

## Usage

Open a JAR in Enigma, then use either:

- `LLM -> Suggest name...`
- `LLM -> Context backend -> Simple` or `Graph-based`
- `LLM -> Batch parallelism -> N parallel requests`
- `LLM -> Batch current class...`
- `LLM -> Batch project...`
- editor right-click context menu entries for single suggestions and current-class batch suggestions

Batch mode always shows a preview table. The current-class batch uses all currently unmapped targets in the active editor class; the project batch uses the whole indexed JAR up to the requested limit and is only available from the `LLM` menu bar entry. Suggestions are applied only for selected rows.
The `LLM` menu shows the currently configured model and active context backend when opened.

## Evaluation

Run the bundled JSONL evaluation set against the configured OpenAI-compatible endpoint:

```sh
ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 \
ENIGMA_LLM_MODEL=qwen2.5-coder-1.5b-q4km \
./gradlew :enigma-llm-plugin:runEvaluation
```

Use `-Pcases=/path/to/cases.jsonl` and `-Pout=/path/to/results.jsonl` for custom runs.
The JSONL harness evaluates the prompts stored in the case file. Context backend comparison currently happens through the live Enigma plugin by switching `ENIGMA_LLM_CONTEXT_BACKEND`.

Run the synthetic owner-vs-graph backend comparison:

```sh
ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 \
ENIGMA_LLM_MODEL=qwen2.5-coder-1.5b-q4km \
./gradlew :enigma-llm-plugin:compareContextBackends
```

The first local 1.5B baseline favored `owner` overall, while `graph` improved one parameter case. Re-test this task with larger local models before deciding which backend should be preferred for demos.

## Project Notes

- `docs/PATCH_STACK.md`: suggested review/PR split
- `docs/DEMO_ROUTE.md`: reproducible manual demo route
- `docs/EVALUATION.md`: evaluation harness and metrics
- `docs/REPORT_OUTLINE.md`: report structure draft
