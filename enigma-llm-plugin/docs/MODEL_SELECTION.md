# Model Selection

Status: 2026-07-05 (original planning snapshot) — **UPDATE 2026-07-08 below.**

> **UPDATE 2026-07-08 — some entries below are superseded.** The authoritative,
> chronological record of what was actually chosen and why lives in the local
> handoff timeline `LLM_ENIGMA_HANDOFF.local.md` (entries 2026-07-06 → 2026-07-08),
> with the short live state in `LLM_ENIGMA_STATE.local.md`. Summary of the outcome:
>
> - **Final essay roster (single RX 9060 XT, 16 GB):** three representative Qwen
>   members — `qwen2.5-coder-14b` Q6_K (dense, code, primary), `qwen3-8b` (newer,
>   small), and `qwen3-coder-30b-a3b` MoE at IQ4_XS (top performer). `qwen2.5-coder-7b`
>   Q4 is the improvement-loop subject (too weak for a headline). DeepSeek-Coder-V2-Lite,
>   `qwen3-14b`, and a 14B Q5/Q6 quant ablation are context, not headline points.
> - **Quant evidence (measured, api exact/336, graph-raised):** within the 30B, IQ4_XS
>   (33) > Q3_K_M (26) — less aggressive quant is better; and 30B IQ4_XS (33) > 14B
>   Q6_K (19) — a bigger model at 4-bit beats a smaller one at 6-bit. The naive
>   "high quant makes a big model worse than a small one" did NOT hold at 3–4 bit.
>   The extreme 1–2 bit regime is being probed separately (Qwen3-Coder-Next).
> - **2026 landscape (primary sources cited in the essay, `references.bib`):** beyond
>   Qwen, the current open families ship only very large mixture-of-experts models
>   (DeepSeek V4 284B/1.6T, Llama 4 109B/400B, Kimi K2.x 1T) that exceed one 16 GB GPU
>   — a small active-parameter count does NOT reduce resident weights. Google **Gemma 4**
>   (Apr 2026, dense 31B) was added as a non-code, cross-family contrast (Q3_K_S,
>   marginal 16 GB fit). Newer Qwen (3.5/3.6) exists; the roster was frozen for
>   reproducibility. **Gemma 3n is fully superseded by this Gemma 4 decision.**
> - **Reproducibility rule (standing):** benchmark/judge scripts under
>   `enigma-llm-plugin/evaluation/` (sweep, backend-matrix, semantic-judge, 7b-loop)
>   are FROZEN once their numbers appear in the essay — do not delete or edit them.

This document tracks model candidates for the Enigma LLM plugin. The plugin is
provider-neutral and only needs an OpenAI-compatible chat-completions endpoint,
but the primary project story should remain local-first: decompiled Minecraft
context and mappings should not be uploaded to commercial providers unless the
demo/evaluation data is synthetic or explicitly cleared for that use.

## Decision Summary

- Best current local story: use Qwen Coder locally, because it is code-oriented,
  available in practical GGUF quantizations, and works with LM Studio's
  OpenAI-compatible endpoint.
- Laptop role: smoke tests only. The measured `qwen2.5-coder-1.5b-q4km`
  baseline proves the pipeline but is too weak for final naming quality.
- RX 9060 XT 16 GB target: `Qwen2.5-Coder-14B-Instruct` is the primary demo
  candidate. Prefer Q6_K (fall back to Q5_K_M only if VRAM is tight); avoid Q4
  because fine semantic discrimination is exactly where quant loss hurts a
  naming task.
- Key A/B: naming is closer to code *comprehension* than code *generation*, so
  test a same-size general model — `Qwen3-14B` (non-thinking) Q6_K — head to
  head against the 14B coder. If the general model is competitive, that is the
  report's most interesting finding.
- Reasoning policy: keep non-thinking models as the default for interactive use
  and JSONL benchmarks. Treat thinking/reasoning models as an optional "Deep
  Analysis" path for difficult single symbols or tie-breaks, not as the normal
  batch mode.
- Non-Qwen contrast: include `DeepSeek-Coder-V2-Lite-Instruct` (16B total /
  2.4B active MoE) as a family/architecture comparison, not an expected winner
  (it is older).
- Stretch model: `Qwen3-Coder-30B-A3B` Q3_K_M is worth trying as a MoE
  comparison but should not block the report. `Qwen3.6-35B-A3B` and Gemma 3n
  are deprioritized; run Gemma only if you explicitly want a "small/efficient,
  non-code-specialized" baseline in the report.
- Hosted/commercial providers: use only on synthetic/cleared cases as a quality
  ceiling, not as the core workflow.

## Current Baseline

The only measured local baseline so far is:

