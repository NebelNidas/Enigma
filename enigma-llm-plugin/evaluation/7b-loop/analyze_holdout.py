#!/usr/bin/env python3
"""Paired holdout (xz) analysis: none vs codex-ext for the 7B, api slice."""
import json, os, sys
OUT = sys.argv[1] if len(sys.argv) > 1 else "enigma-llm-plugin/build/llm-evaluation/holdout-ext"
MODEL = "qwen2.5-coder-7b-instruct"
JAR = "xz-1.9"


def load(cell):
    p = os.path.join(OUT, cell, MODEL, f"{JAR}-realistic-benchmark.jsonl")
    if not os.path.exists(p):
        return None
    d = {}
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
        d[(r.get("obfOwner"), r.get("obfName"), r.get("obfDesc"))] = (bool(r.get("usable")), bool(r.get("exact")))
    return d


base = load("none"); cur = load("codex")
if base is None or cur is None:
    print("not ready:", "none" if base is None else "", "codex" if cur is None else ""); sys.exit(0)
common = set(base) & set(cur)
bu = sum(base[k][0] for k in common); cu = sum(cur[k][0] for k in common)
be = sum(base[k][1] for k in common); ce = sum(cur[k][1] for k in common)
wu = sum(1 for k in common if cur[k][0] and not base[k][0]); lu = sum(1 for k in common if base[k][0] and not cur[k][0])
we = sum(1 for k in common if cur[k][1] and not base[k][1]); le = sum(1 for k in common if base[k][1] and not cur[k][1])
print(f"xz HOLDOUT (n paired={len(common)}):")
print(f"  usable: none={bu} -> codex-ext={cu}  (wins={wu} losses={lu} net={wu-lu})")
print(f"  exact : none={be} -> codex-ext={ce}  (wins={we} losses={le} net={we-le})")
print("  VERDICT:", "extension generalises (no holdout regression)" if cu >= bu and ce >= be else "REGRESSION on holdout -- do not promote")
