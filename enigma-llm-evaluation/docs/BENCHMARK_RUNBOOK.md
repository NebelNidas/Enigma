# Benchmark Runbook

Status: 2026-07-05 (original) — **UPDATE 2026-07-08 below.**

> **UPDATE 2026-07-08.** The as-run procedure, the final roster, and the actual
> results now live in the local journal timeline `LLM_ENIGMA_JOURNAL.local.md`
> (2026-07-06 → 2026-07-08) plus `LLM_ENIGMA_STATE.local.md` (live state), and the
> committed runners/analysers under `enigma-llm-evaluation/evaluation/` (obfuscation
> sweep, `run-pc-queue.sh`, backend-matrix, `semantic-judge/`, `7b-loop/`). The
> per-model context map, `--parallel 1` (full n_ctx per request), and the raised
> `MAX_PROMPT_CHARS=16000` graph cap are the load-time facts that matter. The
> "Gemma 3n deprioritised" note below is superseded: Gemma **4** 31B was added as a
> non-code contrast (see MODEL_SELECTION.md update). **Reproducibility rule:** any
> script whose numbers appear in the essay is frozen — do not delete or edit it.

Use this runbook when testing local and remote models for the Enigma LLM plugin.
It assumes the model is exposed through an OpenAI-compatible endpoint.

## Data Boundary

Use local models for real decompiled Minecraft classes, live Enigma editor
targets, and any prompt that may contain copyrighted bytecode/decompiler
context. Use commercial or hosted providers only for:

- `evaluation/sample-cases.jsonl`,
- hand-written synthetic fixtures,
- prompts that are explicitly cleared for upload.

Do not put API keys into Gradle properties, result files, screenshots, or the
TSV notes. Use environment variables only.

## Codex/Fable Prompt-Batch Frontier Run

The code-in-prompt frontier comparison uses three paired prompt arms:

- `M`: metadata/context only. The model sees the target kind, owner, descriptor,
  access flags, graph/owner context, literals, calls, fields, and already-known
  mappings, but no decompiled target body.
- `M+C`: metadata/context plus the real decompiled body for the target method
  when the body can be extracted.
- `M++`: metadata/context plus a sterile, unrelated boilerplate block with the
  same character length as the real `M+C` body. The output directories use the
  shell-safe arm name `MPP` for this condition.

The intended contrasts are `M -> M+C` for "adding code-shaped material at all"
and `M++ -> M+C` for "real target-body content beyond token volume". Keep all
three arms paired on the same target set.

From the `fabric-enigma` root, build the paired prompt batches without model
calls:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:prepareCodexPromptBatches
```

This downloads/regenerates the OSS and Minecraft benchmark inputs as needed,
dumps offline M/M+C/M++ prompts into `build/llm-evaluation/codex-prompt-dumps/`,
selects semantically interesting METHOD targets from the `M+C` body dump, and
writes filtered replay batches to `build/llm-evaluation/prompt-batches/`.
Minecraft inputs come from Mojang/Fabric/Parchment artifacts; the task does not
download a Yarn source checkout.

The preparation tasks clean only their own prompt-dump and filtered-batch
directories. As a guardrail, they refuse to delete directories that appear to
contain Codex model outputs (`requestedModel`, `latencyMs`, or `ok` +
`suggestedName` records), and they refuse unmarked directories outside
`build/llm-evaluation` unless explicitly overridden with
`-PcodexPromptOverwriteUnmarked=true`. Deleting detected model outputs requires
the separate `-PcodexPromptDeleteModelOutputs=true` override.

Dry-run the model work queue before spending subscription budget:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:runCodexPromptBatch -PcodexDryRun=true
```

Run the default Codex batch with GPT-5.6-Sol high effort:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:runCodexPromptBatch \
  -PcodexModel=gpt-5.6-sol \
  -PcodexEffort=high \
  -PcodexParallel=2
```

The default run consumes `obscure=batch,mc=mc_batch`, arms `M,MC,MPP`, track
`realistic`, kind `METHOD`, and writes resumable one-target JSON files under
`build/llm-evaluation/codex-prompt-batch/`. Each record includes `latencyMs`,
measured around the `codex exec` subprocess, so it includes CLI overhead.

Run the matching Fable batch through the Claude CLI only when Anthropic/Fable
budget is available:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:runFablePromptBatch \
  -PfableEffort=high \
  -PfableParallel=5
```