| Model | Hardware | Cases | Accepted | Exact | Usable | Failed | Avg latency |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `qwen2.5-coder-1.5b-q4km` | current laptop / LM Studio | 25 | 25 | 4 | 9 | 0 | 4490.2 ms |

Context-backend comparison with the same model:

| Backend | Cases | Accepted | Exact | Usable | Failed | Avg latency | Avg prompt chars |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `owner` | 6 | 6 | 1 | 4 | 0 | 4530.2 ms | 1616 |
| `graph` | 6 | 6 | 1 | 3 | 0 | 4858.5 ms | 2239.2 |

Interpretation: the 1.5B model is good enough to prove the pipeline, JSON
parsing, retries, and UI guardrails. It is not good enough to be the quality
target for the final demo.

## Hardware Classes

### Current Laptop

Observed state on 2026-07-03: AMD Cezanne/Vega integrated graphics, 13 GiB RAM,
and low free memory during development. Treat this as a tiny-model smoke-test
machine.

Recommended models:

| Priority | Model | Quantization target | Role |
| ---: | --- | --- | --- |
| 1 | `Qwen2.5-Coder-1.5B-Instruct` | Q4_K_M | Current baseline and smoke tests |
| 2 | `Qwen2.5-Coder-3B-Instruct` | Q4_K_M | Optional upper laptop test if memory allows |
| 3 | `Gemma 3n E2B/E4B` | available local quantization | Efficient fallback, not code-specialized |

Do not spend time tuning prompts around the laptop baseline. Use it to catch
integration regressions and to document small-model failure modes.

### User PC: RX 9060 XT, 16 GB VRAM

This should become the main local evaluation machine. Start with models that
fit comfortably, then try newer MoE candidates with reduced context length.

Recommended test order:

| Priority | Model | Quantization target | Expected role |
| ---: | --- | --- | --- |
| 1 | `Qwen2.5-Coder-14B-Instruct` | Q6_K (Q5_K_M if VRAM tight) | Primary quality candidate; full-offload dense code baseline |
| 2 | `Qwen3-14B` (Instruct / non-thinking) | Q6_K | Clean same-size A/B: general comprehension vs. code specialization |
| 3 | `DeepSeek-Coder-V2-Lite-Instruct` | Q5_K_M or Q6_K | Non-Qwen architectural contrast (16B / 2.4B active MoE); older, family comparison not expected winner |
| 4 | `Qwen3-Coder-30B-A3B-Instruct` | Q3_K_M | MoE stretch; low active params but full quantized weights still large |
| 5 | `Codestral-22B-v0.1` | Q4_K_M | Optional; tight on 16 GB and MNPL-0.1 (non-commercial) license — report caveat only |
| 6 | `Qwen2.5-Coder-7B-Instruct` or `Qwen3-8B` | Q6_K | Optional efficiency / size-down data point |

The LM Studio "Full GPU Offload Possible" badge is only a hint that the model
weights may fit in VRAM. It does not validate a runtime context length. Load
models with 4096 context for the first evaluation pass, then try 8192 only when
VRAM use and latency are stable. The plugin prompts do not need 128K/256K
context for the curated JSONL set, and high context lengths consume VRAM through
the KV cache.

Force non-thinking for Qwen3 models. Thinking is on by default and emits
`<think>...</think>`, which breaks the JSON-only client and inflates latency on
a ~5-token naming output. Prefer a GGUF/runtime path that explicitly sets
`enable_thinking=False`. A prompt-level `/no_think` instruction is not an
equivalent fallback: only count Qwen3 runs when a raw test request proves that
no `<think>` block is emitted. If LM Studio cannot reliably suppress thinking
content, exclude Qwen3 from the JSONL comparison for now or retest later with
explicit client-side `<think>` stripping.

## Analysis Depth Policy

The plugin should optimize for interactive reverse-engineering work, not for
maximal deliberation on every symbol. Use two conceptual modes when describing
or extending the UI; context length, context backend, and hint level are
orthogonal settings rather than separate user-facing modes.

| Mode | Intended use | Model behavior | Context behavior |
| --- | --- | --- | --- |
| Standard | Default editor use and batch preview | Non-thinking instruct/coder model, strict JSON, practical timeout | Short-to-moderate context; Auto context backend and useful analysis hints |
| Deep Analysis | Hard single symbols, ambiguous methods, tie-breaks | Optional reasoning/thinking model, longer timeout, may require loading another local model | Richer graph/caller/callee context |

Deep Analysis is useful when a name depends on several weak signals, such as
callers, field accesses, control-flow shape, or competing candidate names. It is
usually wasteful for obvious getters, setters, collection operations, constants,
or generated-looking boilerplate.

