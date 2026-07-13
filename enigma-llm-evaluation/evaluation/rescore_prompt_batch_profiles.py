#!/usr/bin/env python3
"""Re-score one model prompt-batch run against multiple ground-truth profiles.

The fresh-MC benchmark emits three ground-truth profiles (mojmap/yarn/union) over an IDENTICAL
sampled population and an identical obfuscated jar, so the prompts sent to the model are
profile-independent -- only the accepted names differ. That means a single (paid) model run can be
scored against all three profiles: we re-join each model record to a profile's ground truth on the
obfuscated target coordinates and recompute exact/usable, instead of trusting the `acceptable` list
baked into the record at dump time (which reflects only the dump's profile).

Join key: (kind, obfOwner, obfName, obfDesc, localIndex). The model record carries these inside its
pipe-encoded `key` field: `<jar>|[<track>|]<kind>|<obfOwner>|<obfName>|<obfDesc>|<localIndex>`.
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

ARMS = ("M", "MC", "MPP")
TRACK_TOKENS = {"realistic", "structure-only"}


def normalize(value: str | None) -> str:
    return "".join(ch.lower() for ch in (value or "") if ch.isalnum())


def parse_key(key: str) -> tuple[str, str, str, str, str] | None:
    """Return (kind, obfOwner, obfName, obfDesc, localIndex) from a run record key, tolerating the
    optional track segment that the Sol run includes but the Fable run omits."""
    parts = key.split("|")
    if len(parts) < 6:
        return None
    parts = parts[1:]  # drop <jar>
    if parts and parts[0] in TRACK_TOKENS:
        parts = parts[1:]
    if len(parts) < 5:
        return None
    kind, owner, name, desc, local = parts[0], parts[1], parts[2], parts[3], parts[4]
    return kind, owner, name, desc, local


def load_profile(path: Path) -> dict[tuple, set[str]]:
    acc: dict[tuple, set[str]] = {}
    with open(path, encoding="utf-8") as reader:
        for line in reader:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            jk = (
                row["kind"], row["obfOwner"], row["obfName"],
                row.get("obfDesc", "") or "", str(row.get("localIndex", -1)),
            )
            names = set(row.get("acceptableRealNames") or [])
            if row.get("realName"):
                names.add(row["realName"])
            acc[jk] = {normalize(n) for n in names if n}
    return acc


def score_against(record: dict, acceptable_norm: set[str]) -> dict:
    suggested = record.get("suggestedName")
    alternatives = record.get("alternatives") or []
    exact = normalize(suggested) in acceptable_norm if suggested else False
    alt = any(normalize(a) in acceptable_norm for a in alternatives if a)
    return {"exact": exact, "usable": exact or alt}


def load_run(outdir: Path) -> list[tuple[str, dict]]:
    rows = []
    for arm in ARMS:
        arm_dir = outdir / arm
        if not arm_dir.exists():
            continue
        for path in sorted(arm_dir.glob("*.json")):
            try:
                rows.append((arm, json.loads(path.read_text(encoding="utf-8"))))
            except Exception:
                continue
    return rows


def pct(num: int, den: int) -> str:
    return f"{(100.0 * num / den):.1f}%" if den else "n/a"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path, help="model prompt-batch output dir (M/MC/MPP subdirs)")
    parser.add_argument("--profile", action="append", default=[], metavar="NAME=groundtruth.jsonl",
                        help="repeatable; e.g. --profile mojmap=.../mojmap/...-groundtruth.jsonl")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    profiles = {}
    for spec in args.profile:
        name, path = spec.split("=", 1)
        profiles[name.strip()] = load_profile(Path(path.strip()))
    if not profiles:
        raise SystemExit("at least one --profile NAME=groundtruth.jsonl is required")

    run = load_run(args.run_dir)
    # agg[(profile, arm)] and agg[(profile, 'ALL')]
    agg: dict[tuple, dict] = defaultdict(lambda: {"n": 0, "exact": 0, "usable": 0, "unmatched": 0})
    for arm, record in run:
        if not record.get("key"):
            continue
        parsed = parse_key(record["key"])
        if parsed is None:
            continue
        kind, owner, name, desc, local = parsed
        jk = (kind, owner, name, desc, local)
        for pname, gt in profiles.items():
            if jk not in gt:
                agg[(pname, arm)]["unmatched"] += 1
                agg[(pname, "ALL")]["unmatched"] += 1
                continue
            s = score_against(record, gt[jk])
            for scope in (arm, "ALL"):
                a = agg[(pname, scope)]
                a["n"] += 1
                a["exact"] += s["exact"]
                a["usable"] += s["usable"]

    lines = ["# Fresh-MC per-profile re-scoring", f"run_dir: {args.run_dir}", ""]
    for pname in profiles:
        lines.append(f"## profile={pname}")
        for scope in (*ARMS, "ALL"):
            a = agg.get((pname, scope))
            if not a or a["n"] == 0:
                continue
            lines.append(
                f"  {scope:4} n={a['n']:>4} exact={a['exact']}/{a['n']} ({pct(a['exact'], a['n'])}) "
                f"usable={a['usable']}/{a['n']} ({pct(a['usable'], a['n'])}) unmatched={a['unmatched']}"
            )
        lines.append("")
    report = "\n".join(lines)
    print(report)
    if args.out:
        args.out.write_text(report + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
