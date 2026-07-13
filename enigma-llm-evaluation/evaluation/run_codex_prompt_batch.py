#!/usr/bin/env python3
"""Run dumped prompt batches through `codex exec`, resumably.

Input layout under --prompt-root:
  batch_M/*.jsonl, batch_MC/*.jsonl, batch_MPP/*.jsonl
  mc_batch_M/*.jsonl, mc_batch_MC/*.jsonl, mc_batch_MPP/*.jsonl

The dataset mapping is configurable, so callers can use other prefixes without
changing the script. Output records are one JSON file per target under
<out>/<arm>/ and include enough metadata for offline scoring/auditing.
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import glob
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time


SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["reasoning", "alternatives", "suggestedName", "confidence"],
    "properties": {
        "reasoning": {"type": "string"},
        "alternatives": {
            "type": "array",
            "items": {"type": "string"},
            "minItems": 1,
            "maxItems": 6,
        },
        "suggestedName": {"type": "string"},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
    },
}


def split_csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def parse_datasets(value: str) -> dict[str, str]:
    datasets = {}
    for item in split_csv(value):
        if "=" in item:
            name, prefix = item.split("=", 1)
        else:
            name = item
            prefix = "mc_batch" if item == "mc" else "batch"
        datasets[name.strip()] = prefix.strip()
    return datasets


def track_of(path: str) -> str:
    return "structure-only" if "structure-only" in path else "realistic"


def safe_name(key: str) -> str:
    return key.replace("/", "_").replace("|", "__").replace(";", "_").replace("(", "_").replace(")", "_")


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
        return json.loads(raw[start:end + 1])
    except json.JSONDecodeError:
        return None


# Token-usage fields the codex --json event stream may carry (schema varies across codex versions).
_USAGE_KEYS = (
    "input_tokens", "output_tokens", "reasoning_output_tokens", "reasoning_tokens",
    "cached_input_tokens", "total_tokens",
)


def parse_codex_usage(stdout: str) -> dict:
    """With --json the codex CLI streams JSONL events to stdout, including a token-count event.
    Scan the stream and return the last object that looks like a token-usage record (tolerant of
    schema drift: accepts any nested dict carrying at least one known usage key)."""
    usage: dict = {}
    for line in (stdout or "").splitlines():
        line = line.strip()
        if not line or not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue

        # Depth-first search for the deepest/last dict containing usage keys.
        stack = [event]
        while stack:
            node = stack.pop()
            if isinstance(node, dict):
                if any(k in node for k in _USAGE_KEYS):
                    usage = {k: node[k] for k in _USAGE_KEYS if k in node}
                stack.extend(node.values())
            elif isinstance(node, list):
                stack.extend(node)
    return usage


def prompt_for(row: dict) -> str:
    return f"""You are performing exactly one isolated naming-benchmark call.
Do not use tools. Do not inspect files. Do not run commands. Use only the benchmark prompt below.
Return exactly one JSON object matching this schema:
{{"reasoning":"string","alternatives":["string"],"suggestedName":"string","confidence":0.0}}
No markdown fences, no prose outside JSON.

<benchmark_system_prompt>
{row["systemPrompt"]}
</benchmark_system_prompt>