For local LM Studio workflows, Deep Analysis is not necessarily a free per-
request checkbox. If Standard and Deep use different model weights, switching
requires loading another model or running a second OpenAI-compatible server.
Only same-family models with a reliable runtime thinking toggle can switch modes
without a model reload.

Do not expose chain-of-thought in the plugin UI, JSONL output, screenshots, or
report artifacts. A reasoning model may be used internally, but the accepted
interface remains structured JSON. The current implementation expects numeric
`confidence` in the `0.0` to `1.0` range because auto-preselection,
duplicate-resolution, tie-breaks, dialogs, tests, and JSONL output all consume
that field. Treat the number as a model-reported heuristic score, not as a
calibrated probability. A future UI may display coarse labels such as high,
medium, or low, but that would be a schema and UI change rather than a docs-only
rename.

The current implementation also asks for concise `reasoning` and records it in
dialogs and JSONL output. Omitting reasoning for large Standard batch runs is a
possible throughput optimization, not the current behavior. Deep Analysis should
keep concise user-facing reasoning. If a model emits `<think>...</think>`, strip
or reject that content before parsing and never show it to the user.

### Larger Local GPUs

For 24 GB GPUs:

- `Qwen3-Coder-30B-A3B-Instruct` Q4/Q5 should be the first larger-model target.
- `Qwen2.5-Coder-32B-Instruct` Q4 is worth testing as the older but strong
  dense code baseline.
- `Qwen3-Coder-Next` Q2/Q3 may be possible with partial offload, but 80B total
  parameters make it less convenient than the 30B-A3B model.

For 48 GB or multi-GPU:

- Test `Qwen2.5-Coder-32B-Instruct` at higher quantization.
- Test `Qwen3-Coder-Next` more seriously.
- Treat `DeepSeek-V4-Flash` and `Kimi-K2-Instruct-0905` as remote or very large
  self-host candidates, not normal desktop models.

## Model Families

### Qwen

Qwen is currently the strongest local-first family for this project because it
has code-specialized variants, Apache-2.0 licensing on the relevant Qwen model
cards, OpenAI-compatible serving paths, and readily available GGUF
quantizations.

Recommended practical candidates:

- `Qwen2.5-Coder-14B-Instruct`: best first target for the RX 9060 XT. It is
  code-specific, has 14.7B parameters, and supports long context. Use it as the
  main local report baseline if it performs well.
- `Qwen3-Coder-30B-A3B-Instruct`: newer code MoE model, 30.5B total and 3.3B
  activated, native 256K context. Try after the 14B dense baseline.
- `Qwen3-Coder-Next` and `Qwen3.6-35B-A3B`: interesting but outside the main
  16 GB matrix. Treat them as extra stretch tests for larger GPUs or later
  partial-offload experiments, not as report blockers.

### DeepSeek

`DeepSeek-V4-Flash` and `DeepSeek-V4-Pro` are high-end MoE models. The model
card lists 284B total / 13B activated for Flash and 1.6T total / 49B activated
for Pro, both with 1M context. Quantized variants exist, but the total weights
still make these unrealistic for a 16 GB local demo. Use DeepSeek V4 only as an
external or cloud comparison if legal/data-upload constraints allow it.

### Kimi

`Kimi-K2-Instruct-0905` is a strong agentic coding model, but it is far too
large for normal local testing: the model card lists 1T total parameters, 32B
activated parameters, and 256K context. This is a remote comparison candidate,
not a local LM Studio target for the course demo.

### Gemma

Gemma is attractive for efficient local inference. `gemma-3n-E4B-it` is
designed for low-resource devices and uses selective parameter activation to
operate around effective 2B/4B sizes with 32K context. Keep it only as an
explicit efficiency baseline if the report needs that axis. It is not part of
the main PC quality matrix because it is not code-specialized.

## Commercial and Remote Comparison

Commercial providers are useful for a quality ceiling, but avoid sending real
decompiled Minecraft code or copyrighted artifacts unless this is explicitly
cleared. For the report, use synthetic JSONL cases or non-copyright fixtures.
The current bundled `evaluation/sample-cases.jsonl` is the intended safe
comparison set for hosted providers.

| Provider | Candidate | Role |
| --- | --- | --- |
| OpenAI | `gpt-5.4-mini` | Lower-cost comparison for JSONL synthetic cases |
| OpenAI | `gpt-5.5` | High-quality coding ceiling |
| Google | Gemini 3.5 Flash | Coding/agentic comparison, supports OpenAI compatibility according to Gemini docs |
| Google | Gemini 3.1 Pro | Strong preview comparison if available |
| Anthropic | Claude Sonnet/Opus/Fable line | High-quality comparison through native API or OpenAI-compatible proxy |
| DeepSeek | DeepSeek V4 hosted endpoint | Open-weight quality ceiling, not local |
| Moonshot | Kimi K2 hosted endpoint | Agentic coding quality comparison, not local |

