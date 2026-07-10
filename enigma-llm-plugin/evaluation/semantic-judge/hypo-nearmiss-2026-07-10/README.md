# Hypo near-miss judge package, 2026-07-10

Repro package for the six-model Hypo near-miss aggregation used for the
deferred essay review draft. It preserves the residuals, judge verdicts,
benchmark JSONL inputs, and the exact aggregator that produced
`agg_hypo_report.txt`.

This is separate from the earlier local-model semantic recovery work in the
parent `semantic-judge` directory. The older v2 package scores local Qwen
residuals on the public libraries with a strict maintainer-adoption rubric.
This package scores Hypo residuals for local and commercial models, primarily
to audit exact-match misses on obscure code.

## Contents

- `agg_hypo.py` - final six-model aggregator.
- `agg_hypo_report.txt` / `.json` - generated report from the final run.
- `judge_run_hypo.py` and `judge_run_hypo_strict.py` - judge callers used for
  the lenient and strict rubrics.
- `resid_hypo_*.json` - residual non-exact suggestions, indexed by `id`.
- `judged_hypo*.json` - judge verdicts for lenient and strict passes.
- `hypo-results/**/hypo-model-2.4.1-realistic-benchmark.jsonl` - minimal
  benchmark inputs needed by `agg_hypo.py` for exact counts and denominators.

## Reproduce

Run from this directory:

```bash
python3 -m py_compile agg_hypo.py judge_run_hypo.py judge_run_hypo_strict.py
python3 agg_hypo.py
```

`agg_hypo.py` rewrites `agg_hypo_report.txt` and `agg_hypo_report.json`.

## Final headline

Primary lens: lenient cross-family judge, majority of answered cross-family
judges. Missing or unparseable judge outputs are reported as miss-rate and do
not create accepts.

- Exact only, macro commercial vs local: 21.8% vs 15.0% (delta +6.8pp, 1.45x).
- Exact plus majority near-miss: 53.4% vs 33.0% (delta +20.4pp, 1.62x).
- Permissive sensitivity: 58.2% vs 49.0% (1.19x).
- Strict sensitivity: 49.8% vs 26.0% (1.92x).

Important caveats:

- Gemini and Sonnet only had two cross-family judges available, so their
  majority score requires both judges to accept and is therefore conservative
  relative to the three-judge and four-judge panels.
- Grok-build had many failed or unusable generator calls; its aggregate is
  coverage-limited and should not be read as a clean capability estimate.
- The self-family check was weak and inconsistent: gpt-5.5 self-family judged
  slightly below cross-family mean, while Opus self-family judged above it.
