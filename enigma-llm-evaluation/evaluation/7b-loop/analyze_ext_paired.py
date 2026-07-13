#!/usr/bin/env python3
"""Paired A/B analysis of the PROMPT_EXTENSION cells vs the 'none' baseline. Aligns api targets by
(obfOwner,obfName,obfDesc,jar) and counts totals + paired wins/losses on usable and exact."""
import json, os, sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "enigma-llm-evaluation/build/llm-evaluation/dev-ext"
MODEL = "qwen2.5-coder-7b-instruct"
JARS = ["gson-2.11.0", "commons-lang3-3.14.0"]
CELLS = ["none", "grok", "codex"]


def load(cell):
    d = {}
    for jar in JARS:
        p = os.path.join(OUT, cell, MODEL, f"{jar}-realistic-benchmark.jsonl")
        if not os.path.exists(p):
            return None
        for line in open(p):
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except Exception:
                continue
            if r.get("slice") != "api":
                continue
            k = (jar, r.get("obfOwner"), r.get("obfName"), r.get("obfDesc"))
            d[k] = (bool(r.get("usable")), bool(r.get("exact")))
    return d


cells = {c: load(c) for c in CELLS}
base = cells["none"]
if base is None:
    print("baseline 'none' not ready"); sys.exit(0)
print(f"baseline none: n={len(base)} usable={sum(u for u,_ in base.values())} exact={sum(e for _,e in base.values())}")
for c in CELLS[1:]:
    cur = cells[c]
    if cur is None:
        print(f"{c}: not ready"); continue
    common = set(base) & set(cur)
    bu = sum(base[k][0] for k in common); cu = sum(cur[k][0] for k in common)
    be = sum(base[k][1] for k in common); ce = sum(cur[k][1] for k in common)
    win_u = sum(1 for k in common if cur[k][0] and not base[k][0])
    los_u = sum(1 for k in common if base[k][0] and not cur[k][0])
    win_e = sum(1 for k in common if cur[k][1] and not base[k][1])
    los_e = sum(1 for k in common if base[k][1] and not cur[k][1])
    print(f"\n{c} ext (n paired={len(common)}):")
    print(f"  usable: none={bu} -> {c}={cu}  (paired wins={win_u} losses={los_u} net={win_u-los_u})")
    print(f"  exact : none={be} -> {c}={ce}  (paired wins={win_e} losses={los_e} net={win_e-los_e})")
