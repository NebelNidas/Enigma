#!/usr/bin/env python3
"""Reference-ABLATED judge: given only the code behaviour + suggested name (NO
reference), decide whether the name is a plausible/accurate description of what
the member does. Used to test whether the main judge's ACCEPTs were behaviour-
grounded rather than anchored on the reference string."""
import json, os, random, re, subprocess, sys

SP = os.environ.get("JUDGE_DIR", os.path.dirname(os.path.abspath(__file__)))
IN = f"{SP}/ablation_slice.json"
OUT = f"{SP}/ablation_judged_grok.json"
BATCH = 11
random.seed(7)

RUBRIC = (
    "You are an expert Java developer. Each item has: kind (CLASS/FIELD/METHOD), the declaring class simple "
    "name, the JVM descriptor, the obfuscated identifier, decompiled obfuscated CONTEXT (real behaviour, names "
    "still obfuscated), and a SUGGESTED name (+ alternatives). You are given NO reference/original name. Decide "
    "ONLY whether the suggested name (or one of its alternatives) is an accurate, plausible name for what this "
    "member actually does, judged purely from the code's behaviour.\n"
    "ACCEPT = the name accurately and plausibly describes the member's role/behaviour. REJECT = misleading, "
    "wrong, opposite, or too generic to be useful. UNCERTAIN = the context is insufficient to decide.\n"
    'Output STRICT JSON ONLY: [{"id": <int>, "verdict": "ACCEPT|REJECT|UNCERTAIN", "reason": "<=8 words"}].'
)


def call(prompt):
    p = subprocess.run(["grok", "--disable-web-search", "-p", prompt], capture_output=True, text=True, timeout=300)
    return p.stdout


def parse_array(text):
    for s in [m.start() for m in re.finditer(r"\[", text)]:
        depth = 0
        for e in range(s, len(text)):
            if text[e] == "[":
                depth += 1
            elif text[e] == "]":
                depth -= 1
                if depth == 0:
                    try:
                        arr = json.loads(text[s:e + 1])
                        if isinstance(arr, list) and arr and "verdict" in arr[0]:
                            return arr
                    except Exception:
                        break
    return None


items = json.load(open(IN))
order = list(range(len(items)))
random.shuffle(order)
verdicts = {}
for bi in range(0, len(order), BATCH):
    batch = order[bi:bi + BATCH]
    payload = [{"id": items[i]["id"], "kind": items[i]["kind"], "class": items[i]["owner"],
                "descriptor": items[i]["descriptor"], "obfuscated": items[i]["obfName"],
                "context": items[i]["context"], "suggested": items[i]["suggested"],
                "alternatives": items[i]["alternatives"]} for i in batch]
    prompt = RUBRIC + "\n\nItems:\n" + json.dumps(payload, ensure_ascii=False)
    arr = None
    for _ in range(2):
        try:
            arr = parse_array(call(prompt))
        except Exception:
            arr = None
        if arr:
            break
    if arr:
        for o in arr:
            try:
                verdicts[int(o["id"])] = o["verdict"]
            except Exception:
                pass
    print(f"  batch {bi//BATCH+1}: {len(verdicts)}/{len(items)}", flush=True)

# compare ablated plausibility against the main process verdict
from collections import Counter
res = {"ACCEPT": Counter(), "REJECT": Counter()}
detail = []
for it in items:
    abl = verdicts.get(it["id"], "MISSING")
    res[it["_verdict"]][abl] += 1
    detail.append({**{k: it[k] for k in ("model", "kind", "owner", "obfName", "suggested", "_ref", "_verdict")}, "ablated": abl})
json.dump(detail, open(OUT, "w"), indent=1)
print("\n=== reference-ablated re-judge (behaviour only) vs main verdict ===")
for mv in ("ACCEPT", "REJECT"):
    n = sum(res[mv].values())
    acc = res[mv]["ACCEPT"]
    print(f"  main={mv:7} (n={n}): ablated ACCEPT(plausible-for-behaviour)={acc} ({100*acc/n:.0f}%)  full={dict(res[mv])}")
