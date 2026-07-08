#!/usr/bin/env python3
"""Reference-ablated robustness slice: sample items the full 2-judge+tiebreak
process ACCEPTED, and emit them WITHOUT the reference name, to re-judge whether the
suggestion is a plausible name for the *behaviour* alone. A high plausible-rate
means acceptance was behaviour-grounded, not anchored on the reference string.
Also samples some REJECTED items as a contrast.
"""
import json, os, random, sys

SP = os.environ.get("JUDGE_DIR", os.path.dirname(os.path.abspath(__file__)))
random.seed(424242)
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


accepted, rejected = [], []
for short in MODELS:
    fv = final_verdicts(short)
    for i, (v, it) in fv.items():
        rec = {"model": short, "id": i, "kind": it["kind"], "owner": it["owner"],
               "descriptor": it["descriptor"], "obfName": it["obfName"],
               "context": it["context"], "suggested": it["suggested"],
               "alternatives": it["alternatives"], "_ref": it["reference"], "_verdict": v}
        if v == "ACCEPT":
            accepted.append(rec)
        elif v == "REJECT":
            rejected.append(rec)

random.shuffle(accepted)
random.shuffle(rejected)
slice_acc = accepted[:60]
slice_rej = rejected[:30]
allslice = slice_acc + slice_rej
random.shuffle(allslice)
for k, it in enumerate(allslice):
    it["id"] = k
json.dump(allslice, open(f"{SP}/ablation_slice.json", "w"), indent=1)
print(f"accepted total={len(accepted)} rejected total={len(rejected)}")
print(f"slice: {len(slice_acc)} ACCEPT + {len(slice_rej)} REJECT = {len(allslice)} -> ablation_slice.json")
