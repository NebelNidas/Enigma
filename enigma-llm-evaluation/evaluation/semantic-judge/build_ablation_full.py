#!/usr/bin/env python3
"""Full reference-ablation slice: take ALL accepted + ALL rejected residuals from the final
2-judge+tiebreak process across the three models (no sampling), emit WITHOUT the reference name,
to re-judge whether each suggestion is a plausible name for the *behaviour* alone. UNCERTAIN
(main process) items are excluded (they are neither an accept nor a reject)."""
import json
import os
from collections import Counter

SP = os.environ.get("JUDGE_DIR", os.path.dirname(os.path.abspath(__file__)))
MODELS = ["14b", "8b", "30b"]


def final_verdicts(short):
    g = {o["id"]: o["verdict"] for o in json.load(open(f"{SP}/judged_grok_{short}.json"))}
    c = {o["id"]: o["verdict"] for o in json.load(open(f"{SP}/judged_codex_{short}.json"))}
    tb = {int(k): v for k, v in json.load(open(f"{SP}/tiebreak_{short}.json")).items()}
    resid = {i: it for i, it in enumerate(json.load(open(f"{SP}/resid_{short}.json")))}
    out = {}
    for i in set(g) & set(c):
        v = g[i] if g[i] == c[i] else tb.get(i, "UNCERTAIN")
        out[i] = (v, resid[i])
    return out


allslice = []
for short in MODELS:
    for i, (v, it) in final_verdicts(short).items():
        if v not in ("ACCEPT", "REJECT"):
            continue
        allslice.append({"model": short, "kind": it["kind"], "owner": it["owner"],
                         "descriptor": it["descriptor"], "obfName": it["obfName"],
                         "context": it["context"], "suggested": it["suggested"],
                         "alternatives": it["alternatives"], "_ref": it["reference"], "_verdict": v})
for k, it in enumerate(allslice):
    it["id"] = k
json.dump(allslice, open(f"{SP}/ablation_slice_full.json", "w"), indent=1)
c = Counter(it["_verdict"] for it in allslice)
print(f"full slice: {c['ACCEPT']} ACCEPT + {c['REJECT']} REJECT = {len(allslice)} -> ablation_slice_full.json")
