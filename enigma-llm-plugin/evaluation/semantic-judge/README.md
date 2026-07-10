# Semantic recovery judge

Blinded, cross-family LLM judge that re-scores the round-trip benchmark's residual
misses (non-exact, non-normalized) to measure *semantic* recovery, i.e. whether a
suggested name captures the same meaning as the original even when it is not a
verbatim match (`getUser` for `fetchUser`). Exact matching alone understates
practical usefulness several-fold. Under the **primary v2 rubric** (maintainer
adoption; grok-build flagship judge) on 14b/8b/30B-MoE (graph backend, raised cap,
api realistic, n=300) semantic recovery is 29.0 / 22.7 / 38.3 % versus 6.3 / 7.3 /
11.0 % exact (`results-v2_2026-07-09.txt`). An earlier, more lenient **v1 rubric**
(behavioural plausibility; grok-composer-2.5-fast) gave 37.0 / 37.7 / 50.7 %
(`results_2026-07-08.txt`) and is kept as a sensitivity bound, not the headline.

## Protocol (validated with Codex + Grok)

Residual-only; judged **blind** to which model/backend produced the suggestion.
Per item the judge gets: kind, owner simple name, JVM descriptor, obfuscated name,
the **decompiled obfuscated method body** (so the call rests on behaviour, not
string similarity), the suggested name (+ alternatives), and the original framed
neutrally as a "reference identifier from an unobfuscated build" (never "correct
answer"). It returns `ACCEPT` / `REJECT` / `UNCERTAIN`; `UNCERTAIN` counts as not
recovered. Two judges from families other than the Qwen models under test — xAI
Grok and OpenAI GPT (`codex`) — judge independently; Cohen's κ was 0.70–0.75 for
the v2 panel (0.62–0.75 for v1). Disagreements were adjudicated by a third family
(Anthropic Claude); those manual decisions are recorded verbatim in
`tiebreak_v2_{14b,8b,30b}.json` (v2) / `tiebreak_{14b,8b,30b}.json` (v1) so the
aggregate is reproducible. `semantic-usable = exact + ACCEPT residuals`, with a
Wilson 95 % interval on the residual n. Read this as a *bounded plausibility audit*
— a decompiled body is not full semantics — not ground-truth behavioural equivalence.

## Rubric versions: v1 (lenient, sensitivity) and v2 (strict, primary)

The v1→v2 change is a **joint protocol change** and should not be decomposed into
its two parts:

- **Rubric.** v1 asked "is this a behaviourally *plausible* name?"; v2 asks "would
  a maintainer actually *adopt* this name?" — v2 rejects well-known-type collisions
  (e.g. `FailableConsumer`→`Consumer`), dropped load-bearing qualifiers, and wrong
  granularity. That collision case is exactly what motivated the stricter rubric.
- **Grok judge.** v1 used `grok-composer-2.5-fast` (the "Schnell" tier); v2 uses
  `grok-build` (the "Grok 4.3" flagship), which is also stricter on the *same*
  residuals (130 vs 146 shared ACCEPTs). GPT stayed `gpt-5.5` (high effort) and the
  Claude tie-break stayed `claude-opus-4-8` (high); only Grok was re-run.

Exact-match counts are deterministic and unchanged, so the whole v1→v2 delta is a
reclassification of residual non-exact suggestions. The v2 pipeline mirrors v1 with
`_v2`-suffixed scripts and data: `judge_run_v2.py` (adds the maintainer-adoption
rubric and `-m grok-build`), `tiebreak_v2.py`, `agg_v2.py` (prints v1 and v2 side by
side; reads `judged_v2_{grok,codex}_*.json` + `tiebreak_v2_*.json`). Regenerate the
headline table with `python3 agg_v2.py`.

## Pipeline

1. **Decompile** the obfuscated jars once with Vineflower into `decomp_<lib>/`
   (place these next to the scripts, or point `JUDGE_DIR` at them):
   ```
   java -jar vineflower.jar build/llm-evaluation/obfuscated/<lib>-obf.jar decomp_<lib>/
   ```
2. **Extract residuals** for one model's graph-raised pass dir:
   ```
   python3 extract_residuals.py <benchmark-dir> <model>_graph_k1_raised resid_<m>.json
   ```
3. **Judge** with each judge CLI (batched ~11/call, JSON in/out):
   ```
   python3 judge_run.py resid_<m>.json grok  judged_grok_<m>.json
   python3 judge_run.py resid_<m>.json codex judged_codex_<m>.json
   ```
4. **Aggregate**: agreements become the verdict; disagreements are written to
   `disagreements_<m>.json` for adjudication into `tiebreak_<m>.json`; re-run to
   get the semantic-usable rate, ACCEPT/REJECT/UNCERTAIN split, κ and Wilson CI:
   ```
   BENCH_DIR=<benchmark-dir> python3 agg.py
   ```

`JUDGE_DIR` (default: this directory) is where the `resid_*`, `judged_*`,
`tiebreak_*` and `decomp_*` files live; `BENCH_DIR` (default:
`../../build/llm-evaluation/benchmark`) is the harness output root.

## Reference-ablation robustness (anti-anchoring)

To rule out the judges anchoring on the reference string, every accepted and
rejected residual is re-judged with the reference **withheld** (code + suggestion
only), asking merely whether the name is plausible *for the observed behaviour*.
`build_ablation_full.py` emits all accepted (302) + rejected (494) residuals across
the three models; `judge_ablated2.py <grok|codex|claude> <out> <slice>` re-judges
them (resumable via an `<out>.partial.json`); `ablfull_combine.py` combines the
three runs. Under the **v2 grok-build panel** (`reference-ablation-fullpool-v2_2026-07-09.txt`)
the majority still rate **82 % of accepted vs 24 % of rejected** residuals plausible
without the reference (per judge 70–87 % vs 18–38 %; 33/796 grok parse-fails counted
conservatively as not plausible), so acceptances are behaviour-grounded, not
string-anchored. The v1/grok-fast run is preserved as
`reference-ablation-fullpool_2026-07-08.txt` (majority 86 % vs 32 %).

Exact assistant models (vendor CLIs): **v2 (primary)** — Grok `grok-build` ("Grok 4.3"
flagship), GPT `gpt-5.5` at high reasoning effort via the Codex CLI, Claude
`claude-opus-4-8` at high effort. **v1 (lenient)** used Grok `grok-composer-2.5-fast`.
The earlier 90-item two-judge check is `reference-ablation_2026-07-08.json`.

## Hypo near-miss package

`hypo-nearmiss-2026-07-10/` preserves the later Hypo-only near-miss audit for
the essay review draft. It includes the six-model residuals, lenient and strict
judge verdicts, minimal benchmark JSONL inputs, and `agg_hypo.py`; run
`python3 agg_hypo.py` in that directory to regenerate the majority/permissive/
strict aggregation and self-preference sanity check.