Dry-run first with `-PfableDryRun=true`. The task consumes the same prompt-root
layout as `runCodexPromptBatch` and writes the same per-arm JSON record shape
under `build/llm-evaluation/fable-prompt-batch/` by default. Real runs write a
`run_manifest.json`; a later resume refuses to continue if the model, effort,
dataset, arm, track, kind, or prompt-root config changed. Existing failed or
unparsed records are preserved by default; pass `-PfableRetryFailed=true` only
when deliberately spending budget to retry them. Historical output directories
without a manifest require `-PfableAllowLegacyResume=true`.

Score the saved outputs:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:scoreCodexPromptBatch
```

For the paired frontier comparison between Fable and Sol, use:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:scoreFrontierRuns \
  -PfrontierFableOut=build/llm-evaluation/fable-prompt-batch \
  -PfrontierSolOut=build/llm-evaluation/codex-prompt-batch \
  -PfrontierPromptRoot=build/llm-evaluation/prompt-batches \
  -PfrontierDatasets=obscure=batch,mc=mc_batch \
  -PfrontierTracks=realistic \
  -PfrontierKinds=METHOD
```

This writes `score_fable_high.txt`, `score_gpt_sol.txt`,
`paired_fable_vs_sol.txt`, and, when `frontierPromptRoot` is provided, the
semantic-judge candidate JSONL files under
`build/llm-evaluation/frontier-scores/` by default. Use
`-PfrontierScoreOut=...` to write a different report directory.

After preparing prompt batches, verify that `M+C` and `M++` share the same
non-code context before comparing them:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:verifyCodexPromptPairing \
  -PcodexPromptRoot=build/llm-evaluation/prompt-batches
```

The verifier strips only the real-code or sterile-padding block and fails if
the remaining normalized context differs for paired `MC`/`MPP` rows.

Run the GPT/Codex semantic judge over the required residuals only after the
score step has produced `semantic_judge_required.jsonl`:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:runFrontierSemanticJudge \
  -PfrontierScoreOut=build/llm-evaluation/frontier-scores \
  -PfrontierJudgeModel=gpt-5.5 \
  -PfrontierJudgeEffort=high
```

This uses the same GPT judge model family as the preserved semantic-judge-v2
runs (`gpt-5.5` high via Codex CLI). Dry-run first with
`-PfrontierJudgeDryRun=true`. The task resumes from a
`.partial` verdict map, refuses to overwrite an existing final judge JSON unless
`-PfrontierJudgeOverwrite=true` is passed, and writes
`semantic_judge_codex_<model>_<effort>.json` in the frontier score directory.
Useful cost controls: `-PfrontierJudgeLimit=16`,
`-PfrontierJudgeBatchSize=8`, and `-PfrontierJudgeTimeoutSeconds=600`.

Run the same residual judge set through Grok Build, also without web search:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:runFrontierGrokSemanticJudge \
  -PfrontierScoreOut=build/llm-evaluation/frontier-scores \
  -PfrontierGrokJudgeModel=grok-build
```

This consumes the same `semantic_judge_required.jsonl`, uses the same rubric and
result shape, resumes from
`semantic_judge_grok_<model>_<effort>.json.partial`, and refuses to overwrite a
final result unless `-PfrontierGrokJudgeOverwrite=true` is passed. Dry-run first
with `-PfrontierGrokJudgeDryRun=true`; use `-PfrontierGrokJudgeLimit=16` for a
cheap wiring check. `grok-build` does not expose a reasoning-effort flag through
the CLI, so these outputs are labeled `default`. If Grok returns no parseable
JSON for a multi-item batch, the runner retries and then falls back to
single-item calls for that batch; tune with `-PfrontierGrokJudgeRetries=...`.
Partial fallback progress is written before aborting, so a rerun resumes from
the last successfully parsed item.

Summarize the finished semantic-judge verdicts without additional model calls:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:summarizeFrontierSemanticJudge \
  -PfrontierScoreOut=build/llm-evaluation/frontier-scores
```

This writes `semantic_judge_gpt55_summary.txt` and
`semantic_judge_gpt55_summary.json`. The scope is the
`semantic_judge_required.jsonl` residual-cell judge set; it intentionally does
not claim coverage for non-required residual candidates. Existing summary files
are not overwritten unless `-PfrontierJudgeSummaryOverwrite=true` is passed.

If a secondary judge run exists, compare judge stability without additional
model calls:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:compareFrontierSemanticJudges \
  -PfrontierScoreOut=build/llm-evaluation/frontier-scores
