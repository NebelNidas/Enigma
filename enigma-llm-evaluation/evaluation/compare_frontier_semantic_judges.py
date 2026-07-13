#!/usr/bin/env python3
"""Compare two frontier semantic-judge verdict files by judge_id."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path


VERDICTS = ("ACCEPT", "REJECT", "UNCERTAIN", "MISSING")


def load_verdicts(path: Path) -> dict[int, str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    verdicts: dict[int, str] = {}
    if isinstance(data, dict):
        for key, value in data.items():
            if isinstance(value, dict):
                verdict = value.get("verdict")
            else:
                verdict = value
            if verdict in VERDICTS:
                verdicts[int(key)] = verdict
        return verdicts
    if isinstance(data, list):
        for row in data:
            verdict = row.get("verdict")
            if verdict in VERDICTS:
                verdicts[int(row["judge_id"])] = verdict
        return verdicts
    raise ValueError(f"Unsupported judge verdict file shape: {path}")


def load_keys(path: Path) -> dict[int, str]:
    """judge_id -> target key, only from the final list form (the .partial map carries no key)."""
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        return {}
    keys: dict[int, str] = {}
    for row in data:
        if row.get("key") is not None and row.get("judge_id") is not None:
            keys[int(row["judge_id"])] = row["key"]
    return keys


def assert_same_targets(left_keys: dict[int, str], right_keys: dict[int, str]) -> None:
    """Guard against comparing two judge files whose numeric judge_id maps to different targets
    (e.g. produced from different required lists / prompt roots). Only checks ids present in both."""
    mismatched = [
        i for i in set(left_keys) & set(right_keys) if left_keys[i] != right_keys[i]
    ]
    if mismatched:
        example = mismatched[0]
        raise SystemExit(
            f"Refusing to compare: {len(mismatched)} judge_id(s) map to different targets across the two files "
            f"(e.g. id {example}: {left_keys[example]!r} vs {right_keys[example]!r}). "
            "The files were built from different required lists; regenerate one so the ids align."
        )


def pct(num: int, den: int) -> str:
    return "n/a" if den == 0 else f"{100 * num / den:.1f}%"


def cohen_kappa(left: dict[int, str], right: dict[int, str], keys: list[int]) -> str:
    if not keys:
        return "n/a"
    n = len(keys)
    observed = sum(1 for key in keys if left[key] == right[key]) / n
    left_counts = Counter(left[key] for key in keys)
    right_counts = Counter(right[key] for key in keys)
    expected = sum((left_counts[label] / n) * (right_counts[label] / n) for label in VERDICTS)
    if expected >= 1:
        return "n/a"
    return f"{(observed - expected) / (1 - expected):.4f}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--left", type=Path, required=True)
    parser.add_argument("--right", type=Path, required=True)
    parser.add_argument("--left-label", default="left")
    parser.add_argument("--right-label", default="right")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    left = load_verdicts(args.left)
    right = load_verdicts(args.right)
    assert_same_targets(load_keys(args.left), load_keys(args.right))
    keys = sorted(set(left) & set(right))
    left_only = sorted(set(left) - set(right))
    right_only = sorted(set(right) - set(left))
    agree = sum(1 for key in keys if left[key] == right[key])
    matrix = Counter((left[key], right[key]) for key in keys)

    lines = [
        "# Frontier semantic judge comparison",
        f"{args.left_label}: {args.left}",
        f"{args.right_label}: {args.right}",
        "",
        f"overlap: {len(keys)}",
        f"{args.left_label}-only: {len(left_only)}",
        f"{args.right_label}-only: {len(right_only)}",
        f"agreement: {agree}/{len(keys)} ({pct(agree, len(keys))})",
        f"cohen_kappa: {cohen_kappa(left, right, keys)}",
        "",
        "## Marginals on overlap",
    ]
    for label, rows in ((args.left_label, left), (args.right_label, right)):
        counts = Counter(rows[key] for key in keys)
        lines.append(
            f"{label}: "
            + " ".join(f"{verdict}={counts[verdict]}/{len(keys)} ({pct(counts[verdict], len(keys))})" for verdict in VERDICTS)
        )
    lines.extend(["", "## Confusion matrix"])
    for left_verdict in VERDICTS:
        row = [f"{right_verdict}={matrix[(left_verdict, right_verdict)]}" for right_verdict in VERDICTS]
        lines.append(f"{args.left_label} {left_verdict}: " + " ".join(row))

    lines.extend(["", "## Disagreements"])
    for key in keys:
        if left[key] != right[key]:
            lines.append(f"{key}: {args.left_label}={left[key]} {args.right_label}={right[key]}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
