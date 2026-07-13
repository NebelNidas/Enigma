#!/usr/bin/env python3
"""Verify that M+C and M++ prompt batches share the same non-code context."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


VARIANT_MARKERS = (
    "Decompiled body of the target method",
    "Length-control padding below",
)


def target_key(row: dict) -> tuple:
    return (
        row["jar"],
        row["kind"],
        row["obfOwner"],
        row["obfName"],
        row["obfDesc"],
        row.get("localIndex", -1),
    )


def strip_variant_block(prompt: str) -> str:
    starts = [prompt.find(marker) for marker in VARIANT_MARKERS if prompt.find(marker) >= 0]
    if not starts:
        return prompt
    start = min(starts)
    end = prompt.find("\nRespond with JSON only:", start)
    if end < 0:
        return prompt[:start]
    return prompt[:start] + prompt[end:]


def normalize_context(prompt: str) -> str:
    return "\n".join(line.rstrip() for line in prompt.splitlines() if line.strip())


def load_rows(prompt_root: Path, directory: str) -> dict[tuple, dict]:
    rows = {}
    for path in sorted((prompt_root / directory).glob("*-prompts.jsonl")):
        track = "structure-only" if "structure-only" in path.name else "realistic"
        with open(path, encoding="utf-8") as reader:
            for line in reader:
                if not line.strip():
                    continue
                row = json.loads(line)
                rows[(track,) + target_key(row)] = row
    return rows


def check_pair(prompt_root: Path, prefix: str) -> dict:
    mc = load_rows(prompt_root, f"{prefix}_MC")
    mpp = load_rows(prompt_root, f"{prefix}_MPP")
    common = sorted(set(mc) & set(mpp))
    raw_diffs = []
    normalized_diffs = []
    for key in common:
        left = strip_variant_block(mc[key]["userPrompt"])
        right = strip_variant_block(mpp[key]["userPrompt"])
        if left != right:
            raw_diffs.append(key)
        if normalize_context(left) != normalize_context(right):
            normalized_diffs.append(key)
    return {
        "prefix": prefix,
        "mcRows": len(mc),
        "mppRows": len(mpp),
        "common": len(common),
        "rawContextDiffs": len(raw_diffs),
        "normalizedContextDiffs": len(normalized_diffs),
        "examples": normalized_diffs[:10],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prompt-root", type=Path, required=True)
    parser.add_argument("--prefix", action="append", default=["batch", "mc_batch"])
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    reports = [check_pair(args.prompt_root.resolve(), prefix) for prefix in args.prefix]
    lines = ["# Prompt pairing verification"]
    failed = False
    for report in reports:
        lines.append(
            f"{report['prefix']}: common={report['common']} "
            f"raw_context_diffs={report['rawContextDiffs']} "
            f"normalized_context_diffs={report['normalizedContextDiffs']}"
        )
        if report["normalizedContextDiffs"]:
            failed = True
            lines.append(f"  examples={report['examples']}")
    text = "\n".join(lines) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text, encoding="utf-8")
    print(text, end="")
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
