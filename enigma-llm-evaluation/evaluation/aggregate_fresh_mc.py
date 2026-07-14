#!/usr/bin/env python3
"""Aggregate the fresh-Minecraft (post-cutoff memorization-control) benchmark.

Given one or more model run directories (each with M/MC/MPP arm subdirs of per-target JSON records)
and the mojmap/yarn/union ground-truth profiles, report per-(kind x arm) exact/usable rates for each
run and a paired McNemar test between two designated runs (e.g. Fable-low vs Sol-low) on the targets
both attempted. Records are joined to ground truth on the obfuscated coordinates parsed from each
record's pipe-encoded key, so a single run scores against all three profiles at no extra model cost.
"""
from __future__ import annotations

import argparse
import glob
import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path

ARMS = ("M", "MC", "MPP")
KINDS = ("CLASS", "FIELD", "METHOD", "PARAMETER")
TRACK_TOKENS = {"realistic", "structure-only"}


def normalize(value: str | None) -> str:
    return re.sub(r"[^a-z0-9]", "", (value or "").lower())


def parse_key(key: str):
    parts = key.split("|")[1:]
    if parts and parts[0] in TRACK_TOKENS:
        parts = parts[1:]
    if len(parts) < 5:
        return None
    return (parts[0], parts[1], parts[2], parts[3], parts[4])


def load_profile(path: str) -> dict:
    acc = {}
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        r = json.loads(line)
        jk = (r["kind"], r["obfOwner"], r["obfName"], r.get("obfDesc", "") or "", str(r.get("localIndex", -1)))
        names = set(r.get("acceptableRealNames") or [])
        if r.get("realName"):
            names.add(r["realName"])
        acc[jk] = {normalize(n) for n in names if n}
    return acc


def load_run_dir(run_dir: str, stats: Counter) -> dict:
    """(arm, jk) -> normalized suggested/alternative candidates."""
    out = {}
    for arm in ARMS:
        for f in glob.glob(f"{run_dir}/{arm}/*.json"):
            try:
                d = json.load(open(f))
            except Exception:
                continue
            if not isinstance(d, dict) or not d.get("key"):
                continue
            jk = parse_key(d["key"])
            if jk is None:
                continue
            if not d.get("ok") or not d.get("suggestedName"):
                stats[f"{arm}/{jk[0]} invalid"] += 1
                continue
            suggested = normalize(d.get("suggestedName"))
            alternatives = {normalize(c) for c in (d.get("alternatives") or []) if c}
            out[(arm, jk)] = {
                "suggested": suggested,
                "alternatives": alternatives,
                "candidates": ({suggested} if suggested else set()) | alternatives,
                "source": f,
            }
    return out


def merge_run(into: dict, run_dir: str, stats: Counter) -> None:
    for key, value in load_run_dir(run_dir, stats).items():
        if key in into:
            old = into[key]
            if old["suggested"] != value["suggested"] or old["alternatives"] != value["alternatives"]:
                raise SystemExit(
                    f"Conflicting duplicate result for {key}\n"
                    f"  {old['source']}\n"
                    f"  {value['source']}"
                )
            continue
        into[key] = value


def add_class_field_mc_aliases(run: dict) -> None:
    """CLASS/FIELD have no code section; MC prompts are byte-identical to M and were not re-run."""
    for (arm, jk), value in list(run.items()):
        if arm == "M" and jk[0] in {"CLASS", "FIELD"}:
            run.setdefault(("MC", jk), {**value, "source": value["source"] + " [M-as-MC-alias]"})


def score_record(record: dict, acceptable: set[str]) -> tuple[bool, bool]:
    exact = bool(record["suggested"]) and record["suggested"] in acceptable
    usable = any(candidate in acceptable for candidate in record["candidates"])
    return exact, usable


def pct(num: int, den: int) -> str:
    return "n/a" if den == 0 else f"{100 * num / den:.1f}%"


def binomial_twosided_p(successes: int, trials: int) -> float:
    if trials == 0:
        return 1.0
    lo = min(successes, trials - successes)
    tail = sum(math.comb(trials, k) for k in range(lo + 1)) / (2 ** trials)
    return min(1.0, 2 * tail)


