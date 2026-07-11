# Provenance Ledger — essay empirical claims → data → scripts → commits

This document traces every quantitative claim in the essay
(`digitalisierungskolleg-arbeit`, `main.tex`) back to its origin: **input data →
obfuscation/harness script → raw output → aggregation/analysis script → the
`fabric-enigma` commit that produced it.** It is the reproducibility record
required by the project's standing directive ("the exact version that produced an
essay number must stay recoverable + runnable").

Line numbers refer to `essay/main.tex` as of the 2026-07-11 critique-response pass
(essay commit `da9962e`). All commit hashes below are in the **`fabric-enigma`**
repo unless marked *(essay repo)* — the essay lives in a **separate** git repo
(`github.com/NebelNidas/digitalisierungskolleg-arbeit`); the outer
`Digitalisierungskolleg/` directory is not a repo.

---

## 0. Where the raw rows live (gap now closed)

The headline round-trip outputs behind Tables `tab:sweep`, `tab:semantic`
(exact-match counts), `tab:commercial`, the structure-only track, the context
comparison, temp0.2 and cache-off are written to
`enigma-llm-plugin/build/llm-evaluation/**`, which is **git-ignored** and ephemeral.
As of 2026-07-11 the **essay-critical rows are preserved in version control** at
[`benchmark-raw-2026-07-11/`](benchmark-raw-2026-07-11/) (commit `0e95f53`, 393 files
/ ~18 MB, OSS corpus only) — see its README for the dir→claim map. So the per-target
`exact`/`normalized`/`usable` rows are now durably recoverable, not only
"reproducible in principle" from the committed scripts.

### What "reproducible" means here (three distinct senses — the essay numbers satisfy all three)
1. **Methodologically reproducible.** Every generating + aggregating script is committed and the corpus
   is sha1-pinned in `build.gradle`; checking out the recorded commit and re-running regenerates
   equivalent numbers. The aggregators were smoke-tested 2026-07-11 against the real data and reproduce
   the essay tables exactly (`aggregate_results.py`, `analyze_backend_matrix.py`, `agg_v2.py`, `agg_hypo.py`).
2. **Byte-preserved.** The exact per-target rows that produced the essay figures are now committed
   (`benchmark-raw-2026-07-11/`, `0e95f53`), so any table can be re-derived byte-for-byte from the
   committed rows without re-running any model.
