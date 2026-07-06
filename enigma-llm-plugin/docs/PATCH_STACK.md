# Suggested Patch Stack

1. **Minimal Fabric-Enigma API additions**
   - Add `EntryReferenceView#getNameableEntry()` so extensions can target the real renamable element under the cursor.
   - Add `GuiView#applyRename(EntryView, String)` so extensions can apply names through Enigma's normal validation and `EntryChange` path.

2. **LLM plugin subproject**
   - Add `enigma-llm-plugin` as a runtime dependency of `enigma-swing`.
   - Register `JarIndexerService`, `NameProposalService`, `GuiService`, `ProjectService`, and `I18nService` through Java `ServiceLoader`.
   - Keep LLM calls out of `NameProposalService`; it only serves cached suggestions.

3. **Context, prompt, and OpenAI-compatible client**
   - Build a lightweight ASM index for classes, fields, methods, parameters, field constants, calls, and field-use summaries.
   - Add runtime-selectable context backends: compact `owner` context and Johannes-inspired `graph` context over the live ASM index.
   - Support OpenAI-compatible endpoints through environment variables/system properties.
   - Keep prompt/request/retry/validation/cache updates in `LlmSuggestionEngine`, separate from Swing wiring.
   - Normalize, validate, retry, and filter suggestions before they reach the UI or cache.

4. **Swing UX**
	- Add current-target, current-class batch, and whole-project batch suggestion flows.
	- Keep whole-project batch in the menu bar only; editor context menus expose local actions only.
	- Add an `LLM -> Context backend` radio submenu so demos can switch between simple owner-local and graph-based prompts without restarting Enigma.
	- Add an `LLM -> Batch parallelism` radio submenu and make current-class batch process all active-class targets instead of asking for an artificial limit.
	- Keep menu wiring in `LlmMenu` so `LlmGuiService` focuses on orchestration.
   - Keep reusable Swing dialog construction in `LlmSuggestionDialogs` so single-suggestion and batch-preview UI helpers live outside the request/apply flow.
   - Keep `LlmRenameApplier` responsible for validated rename application, top-level class rename normalization, and selected-row conflict checks.
   - Keep `LlmGuiService` as the thin bridge between Enigma GUI actions, the suggestion engine, dialogs, and rename application.
   - Add selectable alternatives for single suggestions.
   - Add resizable batch preview with Select all / Clear all and pre-apply conflict checks.

5. **Tests, docs, and evaluation**
   - Cover service registration, prompt/context building, parsing, validation, UI helper behavior, API rename validation, and evaluation JSONL parsing/output.
   - Add a synthetic owner-vs-graph comparison task for measuring whether the Johannes-inspired backend improves suggestions for the same targets.
   - Keep README, demo route, and evaluation harness documented.

6. **Optional subscription bridge**
   - Keep the Enigma plugin's direct integration boundary OpenAI-compatible (`/v1/models` and `/v1/chat/completions`).
   - Document subscription-backed providers as separate local sidecars rather than embedding ChatGPT/Claude/Grok web-login automation in the Swing plugin.
   - Add the standalone `enigma-llm-bridge` subproject with `/healthz`, `/v1/models`, `/v1/chat/completions`, optional bearer-token protection, and a fake provider for contract tests.
   - Add the Codex bridge first: the user runs `codex login`, the sidecar talks to `codex app-server`, lists models through Codex `model/list`, and Enigma keeps using its existing endpoint/model dialog.
   - Treat Claude Code and OpenCode-style integrations as later adapters that must use documented provider/tooling behavior.