```

By default this compares the primary `gpt-5.5` high result against the saved
`gpt-5.6-sol` high partial, if both files are present. Override with
`-PfrontierJudgeLeft=...`, `-PfrontierJudgeRight=...`, and
`-PfrontierJudgeComparisonOut=...`.

Useful overrides: `-PcodexPromptRoot=...`, `-PcodexOut=...`,
`-PcodexDatasets=obscure=batch,mc=mc_batch`, `-PcodexTracks=realistic`,
`-PcodexKinds=METHOD`, `-PcodexLimit=10`, `-PcodexTimeoutSeconds=420`, and
`-PcodexNeutralCwd=...`. Passing `-PcodexPromptRoot` tells Gradle to use that
existing prompt root instead of running `prepareCodexPromptBatches`.

## Result Files

Keep three artifacts per model:

- JSONL sample result from `runEvaluation`
- JSONL context comparison result from `compareContextBackends`
- one row in `evaluation/model-benchmark-runs.tsv` per task

The TSV file is intentionally simple so it can be copied into the report or
opened in a spreadsheet.

## Evaluation Reports

For historical essay numbers, use the committed artifacts first. These tasks do
not start LM Studio and do not call Codex, Claude, Grok, Gemini, or any
OpenAI-compatible endpoint. Use `writeEvaluationReports` for the full offline
report set:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
./gradlew :enigma-llm-evaluation:writeEvaluationReports
```

Individual report tasks are:

- `writeMainBenchmarkReport`
- `writeCommercialBenchmarkReport`
- `writeBackendMatrixReports`
- `writeSemanticJudgeV2Report`
- `writeReferenceAblationReport`
- `writeHypoNearMissReport`
- `writeFixedJsonlSummaries`
- `collectReportOnlyArtifacts`

Each task writes to the report's canonical location in the committed
`evaluation/` tree, using these inputs:

- `evaluation/benchmark-raw-2026-07-11/benchmark/`
- `evaluation/benchmark-raw-2026-07-11/commercial/`
- `evaluation/results/*.jsonl`
- `evaluation/semantic-judge/`
- `evaluation/judge-ablation-provenance-2026-07-11/`
- `evaluation/semantic-judge/hypo-nearmiss-2026-07-10/`

Use `-PreportOut=...` to override a task output root and
`-PreportResamples=N` to reduce or increase bootstrap resamples for the
aggregate reports. If any target output already exists, the Python
driver prompts before overwriting it; in non-interactive mode it aborts unless
`-PreportOverwrite=true` is passed. Use `-PreportDryRun=true` to list target
files and whether they already exist without writing anything. Historical
reports whose raw directories are not committed are copied by
`collectReportOnlyArtifacts` and explicitly marked as report-only by that task.

Live generator runs are intentionally separate, because they depend on local
model state, SSH model switching, LM Studio, or subscription-backed CLIs. The
Gradle wrappers are:

- `runObfuscationSweep`
- `runBackendAblation`
- `runCacheOffAblation`
- `runTemp02Stability`
- `runQuantAblation`
- `run7bSweep`
- `run7bPromptExtension`
- `run7bHoldout`

## LM Studio Setup

The LM Studio model card / download view only indicates whether the model
weights are likely to fit in VRAM. It does not prove that a given runtime
context length will fit, because the KV cache is allocated when the model is
loaded. Use short context for the first pass. Larger context lengths increase
KV-cache memory and are not needed for the fixed JSONL sample set.

Recommended first pass:

```sh
lms load -y --gpu max --context-length 4096 --identifier qwen2.5-coder-14b-q6k <model-name-or-path>
```

Then ensure LM Studio's OpenAI-compatible server is listening on:

```text
http://127.0.0.1:1234/v1
```

Preflight before each run:

- LM Studio reports 100% GPU offload for the loaded model, or a plausible
  max-offload setup for the selected model.
- Start by loading the model with 4096 context; repeat at 8192 only if VRAM and
  latency remain stable. The download-card "Full GPU Offload Possible" badge is
  only a weight-fit hint, not a context-fit guarantee.
- `GET /v1/models` responds before starting Gradle.
- For Qwen3 models, a raw test request must emit no `<think>` block.
- The plugin can parse the model's JSON response.

## Analysis Depth Runs

Keep the main benchmark matrix in Standard mode: non-thinking model, strict
JSON, Auto context backend, useful analysis hints enabled, and a practical
interactive timeout. This is the fairest default for editor use and batch
preview. Context length and hint level can still be varied as explicit
benchmark parameters; they are not separate modes.

Use Deep Analysis only as an additional comparison for difficult cases:

