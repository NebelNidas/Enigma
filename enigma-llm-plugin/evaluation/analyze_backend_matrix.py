#!/usr/bin/env python3
"""Analyze the forced-backend matrix produced by run-backend-ablation.sh.

Answers two questions on identical, paired targets:

  Q1 (AUTO routing optimality): join the owner-best pass (owner_k1) with the graph-best pass
     (graph_k1_raised) per target. For each target compare owner vs graph recovery and check whether
     the empirically-better backend matches `autoBackend` (what AUTO would have chosen). Reports AUTO
     regret (targets where the non-chosen backend strictly wins), AUTO vs oracle (best-of-both), and
     the always-owner / always-graph baselines.

  Q2 (client-cap truncation): with --parallel 1 every request gets the full n_ctx, so there is no
     server overflow to decompose; compare graph_k1_raised (cap 16000, untruncated) vs graph_k1_cap8000
     to isolate the client-side MAX_PROMPT_CHARS truncation cost. (The old graph_k2_cap8000 slot-pressure
     pass was void — client concurrency K does not change per-request context — and was removed.)

Usage:  python3 analyze_backend_matrix.py <benchmark-dir> <sanitized-model-prefix> [--track realistic]
Only the api slice is used for the headline (drop --slice to include all).
"""
import argparse
import glob
import json
import os
import sys
from collections import defaultdict


def track_of(path):
    name = os.path.basename(path)
    if "-structure-only-benchmark.jsonl" in name:
        return "structure-only"
    if "-realistic-benchmark.jsonl" in name:
        return "realistic"
    return "?"


def load_pass(bench_dir, prefix, label, track, slice_name):
    """Return {target_key: row} for one pass dir, filtered to track + slice (+ non-preservation)."""
    d = os.path.join(bench_dir, f"{prefix}_{label}")
    rows = {}
    if not os.path.isdir(d):
        return None
    for f in glob.glob(os.path.join(d, "*-benchmark.jsonl")):
        if track and track_of(f) != track:
            continue
        for line in open(f):
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except Exception:
                continue
            if slice_name and r.get("slice") != slice_name:
                continue
            if r.get("preservationControl"):
                continue
            key = (track_of(f), r["jar"], r["kind"], r["obfOwner"], r["obfName"], r["obfDesc"])
            rows[key] = r
    return rows


