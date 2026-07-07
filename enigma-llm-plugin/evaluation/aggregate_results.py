#!/usr/bin/env python3
"""Aggregate the round-trip deobfuscation sweep results (Track d / Phase C).

Reads the per-model benchmark JSONL written by LlmObfuscationBenchmarkHarness
(build/llm-evaluation/benchmark/<model>/<base>-<track>-benchmark.jsonl) and
reports recovery per model x jar x track x slice, WITHOUT ever pooling across
jars or across tracks (the two things the methodology forbids).

Design notes (mirror the methodology in LLM_ENIGMA_STATE.local.md):
  * "correct" = exact OR normalized (case/underscore-insensitive match to any
    acceptable ground-truth name). "usable" additionally counts an alternative
    that matches. Exact and normalized are ALSO reported split out.
  * api is the headline slice; a Wilson 95% CI is printed for it. package is
    secondary, private is diagnostic-only (small n -- never a ranking).
  * The preservation control (rows with preservationControl=true) is scored as
    "kept correctly" = exact (the model re-proposed the name already present);
    a preservation VIOLATION = attempted, no error, suggested != expected.
  * api is additionally split by contextBackend (owner vs graph): AUTO mixes two
    context regimes, so a model must not look better merely for drawing richer
    context on more targets.
  * Realistic - structure-only is a PAIRED delta (same target IDs by seed
    construction): key = (jar, obfOwner, obfName, obfDesc). A paired bootstrap
    95% CI is printed per jar x slice for the correct-rate difference.

Incremental-safe: only reads whatever model subdirs / jar-track files exist, so
it can be run while the sweep is still in progress.

Usage:
  python3 aggregate_results.py [benchmarkDir]
      benchmarkDir defaults to build/llm-evaluation/benchmark (relative to CWD
      or to the repo root guessed from this script's location).
  --json OUT   also write the full aggregate as JSON to OUT.
  --seed N     bootstrap seed (default 1234567, matches the sweep sample seed).
  --resamples N  bootstrap resamples (default 5000).
"""
from __future__ import annotations

import argparse
import glob
import json
import math
import os
import random
import sys
from collections import defaultdict

SLICES = ("api", "package", "private")
TRACKS = ("realistic", "structure-only")


def wilson(k: int, n: int, z: float = 1.96):
    """Wilson score 95% CI for a binomial proportion. Returns (lo, hi, phat)."""
    if n == 0:
        return (0.0, 0.0, 0.0)
    phat = k / n
    denom = 1.0 + z * z / n
    centre = phat + z * z / (2 * n)
    margin = z * math.sqrt(phat * (1 - phat) / n + z * z / (4 * n * n))
    return ((centre - margin) / denom, (centre + margin) / denom, phat)


def pct(x: float) -> str:
    return f"{100.0 * x:5.1f}%"


def target_id(row: dict) -> tuple:
    """Pairing key across tracks/models -- the obfuscated identity is invariant."""
    return (row["jar"], row["obfOwner"], row["obfName"], row.get("obfDesc", ""))