3. **NOT bit-identical on a fresh model re-run** — and this is the only sense in which they are "not
   reproducible", which is a property of the *models*, not of our preservation:
   - **Commercial cloud models** (`gpt-5.5`, `claude-opus/sonnet`, `grok`) are non-deterministic even at
     temperature 0 — vendor-side batching / hardware / routing yields different tokens across identical
     calls, so their per-target verdicts cannot be reproduced token-for-token.
   - **Local models on GPU**: floating-point non-associativity in batched matmul can flip an argmax token
     run-to-run even at temperature 0 (documented as "co-decode is not bit-identical greedy"); `--parallel 1`
     minimises but does not eliminate it.
   - The **aggregates are stable** regardless: the temp=0.2 stability slice measured run-to-run jitter at
     ~±3 targets / 300, so a multi-point model delta is capability, not noise.

   → So "not byte-identically regenerable" (612d09d's README) means *a new run of the models won't be
   token-identical*; it does **not** mean the essay numbers are unrecoverable — they are both preserved (2)
   and re-derivable (1).

Precedent: `612d09d` preserved the *derived* judge / ablation / Hypo outputs from a volatile `/tmp`
scratchpad; `0e95f53` extends the same treatment to the main-corpus per-target rows. Not preserved
(intentionally): running/broken/contaminated gemma dirs, the incomplete coder-next IQ1 probe, backups,
logs — add gemma-4-31b (no-think) + coder-next IQ1 once they complete and land in the essay.

What IS committed as raw output: the 1.5B smoke baseline (`evaluation/results/*.jsonl`),
the Hypo near-miss package (`evaluation/semantic-judge/hypo-nearmiss-2026-07-10/`),
and the extracted residuals + judge verdicts + reference-ablation for the main corpus
(`evaluation/semantic-judge/`, `evaluation/judge-ablation-provenance-2026-07-11/`) —
the last are *derived residuals*, not the full benchmark rows.

---

## 1. The shared pipeline chain (feeds every recovery number)

Input corpus → obfuscation → harness → aggregation, in commit order:

| Stage | Commit | What it added |
|---|---|---|
| Corpus download task | `5bc8477` | Gradle `downloadCorpus` (sha1-pinned in `build.gradle`) |
| Obfuscator + ground truth | `bbc3fdd` | tiny-remapper obfuscator + ground truth (dev-only source set) |
| Harness + classloader fix | `88851b7` | benchmark harness + ground-truth-integrity fix (essay l.480) |
| Debug/source leak strip | `2c925ab` | strip debug/source leaks + harden obfuscator (side-channel fix, l.482) |
| Three-slice scoring + leak audit | `a1edf59` | API/package/private slices + `LeakAudit` (l.338, l.406) |
| Two-track realistic vs structure-only | `6a0cbc6` | string-ablation track (l.336, l.448) |
| Preservation control | `bcb1619` | un-obfuscated stratified control (l.334, l.486) |
| Seeded paired sampling | `83e74c4` | bucket-aware seeded stratified sampling, paired (l.340) |
| Sweep driver | `dc8933d` | multi-model round-trip driver (`run-obfuscation-sweep.sh`) |
| Harden before roster sweep | `3a173f9` | temperature=0 primary decision (l.340) |
| Parallelise + aggregator | `f5037e0` | **`aggregate_results.py`** (the aggregator for `tab:sweep`) + jar-track parallelism |
| Per-model context length | `eee709d` | per-model ctx (deepseek 16384 etc.) — enabled the full roster |
| Forced-backend matrix | `3d1ff1a` | owner-vs-graph instrumentation + `analyze_backend_matrix.py`; `ENIGMA_LLM_MAX_PROMPT_CHARS` cap |
| Cache-off + temp0.2 | `ce45a50` | temp-0.2 stability + cache-off runners (l.340) |

**Harness source** (`enigma-llm-plugin/src/evaluation/java/cuchaz/enigma/llm/`):
`TinyRemapperObfuscator`, `ObfuscateCorpusTool`, `Obfuscator`,
`LlmObfuscationBenchmarkHarness`, `LeakAudit`, `DebugStripper`, `StringScrubber`,
`OpaqueNames`, `ObfuscatedSymbol`, `ObfuscationResult`.
**Input corpus** (`enigma-llm-plugin/evaluation/corpus/`): `gson-2.11.0`,
`commons-lang3-3.14.0`, `xz-1.9` (jars git-ignored; coordinates + sha1 pinned in
`build.gradle`; documented in `corpus/README.md`).

---

## 2. Claim-by-claim ledger

*Scope: this ledger covers only the project's OWN empirical numbers. Externally-sourced figures
(`tab:selfhosted_models` model sizes, the RevEng.AI funding figure, CurseForge / Fabric-API download
counts, Hypo's star count) are omitted deliberately — they carry their own `\cite{}` in the essay and
have no internal data pipeline to trace.*

### `tab:sweep` — Exact API recovery, realistic track, AUTO + graph-raised (l.353–358)
commons-lang3 / gson / xz out of 100 API targets:
qwen2.5-coder-14B Q6_K auto 9/4/3, graph 8/9/2 · qwen3-8B auto 8/6/3, graph 9/10/3 ·
qwen3-coder-30B-a3B IQ4_XS auto 12/15/3, graph 12/16/5.
- **Input** corpus jars → obfuscated by harness (`build/llm-evaluation/obfuscated/`, *gitignored*).
- **Generating** `evaluation/run-obfuscation-sweep.sh` → `LlmObfuscationBenchmarkHarness`.
- **Raw output** `build/llm-evaluation/benchmark/<model>[_graph_k1_raised]/{commons-lang3-3.14.0,gson-2.11.0,xz-1.9}-realistic-benchmark.jsonl` — **UNCOMMITTED (gitignored)**, on local disk.
- **Aggregation** `evaluation/aggregate_results.py`.
- **Commits** `f5037e0` (aggregator) + `eee709d` (per-model ctx) + `3d1ff1a` (raised-cap). Essay backfill `724cf24` *(essay repo)*.

### §Current Results narrative (l.366)
realistic > structure-only; xz hardest; 8B ≈/edges 14B; graph ≥ auto (clearest on gson).
Same raw JSONL as `tab:sweep` (realistic + `…-structure-only-benchmark.jsonl`), same aggregator (prints the paired realistic−structure delta). UNCOMMITTED raw. Commit `f5037e0`.

### `tab:semantic` — Exact / adoption / lenient, pooled n=300 (l.379–381)
14B 6.3/29.0/37.0 · 8B 7.3/22.7/37.7 · 30B-MoE 11.0/38.3/50.7.
- **Input residuals** extracted from the graph-raised sweep JSONL by `evaluation/semantic-judge/extract_residuals.py`.
- **Residuals (COMMITTED)** `evaluation/semantic-judge/resid_{14b,8b,30b}.json` (dup in `judge-ablation-provenance-2026-07-11/`).
- **Judge scripts** `judge_run.py` (v1) / `judge_run_v2.py` (v2, maintainer-adoption rubric + `grok-build`). Judges: Grok `grok-build`, GPT `gpt-5.5` high (Codex CLI); tie-break Claude `claude-opus-4-8` high.
- **Verdicts (COMMITTED)** `judged_v2_{grok,codex}_{14b,8b,30b}.json`, tie-breaks `tiebreak_v2_{14b,8b,30b}.json`.
- **Aggregation** `agg_v2.py` → reports `results-v2_2026-07-09.txt` (adoption) + `results_2026-07-08.txt` (lenient), which reproduce the table verbatim.
- **Commits** `81f708c` (v1) + `879fa05` (v2). Exact-match counts come from the *uncommitted* sweep JSONL.

### §Semantic Recovery narrative (l.370, l.389, l.391)
- "Cohen's κ 0.70–0.75": `agg_v2.py` / `results-v2_2026-07-09.txt` (14b 0.70, 8b 0.74, 30b 0.75). Commit `879fa05`.
- "302 accepted / 494 rejected residuals": `build_ablation_full.py`. Commit `fb735f2`.
- "82% accepted vs 24% rejected; per-judge 70–87% vs 18–38%; 33 unparseable of 796": full-pool reference ablation. Scripts `build_ablation_full.py`, `judge_ablated2.py`, `ablfull_combine.py`; report `reference-ablation-fullpool-v2_2026-07-09.txt` (COMMITTED, reproduces 82/24, grok 70/18, codex 84/23, claude 87/38, 33/796); verdicts `ablfull_{codex,grok,claude}.json` (`judge-ablation-provenance-2026-07-11/`). Commits `8c1169c` + `fb735f2`. Essay `c341e3f`, `177cc70` *(essay repo)*.
- "8–15 points higher, 37–51%" (l.389): v1↔v2 delta, `agg_v2.py`.
- Example names (`appendLong`, `getExceptionMessage`, `isActive`↔`isFalse`, `FailableConsumer`→`Consumer`, `CharSetMatcher`→`CharSearcher`): committed `judged_v2_*` / `tiebreak_v2_*`.

### §Current Results — 1.5B smoke baseline (l.395–402)
25 cases, 25 parseable, 4 exact, 9 usable, 0 failed, 4490.2 ms avg latency.
- **Input** hand-written `evaluation/sample-cases.jsonl`.
- **Generating** `evaluation/run-model-benchmark.sh` (task `runEvaluation`), `qwen2.5-coder-1.5b-q4km`.
- **Raw output (COMMITTED)** `evaluation/results/qwen2.5-coder-1.5b-q4km-2026-07-03.jsonl`.
- **Index** `evaluation/model-benchmark-runs.tsv` (row reproduces all six values incl. 4490.2 ms).
- **Commit** `96bb9dd`. Earliest committed raw output; fully traceable.

### §Context Comparison (l.411–415)
- "six-case ASM fixture, owner 4 usable / graph 3": raw **COMMITTED** `evaluation/results/context-backend-comparison-qwen2.5-coder-1.5b-q4km-2026-07-03.jsonl`; indexed in `model-benchmark-runs.tsv`. Commit `96bb9dd`.
- "same 300 API targets scored twice, graph ≥ owner 1–2 pp; auto picks ≥-good backend on ~98%": forced owner-vs-graph matrix. Script `analyze_backend_matrix.py` (+ `run-backend-ablation.sh`, `run-pc-queue.sh`). Raw JSONL **UNCOMMITTED** (`…_owner_k1/`, `…_graph_k1_raised/`, `…_graph_k1_cap8000/`). Commit `3d1ff1a`.

### §Round-Trip micro-claims (l.340)
- "temp 0.2 moved exact API recovery within ~3 targets": `run-temp02-stability.sh`; raw now **PRESERVED** `benchmark-raw-2026-07-11/benchmark/…_q6_k_temp02_run{1,2,3}/`. Report `temp02-jitter_2026-07-08.txt`. Commit `ce45a50`.
- "de-dup disabled recovered ~3 more per 300" — **provenance CONFIRMED (Codex + Gemini, 2026-07-11):**
  script `run-cacheoff.sh` + engine gate `ENIGMA_LLM_DISABLE_SUGGESTION_CACHE`; run dir
  `…_q6_k_graph_k1_raised_cacheoff` (cache-OFF **22/300**, err=0) vs `…_q6_k_graph_k1_raised` (cache-ON
  **19/300**, err=3) → the whole-jar dedup costs exactly 3 exact hits + 3 hard-rejection errors (55/300
  suggestions changed). Report `cacheoff_2026-07-08.txt` **(committed)**; both run dirs now **PRESERVED**
  in `benchmark-raw-2026-07-11/`. Commit `ce45a50`. (Not UNKNOWN — the earlier "run dir not named" was my
  gap, not the data's.)

### `tab:commercial` — local vs commercial exact/usable/latency, n=300 (l.431–438)
local qwen3-14B 7.0/10.0/7s · local 30B 11.0/14.7/18s · claude-sonnet high 48.7/54.7/5s ·
claude-opus-4-8 high 49.0/57.0/8s · gpt-5.5 low 56.0/62.3/7s, high 61.7/71.0/13s,
extra-high (n=90) 66.7/71.1/14s.
- **Driver** commercial models via the OpenAI-compatible **CLI bridge** (`enigma-llm-bridge` / `CliBridgeProvider.java`) shelling to Codex/Claude/Grok CLIs; `ENIGMA_LLM_MODEL=vendor:model:effort`.
- **Raw output UNCOMMITTED** `build/llm-evaluation/commercial/{codex_gpt-5.5_low,_medium,_high,_xhigh, claude_opus_high, claude_sonnet_high2, grok_grok-build}/…-realistic-benchmark.jsonl`.
- **Aggregation** `aggregate_results.py`; latency from per-suggestion `latencyMs`.
- **Commits** `46a5fb5` (latencyMs + bridge) + `0758f63` (route by vendor:model:effort). Essay `c9c8e31` *(essay repo)*.
- l.422 monotone "56/57/62/67 low→xhigh" = `codex_gpt-5.5_{low,medium,high,xhigh}`. sonnet's earlier "7.3%" was a rate-limit artifact, replaced by the clean `claude_sonnet_high2` run.

### §Local vs Commercial — fame gradient (l.446, l.448)
gpt-5.5 high 76/70/47, opus 67/49/34, local 30B 16/12/5 (gson/commons/xz realistic);
structure-only opus gson 67→44, gpt-5.5 gson 66 vs xz 41, niche+strings-removed commercial 22–41 vs local 3.
`aggregate_results.py` per-jar over realistic + structure-only JSONL in the same UNCOMMITTED commercial + local dirs. Commits `46a5fb5`/`0758f63` (commercial), `f5037e0` (aggregator).

### §Local vs Commercial — Hypo collapse (l.450, l.452) and `tab:hypo` (l.461–465)
gpt-5.5 high 23% Hypo vs 76% gson; local 30B 15%; fivefold → ~1.5×. Table:
local 30B 15/26/33 · sonnet high 25/41/52 · opus high 22/40/56 · gpt-5.5 high 23/53/59.
- **Input** Hypo `hypo-model 2.4.1`, obf jar **COMMITTED** `hypo-nearmiss-2026-07-10/harness/hypo-obf-model/hypo-model-2.4.1-obf.jar` + `…-groundtruth.jsonl`.
- **Raw benchmark JSONL (COMMITTED)** `hypo-nearmiss-2026-07-10/hypo-results/**/hypo-model-2.4.1-realistic-benchmark.jsonl`.
- **Residuals (COMMITTED)** `resid_hypo_{local_30b,sonnet_high,claude_opus_high,codex_gpt-5.5_high}.json`.
- **Judge scripts** `judge_run_hypo.py` (lenient) + `judge_run_hypo_strict.py` (adoption); cross-family judges (codex/claude/gemini/grok); verdicts `judged_hypo[_strict]_*_*.json` (COMMITTED).
- **Aggregation** `agg_hypo.py` → `agg_hypo_report.txt` (reproduces exact 15/25/22/23; macro lens 21.8/15.0 exact, 53.4/33.0 majority).
- **Commits** `16dd983` (repro package) + `612d09d` (preserved results). Essay `76251fd` *(essay repo)*. **This is the best-preserved chain in the essay** (raw + residuals + verdicts all committed).

### §Local vs Commercial — latency (l.472) — provenance CONFIRMED (Codex + Gemini, 2026-07-11)
"commercial 7–14 s/suggestion vs local 30B ~18 s"; Table `tab:commercial` latency column.
- **Source = the `latencyMs` field recorded per suggestion in the ACCURACY-run benchmark JSONL** (full
  ~8000-char prompts), added in commit `46a5fb5`, aggregated (median/p90) by `commercial_analyze.py` /
  `aggregate_results.py`. These are the now-preserved commercial + local rows. HANDOFF pins this: "latencyMs
  aus dem Commercial-Lauf ist autoritativ" (median 7.7 s codex-low; the mini-prompt matrix underestimated).
- A **separate dedicated mini-prompt latency study** (`latency_bench.sh`/`_codex2.sh`/`_fable.sh` →
  `latency_bench.tsv`; `local_latency_bench.sh` → `local_latency_bench.tsv`, 9 local models) exists in the
  HANDOFF narrative but its `.tsv` files are **scratchpad-only / not on disk** (a `find` returns nothing) —
  and they do **not** back an essay number (they used 4 short prompts and *underestimated*; the essay uses
  the full-prompt `latencyMs` instead). So this is a non-essay side-artifact, not a provenance gap.
- **Full-prompt vs short-prompt (already handled correctly):** the essay quotes local-30B ~18 s from the
  full-prompt `latencyMs`, consistent with the commercial 7–14 s which are *also* full-prompt — an apples-to-
  apples comparison. The dedicated mini-prompt study's local-30B ~6.4 s was **deliberately kept out** of the
  essay so it doesn't read as a contradiction (HANDOFF l.4761: "Latenz full-prompt-basiert … mein Roster-'MoE
  schlägt' war SHORT-prompt, NICHT reinschreiben als Widerspruch"). So the essay latency is internally
  consistent; the two local-30B numbers are just different prompt-size regimes. No action needed.

### §Interpretation — roster + 7B loop (l.496)
Roster raw JSONL UNCOMMITTED but on disk: `benchmark/{qwen2.5-coder-7b-instruct, …_14b_instruct_q6_k, qwen3-8b, deepseek-coder-v2-lite-instruct_q6_k, qwen3-coder-30b-a3b-instruct_iq4_xs}/`. Aggregator `aggregate_results.py`. Commits `eee709d`+`f5037e0`.
The 7B improvement-loop (DEV=gson+commons, HOLDOUT=xz; graph+conservative+16000-cap winner) is a *separate* study: `evaluation/7b-loop/{run-7b-sweep,analyze_7b_sweep,analyze_ext_paired,analyze_holdout}.{sh,py}`, report `7b-loop/results_2026-07-08.txt` (COMMITTED). Commit `8502160`. Supports l.496 qualitatively; feeds no headline table.

---

## 3. Gaps — status after the 2026-07-11 preserve + Codex/Gemini provenance hunt

Every ESSAY number now has a complete, verified chain. Remaining notes are minor:

1. ~~Headline raw per-target JSONL UNCOMMITTED~~ → **RESOLVED**: preserved `benchmark-raw-2026-07-11/` (`0e95f53`).
2. ~~Dedicated latency TSVs UNKNOWN~~ → **RESOLVED (non-issue)**: the essay latency uses the accuracy-run
   `latencyMs` (preserved), not the scratchpad mini-prompt `latency_bench.tsv` (which underestimated and is
   not on disk). See §Local-vs-Commercial latency. (The local-30B 18 s vs 6.4 s is full-prompt vs short-prompt,
   already deliberately handled in the essay per HANDOFF l.4761 — no action.)
3. ~~De-dup "~3/300" control run dir UNKNOWN~~ → **RESOLVED**: `run-cacheoff.sh` → `…_graph_k1_raised_cacheoff`
   (22/300) vs `…_graph_k1_raised` (19/300), report `cacheoff_2026-07-08.txt`, commit `ce45a50`; dirs preserved.
4. **Obfuscated jars** (`build/llm-evaluation/obfuscated/*`) — gitignored; regenerable from the sha1-pinned
   corpus + harness, so not preserved (the ground-truth JSONL needed to re-score IS preserved). Acceptable.
5. **Essay-side commits** (`724cf24`, `c9c8e31`, `c341e3f`, `177cc70`, `76251fd`) live in the *separate* essay
   repo, cross-referenced via the HANDOFF timeline (not by inspecting that repo's log here). Acceptable.

**Verification (2026-07-11):** all analysis scripts referenced above `py_compile` cleanly (20/20); the four
pure aggregators were run against the real data and reproduce the essay tables exactly — `aggregate_results.py`
(exit 0, per-jar numbers), `analyze_backend_matrix.py` (owner 14 / graph 19 / AUTO 98%), `agg_v2.py`
(14b/8b/30B 37.0/37.7/50.7 % v1), `agg_hypo.py` (gpt-5.5 23 %, opus 22 %). The `judge_run*` scripts call live
LLM CLIs so they are compile-verified only; their verdict JSONs + reports are committed.

---

## 4. Committed-artifact quick index

| Script (committed) | Feeds | Commit |
|---|---|---|
| `evaluation/aggregate_results.py` | `tab:sweep`, fame gradient, `tab:commercial` | `f5037e0` |
| `evaluation/analyze_backend_matrix.py` | §Context Comparison | `3d1ff1a` |
| `evaluation/semantic-judge/{extract_residuals,judge_run,judge_run_v2,tiebreak_v2,agg_v2}.py` | `tab:semantic`, κ | `81f708c`, `879fa05` |
| `evaluation/semantic-judge/{build_ablation_full,judge_ablated2,ablfull_combine}.py` | anchoring 82/24, 302/494, 33/796 | `8c1169c`, `fb735f2` |
| `evaluation/semantic-judge/hypo-nearmiss-2026-07-10/agg_hypo.py` | `tab:hypo`, Hypo macro lens | `16dd983`, `612d09d` |
| `evaluation/7b-loop/analyze_*.py` | l.496 roster support | `8502160` |
| `evaluation/model-benchmark-runs.tsv` + `evaluation/results/*.jsonl` | smoke baseline, 6-case context fixture | `96bb9dd` |

**Reports that reproduce essay numbers verbatim:** `results_2026-07-08.txt`,
`results-v2_2026-07-09.txt`, `reference-ablation-fullpool-v2_2026-07-09.txt`,
`agg_hypo_report.txt`, `7b-loop/results_2026-07-08.txt`.

---

*Generated 2026-07-11. Line numbers track essay commit `da9962e`; update them if the
essay is re-flowed. When a new benchmark row lands (e.g. gemma-4-31b no-think,
coder-next IQ1, Qwen3.6-27B), add its row here with the same five-link chain.*
