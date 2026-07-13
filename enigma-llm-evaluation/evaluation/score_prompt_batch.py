#!/usr/bin/env python3
"""Score arm-based prompt-batch output directories."""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path
import statistics


ARMS = ("M", "MC", "MPP")


def normalize(value: str | None) -> str:
    return "".join(ch.lower() for ch in (value or "") if ch.isalnum())


def score_record(record: dict) -> dict:
    suggested = record.get("suggestedName")
    alternatives = record.get("alternatives") or []
    acceptable = record.get("acceptable") or []
    acceptable_norm = {normalize(name) for name in acceptable}
    exact = suggested in acceptable
    normalized = normalize(suggested) in acceptable_norm if suggested else False
    alt_usable = any(alt in acceptable or normalize(alt) in acceptable_norm for alt in alternatives)
    return {"exact": exact, "normalized": normalized, "usable": exact or normalized or alt_usable}


def load_arm(outdir: Path, arm: str) -> dict[str, dict]:
    rows = {}
    arm_dir = outdir / arm
    if not arm_dir.exists():
        return rows
    for path in sorted(arm_dir.glob("*.json")):
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            rows[path.stem] = {"key": path.stem, "ok": False, "error": f"json-read:{type(exc).__name__}"}
            continue
        rows[record.get("key") or path.stem] = record
    return rows


def pct(num: int, den: int) -> str:
    return "n/a" if den == 0 else f"{100 * num / den:.1f}%"


def ok_records(rows: dict[str, dict]) -> dict[str, dict]:
    return {key: record for key, record in rows.items() if record.get("ok") and record.get("suggestedName")}


def corpus(record: dict) -> str:
    return record.get("jar") or (record.get("key", "").split("|", 1)[0] if record.get("key") else "unknown")


def counts(records: list[dict]) -> dict:
    scored = [score_record(record) for record in records]
    return {
        "n": len(records),
        "exact": sum(1 for row in scored if row["exact"]),
        "normalized": sum(1 for row in scored if row["normalized"]),
        "usable": sum(1 for row in scored if row["usable"]),
    }


def format_counts(label: str, records: list[dict]) -> list[str]:
    c = counts(records)
    return [
        f"{label}: n={c['n']} exact={c['exact']}/{c['n']} ({pct(c['exact'], c['n'])}) "
        f"normalized={c['normalized']}/{c['n']} ({pct(c['normalized'], c['n'])}) "
        f"usable={c['usable']}/{c['n']} ({pct(c['usable'], c['n'])})"
    ]


def latency_line(label: str, records: list[dict]) -> str:
    values = sorted(record.get("latencyMs") for record in records if isinstance(record.get("latencyMs"), int))
    if not values:
        return f"{label}: latency n=0"
    median = statistics.median(values)
    p90 = values[int(0.9 * (len(values) - 1))]
    return f"{label}: latency n={len(values)} medianMs={median:.0f} p90Ms={p90} maxMs={values[-1]}"


def paired_lines(left_name: str, right_name: str, left: dict[str, dict], right: dict[str, dict]) -> list[str]:
    keys = sorted(set(left) & set(right))
    lines = [f"\npaired {left_name} vs {right_name} n={len(keys)}"]
    for metric in ("exact", "usable"):
        left_scored = [score_record(left[key]) for key in keys]
        right_scored = [score_record(right[key]) for key in keys]
        left_count = sum(1 for row in left_scored if row[metric])
        right_count = sum(1 for row in right_scored if row[metric])
        left_only = sum(1 for lrow, rrow in zip(left_scored, right_scored) if lrow[metric] and not rrow[metric])
        right_only = sum(1 for lrow, rrow in zip(left_scored, right_scored) if rrow[metric] and not lrow[metric])
        lines.append(
            f"  {metric}: {left_name}={left_count}/{len(keys)} ({pct(left_count, len(keys))}) "
            f"{right_name}={right_count}/{len(keys)} ({pct(right_count, len(keys))}) delta={right_count - left_count:+d}; "
            f"McNemar {left_name}-only={left_only}, {right_name}-only={right_only}, discordant={left_only + right_only}"
        )
    return lines


def score_dir(outdir: Path) -> str:
    raw = {arm: load_arm(outdir, arm) for arm in ARMS}
    ok = {arm: ok_records(raw[arm]) for arm in ARMS}
    lines = [f"# {outdir}"]
    for arm in ARMS:
        failed = len(raw[arm]) - len(ok[arm])
        lines.append(f"{arm}: files={len(raw[arm])} ok={len(ok[arm])} failed_or_incomplete={failed}")
        lines.extend(format_counts(f"  {arm} all-ok", list(ok[arm].values())))
        lines.append(latency_line(f"  {arm} all-ok", list(ok[arm].values())))
        by_corpus: dict[str, list[dict]] = defaultdict(list)
        for record in ok[arm].values():
            by_corpus[corpus(record)].append(record)
        for jar in sorted(by_corpus):
            lines.extend(format_counts(f"    {arm} {jar}", by_corpus[jar]))
    for left_name, right_name in (("M", "MC"), ("M", "MPP"), ("MC", "MPP")):
        lines.extend(paired_lines(left_name, right_name, ok[left_name], ok[right_name]))
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("outdirs", nargs="+", type=Path)
    args = parser.parse_args()
    print("\n\n".join(score_dir(path.resolve()) for path in args.outdirs))


if __name__ == "__main__":
    main()