- ambiguous methods where callers/callees decide the name,
- classes with many unknown members,
- fields whose purpose is only visible through usage,
- duplicate-name tie-breaks where two candidates have similar heuristic
  confidence.

Deep Analysis may use a thinking/reasoning model, richer context, and a longer
timeout. In local LM Studio setups this may require loading a different model or
running a second server; it is not necessarily a free checkbox per request. It
should not replace the main JSONL run, and it should not be used as the default
batch mode.

The current plugin still requests and records concise reasoning for Standard
runs. Omitting per-item reasoning in large Standard batches is a possible future
throughput optimization, not part of the current benchmark schema. Deep Analysis
should keep concise user-facing reasoning. If the model emits
`<think>...</think>`, remove or reject that content before parsing; result files
and screenshots should contain only the final structured suggestion and concise
user-facing reasoning.

## Fixed Sample Evaluation

From the `fabric-enigma` root:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 \
ENIGMA_LLM_MODEL=qwen2.5-coder-14b-q6k \
./gradlew :enigma-llm-evaluation:runEvaluation \
  -Pout=enigma-llm-evaluation/evaluation/results/qwen2.5-coder-14b-q6k-2026-07-03.jsonl
```

Record the printed summary in `evaluation/model-benchmark-runs.tsv`.

For a full model pass, use the helper script instead of running each Gradle task
manually:

```sh
ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 \
BENCHMARK_HARDWARE="RX 9060 XT 16 GB / LM Studio" \
BENCHMARK_QUANTIZATION=Q6_K \
BENCHMARK_CONTEXT_TOKENS=4096 \
BENCHMARK_GPU_OFFLOAD=max \
ENIGMA_LLM_MODEL=qwen2.5-coder-14b-q6k \
enigma-llm-evaluation/evaluation/run-model-benchmark.sh qwen2.5-coder-14b-q6k
```

The script writes:

- `evaluation/results/<model-id>-<date>.jsonl`
- `evaluation/results/context-backend-comparison-<model-id>-<date>.jsonl`
- `evaluation/results/<model-id>-<date>.summary.txt`

It also appends two rows to `evaluation/model-benchmark-runs.tsv`: one for the
fixed sample evaluation and one for the context-backend comparison.

The same summary can be regenerated from the saved JSONL file:

```sh
./gradlew :enigma-llm-evaluation:summarizeEvaluation \
  -Presults=enigma-llm-evaluation/evaluation/results/qwen2.5-coder-14b-q6k-2026-07-03.jsonl
```

## Context Backend Comparison

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 \
ENIGMA_LLM_MODEL=qwen2.5-coder-14b-q6k \
./gradlew :enigma-llm-evaluation:compareContextBackends \
  -Pout=enigma-llm-evaluation/evaluation/results/context-backend-comparison-qwen2.5-coder-14b-q6k-2026-07-03.jsonl
```

Record the owner and graph summaries in the notes column. The TSV totals can use
the combined 12-case result file, but the report should mention backend-specific
usable/exact counts.

You can reprint the backend-specific summaries later:

```sh
./gradlew :enigma-llm-evaluation:summarizeEvaluation \
  -Presults=enigma-llm-evaluation/evaluation/results/context-backend-comparison-qwen2.5-coder-14b-q6k-2026-07-03.jsonl
```

## Recommended PC Test Order

For the RX 9060 XT 16 GB machine:

1. `Qwen2.5-Coder-14B-Instruct` Q6_K, with Q5_K_M as the fallback if VRAM is tight.
2. `Qwen3-14B` non-thinking Q6_K for the same-size comprehension-vs-coder A/B.
3. `DeepSeek-Coder-V2-Lite-Instruct` Q5_K_M or Q6_K as the non-Qwen architecture contrast.
4. `Qwen3-Coder-30B-A3B-Instruct` Q3_K_M as the MoE stretch.
5. Optional: `Codestral-22B-v0.1` Q4_K_M as a license/fit caveat.
6. Optional: `Qwen2.5-Coder-7B-Instruct` or `Qwen3-8B` Q6_K as an efficiency/size-down data point.

Only test DeepSeek V4 or Kimi K2 if a hosted endpoint is already available and
the prompts are synthetic or cleared for upload.

After the Standard matrix, optionally run one Deep Analysis comparison on the
small curated hard-case subset. Do not let this block the report: it is a design
trade-off data point, not the core local workflow.

## Concrete GGUF Candidates

