#!/usr/bin/env python3
"""Judge frontier residual naming suggestions with the semantic-judge v2 rubric.

Input is the `semantic_judge_required.jsonl` file emitted by
score_frontier_runs.py. The runner calls `codex exec` in batches and writes a
resumable partial verdict map plus one final JSON result file. It never calls
Anthropic/Fable.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import random
import re
import subprocess
import tempfile
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
    return None


def safe_model_name(model: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", model)


def atomic_write(path: Path, text: str) -> None:
    tmp = path.with_suffix(path.suffix + ".partial-write")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)


def write_schema(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(SCHEMA, indent=2) + "\n", encoding="utf-8")


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


def compact_item(item: dict) -> dict:
    target = item.get("target") or {}
    owner = str(target.get("obfOwner") or "").split("/")[-1]
    descriptor = target.get("obfDesc") or ""
    return {
        "id": int(item["judge_id"]),
        "kind": item.get("kind"),
        "owner": owner,
        "descriptor": descriptor,
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


def call_codex(args: argparse.Namespace, schema: Path, prompt: str) -> tuple[dict | None, dict]:
    with tempfile.NamedTemporaryFile("w", suffix=".out", delete=False, dir=args.neutral_cwd, encoding="utf-8") as out_file:
        out_path = Path(out_file.name)
    cmd = [
        "codex", "exec",
        "-m", args.model,
        "-c", f'model_reasoning_effort="{args.effort}"',
        "-C", str(args.neutral_cwd),
        "--skip-git-repo-check",
        "--ephemeral",
        "--ignore-rules",
        "-s", "read-only",
        "--output-schema", str(schema),
        "-o", str(out_path),
        "-",
    ]
    start = time.time()
    try:
        proc = subprocess.run(
            cmd,
            input=prompt,
            text=True,
            capture_output=True,
            timeout=args.timeout_seconds,
            cwd=args.neutral_cwd,
        )
        latency_ms = int((time.time() - start) * 1000)
        raw = out_path.read_text(encoding="utf-8").strip() if out_path.exists() else (proc.stdout or "").strip()
        parsed = extract_json(raw)
        meta = {
            "rc": proc.returncode,
            "latencyMs": latency_ms,
            "stderrTail": (proc.stderr or "")[-1000:],
            "rawHead": raw[:1000],
        }
        return parsed, meta
    finally:
        try:
            out_path.unlink()
        except FileNotFoundError:
            pass


def final_rows(items: list[dict], verdicts: dict[int, dict], args: argparse.Namespace) -> list[dict]:
    by_id = {int(item["judge_id"]): item for item in items}
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
            "judgeEffort": args.effort,
        })
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--required", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model", default="gpt-5.5")
    parser.add_argument("--effort", default="high")
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--seed", type=int, default=20260713)
    parser.add_argument("--timeout-seconds", type=int, default=600)
    parser.add_argument("--neutral-cwd", type=Path)
    parser.add_argument("--schema", type=Path)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    args.required = args.required.resolve()
    args.out = args.out.resolve()
    args.neutral_cwd = (args.neutral_cwd or args.out / "_codex_judge_cwd").resolve()
    schema = (args.schema or args.out / "_schema" / "frontier_semantic_judge.schema.json").resolve()
    args.out.mkdir(parents=True, exist_ok=True)
    args.neutral_cwd.mkdir(parents=True, exist_ok=True)
    write_schema(schema)

    output = args.out / f"semantic_judge_codex_{safe_model_name(args.model)}_{args.effort}.json"
    partial = output.with_suffix(output.suffix + ".partial")
    if output.exists() and not args.overwrite:
        raise SystemExit(f"Refusing to overwrite existing judge output: {output}")

    items = load_items(args.required)
    if args.limit > 0:
        items = items[:args.limit]
    verdicts = load_partial(partial)
    order = [int(item["judge_id"]) for item in items]
    random.Random(args.seed).shuffle(order)
    by_id = {int(item["judge_id"]): item for item in items}
    pending = [item_id for item_id in order if item_id not in verdicts]
    print(
        f"judge items: {len(items)} pending={len(pending)} model={args.model} "
        f"effort={args.effort} batch={args.batch_size}",
        flush=True,
    )
    if args.dry_run:
        return

    for index in range(0, len(pending), args.batch_size):
        batch_ids = pending[index:index + args.batch_size]
        batch = [by_id[item_id] for item_id in batch_ids]
        parsed, meta = call_codex(args, schema, prompt_for(batch))
        batch_verdicts = parse_verdicts(parsed or {}, set(batch_ids))
        if len(batch_verdicts) != len(batch_ids):
            missing = sorted(set(batch_ids) - set(batch_verdicts))
            raise RuntimeError(f"Judge returned {len(batch_verdicts)}/{len(batch_ids)} verdicts; missing {missing[:5]}; meta={meta}")
        for item_id, verdict in batch_verdicts.items():
            verdicts[item_id] = verdict
        atomic_write(partial, json.dumps({str(key): value for key, value in sorted(verdicts.items())}, indent=2, sort_keys=True) + "\n")
        print(f"  judged {len(verdicts)}/{len(items)}; lastLatencyMs={meta['latencyMs']}", flush=True)

    rows = final_rows(items, verdicts, args)
    atomic_write(output, json.dumps(rows, indent=2, sort_keys=True) + "\n")
    print(f"DONE: wrote {output}", flush=True)


if __name__ == "__main__":
    main()
