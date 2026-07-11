# Preserved from ephemeral scratchpad — 2026-07-11

These are COMPLETED experiment outputs that were sitting only in the session scratchpad
(`/tmp/claude-.../scratchpad`), which is volatile. They are PROVENANCE for numbers/claims already
in the essay and are not byte-identically regenerable (cloud judges/models are non-deterministic even
at temperature 0; local re-runs cost hours). The reproducible *scripts* live in `.dk-eval-scripts/`
and this `evaluation/` tree; these are the frozen *outputs*. Provenance verified against
`LLM_ENIGMA_HANDOFF.local.md` (2026-07-11).

## Reference-ablation (anchoring-bias test)
- `ablation_slice_full.json` (796 records: model 8b/14b/30b, obf method, decompiled context).
- `ablfull_{codex,grok,claude}.json` — the 796 residuals RE-JUDGED with the reference name REMOVED
  (code + suggestion only, "is this a plausible name for the behaviour?"). `_verdict` = baseline
  (judge saw the reference), `ablated` = reference-free re-judgment. Backs the essay's anchoring-bias
  finding: accepted residuals stay plausible without the reference (~82%) vs rejected (~24%) →
  acceptances are behaviour-grounded, not reference-anchored (essay commits c341e3f, 177cc70; 3 judge
  families Grok/Codex/Claude). NOTE: the 33/796 grok parse-fails are the origin of the "33 unparseable"
  caveat; a grok-judge rerun to drop it is optional backlog.
- `ablfull_grokfast.json` — **SUPERSEDED**: grok-fast was replaced by grok-build for the final numbers.
  Kept only as a provenance backup; do NOT use for essay numbers.

## Main-benchmark semantic recovery (near-miss residuals + judges)
- `resid_{8b,14b,30b}.json` — non-exact misses that still produced a real suggestion (semantically
  correct but not string-identical), per local Qwen model. From `extract_residuals.py`.
- `judged_{codex,grok}_{8b,14b,30b}.json` — those residuals judged by TWO cross-family judges (Grok +
  GPT/codex; Qwen made the suggestions), Claude tie-breaks disagreements. Back the essay §Semantic
  Recovery table (exact / adoption / lenient columns; e.g. 30B exact 11.0 / adoption 38.3 / lenient
  50.7). v2 semantic judge (grok-build) is FINAL and essay-adopted.
- `judged_hypo_*.json` — semantic-judge verdicts for the Hypo near-miss runs (some also under
  `../semantic-judge/hypo-nearmiss-2026-07-10/`).

Hypo per-model raw runs (haiku high/low, codex gpt-5.5 low, gemma4, coder_next_iq1) were preserved into
`../semantic-judge/hypo-nearmiss-2026-07-10/hypo-results/`. `coder_next_iq1` (qwen3-coder-next @ IQ1_S,
VRAM-overflowed → CPU-offload → collapsed, exact=8) was cut short at n~52 when the accuracy pass was
terminated to free the GPU. A FULL n=100 completion is queued (overnight, after the gemma run) so the
"IQ1 is unsuitable for this task" claim rests on a complete run rather than an aborted one; the full
result will replace the partial. Until then: do NOT use in headline tables.

## 2026-07-11 — Grok rerun of the 33 unparseable verdicts
`ablfull_grok.json` had 33/796 grok verdicts as MISSING (unparseable), conservatively counted as
not-plausible → the essay's "33 unparseable" caveat. Re-ran ONLY those 33 through grok (seeded partial,
`judge_ablated2.py grok`): **21 ACCEPT, 11 REJECT, 1 UNCERTAIN**. Full re-judged verdicts in
`ablfull_grok_rerun.json`. Recomputed separation (Codex + Gemini confirmed): panel majority 82/24 → **84/25%**,
grok-alone 70/18 → **74/19%**, per-judge range 74–87% vs 19–38%. Essay updated (commit 90ceba4); the "33
unparseable" caveat is DROPPED. `ablfull_grok.json` kept as the pre-rerun provenance.
