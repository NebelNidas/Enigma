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
    "You are a reverse engineer / library maintainer deciding whether you would ADOPT the suggested "
    "identifier when renaming this obfuscated member -- would you keep it in production code without "
    "renaming again for clarity, safety, or API hygiene? Judge only from the decompiled behaviour, the "
    "JVM descriptor, the owner simple name, and the kind; ignore which model produced it. The reference "
    "identifier (from an unobfuscated build) is a NEUTRAL equivalence anchor for the intended role/contract "
    "-- not wording to copy or beat.\n"
    "ACCEPT -- you would adopt it: a genuine synonym, correct role name, precise behavioural name, or "
    "justified overload/arity specialisation that preserves the essential contract; drops only cosmetic "
    "detail. A JDK/framework homonym is acceptable ONLY if this symbol really IS that exact standard "
    "concept with the same contract.\n"
    "REJECT -- you would not adopt it: misleading, wrong, or opposite of the behaviour; too generic to "
    "disambiguate among peers (Handler, Processor, Manager, Util, ...) without support; drops an essential "
    "qualifier implied by the code (failable/throwing, lazy, immutable, cached, checked, nullable, "
    "decoder-vs-encoder, ...) that changes the contract; a confusing collision with a well-known "
    "JDK/framework type (Consumer, Function, Map, List, Builder, Stream, Handler, ...) when this is NOT "
    "that type's contract (e.g. Consumer for a failable/throwing functor); or wrong granularity "
    "(interface vs impl, decoder vs stream, factory vs product).\n"
    "UNCERTAIN -- cannot decide adoption from the given context (body too thin or ambiguous).\n"
    'Output STRICT JSON ONLY: [{"id": <int>, "verdict": "ACCEPT|REJECT|UNCERTAIN", "reason": "<=8 words"}].'
)


def call(judge, prompt):
    if judge == "grok":
        cmd = ["grok", "--disable-web-search", "-m", "grok-build", "-p", prompt]
    elif judge == "claude":
        cmd = ["claude", "-p", prompt, "--model", "opus"]
    elif judge == "gemini":
        cmd = ["agy", "--model", "Gemini 3.1 Pro (High)", "-p", prompt]
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
