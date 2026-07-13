# Evaluation Plan

## Goal

Measure whether the extension produces usable deobfuscation suggestions and where model quality, prompt context, validation, or UI guardrails fail.

## Run

```sh
ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 \
ENIGMA_LLM_MODEL=qwen2.5-coder-1.5b-q4km \
./gradlew :enigma-llm-evaluation:runEvaluation
```

Custom input/output:

```sh
./gradlew :enigma-llm-evaluation:runEvaluation \
  -Pcases=/path/to/cases.jsonl \
  -Pout=/path/to/results.jsonl
```

The same command works with other OpenAI-compatible providers by changing `ENIGMA_LLM_BASE_URL`, `ENIGMA_LLM_MODEL`, and optionally `ENIGMA_LLM_API_KEY`.

The JSONL harness evaluates prompts stored directly in the case file. It is useful for reproducible model comparisons, but it does not rebuild prompts from Enigma state.
See `MODEL_SELECTION.md` for the current local and remote model candidates by hardware class.
Use hosted/commercial providers only with `evaluation/sample-cases.jsonl` or other synthetic/cleared cases. Keep live Enigma prompts and real decompiled Minecraft context on local endpoints.

Existing result files can be summarized again without calling an LLM:

```sh
./gradlew :enigma-llm-evaluation:summarizeEvaluation \
  -Presults=enigma-llm-evaluation/evaluation/results/qwen2.5-coder-1.5b-q4km-2026-07-03.jsonl
```

For live context-strategy comparison, run Enigma with the same JAR/model/targets and switch:

```sh
ENIGMA_LLM_CONTEXT_BACKEND=owner
ENIGMA_LLM_CONTEXT_BACKEND=graph
```

`owner` is the compact current Enigma context. `graph` is a Johannes-inspired graph/RAG backend over the live ASM index, adding callers, field accessors, referenced classes, and mapped-name evidence.

A small automated synthetic comparison is also available:

```sh
ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 \
ENIGMA_LLM_MODEL=qwen2.5-coder-1.5b-q4km \
./gradlew :enigma-llm-evaluation:compareContextBackends
```

This task builds the same in-memory ASM fixture twice, once with `owner` prompts and once with `graph` prompts, then writes JSONL results.

## Case Format

Each JSONL line contains:

- `id`: stable case id
- `kind`: `CLASS`, `FIELD`, `METHOD`, or `PARAMETER`
- `targetName`: display name sent to the model
- `prompt`: bytecode/decompiler context
- `expected`: ideal primary suggestion
- `acceptable`: optional list of also-usable suggestions

## Metrics

The harness writes one JSON object per case with:

- model and case id
- suggested name, alternatives, numeric confidence, reasoning
- `accepted`: endpoint returned parseable output
- `exact`: primary suggestion equals `expected`
- `usable`: primary or alternative is in the acceptable set
- latency in milliseconds
- `errorCategory` and error message if the request failed

The summary tool reports `invalidJson` and `truncated` separately. Both matter
for strict JSON-schema runs, but they point at different fixes:

- `truncated` means the endpoint reported `finish_reason == "length"`, i.e. the
  response hit the configured response-token budget mid-JSON. The fix is a
  larger `MAX_RESPONSE_TOKENS` (or a less verbose reasoning prompt), not a
  prompt/context change. A non-zero `truncated` count is also the expected
  failure signature of running a thinking/reasoning model through the
  interactive 384-token budget.
- `invalidJson` means the response parsed as malformed for another reason
  (endpoint ignored `response_format`, a leaked `<think>` block broke
  extraction, wrong field types). The fix is on the prompt/endpoint side.

If either bucket is non-zero for a model, inspect the saved error messages
before judging the prompt or context backend.

The current schema stores numeric model-reported confidence because the plugin
uses it for batch preselection, duplicate-name conflict resolution, and
tie-breaks. Treat this number as an uncalibrated heuristic score, not as a
probability. For report discussion, use exact/usable/failure metrics, latency,
and concrete examples as the real evidence; confidence is supporting context
only.

Before recommending any automatic threshold, compare this model score against
`exact` and `usable` outcomes in the JSONL results. If the score does not
correlate with accepted quality for a model, keep it as a display/sorting hint
only and do not use that model's score as evidence for reliable auto-apply.

## Initial Test Set

`evaluation/sample-cases.jsonl` covers the current manual-test themes:

- numeric constant fields: `pi`, degrees/radians conversion, epsilon
- constants class naming
- parameter naming from method context
- small-model failure modes around duplicate names and owner-prefix copying
- method naming from call targets
- simple Minecraft/Yarn-like examples for block positions, item stacks, and colors

## Local Baseline

The first expanded local baseline was run on 2026-07-03 with LM Studio and
`qwen2.5-coder-1.5b-q4km`.

Results:

- total cases: 25
- accepted parseable responses: 25
- exact primary matches: 4
- usable primary or alternative suggestions: 9
- failed requests: 0
- average latency: 4490.2 ms

The saved JSONL result is:

```text
evaluation/results/qwen2.5-coder-1.5b-q4km-2026-07-03.jsonl
```

Representative observations:

- The endpoint and JSON parsing were stable; every request returned a parseable suggestion.
- The small local model handled obvious constants such as `epsilon`, `alpha`, and some count-like parameters.
- It often copied obfuscated names or local variable placeholders for parameters.
- It frequently produced owner-prefixed member names such as `geometryConstantsB` or `rotationUtilsA`; this supports the plugin-side guardrails.
- It sometimes placed a usable answer in `alternatives` instead of the primary suggestion, which supports the single-suggestion dropdown UX.
- It hallucinated domain names for weak class contexts, for example texture/shader/model alternatives for a color-like class.

## Context Backend Comparison

The plugin currently supports two runtime prompt backends:

- `owner`: target plus declaring class inventory, constants, methods, calls, field uses, parameters, and current mappings.
- `graph`: the `owner` root context plus Johannes-style graph neighbors from the in-memory ASM index: incoming callers, field accessors, referenced classes, and dependency summaries.

The first automated comparison was run on 2026-07-03 with LM Studio and
`qwen2.5-coder-1.5b-q4km`.

Results:

- `owner`: 6 total, 6 accepted, 1 exact, 4 usable, 0 failed, 4530.2 ms average latency, 1616 average prompt chars
- `graph`: 6 total, 6 accepted, 1 exact, 3 usable, 0 failed, 4858.5 ms average latency, 2239.2 average prompt chars

The saved JSONL result is:

```text
evaluation/results/context-backend-comparison-qwen2.5-coder-1.5b-q4km-2026-07-03.jsonl
```

Interpretation:

- The graph backend helped one parameter case where `owner` repeated the placeholder `p_1_` and `graph` returned `count`.
- The graph backend also confused the small model in caller-heavy cases, for example suggesting `renderStackCount` for a getter because the caller method had that mapped name.
- For the current 1.5B local model, `graph` is not globally better than `owner`; it is an experimental backend worth re-testing with larger models.
- A future improvement would export generated prompts from real Enigma targets so the same Minecraft targets can be benchmarked through both backends automatically.