Recommended report framing:

- local runs answer whether the workflow is privacy-preserving and usable in the
  actual Enigma setting;
- hosted runs answer how much quality is left on the table because of local
  hardware limits;
- do not compare hosted providers on real Minecraft prompts unless upload rights
  are explicitly clarified.

## Benchmark Protocol

Always keep the model, backend, and output path in the result filename.
Use `BENCHMARK_RUNBOOK.md` for the exact PC test workflow and record every run
in `evaluation/model-benchmark-runs.tsv`.

Run the fixed JSONL benchmark:

```sh
ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 \
ENIGMA_LLM_MODEL=qwen2.5-coder-14b-q6k \
./gradlew :enigma-llm-plugin:runEvaluation \
  -Pout=enigma-llm-plugin/evaluation/results/qwen2.5-coder-14b-q6k-2026-07-03.jsonl
```

Run the context-backend comparison:

```sh
ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 \
ENIGMA_LLM_MODEL=qwen2.5-coder-14b-q6k \
./gradlew :enigma-llm-plugin:compareContextBackends \
  -Pout=enigma-llm-plugin/evaluation/results/context-backend-comparison-qwen2.5-coder-14b-q6k-2026-07-03.jsonl
```

For each model record:

- LM Studio model identifier and quantization
- context length
- GPU offload setting
- batch parallelism
- exact, usable, failed, and accepted counts
- average latency
- notable failure modes
- whether `owner` or `graph` looked better

Concrete GGUF targets and approximate file sizes are listed in
`BENCHMARK_RUNBOOK.md`. The important practical correction is that the 30B/35B
MoE models have low active-parameter counts, but their quantized total weights
still need substantial memory. On the 16 GB GPU, treat `Qwen2.5-Coder-14B`
Q5_K_M/Q6_K as the realistic quality target and 30B/35B MoE models as stretch
tests.

Manual demo acceptance should still be done in the live UI:

- single suggestion
- alternative selection
- current-class batch
- project batch
- Select all / Clear all
- duplicate-name blocking before apply
- saved mappings contain only accepted names

## Current Recommendation

For the course demo and report:

1. Keep `qwen2.5-coder-1.5b-q4km` as the laptop smoke-test baseline.
2. Make `Qwen2.5-Coder-14B-Instruct` Q6_K (Q5_K_M fallback) the primary RX 9060
   XT candidate.
3. Run the general-vs-coder A/B: `Qwen3-14B` (non-thinking) Q6_K against the 14B
   coder on the same JSONL set. This is the report's most interesting finding if
   the general model competes.
4. Add `DeepSeek-Coder-V2-Lite-Instruct` Q5/Q6 as a non-Qwen contrast.
5. Test `Qwen3-Coder-30B-A3B-Instruct` Q3_K_M as the MoE stretch; use short
   context and accept partial offload if necessary.
6. Treat `Codestral-22B` and `Qwen3-8B` as optional; run Gemma 3n only for an
   explicit "small/efficient, non-code" baseline.
7. Treat DeepSeek V4 and Kimi K2 as optional remote comparisons, not local
   deliverables.

## Sources

- Qwen2.5-Coder-14B-Instruct: https://huggingface.co/Qwen/Qwen2.5-Coder-14B-Instruct
- Qwen3-14B: https://huggingface.co/Qwen/Qwen3-14B
- Qwen3-8B: https://huggingface.co/Qwen/Qwen3-8B
- DeepSeek-Coder-V2-Lite-Instruct: https://huggingface.co/deepseek-ai/DeepSeek-Coder-V2-Lite-Instruct
- Codestral-22B-v0.1: https://huggingface.co/mistralai/Codestral-22B-v0.1
- Qwen3-Coder-30B-A3B-Instruct: https://huggingface.co/Qwen/Qwen3-Coder-30B-A3B-Instruct
- Qwen3-Coder-Next: https://huggingface.co/Qwen/Qwen3-Coder-Next
- Qwen3.6-35B-A3B: https://huggingface.co/Qwen/Qwen3.6-35B-A3B
- DeepSeek-V4-Flash: https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash
- Kimi-K2-Instruct-0905: https://huggingface.co/moonshotai/Kimi-K2-Instruct-0905
- Gemma 3n E4B it: https://huggingface.co/google/gemma-3n-E4B-it
- OpenAI model docs: https://developers.openai.com/api/docs/models
- Gemini model docs: https://ai.google.dev/gemini-api/docs/models
- Claude model docs: https://platform.claude.com/docs/en/about-claude/models/overview
