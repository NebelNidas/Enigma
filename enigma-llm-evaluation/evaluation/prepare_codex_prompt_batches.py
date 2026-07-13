#!/usr/bin/env python3
"""Build filtered Codex prompt batches from offline harness prompt dumps.

The benchmark emits three arms:

* M   : metadata/context prompt only
* MC  : metadata/context plus the real decompiled target body
* MPP : metadata/context plus sterile length-matched padding

This script selects the target set from the MC dump, then filters all arms to
the same keys so the later model run is paired and reproducible.
"""
from __future__ import annotations

import argparse
from collections import Counter
import glob
import json
from pathlib import Path
import re
import shutil


CODE_HDR = "Decompiled body of the target method"
BOILERPLATE = {"equals", "hashCode", "toString", "compareTo", "clone", "finalize", "readObject", "writeObject"}
CF = re.compile(r"\b(if|for|while|switch|case|catch|do)\b")
ACCESSOR = re.compile(r"^(get|set|is|has)[A-Z0-9_]")


def code_block(prompt: str) -> str | None:
    start = prompt.find(CODE_HDR)
    if start < 0:
        return None
    end = prompt.find("\nRespond with JSON only:", start)
    block = prompt[start:end] if end > 0 else prompt[start:]
    newline = block.find("\n")
    return block[newline + 1:] if newline >= 0 else ""


def features(code: str) -> tuple[int, int, int]:
    calls = len(re.findall(r"[A-Za-z_]\w*\s*\(", code))
    statements = code.count(";")
    branches = len(CF.findall(code))
    return calls, statements, branches


def interesting_method(name: str, code: str) -> tuple[bool, str]:
    if name in BOILERPLATE:
        return False, "boilerplate"
    calls, statements, branches = features(code)
    body_calls = max(0, calls - 1)
    if ACCESSOR.match(name or ""):
        if branches >= 2 or (body_calls >= 3 and statements >= 3) or statements >= 6:
            return True, "interesting-accessor-named"
        return False, "accessor"
    if branches >= 1 or body_calls >= 2 or statements >= 4:
        return True, "interesting"
    return False, "thin"


def track_of(path: Path) -> str:
    return "structure-only" if "structure-only" in path.name else "realistic"


def target_key(row: dict, track: str) -> tuple:
    return (
        row["jar"],
        track,
        row["kind"],
        row["obfOwner"],
        row["obfName"],
        row["obfDesc"],
        row.get("localIndex", -1),
    )


def method_key(row: dict) -> tuple:
    return row["jar"], row["obfOwner"], row["obfName"], row["obfDesc"]


def select_manifest(code_dump: Path, include_parameters: bool) -> set[tuple]:
    methods: dict[tuple, tuple[bool, str, str]] = {}
    bodyless = 0
    for filename in glob.glob(str(code_dump / "*realistic*.jsonl")):
        with open(filename, encoding="utf-8") as reader:
            for line in reader:
                row = json.loads(line)
                if row.get("kind") != "METHOD":
                    continue
                if not row.get("codeIncluded"):
                    bodyless += 1
                    continue
                keep, reason = interesting_method(row["expected"], code_block(row["userPrompt"]) or "")
                methods[method_key(row)] = (keep, reason, row["expected"])

    kept_methods = {key for key, value in methods.items() if value[0]}
    reasons = Counter(value[1] for value in methods.values())
    per_jar = Counter(key[0] for key in kept_methods)
    print(
        f"{code_dump.name}: methods_with_body={len(methods)} bodyless={bodyless} "
        f"interesting={len(kept_methods)} per_jar={dict(per_jar)} reasons={dict(reasons)}",
        flush=True,
    )

    manifest: set[tuple] = set()
    for filename in glob.glob(str(code_dump / "*-prompts.jsonl")):
        track = track_of(Path(filename))
        with open(filename, encoding="utf-8") as reader:
            for line in reader:
                row = json.loads(line)
                kind = row.get("kind")
                if kind == "METHOD":
                    pass
                elif kind == "PARAMETER" and include_parameters:
                    pass
                else:
                    continue
                if method_key(row) in kept_methods:
                    manifest.add(target_key(row, track))
    return manifest


def copy_filtered(src: Path, dst: Path, manifest: set[tuple]) -> Counter:
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True, exist_ok=True)
    counts: Counter = Counter()
    for filename in sorted(src.glob("*-prompts.jsonl")):
        track = track_of(filename)
        out_file = dst / filename.name
        written = 0
        with open(filename, encoding="utf-8") as reader, open(out_file, "w", encoding="utf-8") as writer:
            for line in reader:
                row = json.loads(line)
                if target_key(row, track) not in manifest:
                    continue
                writer.write(json.dumps(row, sort_keys=True) + "\n")
                written += 1
                counts[(row["jar"], track, row["kind"])] += 1
        if written == 0:
            out_file.unlink()
    return counts


def arm_map(values: list[str]) -> dict[str, Path]:
    result = {}
    for item in values:
        arm, path = item.split("=", 1)
        result[arm.strip()] = Path(path).resolve()
    missing = {"M", "MC", "MPP"} - set(result)
    if missing:
        raise SystemExit(f"missing arm dump(s): {', '.join(sorted(missing))}")
    return result


def build_dataset(name: str, prefix: str, arms: dict[str, Path], out: Path, include_parameters: bool) -> None:
    manifest = select_manifest(arms["MC"], include_parameters)
    print(f"{name}: selected_targets={len(manifest)} include_parameters={include_parameters}", flush=True)
    for arm, src in arms.items():
        counts = copy_filtered(src, out / f"{prefix}_{arm}", manifest)
        print(f"{name} {arm}: wrote {sum(counts.values())} rows counts={dict(counts)}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--obscure-arm", action="append", default=[], help="ARM=promptDumpDir; repeat for M, MC, MPP")
    parser.add_argument("--mc-arm", action="append", default=[], help="ARM=promptDumpDir; repeat for M, MC, MPP")
    args = parser.parse_args()

    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=True)

    if args.obscure_arm:
        build_dataset("obscure", "batch", arm_map(args.obscure_arm), args.out, include_parameters=True)
    if args.mc_arm:
        build_dataset("minecraft", "mc_batch", arm_map(args.mc_arm), args.out, include_parameters=False)


if __name__ == "__main__":
    main()
