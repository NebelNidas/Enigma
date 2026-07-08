#!/usr/bin/env python3
"""Aggregate the two-judge semantic verdicts into a semantic-recovery metric.

final verdict per residual: agreement(grok,codex) -> that verdict;
disagreement -> Claude tie-break (from tiebreak_<model>.json {id: verdict}); if a
disagreement has no tie-break yet it is written to disagreements_<model>.json for review.

semantic-usable(model) = exact(graph_raised) + ACCEPT residuals.
Rates: (A) semantic-usable / 300 ; also raw exact/300 for comparison.
Reports ACCEPT/REJECT/UNCERTAIN split, Cohen's kappa(grok,codex), disagreement rate,
Wilson 95% CI on the semantic-usable rate.
"""
import glob, json, math, os, sys

SP = os.environ.get("JUDGE_DIR", os.path.dirname(os.path.abspath(__file__)))
BR = os.environ.get("BENCH_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "build", "llm-evaluation", "benchmark"))
MODELS = [("14b q6_k", "14b", "qwen2.5-coder-14b-instruct_q6_k_graph_k1_raised"),
          ("qwen3-8b", "8b", "qwen3-8b_graph_k1_raised"),
          ("30B-MoE iq4_xs", "30b", "qwen3-coder-30b-a3b-instruct_iq4_xs_graph_k1_raised")]


def exact_and_n(passdir):
    n = ex = 0
    for f in glob.glob(os.path.join(BR, passdir, "*-realistic-benchmark.jsonl")):
        for line in open(f):
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            if r.get("slice") != "api" or r.get("preservationControl"):
                continue
            n += 1
            if r.get("exact") or r.get("normalized"):
                ex += 1
    return ex, n


def wilson(k, n, z=1.96):
    if n == 0:
        return (0, 0)
    p = k / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return ((c - h) / d * 100, (c + h) / d * 100)


def kappa(pairs):
    cats = ["ACCEPT", "REJECT", "UNCERTAIN"]
    n = len(pairs)
    if n == 0:
        return float("nan")
    po = sum(1 for a, b in pairs if a == b) / n
    pe = 0
    for c in cats:
        pa = sum(1 for a, b in pairs if a == c) / n
        pb = sum(1 for a, b in pairs if b == c) / n
        pe += pa * pb
    return (po - pe) / (1 - pe) if pe != 1 else float("nan")


need_tiebreak = False
print(f"{'model':<16}{'exact':>7}{'ACCEPT':>8}{'REJECT':>8}{'UNCRT':>7}{'sem-use':>9}{'exact%':>8}{'sem%':>7}{'Wilson95':>16}{'kappa':>7}{'disag':>7}")
print("-" * 108)
for name, short, passdir in MODELS:
    grokf = f"{SP}/judged_grok_{short}.json"
    codexf = f"{SP}/judged_codex_{short}.json"
    if not (os.path.isfile(grokf) and os.path.isfile(codexf)):
        print(f"{name:<16} (waiting for judge output)")
        continue
    grok = {o["id"]: o["verdict"] for o in json.load(open(grokf))}
    codex = {o["id"]: o["verdict"] for o in json.load(open(codexf))}
    resid = {}
    for i, it in enumerate(json.load(open(f"{SP}/resid_{short}.json"))):
        it["id"] = i
        resid[i] = it
    tb = json.load(open(f"{SP}/tiebreak_{short}.json")) if os.path.isfile(f"{SP}/tiebreak_{short}.json") else {}
    tb = {int(k): v for k, v in tb.items()}
    ids = sorted(set(grok) & set(codex))
    pairs = [(grok[i], codex[i]) for i in ids if grok[i] in ("ACCEPT", "REJECT", "UNCERTAIN") and codex[i] in ("ACCEPT", "REJECT", "UNCERTAIN")]
    disagreements = []
    final = {}
    for i in ids:
        g, c = grok[i], codex[i]
        if g == c:
            final[i] = g
        elif i in tb:
            final[i] = tb[i]
        else:
            disagreements.append(i)
    if disagreements:
        need_tiebreak = True
        out = []
        for i in disagreements:
            it = resid.get(i, {})
            out.append({"id": i, "kind": it.get("kind"), "owner": it.get("owner"), "obfName": it.get("obfName"),
                        "descriptor": it.get("descriptor"), "reference": it.get("reference"),
                        "suggested": it.get("suggested"), "alternatives": it.get("alternatives"),
                        "context": it.get("context"), "grok": grok[i], "codex": codex[i]})
        json.dump(out, open(f"{SP}/disagreements_{short}.json", "w"), indent=1)
    ex, n = exact_and_n(passdir)
    acc = sum(1 for v in final.values() if v == "ACCEPT")
    rej = sum(1 for v in final.values() if v == "REJECT")
    unc = sum(1 for v in final.values() if v == "UNCERTAIN")
    semuse = ex + acc
    lo, hi = wilson(semuse, n)
    k = kappa(pairs)
    dis = len(disagreements) + sum(1 for i in ids if grok[i] != codex[i] and i in tb)
    disrate = 100.0 * sum(1 for i in ids if grok[i] != codex[i]) / len(ids) if ids else 0
    tbmark = f" (+{len(disagreements)} TB-needed)" if disagreements else ""
    print(f"{name:<16}{ex:>7}{acc:>8}{rej:>8}{unc:>7}{semuse:>9}{100*ex/n:>7.1f}%{100*semuse/n:>6.1f}%{f'[{lo:.1f},{hi:.1f}]':>16}{k:>7.2f}{disrate:>6.0f}%{tbmark}")

if need_tiebreak:
    print("\n>> disagreements written to disagreements_<model>.json — Claude to adjudicate -> tiebreak_<model>.json {id: verdict}")
