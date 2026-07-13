#!/usr/bin/env python3
"""Summarize frontier semantic-judge verdicts without making model calls."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path


VERDICTS = ("ACCEPT", "REJECT", "UNCERTAIN", "MISSING")
VENDORS = ("fable", "sol")
ARMS = ("M", "MC", "MPP")


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    with open(path, encoding="utf-8") as reader:
        for line in reader:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def load_judge_rows(path: Path) -> dict[int, dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, dict):
        return {int(key): value for key, value in data.items()}
    if isinstance(data, list):
        return {int(row["judge_id"]): row for row in data}
    raise ValueError(f"Unsupported judge file shape: {path}")


def pct(num: int, den: int) -> str:
    return "n/a" if den == 0 else f"{100 * num / den:.1f}%"


def verdict_of(row: dict | None) -> str:
    verdict = (row or {}).get("verdict")
    return verdict if verdict in VERDICTS else "MISSING"


def counter_row(label: str, counts: Counter, total: int) -> str:
    parts = [f"{verdict}={counts[verdict]}/{total} ({pct(counts[verdict], total)})" for verdict in VERDICTS]
    return f"{label}: " + " ".join(parts)


def grouped_counts(rows: list[dict], fields: tuple[str, ...]) -> dict[str, Counter]:
    grouped: dict[str, Counter] = defaultdict(Counter)
    for row in rows:
        label = " ".join(f"{field}={row.get(field, 'unknown')}" for field in fields)
        grouped[label][row["_verdict"]] += 1
    return dict(sorted(grouped.items()))


def write_outputs(summary: dict, text_out: Path, json_out: Path, overwrite: bool) -> None:
    for path in (text_out, json_out):
        if path.exists() and not overwrite:
            raise SystemExit(f"Refusing to overwrite existing semantic judge summary: {path}")
        path.parent.mkdir(parents=True, exist_ok=True)

    lines = [
        "# Frontier semantic-judge summary",
        f"required: {summary['inputs']['required']}",
        f"judge: {summary['inputs']['judge']}",
        f"potential residual cells: {summary['inputs']['potentialCells']}",
        "",
        "Scope: required residual-cell judge set only. This does not score non-required residual candidates.",
        "",
        f"required records: {summary['totals']['requiredRecords']}",
        f"judge rows: {summary['totals']['judgeRows']}",
        counter_row("verdicts", Counter(summary["totals"]["verdictCounts"]), summary["totals"]["requiredRecords"]),
        "",
        "## Verdicts by vendor/arm",
    ]
    for label, counts in summary["byVendorArm"].items():
        lines.append(counter_row(label, Counter(counts), sum(counts.values())))

    lines.extend(["", "## Verdicts by dataset/vendor/arm"])
    for label, counts in summary["byDatasetVendorArm"].items():
        lines.append(counter_row(label, Counter(counts), sum(counts.values())))

    lines.extend(["", "## Verdicts by corpus/vendor/arm"])
    for label, counts in summary["byCorpusVendorArm"].items():
        lines.append(counter_row(label, Counter(counts), sum(counts.values())))

    residual = summary["residualCells"]
    lines.extend([
        "",
        "## Potential residual target cells",
        f"cells: {residual['cells']}",
        f"cleared by any ACCEPT: {residual['anyAccept']}/{residual['cells']} ({pct(residual['anyAccept'], residual['cells'])})",
        f"not cleared: {residual['notCleared']}/{residual['cells']} ({pct(residual['notCleared'], residual['cells'])})",
        f"missing required verdicts: {residual['missingRequiredVerdicts']}",
    ])
    for vendor in VENDORS:
        count = residual["acceptedByVendor"][vendor]
        lines.append(f"accepted by {vendor}: {count}/{residual['cells']} ({pct(count, residual['cells'])})")
    lines.append("")
    lines.append("## Potential residual cells by arm")
    for arm, row in residual["byArm"].items():
        lines.append(
            f"{arm}: cells={row['cells']} anyAccept={row['anyAccept']} ({pct(row['anyAccept'], row['cells'])}) "
            f"notCleared={row['notCleared']} ({pct(row['notCleared'], row['cells'])})"
        )

    text_out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    json_out.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--required", type=Path, required=True)
    parser.add_argument("--judge", type=Path, required=True)
    parser.add_argument("--potential-cells", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()

    required = load_jsonl(args.required)
    judge_rows = load_judge_rows(args.judge)
    cells = load_jsonl(args.potential_cells)

    enriched = []
    verdict_counts = Counter()
    missing_required = 0
    by_identity: dict[tuple[str, str, str], str] = {}
    for item in required:
        judge_id = int(item["judge_id"])
        verdict = verdict_of(judge_rows.get(judge_id))
        row = dict(item)
        row["_verdict"] = verdict
        enriched.append(row)
        verdict_counts[verdict] += 1
        by_identity[(row["vendor"], row["arm"], row["key"])] = verdict
        if verdict == "MISSING":
            missing_required += 1

    by_arm = {arm: {"cells": 0, "anyAccept": 0, "notCleared": 0} for arm in ARMS}
    accepted_by_vendor = Counter()
    any_accept = 0
    not_cleared = 0
    for cell in cells:
        arm = cell["arm"]
        key = cell["key"]
        verdicts = {}
        for vendor in VENDORS:
            verdicts[vendor] = by_identity.get((vendor, arm, key), "MISSING")
        cleared = any(verdict == "ACCEPT" for verdict in verdicts.values())
        any_accept += 1 if cleared else 0
        not_cleared += 0 if cleared else 1
        if arm not in by_arm:
            by_arm[arm] = {"cells": 0, "anyAccept": 0, "notCleared": 0}
        by_arm[arm]["cells"] += 1
        by_arm[arm]["anyAccept"] += 1 if cleared else 0
        by_arm[arm]["notCleared"] += 0 if cleared else 1
        for vendor, verdict in verdicts.items():
            if verdict == "ACCEPT":
                accepted_by_vendor[vendor] += 1

    summary = {
        "inputs": {
            "required": str(args.required),
            "judge": str(args.judge),
            "potentialCells": str(args.potential_cells),
        },
        "totals": {
            "requiredRecords": len(required),
            "judgeRows": len(judge_rows),
            "verdictCounts": dict(verdict_counts),
        },
        "byVendorArm": {label: dict(counts) for label, counts in grouped_counts(enriched, ("vendor", "arm")).items()},
        "byDatasetVendorArm": {label: dict(counts) for label, counts in grouped_counts(enriched, ("dataset", "vendor", "arm")).items()},
        "byCorpusVendorArm": {label: dict(counts) for label, counts in grouped_counts(enriched, ("corpus", "vendor", "arm")).items()},
        "residualCells": {
            "cells": len(cells),
            "anyAccept": any_accept,
            "notCleared": not_cleared,
            "missingRequiredVerdicts": missing_required,
            "acceptedByVendor": {vendor: accepted_by_vendor[vendor] for vendor in VENDORS},
            "byArm": by_arm,
        },
    }

    json_out = args.json_out or args.out.with_suffix(".json")
    write_outputs(summary, args.out, json_out, args.overwrite)


if __name__ == "__main__":
    main()
