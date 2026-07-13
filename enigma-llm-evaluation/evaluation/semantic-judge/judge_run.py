#!/usr/bin/env python3
"""Run a blinded semantic judge over residual items via an external LLM CLI.

Usage: judge_run.py <items.json> <judge: grok|codex> <out.json> [max_items]
Batches ~11 shuffled items/call, parses a strict JSON array verdict, retries once.
"""
import json, os, random, subprocess, sys, re

ITEMS = sys.argv[1]
JUDGE = sys.argv[2]
OUT = sys.argv[3]
MAXN = int(sys.argv[4]) if len(sys.argv) > 4 else 10**9
BATCH = 11
random.seed(20260708)

RUBRIC = (
    "You are an expert Java developer judging DEOBFUSCATION name suggestions. Each item has: kind "
    "(CLASS/FIELD/METHOD), the declaring class simple name, the JVM descriptor, the obfuscated identifier, "
    "decompiled obfuscated code CONTEXT (real behaviour, names still obfuscated), a SUGGESTED name from a "
    "tool, optional ALTERNATIVES, and a REFERENCE identifier taken from an unobfuscated build of the same "
    "code. Decide whether the suggested name (or one of its alternatives) is an acceptable SEMANTIC recovery: "
    "a competent maintainer would find it captures the same role/meaning as the reference, even if worded "
    "differently (getUser vs fetchUser = ACCEPT; getUser vs deleteUser = REJECT).\n"
    "Rules: judge by what the CODE does and the role, NOT by string similarity to the reference (a name can "
    "be lexically close but wrong, e.g. append vs appendLongWithFlag, or different but right, e.g. size vs "
    "length). The reference is ONE acceptable name, not the only one; do not require exact wording.\n"
    "Verdicts: ACCEPT = semantically equivalent / practically interchangeable. REJECT = materially "
    "different, misleading, opposite, too broad/narrow, or unrelated. UNCERTAIN = context insufficient to "
    "decide.\n"
    "Output STRICT JSON ONLY: a JSON array, one object per item, "
    '[{"id": <int>, "verdict": "ACCEPT|REJECT|UNCERTAIN", "reason": "<=8 words"}]. No text outside the array.'
)


def call(judge, prompt):
    if judge == "grok":
        cmd = ["grok", "--disable-web-search", "-p", prompt]
    else:
        cmd = ["codex", "exec", "--skip-git-repo-check", prompt]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    return p.stdout


def parse_array(text):
    # grab the last balanced [...] JSON array in the output
    starts = [m.start() for m in re.finditer(r"\[", text)]
    for s in starts:
        depth = 0
        for e in range(s, len(text)):
            if text[e] == "[":
                depth += 1
            elif text[e] == "]":
                depth -= 1
                if depth == 0:
                    try:
                        arr = json.loads(text[s:e + 1])
                        if isinstance(arr, list) and arr and isinstance(arr[0], dict) and "verdict" in arr[0]:
                            return arr
                    except Exception:
                        break
    return None


items = json.load(open(ITEMS))[:MAXN]
for i, it in enumerate(items):
    it["id"] = i
order = list(range(len(items)))
random.shuffle(order)

verdicts = {}
batches = [order[i:i + BATCH] for i in range(0, len(order), BATCH)]
for bi, batch in enumerate(batches):
    payload = []
    for idx in batch:
        it = items[idx]
        payload.append({
            "id": it["id"], "kind": it["kind"], "class": it["owner"],
            "descriptor": it["descriptor"], "obfuscated": it["obfName"],
            "context": it["context"], "suggested": it["suggested"],
            "alternatives": it["alternatives"], "reference": it["reference"],
        })
    prompt = RUBRIC + "\n\nItems:\n" + json.dumps(payload, ensure_ascii=False)
    arr = None
    for attempt in range(2):
        try:
            arr = parse_array(call(JUDGE, prompt))
        except Exception as e:
            arr = None
        if arr:
            break
    if not arr:
        sys.stderr.write(f"batch {bi}: no parse\n")
        continue
    for o in arr:
        try:
            verdicts[int(o["id"])] = {"verdict": o["verdict"], "reason": o.get("reason", "")}
        except Exception:
            pass
    print(f"  batch {bi+1}/{len(batches)}: {len(arr)} verdicts (total {len(verdicts)}/{len(items)})", flush=True)

out = []
for it in items:
    v = verdicts.get(it["id"], {"verdict": "MISSING", "reason": ""})
    out.append({**{k: it[k] for k in ("id", "jar", "kind", "owner", "obfName", "reference", "suggested")},
                "verdict": v["verdict"], "reason": v["reason"]})
json.dump(out, open(OUT, "w"), indent=1)
from collections import Counter
c = Counter(o["verdict"] for o in out)
print(f"{JUDGE} {os.path.basename(ITEMS)}: {dict(c)}  n={len(out)}")