def mcnemar_counts(ra: dict, rb: dict, acc: dict, arm: str, kind: str, metric: str) -> tuple[int, int, int, int, int]:
    common = [
        key for key in ra
        if key in rb and key[0] == arm and (kind == "ALL" or key[1][0] == kind) and key[1] in acc
    ]
    aonly = bonly = both = neither = 0
    metric_idx = 0 if metric == "exact" else 1
    for key in common:
        accepted = acc[key[1]]
        au = score_record(ra[key], accepted)[metric_idx]
        bu = score_record(rb[key], accepted)[metric_idx]
        if au and bu:
            both += 1
        elif au:
            aonly += 1
        elif bu:
            bonly += 1
        else:
            neither += 1
    return len(common), aonly, bonly, both, neither


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="append", default=[], metavar="LABEL=dir", help="repeatable")
    ap.add_argument("--profile", action="append", default=[], metavar="NAME=groundtruth.jsonl")
    ap.add_argument("--mcnemar", nargs=2, metavar=("LABEL_A", "LABEL_B"), help="paired test between two runs")
    ap.add_argument("--profile-for-mcnemar", default="union")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--no-class-field-mc-alias", action="store_true",
                    help="do not copy CLASS/FIELD M rows into MC, even though those prompts are byte-identical")
    args = ap.parse_args()

    runs = defaultdict(dict)
    run_stats = defaultdict(Counter)
    for spec in args.run:
        label, d = spec.split("=", 1)
        merge_run(runs[label.strip()], d.strip(), run_stats[label.strip()])
    runs = dict(runs)
    if not args.no_class_field_mc_alias:
        for run in runs.values():
            add_class_field_mc_aliases(run)

    profiles = {}
    for spec in args.profile:
        name, p = spec.split("=", 1)
        profiles[name.strip()] = load_profile(p.strip())

    lines = [
        "# Fresh-MC aggregate",
        "Cell format: n exact/usable (exact%=... usable%=...). Exact uses only suggestedName; usable also accepts alternatives.",
    ]
    if not args.no_class_field_mc_alias:
        lines.append("CLASS/FIELD MC rows are aliases of M rows because no code is added for those kinds.")
    lines.append("")

    for label, run in runs.items():
        counts = Counter((arm, jk[0]) for arm, jk in run)
        parts = [f"{arm}/{kind}={counts[(arm, kind)]}" for arm in ARMS for kind in KINDS if counts[(arm, kind)]]
        lines.append(f"run {label}: " + " ".join(parts))
        if run_stats[label]:
            invalid = " ".join(f"{key}={value}" for key, value in sorted(run_stats[label].items()))
            lines.append(f"  invalid/unparsed skipped: {invalid}")
    lines.append("")

    for pname, acc in profiles.items():
        lines.append(f"===== profile={pname} =====")
        header = f"{'kind':10} {'arm':4}" + "".join(f"{lbl:>30}" for lbl in runs)
        lines.append(header)
        for arm in ARMS:
            for kind in KINDS:
                cells = []
                any_n = 0
                for lbl, run in runs.items():
                    n = ex = us = 0
                    for (a, jk), record in run.items():
                        if a != arm or jk[0] != kind or jk not in acc:
                            continue
                        n += 1
                        exact, usable = score_record(record, acc[jk])
                        ex += 1 if exact else 0
                        us += 1 if usable else 0
                    any_n = max(any_n, n)
                    cells.append(f"{n}:{ex}/{us} ({pct(ex, n)}/{pct(us, n)})" if n else "-")
                if any_n:
                    lines.append(f"{kind:10} {arm:4}" + "".join(f"{c:>28}" for c in cells))
        lines.append("")

    if args.mcnemar and len(runs) >= 2:
        a, b = args.mcnemar
        acc = profiles[args.profile_for_mcnemar]
        ra, rb = runs[a], runs[b]
        lines.append(f"===== McNemar {a} vs {b} (profile={args.profile_for_mcnemar}) =====")
        for metric in ("exact", "usable"):
            lines.append(f"-- metric={metric} --")
            for arm in ARMS:
                for kind in (*KINDS, "ALL"):
                    n, aonly, bonly, both, neither = mcnemar_counts(ra, rb, acc, arm, kind, metric)
                    if not n:
                        continue
                    disc = aonly + bonly
                    chi = ((abs(aonly - bonly) - 1) ** 2) / disc if disc else 0.0
                    p = binomial_twosided_p(aonly, disc) if disc else 1.0
                    lines.append(
                        f"  {arm:4} {kind:10} n={n:>3} {a}-only={aonly:>3} {b}-only={bonly:>3} "
                        f"both={both:>3} neither={neither:>3} chi2cc={chi:.2f} exact_p={p:.4g}"
                    )
            lines.append("")

    report = "\n".join(lines).rstrip() + "\n"
    print(report)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(report, encoding="utf-8")


if __name__ == "__main__":
    main()
