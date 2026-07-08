# Semantic recovery judge

Blinded, cross-family LLM judge that re-scores the round-trip benchmark's residual
misses (non-exact, non-normalized) to measure *semantic* recovery, i.e. whether a
suggested name captures the same meaning as the original even when it is not a
verbatim match (`getUser` for `fetchUser`). Exact matching alone understates
practical usefulness roughly 4–5×; on 14b/8b/30B-MoE (graph backend, raised cap,
api realistic, n=300) semantic recovery was 37.0 / 37.7 / 50.7 % versus 6.3 / 7.3 /
11.0 % exact (`results_2026-07-08.txt`).

## Protocol (validated with Codex + Grok)

Residual-only; judged **blind** to which model/backend produced the suggestion.
Per item the judge gets: kind, owner simple name, JVM descriptor, obfuscated name,
the **decompiled obfuscated method body** (so the call rests on behaviour, not
string similarity), the suggested name (+ alternatives), and the original framed
neutrally as a "reference identifier from an unobfuscated build" (never "correct
answer"). It returns `ACCEPT` / `REJECT` / `UNCERTAIN`; `UNCERTAIN` counts as not
recovered. Two judges from families other than the Qwen models under test — xAI
Grok and OpenAI GPT (`codex`) — judge independently; Cohen's κ was 0.62–0.75.
Disagreements were adjudicated by a third family (Anthropic Claude); those manual
decisions are recorded verbatim in `tiebreak_{14b,8b,30b}.json` so the aggregate
is reproducible. `semantic-usable = exact + ACCEPT residuals`, with a Wilson 95 %
interval on the residual n. Read this as a *bounded plausibility audit* — a
decompiled body is not full semantics — not ground-truth behavioural equivalence.

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
three runs. All three families agree closely (`reference-ablation-fullpool_2026-07-08.txt`):
**83–85 % of accepted vs 23–38 % of rejected** residuals stay behaviour-plausible
without the reference, so acceptances are behaviour-grounded, not string-anchored.

Exact assistant models (vendor CLIs, 2026-07-08): Grok `grok-composer-2.5-fast`
(xAI), GPT `gpt-5.5` via the Codex CLI (OpenAI), Claude `claude-opus-4-8`
(Anthropic). The earlier 90-item two-judge check is `reference-ablation_2026-07-08.json`.
