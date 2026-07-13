#!/usr/bin/env python3
"""Combine the three full-pool reference-ablation runs (grok/codex/claude) into one table:
per-judge ACCEPT-plausible rate for main=ACCEPT vs main=REJECT, plus a majority-vote view."""
import json
import os
from collections import Counter

SP = os.environ.get("JUDGE_DIR", os.path.dirname(os.path.abspath(__file__)))
JUDGES = ["grok", "codex", "claude"]
grok_file = os.environ.get("ABLFULL_GROK_FILE")
if grok_file is None:
    rerun = os.path.join(SP, "ablfull_grok_rerun.json")
    grok_file = rerun if os.path.exists(rerun) else os.path.join(SP, "ablfull_grok.json")
data = {
    "grok": json.load(open(grok_file)),
    "codex": json.load(open(f"{SP}/ablfull_codex.json")),
    "claude": json.load(open(f"{SP}/ablfull_claude.json")),
}
# index by (model,owner,obfName,suggested) which is unique per row
key = lambda d: (d["model"], d["owner"], d["obfName"], d["suggested"], d["_verdict"])
rows = {key(d): {"_verdict": d["_verdict"]} for d in data["grok"]}
for j in JUDGES:
    for d in data[j]:
        rows.setdefault(key(d), {"_verdict": d["_verdict"]})[j] = d["ablated"]

print(f"n rows = {len(rows)}")
print(f"grok source = {os.path.basename(grok_file)}")
print(f"{'judge':8} {'ACCEPT plausible':>22} {'REJECT plausible':>22}")
for j in JUDGES:
    acc = Counter(); rej = Counter()
    for k, r in rows.items():
        v = r.get(j, "MISSING")
        (acc if r["_verdict"] == "ACCEPT" else rej)["plaus" if v == "ACCEPT" else "no"] += 1
    na = acc["plaus"] + acc["no"]; nr = rej["plaus"] + rej["no"]
    print(f"{j:8} {acc['plaus']:>5}/{na:<4} = {100*acc['plaus']/na:>3.0f}%      {rej['plaus']:>5}/{nr:<4} = {100*rej['plaus']/nr:>3.0f}%")

# majority vote across the 3 judges (>=2 say ACCEPT => plausible)
acc = Counter(); rej = Counter()
for k, r in rows.items():
    votes = sum(1 for j in JUDGES if r.get(j) == "ACCEPT")
    plaus = votes >= 2
    (acc if r["_verdict"] == "ACCEPT" else rej)["plaus" if plaus else "no"] += 1
na = acc["plaus"] + acc["no"]; nr = rej["plaus"] + rej["no"]
print(f"\nmajority(>=2/3 ACCEPT): accepted {acc['plaus']}/{na} = {100*acc['plaus']/na:.0f}%   rejected {rej['plaus']}/{nr} = {100*rej['plaus']/nr:.0f}%")