These Hugging Face GGUF repositories are good LM Studio search/download targets.
Start with the "first try" quantization, then move up or down depending on
whether LM Studio reports comfortable memory use and acceptable latency.

| Priority | Repository | First try | Approx file size | Fallback / upgrade | Why |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `unsloth/Qwen2.5-Coder-14B-Instruct-GGUF` | Q6_K | 12.1 GB | Q5_K_M 10.5 GB if VRAM is tight | Primary full-offload dense code baseline |
| 2 | `unsloth/Qwen3-14B-GGUF` | Q6_K | verify in LM Studio | Q5_K_M if needed | Same-size general comprehension vs. coder A/B; require non-thinking output |
| 3 | `unsloth/DeepSeek-Coder-V2-Lite-Instruct-GGUF` | Q5_K_M or Q6_K | verify in LM Studio | lower quant only if needed | Non-Qwen MoE architecture/family contrast |
| 4 | `unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF` | Q3_K_M | 14.7 GB | Q3_K_S 13.3 GB / Q4_K_S 17.5 GB | Modern code MoE stretch; Q4 likely needs partial offload or more than 16 GB |
| 5 | `unsloth/Codestral-22B-v0.1-GGUF` | Q4_K_M | verify in LM Studio | lower quant only if needed | Optional code model with MNPL-0.1/non-commercial license caveat |
| 6 | `unsloth/Qwen2.5-Coder-7B-Instruct-GGUF` or `unsloth/Qwen3-8B-GGUF` | Q6_K | 6-8 GB range | Q5_K_M if needed | Optional efficiency/size-down data point |

`Qwen3.6-35B-A3B` and Gemma 3n are no longer part of the main PC matrix. Run
Gemma only if the report explicitly needs a small, efficient, non-code baseline;
run 35B-class Qwen variants only as extra stretch tests after the main matrix.

For the 16 GB GPU, load models with 4096 context for the first run. If the
runtime load succeeds with full or acceptable offload and single suggestions are
fast enough, repeat promising models at 8192 context.

In LM Studio, use stable local identifiers such as:

```text
qwen2.5-coder-7b-q6k
qwen2.5-coder-14b-q6k
qwen3-14b-q6k
deepseek-coder-v2-lite-q5km
qwen3-coder-30b-a3b-q3km
codestral-22b-q4km
```

## Hosted Provider Comparison

Hosted comparisons are optional quality-ceiling runs. They are useful for the
report because they show whether failures are caused by our context/prompt or by
small local model capacity. Run them against `evaluation/sample-cases.jsonl`,
not against live Minecraft prompts.

OpenAI-compatible providers all use the same Gradle task shape:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
PATH=/usr/lib/jvm/java-17-openjdk/bin:$PATH \
GRADLE_USER_HOME="$PWD/.gradle" \
ENIGMA_LLM_BASE_URL=https://provider.example/v1 \
ENIGMA_LLM_MODEL=model-id \
ENIGMA_LLM_API_KEY="$PROVIDER_API_KEY" \
./gradlew :enigma-llm-evaluation:runEvaluation \
  -Pout=enigma-llm-evaluation/evaluation/results/model-id-synthetic-2026-07-03.jsonl
```

Suggested optional comparisons:

| Provider | Candidate | Use |
| --- | --- | --- |
| OpenAI | `gpt-5.4-mini` | cheaper quality comparison |
| OpenAI | `gpt-5.5` | high-quality coding ceiling |
| Google Gemini | Gemini 3.5 Flash | strong coding/agentic comparison through Gemini OpenAI compatibility |
| Google Gemini | Gemini 3.1 Pro | preview high-capability comparison if available |
| DeepSeek hosted | DeepSeek V4 Flash | open-weight hosted comparison |
| Moonshot hosted | Kimi K2 | large agentic coding comparison |

If a provider is not directly OpenAI-compatible, use a local compatibility proxy
only for synthetic cases and record the proxy in the TSV notes.

## Manual Notes Per Run

Capture these observations while the model is loaded:

- model identifier as shown by LM Studio
- quantization
- context length
- GPU offload setting
- approximate tokens/second if LM Studio shows it
- whether JSON output was reliable
- common naming errors
- whether alternatives were useful
- whether `owner` or `graph` looked better

## Stop Criteria

A model is demo-worthy if it:

- returns parseable JSON consistently,
- improves clearly over the 1.5B baseline,
- avoids frequent duplicate/owner-prefixed names after guardrail retries,
- has acceptable latency for single suggestions,
- can run the 25-case sample without manual intervention.
