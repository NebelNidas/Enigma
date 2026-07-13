#!/usr/bin/env python3
"""Judge frontier residual naming suggestions with Grok Build.

This is the Grok-backed companion to run_frontier_semantic_judge.py. It consumes
the same `semantic_judge_required.jsonl`, writes the same final JSON shape, and
uses a resumable partial verdict map. It never calls Anthropic/Fable.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import random
import re
import subprocess
import time


SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["verdicts"],
    "properties": {
        "verdicts": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["id", "verdict", "reason"],
                "properties": {
                    "id": {"type": "integer"},
                    "verdict": {"type": "string", "enum": ["ACCEPT", "REJECT", "UNCERTAIN"]},
                    "reason": {"type": "string"},
                },
            },
        },
    },
}

RUBRIC = """You are a reverse engineer / library maintainer deciding whether you would ADOPT the suggested identifier when renaming this obfuscated member.
Judge only from the decompiled behaviour, JVM descriptor, owner simple name, and kind. The reference identifier from an unobfuscated build is a neutral equivalence anchor for the intended role/contract, not wording to copy.

ACCEPT: you would keep the suggestion in production code without renaming again for clarity, safety, or API hygiene. Accept genuine synonyms, correct role names, precise behavioural names, or justified overload/arity specialisations that preserve the essential contract and drop only cosmetic detail.

REJECT: the suggestion is misleading, wrong, opposite of the behaviour, too generic to disambiguate among peers without support, drops an essential qualifier implied by the code, confusingly collides with a well-known JDK/framework concept when this symbol is not that concept, or has wrong granularity.

UNCERTAIN: cannot decide adoption from the given context.

