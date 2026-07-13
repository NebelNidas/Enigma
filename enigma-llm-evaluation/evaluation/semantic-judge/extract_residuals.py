#!/usr/bin/env python3
"""Extract judge-ready residual items for the semantic LLM judge.

For one model's clean AUTO run (api realistic), pick residuals (non-exact/non-normalized
misses that have a real suggestion, no error) and attach decompiled obfuscated context:
  METHOD -> the obf method body (+ 1-hop callee body if the direct body is trivial delegation)
  FIELD  -> the field declaration line + sibling field declarations of the owner class
  CLASS  -> the class declaration + member signature skeleton
Emits a JSON list of blind judge items (no reference/suggestion labelling of provenance).
"""
import glob, json, os, re, sys

BR = sys.argv[1]           # benchmark dir
MODEL_DIR = sys.argv[2]    # e.g. qwen3-coder-30b-a3b-instruct_iq4_xs
OUT = sys.argv[3]
SP = os.environ.get("JUDGE_DIR", os.path.dirname(os.path.abspath(__file__)))
JAR_TO_DECOMP = {
    "commons-lang3-3.14.0": f"{SP}/decomp_commons-lang3-3.14.0",
    "gson-2.11.0": f"{SP}/decomp_gson-2.11.0",
    "xz-1.9": f"{SP}/decomp_xz-1.9",
}
_class_cache = {}


def class_source(jar, owner):
    # owner "obf/C27$C34" -> outer file obf/C27.java
    outer = owner.split("$")[0]           # obf/C27
    path = os.path.join(JAR_TO_DECOMP[jar], outer + ".java")
    if path in _class_cache:
        return _class_cache[path]
    src = open(path).read() if os.path.isfile(path) else ""
    _class_cache[path] = src
    return src


def extract_method(src, name):
    """Return the source of method `name(` including its brace-balanced body, or ''."""
    m = re.search(r"[\w<>\[\]\.\?, ]+\b" + re.escape(name) + r"\s*\([^;{]*\)\s*(?:throws [\w, ]+)?\{", src)
    if not m:
        return ""
    start = src.rfind("\n", 0, m.start()) + 1
    i = src.index("{", m.start())
    depth = 0
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[start:j + 1]
    return src[start:i + 200]


def callee_names(body):
    # crude 1-hop: method-call tokens like .mXXX( or plain mXXX(
    return set(re.findall(r"\b(m\d+)\s*\(", body))


def trivial(body):
    # 1-2 statement / pure delegation body
    inner = body[body.index("{") + 1: body.rindex("}")] if "{" in body and "}" in body else body
    stmts = [s for s in inner.split(";") if s.strip()]
    return len(stmts) <= 2


def field_context(src, name):
    lines = src.splitlines()
    decl = next((l.strip() for l in lines if re.search(r"\b" + re.escape(name) + r"\b\s*[;=]", l)), "")
    sibs = [l.strip() for l in lines if re.search(r"\b(f\d+)\b\s*[;=]", l)][:25]
    return "// field declaration:\n" + decl + "\n// sibling fields in the same class:\n" + "\n".join(sibs)


def class_context(src):
    lines = src.splitlines()
    head = []
    for l in lines:
        s = l.strip()
        if s.startswith("package") or s.startswith("import"):
            continue
        head.append(l)
        if len(head) >= 25:
            break
    return "\n".join(head)


def simple_owner(owner):
    return owner.split("/")[-1].split("$")[-1]


items = []
for f in glob.glob(os.path.join(BR, MODEL_DIR, "*-realistic-benchmark.jsonl")):
    for line in open(f):
        line = line.strip()
        if not line:
            continue
        r = json.loads(line)
        if r.get("slice") != "api" or r.get("preservationControl"):
            continue
        if r.get("exact") or r.get("normalized"):
            continue
        sug = (r.get("suggested") or "").strip()
        if not sug or (r.get("error") or ""):
            continue
        jar, kind, owner = r["jar"], r["kind"], r["obfOwner"]
        src = class_source(jar, owner)
        ctx = ""
        if kind == "METHOD":
            body = extract_method(src, r["obfName"])
            if body and trivial(body):
                for cn in list(callee_names(body))[:2]:
                    cb = extract_method(src, cn)
                    if cb and cn != r["obfName"]:
                        body += f"\n// callee {cn}:\n" + cb
            ctx = body or "(body not found)"
        elif kind == "FIELD":
            ctx = field_context(src, r["obfName"])
        elif kind == "CLASS":
            ctx = class_context(src)
        ref = (r.get("acceptable") or [r.get("expected")])[0]
        items.append({
            "jar": jar, "kind": kind, "owner": simple_owner(owner),
            "obfName": r["obfName"], "descriptor": r.get("obfDesc", ""),
            "reference": ref, "suggested": sug,
            "alternatives": r.get("alternatives") or [],
            "context": ctx[:1400],
        })

json.dump(items, open(OUT, "w"), indent=1)
print(f"{MODEL_DIR}: {len(items)} residual items -> {OUT}")
byk = {}
for it in items:
    byk[it["kind"]] = byk.get(it["kind"], 0) + 1
print("  by kind:", byk)