<benchmark_user_prompt>
{row["userPrompt"]}
</benchmark_user_prompt>
"""


def load_rows(prompt_root: Path, dataset_name: str, prefix: str, arm: str,
              tracks: set[str], kinds: set[str]) -> dict[str, dict]:
    rows = {}
    for filename in glob.glob(str(prompt_root / f"{prefix}_{arm}" / "*-prompts.jsonl")):
        track = track_of(filename)
        if track not in tracks:
            continue
        with open(filename, encoding="utf-8") as reader:
            for line in reader:
                obj = json.loads(line)
                if obj.get("kind") not in kinds:
                    continue
                key = (
                    f'{obj["jar"]}|{track}|{obj["kind"]}|{obj["obfOwner"]}|'
                    f'{obj["obfName"]}|{obj["obfDesc"]}|{obj.get("localIndex", -1)}'
                )
                obj["_dataset"] = dataset_name
                obj["_track"] = track
                rows[key] = obj
    return rows


def write_schema(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(SCHEMA, indent=2) + "\n", encoding="utf-8")


def build_work(args: argparse.Namespace) -> list[tuple[str, str, dict]]:
    datasets = parse_datasets(args.datasets)
    tracks = set(split_csv(args.tracks))
    kinds = set(split_csv(args.kinds))
    arms = split_csv(args.arms)
    work = []
    for arm in arms:
        for dataset, prefix in datasets.items():
            for key, row in load_rows(args.prompt_root, dataset, prefix, arm, tracks, kinds).items():
                work.append((arm, key, row))
    if args.limit > 0:
        work = work[:args.limit]
    return work


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prompt-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--model", default="gpt-5.6-sol")
    parser.add_argument("--effort", default="high")
    parser.add_argument("--datasets", default="obscure=batch,mc=mc_batch")
    parser.add_argument("--arms", default="M,MC,MPP")
    parser.add_argument("--tracks", default="realistic")
    parser.add_argument("--kinds", default="METHOD")
    parser.add_argument("--parallel", type=int, default=2)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--timeout-seconds", type=int, default=420)
    parser.add_argument("--neutral-cwd", type=Path)
    parser.add_argument("--schema", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    args.prompt_root = args.prompt_root.resolve()
    args.out = args.out.resolve()
    neutral = (args.neutral_cwd or args.out / "_codex_cwd").resolve()
    schema = (args.schema or args.out / "_schema" / "suggestion.schema.json").resolve()
    neutral.mkdir(parents=True, exist_ok=True)
    write_schema(schema)

    work = build_work(args)
    print(
        f"work items: {len(work)} (datasets={args.datasets} arms={split_csv(args.arms)} "
        f"tracks={set(split_csv(args.tracks))} kinds={set(split_csv(args.kinds))} "
        f"model={args.model} effort={args.effort} par={args.parallel})",
        flush=True,
    )
    if args.dry_run:
        return

    lock = threading.Lock()
    done = [0]
    errs = [0]

    def call(item: tuple[str, str, dict]) -> None:
        arm, key, obj = item
        outdir = args.out / arm
        outdir.mkdir(parents=True, exist_ok=True)
        dst = outdir / f"{safe_name(key)}.json"
        if dst.exists() and dst.stat().st_size > 0:
            try:
                prev = json.loads(dst.read_text(encoding="utf-8"))
                if prev.get("ok") and prev.get("suggestedName"):
                    with lock:
                        done[0] += 1
                    return
            except Exception:
                pass

        with tempfile.NamedTemporaryFile("w", suffix=".out", delete=False, dir=neutral, encoding="utf-8") as out_file:
            out_path = Path(out_file.name)

        cmd = [
            "codex", "exec",
            "-m", args.model,
            "-c", f'model_reasoning_effort="{args.effort}"',
            "-C", str(neutral),
            "--skip-git-repo-check",
            "--ephemeral",
            "--ignore-rules",
            "-s", "read-only",
            "--output-schema", str(schema),
            "-o", str(out_path),
            "--json",
            "-",
        ]

        start = time.time()
        try:
            proc = subprocess.run(
                cmd,
                input=prompt_for(obj),
                text=True,
                capture_output=True,
                timeout=args.timeout_seconds,
                cwd=neutral,
            )
            latency_ms = int((time.time() - start) * 1000)
            # The answer is written to out_path by -o; --json turns stdout into the JSONL event
            # stream, which carries token usage.
            raw = out_path.read_text(encoding="utf-8").strip() if out_path.exists() else ""
            parsed = extract_json(raw)
            usage = parse_codex_usage(proc.stdout or "")
            rec = {
                "arm": arm,
                "key": key,
                "expected": obj["expected"],
                "acceptable": obj["acceptable"],
                "kind": obj["kind"],
                "jar": obj["jar"],
                "dataset": obj.get("_dataset"),
                "track": obj.get("_track"),
                "requestedModel": args.model,
                "effort": args.effort,
                "latencyMs": latency_ms,
                "suggestedName": (parsed or {}).get("suggestedName"),
                "alternatives": (parsed or {}).get("alternatives", []),
                "confidence": (parsed or {}).get("confidence"),
                "usage": usage,
                "ok": parsed is not None,
                "rc": proc.returncode,
            }
            if parsed is None:
                rec["raw"] = raw[:1000]
                rec["stderr"] = (proc.stderr or "")[-1000:]
            dst.write_text(json.dumps(rec, sort_keys=True) + "\n", encoding="utf-8")
            with lock:
                done[0] += 1
                if parsed is None:
                    errs[0] += 1
                if done[0] % 10 == 0 or parsed is None:
                    print(f"  {done[0]}/{len(work)} done, {errs[0]} unparsed", flush=True)
        except Exception as exc:
            rec = {
                "arm": arm,
                "key": key,
                "expected": obj["expected"],
                "acceptable": obj["acceptable"],
                "kind": obj["kind"],
                "jar": obj["jar"],
                "dataset": obj.get("_dataset"),
                "track": obj.get("_track"),
                "requestedModel": args.model,
                "effort": args.effort,
                "ok": False,
                "error": f"{type(exc).__name__}: {exc}",
            }
            dst.write_text(json.dumps(rec, sort_keys=True) + "\n", encoding="utf-8")
            with lock:
                done[0] += 1
                errs[0] += 1
                print(f"  ERROR {arm} {key[:60]}: {type(exc).__name__} {exc}", flush=True)
        finally:
            try:
                out_path.unlink()
            except FileNotFoundError:
                pass

    with cf.ThreadPoolExecutor(max_workers=args.parallel) as executor:
        list(executor.map(call, work))

    print(f"DONE: {done[0]}/{len(work)} written, {errs[0]} unparsed/errored", flush=True)


if __name__ == "__main__":
    main()
