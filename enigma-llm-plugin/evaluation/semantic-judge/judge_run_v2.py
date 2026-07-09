#!/usr/bin/env python3
"""Semantic judge v2 -- REVERSE-ENGINEER / MAINTAINER-ADOPTION rubric (Codex+Grok co-designed).
Re-judges the SAME residuals as the v1 run, WITH the neutral reference, but asks "would a maintainer
adopt this name?" -- rejecting confusing JDK/framework collisions (Consumer, ...), dropped essential
qualifiers (failable/lazy/...), wrong granularity, or too-generic names. Preserves v1 (separate files).
Usage: judge_run_v2.py <grok|codex|claude>   -> writes judged_v2_<judge>_{14b,8b,30b}.json (+ .partial).
"""
import json, os, random, re, subprocess, sys

SP = "/tmp/claude-1000/-home-julian-Dev-Env-Digitalisierungskolleg/32e22798-6bde-488a-b047-83d0956e3383/scratchpad"
JUDGE = sys.argv[1] if len(sys.argv) > 1 else None  # None when imported (e.g. by tiebreak_v2)
# Judge flagship models (top tier per vendor): Grok -> grok-build ("Grok 4.3", advanced-coding /
# "Experte" web tier; NOT the cheap grok-composer-2.5-fast); Codex -> gpt-5.5; Claude -> opus-4-8.
GROK_MODEL = "grok-build"
MODELS = ["14b", "8b", "30b"]
BATCH = 11

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


def call(prompt):
    if JUDGE == "grok":
        cmd = ["grok", "--disable-web-search", "-m", GROK_MODEL, "-p", prompt]
    elif JUDGE == "claude":
        cmd = ["claude", "-p", prompt]
    else:
        cmd = ["codex", "exec", "--skip-git-repo-check", prompt]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=400).stdout


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


def main():
  for model in MODELS:
    items = json.load(open(f"{SP}/resid_{model}.json"))
    OUT = f"{SP}/judged_v2_{JUDGE}_{model}.json"
    PARTIAL = OUT + ".partial.json"
    verdicts = {}
    if os.path.exists(PARTIAL):
        verdicts = {int(k): v for k, v in json.load(open(PARTIAL)).items()}
    order = list(range(len(items)))
    random.seed(20260708)
    random.shuffle(order)
    for bi in range(0, len(order), BATCH):
        batch = [i for i in order[bi:bi + BATCH] if i not in verdicts]
        if not batch:
            continue
        payload = [{"id": i, "kind": items[i]["kind"], "owner": items[i]["owner"].split("/")[-1],
                    "descriptor": items[i]["descriptor"], "obfuscated": items[i]["obfName"],
                    "context": items[i]["context"], "suggested": items[i]["suggested"],
                    "alternatives": items[i]["alternatives"], "reference": items[i]["reference"]} for i in batch]
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
        json.dump({str(k): v for k, v in verdicts.items()}, open(PARTIAL, "w"))
        print(f"  {model} batch@{bi//BATCH+1}: {len(verdicts)}/{len(items)}", flush=True)
    out = [{"id": i, "kind": items[i]["kind"], "owner": items[i]["owner"].split("/")[-1],
            "obfName": items[i]["obfName"], "reference": items[i]["reference"],
            "suggested": items[i]["suggested"], "verdict": verdicts.get(i, "MISSING")} for i in range(len(items))]
    json.dump(out, open(OUT, "w"), indent=1)
    print(f"{model}: wrote {OUT} ({len(items)} items)", flush=True)


if __name__ == "__main__":
    main()
