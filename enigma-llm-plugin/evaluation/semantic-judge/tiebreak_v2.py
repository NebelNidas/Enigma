#!/usr/bin/env python3
"""v2 tie-break: for residuals where grok-v2 and codex-v2 disagree, have Claude (CLI) apply the SAME
maintainer-adoption rubric fresh (blind to the two verdicts) -> tiebreak_v2_<model>.json {id: verdict}."""
import json, os, re, subprocess, sys
sys.path.insert(0, os.path.dirname(__file__))
from judge_run_v2 import RUBRIC, parse_array  # reuse identical rubric + parser

SP = "/tmp/claude-1000/-home-julian-Dev-Env-Digitalisierungskolleg/32e22798-6bde-488a-b047-83d0956e3383/scratchpad"
MODELS = ["14b", "8b", "30b"]
BATCH = 11


def call_claude(prompt):
    return subprocess.run(["claude", "-p", prompt], capture_output=True, text=True, timeout=400).stdout


for model in MODELS:
    g = {o["id"]: o["verdict"] for o in json.load(open(f"{SP}/judged_v2_grok_{model}.json"))}
    c = {o["id"]: o["verdict"] for o in json.load(open(f"{SP}/judged_v2_codex_{model}.json"))}
    items = json.load(open(f"{SP}/resid_{model}.json"))
    # Adjudicate every non-agreement INCLUDING items where one judge is MISSING (grok's residual
    # parse failures after the subscription-lapse re-fill). Claude judges blind from the item content,
    # so a MISSING grok/codex verdict costs no residual -- Claude supplies the deciding verdict.
    dis = [i for i in set(g) & set(c) if g[i] != c[i]]
    print(f"{model}: {len(dis)} disagreements to tie-break "
          f"({sum(1 for i in dis if g[i]=='MISSING' or c[i]=='MISSING')} involve a MISSING verdict)", flush=True)
    tb = {}
    outp = f"{SP}/tiebreak_v2_{model}.json"
    if os.path.exists(outp):
        tb = {int(k): v for k, v in json.load(open(outp)).items()}
    for bi in range(0, len(dis), BATCH):
        batch = [i for i in dis[bi:bi + BATCH] if i not in tb]
        if not batch:
            continue
        payload = [{"id": i, "kind": items[i]["kind"], "owner": items[i]["owner"].split("/")[-1],
                    "descriptor": items[i]["descriptor"], "obfuscated": items[i]["obfName"],
                    "context": items[i]["context"], "suggested": items[i]["suggested"],
                    "alternatives": items[i]["alternatives"], "reference": items[i]["reference"]} for i in batch]
        arr = None
        for _ in range(2):
            try:
                arr = parse_array(call_claude(RUBRIC + "\n\nItems:\n" + json.dumps(payload, ensure_ascii=False)))
            except Exception:
                arr = None
            if arr:
                break
        if arr:
            for o in arr:
                try:
                    tb[int(o["id"])] = o["verdict"]
                except Exception:
                    pass
        json.dump({str(k): v for k, v in tb.items()}, open(outp, "w"))
        print(f"  {model} tb {len(tb)}/{len(dis)}", flush=True)
    print(f"{model}: wrote {outp}", flush=True)
