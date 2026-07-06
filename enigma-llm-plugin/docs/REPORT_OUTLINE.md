# Report Outline

## 1. Problem And Motivation

- Enigma is used for manual deobfuscation, but naming classes, methods, fields, and parameters is slow and context-heavy.
- LLMs can propose names from surrounding bytecode/decompiler context, but quality depends strongly on model capability and context design.

## 2. Background

- Fabric-Enigma plugin/service architecture.
- Difference between cached name proposals and explicit LLM requests.
- Provider-independent OpenAI-compatible API design, with LM Studio as local reference.
- Optional subscription-backed providers through local sidecar bridges: Enigma keeps the stable `/v1/models` and `/v1/chat/completions` contract, while provider-specific tools such as Codex own their login/session handling.

## 3. Implementation

- New `enigma-llm-plugin` subproject using Fabric/Filament-style plugin registration.
- Minimal Enigma API additions for cursor target resolution and validated rename application.
- ASM project index for owner class, fields, methods, descriptors, constants, method calls, field uses, and parameters.
- Runtime-selectable prompt context backends: compact Enigma-native `owner` context and Johannes-inspired `graph` context.
- Prompt construction, response parsing, normalization, validation, retry, and caching.
- Swing UI: `LLM` menu with model/backend status, current-target suggestion, selectable alternatives, current-class and whole-project batch preview, Select all / Clear all, and pre-apply conflict checks.
- Code organization: `LlmSuggestionEngine` owns prompt/request/retry/validation/cache logic, `LlmMenu` owns menu wiring/status rendering, `LlmSuggestionDialogs` owns single-suggestion and batch-preview dialog construction, `LlmRenameApplier` owns rename normalization/application and selected-row conflict checks, and `LlmGuiService` remains the thin Enigma GUI bridge.
- Package note: the prototype keeps these package-private components in one Java package to avoid widening internal Enigma adapter types; responsibilities are separated by classes rather than artificial public subpackages.

## 4. Evaluation

- JSONL harness over OpenAI-compatible endpoints.
- Local LM Studio model as main evaluation target.
- Optional stronger models through alternate Base URL / Model / API key.
- Metrics: accepted, exact, usable, failed, latency, retry/failure reason.
- Discussion of representative cases and failure modes.
- Current local baseline: `qwen2.5-coder-1.5b-q4km`, 25 cases, 25 accepted responses, 4 exact matches, 9 usable suggestions, 0 failed requests, 4490.2 ms average latency.
- Context-backend baseline: `owner` beat `graph` on the 1.5B local model in the synthetic comparison, with 4 usable suggestions vs. 3 usable suggestions, although `graph` fixed one parameter-placeholder failure.

## 5. Results And Discussion

- Show where the local small model works: obvious constants and simple context.
- Show limitations: hallucinated domain names, copied mapped names, owner-prefix suggestions, invalid Java style.
- Explain how guardrails and retry improve robustness without hiding model limitations.
- Use concrete examples from the baseline: `epsilon`, `alpha`, and `count` worked; parameter placeholders such as `p_1_`, owner-prefixed names such as `geometryConstantsB`, and hallucinated texture/shader/model class names did not.
- Discuss Johannes' RAG prototype as an alternative context strategy: richer graph context and token prioritization, but weaker Enigma integration than the implemented live plugin path.
- Explain that richer context is not automatically better for small models: the graph backend can overemphasize caller names and make the model copy the surrounding operation instead of naming the target.

## 6. Conclusion And Outlook

- Prototype demonstrates feasibility of LLM-assisted Enigma naming with local model support.
- Upstream path requires API review, UX polishing, and larger evaluation.
- Future work: inline editor suggestions, richer data-flow/call-site context, better model selection, Codex/OpenCode-style subscription bridges, and team/user study.
