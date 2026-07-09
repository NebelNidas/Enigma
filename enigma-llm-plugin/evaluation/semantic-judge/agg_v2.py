#!/usr/bin/env python3
"""Aggregate the v2 (maintainer-adoption) judge verdicts and compare to v1 (behaviour-plausibility).
semantic-usable = exact + ACCEPT residuals; final verdict = grok if grok==codex else claude tie-break."""
import glob, json, math, os

SP = "/tmp/claude-1000/-home-julian-Dev-Env-Digitalisierungskolleg/32e22798-6bde-488a-b047-83d0956e3383/scratchpad"
BR = "/home/julian/Dev-Env/Digitalisierungskolleg/fabric-enigma/enigma-llm-plugin/build/llm-evaluation/benchmark"
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
    p = k / n; d = 1 + z * z / n; c = p + z * z / (2 * n)
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return ((c - h) / d * 100, (c + h) / d * 100)


def final_verdicts(short, ver):
    """ver='v2' or 'v1'. returns dict id->verdict using agreement + tie-break."""
    if ver == "v2":
        g = {o["id"]: o["verdict"] for o in json.load(open(f"{SP}/judged_v2_grok_{short}.json"))}
        c = {o["id"]: o["verdict"] for o in json.load(open(f"{SP}/judged_v2_codex_{short}.json"))}
        tbf = f"{SP}/tiebreak_v2_{short}.json"
    else:
        g = {o["id"]: o["verdict"] for o in json.load(open(f"{SP}/judged_grok_{short}.json"))}
        c = {o["id"]: o["verdict"] for o in json.load(open(f"{SP}/judged_codex_{short}.json"))}
        tbf = f"{SP}/tiebreak_{short}.json"
    tb = {int(k): v for k, v in json.load(open(tbf)).items()} if os.path.isfile(tbf) else {}
    out, pend = {}, 0
    for i in set(g) & set(c):
        if g[i] == c[i]:
            out[i] = g[i]
        elif i in tb:
            out[i] = tb[i]
        else:
            pend += 1
    return out, g, c, pend


print(f"{'model':<16}{'ver':>4}{'exact':>7}{'ACC':>6}{'REJ':>6}{'UNC':>6}{'sem-use':>9}{'sem%':>7}{'Wilson95':>15}{'pend':>6}")
print("-" * 92)
for name, short, passdir in MODELS:
    ex, n = exact_and_n(passdir)
    for ver in ("v1", "v2"):
        try:
            fin, g, c, pend = final_verdicts(short, ver)
        except FileNotFoundError:
            print(f"{name:<16}{ver:>4}  (missing files)"); continue
        acc = sum(1 for v in fin.values() if v == "ACCEPT")
        rej = sum(1 for v in fin.values() if v == "REJECT")
        unc = sum(1 for v in fin.values() if v == "UNCERTAIN")
        semuse = ex + acc
        lo, hi = wilson(semuse, n)
        print(f"{name:<16}{ver:>4}{ex:>7}{acc:>6}{rej:>6}{unc:>6}{semuse:>9}{100*semuse/n:>6.1f}%  [{lo:4.1f},{hi:4.1f}]{pend:>6}")
    print()
