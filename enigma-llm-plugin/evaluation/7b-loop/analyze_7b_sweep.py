#!/usr/bin/env python3
"""Rank the 7B DEV grid. Reads dev-bench/<cell>/<model>/*-realistic-benchmark.jsonl, computes
api-slice exact/normalized/usable + length/error + attempted, per jar and combined. Primary =
usable; tie-break exact then normalized; guardrail flags (exact materially worse, error rate up).
Usage: analyze_7b_sweep.py <dev-bench-dir> <model>"""
import json, os, sys, glob
from collections import defaultdict

DEVBENCH = sys.argv[1] if len(sys.argv) > 1 else "enigma-llm-plugin/build/llm-evaluation/dev-bench"
MODEL = sys.argv[2] if len(sys.argv) > 2 else "qwen2.5-coder-7b-instruct"
JARS = ["gson-2.11.0", "commons-lang3-3.14.0"]


def load_api(path):
    ex = nm = us = err = att = n = 0
    if not os.path.exists(path):
        return None
    for line in open(path):
        line = line.strip()
        if not line:
            continue
        try:
            d = json.loads(line)
        except Exception:
            continue
        if d.get("slice") != "api":
            continue
        n += 1
        ex += bool(d.get("exact"))
        nm += bool(d.get("normalized"))
        us += bool(d.get("usable"))
        att += bool(d.get("attempted"))
        if d.get("error"):
            err += 1
    return dict(n=n, exact=ex, normalized=nm, usable=us, err=err, att=att)


cells = sorted(d for d in os.listdir(DEVBENCH) if os.path.isdir(os.path.join(DEVBENCH, d))) if os.path.isdir(DEVBENCH) else []
rows = []
for cell in cells:
    per = {}
    ok = True
    for jar in JARS:
        r = load_api(os.path.join(DEVBENCH, cell, MODEL, f"{jar}-realistic-benchmark.jsonl"))
        if r is None:
            ok = False
            break
        per[jar] = r
    if not ok:
        continue
    comb = {k: sum(per[j][k] for j in JARS) for k in ("n", "exact", "normalized", "usable", "err", "att")}
    rows.append((cell, per, comb))

# baseline = auto_conservative (product default) if present
base = next((c for c in rows if c[0] == "auto_conservative"), None)
bexact = base[2]["exact"] if base else None

rows.sort(key=lambda r: (r[2]["usable"], r[2]["exact"], r[2]["normalized"]), reverse=True)
print(f"{'cell':22} {'n':>4} {'usable':>7} {'exact':>6} {'norm':>5} {'err':>4}  | per-jar usable (gson/commons)  guardrail")
for cell, per, comb in rows:
    g = per["gson-2.11.0"]; c = per["commons-lang3-3.14.0"]
    flag = ""
    if bexact is not None and comb["exact"] < bexact - 2:
        flag += " EXACT_DROP"
    pj = f"{g['usable']}/{g['n']} , {c['usable']}/{c['n']}"
    print(f"{cell:22} {comb['n']:>4} {comb['usable']:>7} {comb['exact']:>6} {comb['normalized']:>5} {comb['err']:>4}  | {pj:22}{flag}")
if base:
    print(f"\nbaseline auto_conservative: usable={base[2]['usable']} exact={base[2]['exact']} (n={base[2]['n']})")
