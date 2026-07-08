#!/usr/bin/env python3
"""Combine the three full-pool reference-ablation runs (grok/codex/claude) into one table:
per-judge ACCEPT-plausible rate for main=ACCEPT vs main=REJECT, plus a majority-vote view."""
import json
from collections import Counter

SP = "/tmp/claude-1000/-home-julian-Dev-Env-Digitalisierungskolleg/32e22798-6bde-488a-b047-83d0956e3383/scratchpad"
JUDGES = ["grok", "codex", "claude"]
data = {j: json.load(open(f"{SP}/ablfull_{j}.json")) for j in JUDGES}
# index by (model,owner,obfName,suggested) which is unique per row
key = lambda d: (d["model"], d["owner"], d["obfName"], d["suggested"], d["_verdict"])
rows = {key(d): {"_verdict": d["_verdict"]} for d in data["grok"]}
for j in JUDGES:
    for d in data[j]:
        rows.setdefault(key(d), {"_verdict": d["_verdict"]})[j] = d["ablated"]

print(f"n rows = {len(rows)}")
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