def load_model_rows(model_dir: str) -> list[dict]:
    rows = []
    for path in sorted(glob.glob(os.path.join(model_dir, "*-benchmark.jsonl"))):
        fname = os.path.basename(path)
        # <base>-<track>-benchmark.jsonl ; track is the longest known suffix match
        track = None
        for t in TRACKS:
            if fname.endswith(f"-{t}-benchmark.jsonl"):
                track = t
                break
        if track is None:
            print(f"  ! skip (unrecognized track): {fname}", file=sys.stderr)
            continue
        with open(path, encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError as e:
                    # A benchmark process killed mid-write can leave one truncated JSONL line
                    # (seen in an old restored AUTO dir). Skip it rather than aborting the whole
                    # aggregate -- mirrors the defensive parse in analyze_backend_matrix.py.
                    print(f"  ! skip (malformed JSON, {fname}:{lineno}): {e}", file=sys.stderr)
                    continue
                row["_track"] = track
                rows.append(row)
    return rows


def bucket_of(row: dict) -> str:
    return "preservation" if row.get("preservationControl") else row["slice"]


def summarize(rows: list[dict]) -> dict:
    """counts keyed by (jar, track, bucket) -> stat dict."""
    stats = defaultdict(lambda: {"n": 0, "exact": 0, "norm": 0, "correct": 0,
                                 "usable": 0, "error": 0, "resolved": 0,
                                 "pres_violation": 0})
    for row in rows:
        if not row.get("attempted"):
            continue
        key = (row["jar"], row["_track"], bucket_of(row))
        s = stats[key]
        s["n"] += 1
        if row.get("resolvedInIndex"):
            s["resolved"] += 1
        if row.get("error"):
            s["error"] += 1
        ex, nm = bool(row.get("exact")), bool(row.get("normalized"))
        if ex:
            s["exact"] += 1
        if nm and not ex:
            s["norm"] += 1
        if ex or nm:
            s["correct"] += 1
        if row.get("usable"):
            s["usable"] += 1
        if row.get("preservationControl"):
            # violation = model changed a name that was never obfuscated
            if not row.get("error") and row.get("suggested") and not ex:
                s["pres_violation"] += 1
    return stats


def backend_split(rows: list[dict]) -> dict:
    """api-only recovery split by contextBackend, keyed by (jar, track, backend)."""
    stats = defaultdict(lambda: {"n": 0, "correct": 0})
    for row in rows:
        if not row.get("attempted") or row.get("preservationControl"):
            continue
        if row["slice"] != "api":
            continue
        key = (row["jar"], row["_track"], row.get("contextBackend", "?"))
        s = stats[key]
        s["n"] += 1
        if row.get("exact") or row.get("normalized"):
            s["correct"] += 1
    return stats


def paired_bootstrap(rows: list[dict], slice_name: str, resamples: int, rng: random.Random):
    """Per jar: paired (realistic - structure-only) correct-rate delta + 95% CI.

    Pairs targets present in BOTH tracks by target_id. Returns
    {jar: (delta, lo, hi, n_pairs)}.
    """
    # (jar, target_id) -> {track: correct_bool}
    by_target = defaultdict(dict)
    for row in rows:
        if not row.get("attempted") or row.get("preservationControl"):
            continue
        if row["slice"] != slice_name:
            continue
        correct = 1 if (row.get("exact") or row.get("normalized")) else 0
        by_target[(row["jar"], target_id(row))][row["_track"]] = correct

    per_jar_pairs = defaultdict(list)
    for (jar, _tid), tracks in by_target.items():
        if "realistic" in tracks and "structure-only" in tracks:
            per_jar_pairs[jar].append((tracks["realistic"], tracks["structure-only"]))

    out = {}
    for jar, pairs in per_jar_pairs.items():
        n = len(pairs)
        if n == 0:
            continue
        diffs = [r - s for (r, s) in pairs]
        point = sum(diffs) / n
        boot = []
        for _ in range(resamples):
            acc = 0
            for _ in range(n):
                acc += diffs[rng.randrange(n)]
            boot.append(acc / n)
        boot.sort()
        lo = boot[int(0.025 * resamples)]
        hi = boot[min(int(0.975 * resamples), resamples - 1)]
        out[jar] = (point, lo, hi, n)
    return out


def print_model_report(model: str, rows: list[dict], resamples: int, rng: random.Random):
    print(f"\n{'=' * 78}\nMODEL: {model}   ({len(rows)} attempted-or-structural rows)\n{'=' * 78}")
    stats = summarize(rows)
    jars = sorted({j for (j, _t, _b) in stats})
    for jar in jars:
        print(f"\n  --- {jar} ---")
        for track in TRACKS:
            present = [(b, stats[(jar, track, b)]) for b in list(SLICES) + ["preservation"]
                       if (jar, track, b) in stats]
            if not present:
                continue
            print(f"    [{track}]")
            for bucket, s in present:
                n = s["n"]
                if n == 0:
                    continue
                corr = s["correct"]
                line = (f"      {bucket:12s} n={n:3d}  correct={pct(corr / n)}"
                        f" (exact={s['exact']:3d} norm={s['norm']:3d})"
                        f"  usable={pct(s['usable'] / n)}"
                        f"  err={s['error']:3d}")
                if bucket == "api":
                    lo, hi, _ = wilson(corr, n)
                    line += f"  Wilson95=[{pct(lo)},{pct(hi)}]"
                if bucket == "preservation":
                    line += f"  violations={s['pres_violation']:3d}"
                print(line)

    # api backend split
    bstats = backend_split(rows)
    if bstats:
        print("\n    api by contextBackend (owner vs graph):")
        for (jar, track, backend) in sorted(bstats):
            s = bstats[(jar, track, backend)]
            if s["n"] == 0:
                continue
            lo, hi, _ = wilson(s["correct"], s["n"])
            print(f"      {jar:24s} [{track:14s}] {backend:6s} n={s['n']:3d}"
                  f"  correct={pct(s['correct'] / s['n'])}  Wilson95=[{pct(lo)},{pct(hi)}]")

    # paired realistic - structure-only on api
    boot = paired_bootstrap(rows, "api", resamples, rng)
    if boot:
        print("\n    paired  realistic - structure-only  (api correct-rate, 95% bootstrap):")
        for jar in sorted(boot):
            d, lo, hi, npair = boot[jar]
            print(f"      {jar:24s} delta={pct(d)}  CI=[{pct(lo)},{pct(hi)}]  pairs={npair}")

    return {
        "model": model,
        "buckets": {f"{j}|{t}|{b}": s for (j, t, b), s in stats.items()},
        "api_backend": {f"{j}|{t}|{bk}": s for (j, t, bk), s in bstats.items()},
        "paired_api_realistic_minus_structure": {j: {"delta": d, "lo": lo, "hi": hi, "pairs": n}
                                                  for j, (d, lo, hi, n) in boot.items()},
    }


def find_benchmark_dir(explicit: str | None) -> str:
    if explicit:
        return explicit
    here = os.path.dirname(os.path.abspath(__file__))
    # this script lives in enigma-llm-plugin/evaluation/
    candidate = os.path.normpath(os.path.join(here, "..", "build", "llm-evaluation", "benchmark"))
    if os.path.isdir(candidate):
        return candidate
    return os.path.join(os.getcwd(), "build", "llm-evaluation", "benchmark")


def main():
    ap = argparse.ArgumentParser(description="Aggregate the obfuscation sweep results.")
    ap.add_argument("benchmark_dir", nargs="?", default=None)
    ap.add_argument("--json", dest="json_out", default=None)
    ap.add_argument("--seed", type=int, default=1234567)
    ap.add_argument("--resamples", type=int, default=5000)
    args = ap.parse_args()

    bench_dir = find_benchmark_dir(args.benchmark_dir)
    if not os.path.isdir(bench_dir):
        print(f"benchmark dir not found: {bench_dir}", file=sys.stderr)
        sys.exit(1)

    model_dirs = sorted(d for d in glob.glob(os.path.join(bench_dir, "*")) if os.path.isdir(d))
    if not model_dirs:
        print(f"no per-model subdirs under {bench_dir} yet (sweep may not have written any).",
              file=sys.stderr)
        sys.exit(0)

    print(f"benchmark dir: {bench_dir}")
    print(f"models found : {len(model_dirs)}")
    rng = random.Random(args.seed)
    all_out = []
    for md in model_dirs:
        model = os.path.basename(md)
        rows = load_model_rows(md)
        if not rows:
            print(f"\n(no rows yet for {model})")
            continue
        all_out.append(print_model_report(model, rows, args.resamples, rng))

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(all_out, fh, indent=2)
        print(f"\nwrote aggregate JSON -> {args.json_out}")


if __name__ == "__main__":
    main()