def rate(n, d):
    return (100.0 * n / d) if d else float("nan")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bench_dir")
    ap.add_argument("prefix")
    ap.add_argument("--track", default="realistic")
    ap.add_argument("--slice", dest="slice_name", default="api")
    args = ap.parse_args()

    passes = {}
    for label in ("owner_k1", "graph_k1_raised", "graph_k1_cap8000"):
        passes[label] = load_pass(args.bench_dir, args.prefix, label, args.track, args.slice_name)

    print(f"# backend matrix — model={args.prefix} track={args.track} slice={args.slice_name}\n")

    # Per-pass headline: exact rate, ctx-error rate, client-truncation rate, prompt-char stats.
    print(f"{'pass':<18} {'n':>4} {'exact':>12} {'ctx_err':>8} {'client_trunc':>13} {'promptChars p50/p90/max':>26}")
    for label, rows in passes.items():
        if not rows:
            print(f"{label:<18} {'--- missing ---':>4}")
            continue
        n = len(rows)
        ex = sum(1 for r in rows.values() if r.get("exact"))
        cerr = sum(1 for r in rows.values() if (r.get("error") or "") and "ontext" in (r.get("error") or ""))
        trunc = sum(1 for r in rows.values() if r.get("promptTruncated"))
        chars = sorted(r.get("promptChars", 0) for r in rows.values())
        p50 = chars[len(chars) // 2] if chars else 0
        p90 = chars[int(len(chars) * 0.9)] if chars else 0
        mx = chars[-1] if chars else 0
        print(f"{label:<18} {n:>4} {ex:>4} {rate(ex,n):>5.1f}% {cerr:>8} {trunc:>6} {rate(trunc,n):>5.1f}% {p50:>8}/{p90}/{mx}")

    owner = passes.get("owner_k1")
    graph_best = passes.get("graph_k1_raised")

    # ---- Q1: AUTO routing optimality (owner-best vs graph-best, per target) ----
    if owner and graph_best:
        keys = set(owner) & set(graph_best)
        auto_owner = auto_graph = 0
        auto_correct = auto_regret = 0
        auto_recovered = oracle_recovered = always_owner = always_graph = 0
        graph_overflow = 0  # graph prompt too big for the ctx -> HTTP 400, NOT a backend-quality signal
        regret_detail = defaultdict(int)  # (auto_pick, winner)
        for k in keys:
            # EXCLUDE targets whose graph prompt overflowed the server context (Codex+Grok): those measure
            # transport/context failure, not graph quality, and would bias graph down on exactly the most-
            # connected symbols. Report them as a separate bucket, not as graph=fail in the verdict.
            if "ontext" in (graph_best[k].get("error") or ""):
                graph_overflow += 1
                continue
            o = bool(owner[k].get("exact"))
            g = bool(graph_best[k].get("exact"))
            auto = owner[k].get("autoBackend") or graph_best[k].get("autoBackend")
            always_owner += o
            always_graph += g
            oracle_recovered += (o or g)
            picked = o if auto == "owner" else g
            auto_recovered += picked
            if auto == "owner":
                auto_owner += 1
            else:
                auto_graph += 1
            # did AUTO pick the (weakly) better backend?
            if (auto == "owner" and o >= g) or (auto == "graph" and g >= o):
                auto_correct += 1
            if (auto == "owner" and g and not o) or (auto == "graph" and o and not g):
                auto_regret += 1
                winner = "graph" if auto == "owner" else "owner"
                regret_detail[(auto, winner)] += 1
        n = len(keys) - graph_overflow  # verdict base excludes graph-overflow targets
        print("\n## Q1 — AUTO routing optimality (paired owner-best vs graph-best)")
        print(f"paired targets: {len(keys)}   graph-ctx-overflow EXCLUDED: {graph_overflow}   verdict base n={n}")
        print(f"(AUTO would route: owner={auto_owner}, graph={auto_graph})")
        print(f"AUTO picked the >= better backend on {auto_correct}/{n} ({rate(auto_correct,n):.1f}%)")
        print(f"AUTO REGRET (non-chosen backend strictly wins): {auto_regret}/{n} ({rate(auto_regret,n):.1f}%)")
        for (auto, winner), c in sorted(regret_detail.items()):
            print(f"    AUTO->{auto} but {winner} recovered it: {c}")
        print(f"recovery — AUTO routing: {auto_recovered} ({rate(auto_recovered,n):.1f}%)   "
              f"oracle best-of-both: {oracle_recovered} ({rate(oracle_recovered,n):.1f}%)   "
              f"gap AUTO->oracle: {oracle_recovered-auto_recovered} ({rate(oracle_recovered-auto_recovered,n):.1f}pp)")
        print(f"baselines — always-owner: {always_owner} ({rate(always_owner,n):.1f}%)   "
              f"always-graph: {always_graph} ({rate(always_graph,n):.1f}%)")

    # ---- Q2: graph client-cap truncation (raised=untruncated vs cap8000) ----
    # CORRECTED 3-pass design (2026-07-07): models load with --parallel 1 => each request gets the FULL
    # n_ctx, so there is NO server-side overflow to decompose (the old graph_k2_cap8000 "slot pressure"
    # pass was void — client K does not shrink per-slot). The only remaining truncation is the client-side
    # MAX_PROMPT_CHARS cap, isolated by raised(cap 16000, untruncated) - cap8000.
    def ex_rate(label):
        r = passes.get(label)
        if not r:
            return None, 0
        n = len(r)
        return sum(1 for x in r.values() if x.get("exact")), n

    gr_raised, n_r = ex_rate("graph_k1_raised")
    gr_k1cap, n_1 = ex_rate("graph_k1_cap8000")
    if None not in (gr_raised, gr_k1cap) and n_r:
        print("\n## Q2 — graph client-cap truncation (exact recovery, api; --parallel 1 => no server overflow)")
        print(f"graph cap-raised 16000 (untruncated): {gr_raised}/{n_r} ({rate(gr_raised,n_r):.1f}%)")
        print(f"graph cap-8000         (client cap):  {gr_k1cap}/{n_1} ({rate(gr_k1cap,n_1):.1f}%)")
        print(f"  client-cap truncation cost (raised - cap8000): {gr_raised-gr_k1cap:+d} targets ({rate(gr_raised-gr_k1cap,n_r):+.1f}pp)")
        if owner:
            ow = sum(1 for r in owner.values() if r.get("exact"))
            print(f"  intrinsic backend gap (owner - graph raised): {ow-gr_raised:+d} ({rate(ow,len(owner))-rate(gr_raised,n_r):+.1f}pp) "
                  f"[both untruncated; residual = backend quality + AUTO-forced-difficulty]")


if __name__ == "__main__":
    sys.exit(main())
