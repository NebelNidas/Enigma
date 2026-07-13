# Preserved raw benchmark rows (2026-07-11)

Durable snapshot of the **per-target raw benchmark JSONL** that produced the essay's
headline recovery numbers. These files are normally written to
`enigma-llm-evaluation/build/llm-evaluation/**`, which is **git-ignored** and ephemeral;
they are preserved here so the essay figures stay recoverable at the row level, not
only "reproducible in principle" from the committed scripts. See
[`../PROVENANCE.md`](../PROVENANCE.md) for the full claim → data → script → commit chain.

Decision context: Julian, 2026-07-11 — chose *preserve + commit* over "document-as-is",
consistent with the standing reproducibility directive and the earlier precedent
`612d09d` (which preserved the derived judge / Hypo outputs). OSS corpus only
(gson, commons-lang3, xz) → no proprietary leak.

## What each row file is
- `*-realistic-benchmark.jsonl` — scored suggestions on the realistically-obfuscated jar (identifiers scrambled, strings kept).
- `*-structure-only-benchmark.jsonl` — the string-ablation track (strings also removed).
- `*-benchmark.jsonl` (commercial dirs) — the single-track commercial run.
- `*-leaks.jsonl` — the leak-audit record (where a real name leaked via strings/debug/signatures).
- `obfuscated/*-groundtruth.jsonl` — the obf→real name mapping needed to re-score. (The `.jar`s themselves are regenerable from the sha1-pinned corpus + harness and are NOT preserved here.)

Each benchmark row carries the authoritative `exact` / `normalized` / `usable` booleans
(deterministic Java string-match, no LLM judge) plus `contextBackend`, `latencyMs`, and
error/rejection fields. Re-aggregate with `evaluation/aggregate_results.py`.

## Directory → essay claim map
| Dir | Feeds |
|---|---|
| `benchmark/{qwen2.5-coder-14b-instruct_q6_k, qwen3-8b, qwen3-coder-30b-a3b-instruct_iq4_xs}` (AUTO) + `…_graph_k1_raised` | `tab:sweep`, `tab:semantic` (exact counts), §Current Results |
| `benchmark/…_owner_k1`, `…_graph_k1_cap8000` (14b/8b/30b) | §Context Comparison (owner vs graph, ~98% auto-selection) |
| `benchmark/…_temp02_run{1,2,3}` | temp-0.2 stability slice (l.340) |
| `benchmark/…_graph_k1_raised_cacheoff` | de-dup cache-off control (l.340) |
| `benchmark/{qwen2.5-coder-7b-instruct, qwen3-14b, qwen2.5-coder-14b-instruct_q5_k_m, deepseek-coder-v2-lite-instruct_q6_k, qwen3-coder-30b-a3b-instruct_q3_k_m, …-instruct}` | roster / quant-ablation support (l.496); some are pre-migration (see caveat) |
| `benchmark-seq-reference/qwen2.5-coder-14b-instruct_q6_k` | K=1 sequential reference for the parallelism (K=2) validation |
| `commercial/{claude_opus_high, claude_sonnet_high2, codex_gpt-5.5_{low,medium,high,xhigh}, grok_grok-build, grok_build2}` | `tab:commercial`, fame gradient (l.446/448) |
| `commercial/{gemini_pro_famous, gemini_pro_hypo, grok_build_hypo}` | Gemini commercial row + Hypo commercial points (cross-checks §Local-vs-Commercial) |

## Caveats (do not misread the rows)
- **`claude_sonnet_high` is superseded by `claude_sonnet_high2`** — the first run's low rate was a rate-limit artifact; the essay uses the clean `_high2` run. Both are kept for the record.
- Some `benchmark/` dirs without a `_k1`/`_graph`/`_owner` suffix are **pre-migration** runs (per-slot context possibly ≠1, graph possibly silently truncated) → NOT apples-to-apples with the clean `--parallel 1` roster; the essay's clean cross-model comparison uses only the post-migration `_graph_k1_raised` / AUTO dirs. See `LLM_ENIGMA_HANDOFF.local.md` "Prior-Runs caveat".
- **NOT preserved here** (intentionally): the running/broken/contaminated gemma dirs (`gemma-4-31b-it*`, `google_gemma-4-31b-it`), the incomplete `qwen3-coder-next` IQ1 probe, `*_preclean_bak_*` backups, stray pre-per-model-subdir top-level `benchmark/*.jsonl`, and all logs. Preserve gemma-4-31b (no-think) + coder-next IQ1 here once those runs complete and land in the essay.

*Snapshot taken 2026-07-11 from `build/llm-evaluation/`. 393 files, ~18 MB.*
