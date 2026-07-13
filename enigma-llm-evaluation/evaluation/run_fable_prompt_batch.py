#!/usr/bin/env python3
"""Run dumped prompt batches through the Claude/Fable CLI, resumably.

Input layout under --prompt-root:
  batch_M/*.jsonl, batch_MC/*.jsonl, batch_MPP/*.jsonl
  mc_batch_M/*.jsonl, mc_batch_MC/*.jsonl, mc_batch_MPP/*.jsonl

This mirrors run_codex_prompt_batch.py's output contract but uses Claude's Fable
CLI. Use --dry-run to validate pairing and counts without spending Anthropic
budget.
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import glob
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time


DEFAULT_DISALLOWED_TOOLS = (
    "Bash Read Edit Write WebFetch WebSearch Agent Glob Grep TodoWrite "
    "NotebookEdit Task"
)


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


def output_files(outdir: Path) -> list[Path]:
    return sorted(path for path in outdir.glob("*/*.json") if path.is_file())


def run_config(args: argparse.Namespace) -> dict:
    prompt_root = str(args.prompt_root)
    return {
        "model": args.model,
        "effort": args.effort,
        "datasets": args.datasets,
        "arms": args.arms,
        "tracks": args.tracks,
        "kinds": args.kinds,
        "promptRoot": prompt_root,
        "promptRootSha256": hashlib.sha256(prompt_root.encode("utf-8")).hexdigest(),
    }


def check_or_write_manifest(args: argparse.Namespace) -> None:
    manifest_path = args.out / "run_manifest.json"
    config = run_config(args)
    existing_files = output_files(args.out)
    if manifest_path.exists():
        previous = json.loads(manifest_path.read_text(encoding="utf-8"))
        if previous.get("config") != config:
            raise SystemExit(
                "Refusing to resume Fable run with different configuration in "
                f"{args.out}. Use a different -PfableOut directory."
            )
        return
    if existing_files and not args.allow_legacy_resume:
        raise SystemExit(
            "Refusing to resume non-empty Fable output without run_manifest.json. "
            "Pass -PfableAllowLegacyResume=true only for known historical outputs."
        )
    manifest = {
        "version": 1,
        "config": config,
        "legacyResume": bool(existing_files),
    }
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def should_skip_existing(dst: Path, args: argparse.Namespace) -> bool:
    if not (dst.exists() and dst.stat().st_size > 0):
        return False
    try:
        previous = json.loads(dst.read_text(encoding="utf-8"))
    except Exception:
        if not args.retry_failed:
            return True
        return False

    previous_model = previous.get("requestedModel")
    previous_effort = previous.get("effort")
    if previous_model and previous_model != args.model:
        raise RuntimeError(f"{dst} was generated with model={previous_model}, not {args.model}")
    if previous_effort and previous_effort != args.effort:
        raise RuntimeError(f"{dst} was generated with effort={previous_effort}, not {args.effort}")

    if previous.get("ok") and previous.get("suggestedName"):
        return True
    return not args.retry_failed


def atomic_write_json(path: Path, record: dict) -> None:
    tmp = path.with_suffix(path.suffix + ".partial")
    tmp.write_text(json.dumps(record, sort_keys=True) + "\n", encoding="utf-8")
    tmp.replace(path)


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
                # Fable historical output did not include track in the key. Keep that
                # contract so old partial runs resume cleanly; current datasets have
                # disjoint jar names, so obscure/minecraft do not collide.
                key = (
                    f'{obj["jar"]}|{obj["kind"]}|{obj["obfOwner"]}|'
                    f'{obj["obfName"]}|{obj["obfDesc"]}|{obj.get("localIndex", -1)}'
                )
                obj["_dataset"] = dataset_name
                obj["_track"] = track
                rows[key] = obj
    return rows


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
    parser.add_argument("--model", default="fable")
    parser.add_argument("--effort", default="high")
    parser.add_argument("--datasets", default="obscure=batch,mc=mc_batch")
    parser.add_argument("--arms", default="M,MC,MPP")
    parser.add_argument("--tracks", default="realistic")
    parser.add_argument("--kinds", default="METHOD")
    parser.add_argument("--parallel", type=int, default=5)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--timeout-seconds", type=int, default=420)
    parser.add_argument("--neutral-cwd", type=Path)
    parser.add_argument("--disallowed-tools", default=DEFAULT_DISALLOWED_TOOLS)
    parser.add_argument("--retry-failed", action="store_true")
    parser.add_argument("--allow-legacy-resume", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    args.prompt_root = args.prompt_root.resolve()
    args.out = args.out.resolve()
    neutral = (args.neutral_cwd or args.out / "_fable_cwd").resolve()
    neutral.mkdir(parents=True, exist_ok=True)

    work = build_work(args)
    print(
        f"work items: {len(work)} (datasets={args.datasets} arms={split_csv(args.arms)} "
        f"tracks={set(split_csv(args.tracks))} kinds={set(split_csv(args.kinds))} "
        f"model={args.model} effort={args.effort} par={args.parallel})",
        flush=True,
    )
    if args.dry_run:
        return

    check_or_write_manifest(args)

    lock = threading.Lock()
    done = [0]
    errs = [0]
    disallowed = split_csv(args.disallowed_tools.replace(" ", ","))

    def call(item: tuple[str, str, dict]) -> None:
        arm, key, obj = item
        outdir = args.out / arm
        outdir.mkdir(parents=True, exist_ok=True)
        dst = outdir / f"{safe_name(key)}.json"
        if should_skip_existing(dst, args):
            with lock:
                done[0] += 1
            return

        try:
            with tempfile.NamedTemporaryFile("w", suffix=".sys", dir=neutral, encoding="utf-8") as sys_file:
                sys_file.write(obj["systemPrompt"])
                sys_file.flush()
                cmd = [
                    "claude", "-p",
                    "--model", args.model,
                    "--effort", args.effort,
                    "--disallowed-tools", *disallowed,
                    "--system-prompt-file", sys_file.name,
                ]
                start = time.time()
                proc = subprocess.run(
                    cmd,
                    input=obj["userPrompt"],
                    text=True,
                    capture_output=True,
                    timeout=args.timeout_seconds,
                    cwd=neutral,
                )
                latency_ms = int((time.time() - start) * 1000)
            parsed = extract_json(proc.stdout or "")
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
                "ok": parsed is not None,
                "rc": proc.returncode,
            }
            if parsed is None:
                rec["raw"] = (proc.stdout or "")[:1000]
                rec["stderr"] = (proc.stderr or "")[-1000:]
            atomic_write_json(dst, rec)
            with lock:
                done[0] += 1
                if parsed is None:
                    errs[0] += 1
                if done[0] % 20 == 0 or parsed is None:
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
            atomic_write_json(dst, rec)
            with lock:
                done[0] += 1
                errs[0] += 1
                print(f"  ERROR {arm} {key[:60]}: {type(exc).__name__} {exc}", flush=True)

    with cf.ThreadPoolExecutor(max_workers=args.parallel) as executor:
        list(executor.map(call, work))

    print(f"DONE: {done[0]}/{len(work)} written, {errs[0]} unparsed/errored", flush=True)


if __name__ == "__main__":
    main()