Return STRICT JSON ONLY as {"verdicts":[{"id":1,"verdict":"ACCEPT|REJECT|UNCERTAIN","reason":"<=8 words"}]}."""


def extract_json(raw: str) -> dict | None:
    raw = raw.strip()
    if raw.startswith("```"):
        lines = raw.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].startswith("```"):
            lines = lines[:-1]
        raw = "\n".join(lines).strip()
    start = raw.find("{")
    end = raw.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(raw[start:end + 1])
    except json.JSONDecodeError:
        return None
    if isinstance(parsed, dict) and isinstance(parsed.get("verdicts"), list):
        return parsed
    # Some headless CLIs wrap the assistant text in a metadata envelope.
    for key in ("output", "response", "result", "text"):
        value = parsed.get(key)
        if isinstance(value, str):
            nested = extract_json(value)
            if nested is not None:
                return nested
    return None


def safe_model_name(model: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", model)


def effective_effort(args: argparse.Namespace) -> str:
    if args.model == "grok-build":
        return "default"
    return args.effort or "default"


def atomic_write(path: Path, text: str) -> None:
    tmp = path.with_suffix(path.suffix + ".partial-write")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)


def load_items(path: Path) -> list[dict]:
    items = []
    with open(path, encoding="utf-8") as reader:
        for line in reader:
            if line.strip():
                items.append(json.loads(line))
    return items


def load_partial(path: Path) -> dict[int, dict]:
    if not path.exists():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    return {int(key): value for key, value in raw.items()}


def required_fingerprint(items: list[dict]) -> str:
    digest = hashlib.sha256()
    for item in items:
        digest.update(json.dumps(item, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
        digest.update(b"\n")
    return digest.hexdigest()


def validate_partial_fingerprint(partial: Path, items: list[dict], dry_run: bool) -> None:
    sidecar = partial.with_suffix(partial.suffix + ".required.sha256")
    fingerprint = required_fingerprint(items)
    if partial.exists() and sidecar.exists():
        previous = sidecar.read_text(encoding="utf-8").strip()
        if previous != fingerprint:
            raise SystemExit(
                f"Refusing to reuse stale partial {partial}: required-input fingerprint changed. "
                "Move the partial aside or pass --overwrite to start fresh."
            )
    if not dry_run:
        if partial.exists() and not sidecar.exists():
            atomic_write(sidecar, fingerprint + "\n")
        elif not partial.exists():
            atomic_write(sidecar, fingerprint + "\n")


def compact_item(item: dict) -> dict:
    target = item.get("target") or {}
    owner = str(target.get("obfOwner") or "").split("/")[-1]
    return {
        "id": int(item["judge_id"]),
        "kind": item.get("kind"),
        "owner": owner,
        "descriptor": target.get("obfDesc") or "",
        "obfuscated": target.get("obfName"),
        "context": item.get("context"),
        "suggested": item.get("suggested"),
        "alternatives": item.get("alternatives") or [],
        "reference": item.get("expected"),
        "acceptableReferenceIdentifiers": item.get("acceptable") or [],
    }


def prompt_for(batch: list[dict]) -> str:
    payload = [compact_item(item) for item in batch]
    return RUBRIC + "\n\nItems:\n" + json.dumps(payload, ensure_ascii=False)


def parse_verdicts(parsed: dict, expected_ids: set[int]) -> dict[int, dict]:
    verdicts = {}
    for row in parsed.get("verdicts") or []:
        try:
            item_id = int(row["id"])
        except Exception:
            continue
        if item_id not in expected_ids:
            continue
        verdict = row.get("verdict")
        if verdict not in {"ACCEPT", "REJECT", "UNCERTAIN"}:
            continue
        verdicts[item_id] = {
            "verdict": verdict,
            "reason": str(row.get("reason") or "")[:120],
        }
    return verdicts


def call_grok(args: argparse.Namespace, prompt: str) -> tuple[dict | None, dict]:
    cmd = [
        "grok",
        "--disable-web-search",
        "-m", args.model,
        "--no-memory",
        "-p", prompt,
    ]
    if args.effort and args.effort != "none" and args.model != "grok-build":
        cmd.extend(["--effort", args.effort])
    start = time.time()
    proc = subprocess.run(
        cmd,
        text=True,
        capture_output=True,
        timeout=args.timeout_seconds,
        cwd=args.neutral_cwd,
    )
    latency_ms = int((time.time() - start) * 1000)
    raw = (proc.stdout or "").strip()
    parsed = extract_json(raw)
    meta = {
        "rc": proc.returncode,
        "latencyMs": latency_ms,
        "stderrTail": (proc.stderr or "")[-1000:],
        "rawHead": raw[:1000],
    }
    return parsed, meta


def judge_batch(args: argparse.Namespace, by_id: dict[int, dict], batch_ids: list[int]) -> tuple[dict[int, dict], dict]:
    expected_ids = set(batch_ids)
    last_meta = {}
    for _ in range(args.retries + 1):
        parsed, meta = call_grok(args, prompt_for([by_id[item_id] for item_id in batch_ids]))
        last_meta = meta
        batch_verdicts = parse_verdicts(parsed or {}, expected_ids)
        if len(batch_verdicts) == len(batch_ids):
            return batch_verdicts, meta
    if len(batch_ids) == 1:
        return {}, last_meta

    combined: dict[int, dict] = {}
    latency_ms = int(last_meta.get("latencyMs") or 0)
    for item_id in batch_ids:
        single_verdicts, single_meta = judge_batch(args, by_id, [item_id])
        latency_ms += int(single_meta.get("latencyMs") or 0)
        combined.update(single_verdicts)
    fallback_meta = dict(last_meta)
    fallback_meta["latencyMs"] = latency_ms
    fallback_meta["fallback"] = "single-item"
    return combined, fallback_meta


def final_rows(items: list[dict], verdicts: dict[int, dict], args: argparse.Namespace) -> list[dict]:
    by_id = {int(item["judge_id"]): item for item in items}
    judge_effort = effective_effort(args)
    rows = []
    for item_id in sorted(by_id):
        item = by_id[item_id]
        verdict = verdicts.get(item_id, {"verdict": "MISSING", "reason": ""})
        rows.append({
            "judge_id": item_id,
            "vendor": item.get("vendor"),
            "arm": item.get("arm"),
            "key": item.get("key"),
            "dataset": item.get("dataset"),
            "corpus": item.get("corpus"),
            "expected": item.get("expected"),
            "acceptable": item.get("acceptable") or [],
            "suggested": item.get("suggested"),
            "alternatives": item.get("alternatives") or [],
            "verdict": verdict.get("verdict"),
            "reason": verdict.get("reason"),
            "judgeModel": args.model,
            "judgeEffort": judge_effort,
        })
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--required", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model", default="grok-build")
    parser.add_argument("--effort", default="high")
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--seed", type=int, default=20260713)
    parser.add_argument("--timeout-seconds", type=int, default=600)
    parser.add_argument("--retries", type=int, default=1)
    parser.add_argument("--neutral-cwd", type=Path)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    args.required = args.required.resolve()
    args.out = args.out.resolve()
    args.neutral_cwd = (args.neutral_cwd or args.out / "_grok_judge_cwd").resolve()
    args.out.mkdir(parents=True, exist_ok=True)
    args.neutral_cwd.mkdir(parents=True, exist_ok=True)

    output_name = f"semantic_judge_grok_{safe_model_name(args.model)}_{effective_effort(args)}"
    if args.limit > 0:
        output_name += f".limit-{args.limit}"
    output = args.out / f"{output_name}.json"
    partial = output.with_suffix(output.suffix + ".partial")
    if output.exists() and not args.overwrite:
        raise SystemExit(f"Refusing to overwrite existing judge output: {output}")
    if args.overwrite:
        partial.unlink(missing_ok=True)
        partial.with_suffix(partial.suffix + ".required.sha256").unlink(missing_ok=True)

    items = load_items(args.required)
    if args.limit > 0:
        items = items[:args.limit]
    validate_partial_fingerprint(partial, items, args.dry_run)
    verdicts = load_partial(partial)
    order = [int(item["judge_id"]) for item in items]
    random.Random(args.seed).shuffle(order)
    by_id = {int(item["judge_id"]): item for item in items}
    pending = [item_id for item_id in order if item_id not in verdicts]
    print(
        f"grok judge items: {len(items)} pending={len(pending)} model={args.model} "
        f"effort={effective_effort(args)} batch={args.batch_size}",
        flush=True,
    )
    if args.dry_run:
        return

    stubborn: list[int] = []
    for index in range(0, len(pending), args.batch_size):
        batch_ids = pending[index:index + args.batch_size]
        batch_verdicts, meta = judge_batch(args, by_id, batch_ids)
        for item_id, verdict in batch_verdicts.items():
            verdicts[item_id] = verdict
        if batch_verdicts:
            atomic_write(partial, json.dumps({str(key): value for key, value in sorted(verdicts.items())}, indent=2, sort_keys=True) + "\n")
        if len(batch_verdicts) != len(batch_ids):
            # Grok occasionally returns no parseable verdict for a specific item even in the
            # single-item fallback. Do not throw away the whole run: defer these to a final
            # reconciliation pass so the ~90 min of completed work is never lost.
            missing = sorted(set(batch_ids) - set(batch_verdicts))
            stubborn.extend(missing)
            print(f"  grok deferred {len(missing)} unparseable item(s) {missing[:5]}; meta={meta}", flush=True)
        print(f"  grok judged {len(verdicts)}/{len(items)}; lastLatencyMs={meta['latencyMs']}", flush=True)

    # Reconciliation: retry each stubborn item on its own with extra attempts before giving up.
    stubborn = sorted({item_id for item_id in stubborn if item_id not in verdicts})
    if stubborn:
        print(f"grok reconciliation pass for {len(stubborn)} item(s): {stubborn[:10]}", flush=True)
        reconcile_args = copy.copy(args)
        reconcile_args.retries = max(args.retries, 3)
        for item_id in stubborn:
            single_verdicts, single_meta = judge_batch(reconcile_args, by_id, [item_id])
            if item_id in single_verdicts:
                verdicts[item_id] = single_verdicts[item_id]
            else:
                verdicts[item_id] = {"verdict": "MISSING", "reason": "grok-no-parseable-verdict"}
                print(f"  grok gave up on item {item_id}; recorded MISSING; meta={single_meta}", flush=True)
            atomic_write(partial, json.dumps({str(key): value for key, value in sorted(verdicts.items())}, indent=2, sort_keys=True) + "\n")

    rows = final_rows(items, verdicts, args)
    atomic_write(output, json.dumps(rows, indent=2, sort_keys=True) + "\n")
    print(f"DONE: wrote {output}", flush=True)


if __name__ == "__main__":
    main()
