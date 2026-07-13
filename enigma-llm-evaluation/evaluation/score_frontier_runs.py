#!/usr/bin/env python3
"""Score paired Fable/Sol frontier prompt-batch outputs.

This is the Gradle-reproducible version of the scratchpad scoring used for the
M / M+C / M++ frontier comparison. It consumes only saved JSON outputs and, when
--prompt-root is provided, saved prompt batches for semantic-judge preparation.
It performs no model calls.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path


ARMS = ("M", "MC", "MPP")


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


def track_of(path: Path) -> str:
    return "structure-only" if "structure-only" in path.name else "realistic"


def normalize(value: str | None) -> str:
    return "".join(ch.lower() for ch in (value or "") if ch.isalnum())


def canonical_key(key: str) -> str:
    parts = key.split("|")
    if len(parts) >= 7 and parts[1] in {"realistic", "structure-only"}:
        return "|".join([parts[0]] + parts[2:])
    return key


def key_from_prompt_row(row: dict) -> str:
    return "|".join(str(row.get(field, -1 if field == "localIndex" else "")) for field in (
        "jar", "kind", "obfOwner", "obfName", "obfDesc", "localIndex"
    ))


def dataset_of(record: dict) -> str:
    if record.get("dataset"):
        return record["dataset"]
    jar = corpus_of(record)
    return "mc" if jar.startswith("minecraft-") else "obscure"


def corpus_of(record: dict) -> str:
    return record.get("jar") or (record.get("key", "").split("|", 1)[0] if record.get("key") else "unknown")


def score_record(record: dict) -> dict:
    suggested = record.get("suggestedName")
    alternatives = record.get("alternatives") or []
    acceptable = record.get("acceptable") or []
    acceptable_norm = {normalize(name) for name in acceptable}
    exact = suggested in acceptable
    normalized = normalize(suggested) in acceptable_norm if suggested else False
    alt_usable = any(alt in acceptable or normalize(alt) in acceptable_norm for alt in alternatives)
    return {"exact": exact, "normalized": normalized, "usable": exact or normalized or alt_usable}


def pct(num: int, den: int) -> str:
    return "n/a" if den == 0 else f"{100 * num / den:.1f}%"


def exact_mcnemar_p(left_only: int, right_only: int) -> str:
    n = left_only + right_only
    if n == 0:
        return "n/a"
    k = min(left_only, right_only)
    probability = sum(math.comb(n, i) for i in range(k + 1)) / (2 ** n)
    return f"{min(1.0, 2 * probability):.4g}"


def load_arm(outdir: Path, arm: str) -> dict[str, dict]:
    rows = {}
    for path in sorted((outdir / arm).glob("*.json")):
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            record = {"key": path.stem, "ok": False, "error": f"json-read:{type(exc).__name__}"}
        key = canonical_key(record.get("key") or path.stem)
        record["_canonicalKey"] = key
        record["_dataset"] = dataset_of(record)
        record["_corpus"] = corpus_of(record)
        record["_path"] = str(path)
        rows[key] = record
    return rows


def load_run(outdir: Path) -> dict[str, dict[str, dict]]:
    return {arm: load_arm(outdir, arm) for arm in ARMS}


def ok_records(rows: dict[str, dict]) -> dict[str, dict]:
    return {key: record for key, record in rows.items() if record.get("ok") and record.get("suggestedName")}


def counts(records: list[dict]) -> dict[str, int]:
    scored = [score_record(record) for record in records]
    return {
        "n": len(records),
        "exact": sum(1 for row in scored if row["exact"]),
        "normalized": sum(1 for row in scored if row["normalized"]),
        "usable": sum(1 for row in scored if row["usable"]),
    }


def format_counts(label: str, records: list[dict]) -> str:
    c = counts(records)
    return (
        f"{label}: n={c['n']} exact={c['exact']}/{c['n']} ({pct(c['exact'], c['n'])}) "
        f"normalized={c['normalized']}/{c['n']} ({pct(c['normalized'], c['n'])}) "
        f"usable={c['usable']}/{c['n']} ({pct(c['usable'], c['n'])})"
    )


def paired_metric(left: dict[str, dict], right: dict[str, dict], keys: list[str], metric: str) -> tuple[int, int, int, int]:
    left_scored = [score_record(left[key]) for key in keys]
    right_scored = [score_record(right[key]) for key in keys]
    left_count = sum(1 for row in left_scored if row[metric])
    right_count = sum(1 for row in right_scored if row[metric])
    left_only = sum(1 for lrow, rrow in zip(left_scored, right_scored) if lrow[metric] and not rrow[metric])
    right_only = sum(1 for lrow, rrow in zip(left_scored, right_scored) if rrow[metric] and not lrow[metric])
    return left_count, right_count, left_only, right_only


def group_keys(keys: list[str], records: dict[str, dict], attribute: str) -> dict[str, list[str]]:
    groups: dict[str, list[str]] = defaultdict(list)
    for key in keys:
        groups[records[key][attribute]].append(key)
    return {name: sorted(group) for name, group in sorted(groups.items())}


def paired_lines(title: str, left_name: str, right_name: str,
                 left: dict[str, dict], right: dict[str, dict], keys: list[str]) -> list[str]:
    lines = [f"{title}: paired {left_name} vs {right_name} n={len(keys)}"]
    for metric in ("exact", "normalized", "usable"):
        left_count, right_count, left_only, right_only = paired_metric(left, right, keys, metric)
        lines.append(
            f"  {metric}: {left_name}={left_count}/{len(keys)} ({pct(left_count, len(keys))}) "
            f"{right_name}={right_count}/{len(keys)} ({pct(right_count, len(keys))}) "
            f"delta={right_count - left_count:+d}; {left_name}-only={left_only}, "
            f"{right_name}-only={right_only}, discordant={left_only + right_only}, "
            f"exactMcNemarP={exact_mcnemar_p(left_only, right_only)}"
        )
    return lines


def write_run_report(name: str, outdir: Path, output_file: Path) -> None:
    raw = load_run(outdir)
    ok = {arm: ok_records(raw[arm]) for arm in ARMS}
    lines = [f"# {name} score report", f"root: {outdir}", "", "## Raw totals"]
    for arm in ARMS:
        lines.append(
            f"{arm}: files={len(raw[arm])} ok_with_suggestion={len(ok[arm])} "
            f"failed_or_incomplete={len(raw[arm]) - len(ok[arm])}"
        )
    lines.extend(["", "## Per-arm all ok rows"])
    for arm in ARMS:
        records = list(ok[arm].values())
        lines.append(format_counts(arm, records))
        lines.append("  by dataset:")
        for dataset in sorted({record["_dataset"] for record in records}):
            lines.append("    " + format_counts(dataset, [record for record in records if record["_dataset"] == dataset]))
        lines.append("  by corpus:")
        for corpus in sorted({record["_corpus"] for record in records}):
            lines.append("    " + format_counts(corpus, [record for record in records if record["_corpus"] == corpus]))

    lines.extend(["", "## Paired arms"])
    for left_name, right_name in (("M", "MC"), ("M", "MPP"), ("MC", "MPP")):
        keys = sorted(set(ok[left_name]) & set(ok[right_name]))
        lines.extend(paired_lines("overall", left_name, right_name, ok[left_name], ok[right_name], keys))
        lines.append("  by dataset:")
        for dataset, subkeys in group_keys(keys, ok[left_name], "_dataset").items():
            lines.extend("    " + line for line in paired_lines(dataset, left_name, right_name, ok[left_name], ok[right_name], subkeys))
        lines.append("  by corpus:")
        for corpus, subkeys in group_keys(keys, ok[left_name], "_corpus").items():
            lines.extend("    " + line for line in paired_lines(corpus, left_name, right_name, ok[left_name], ok[right_name], subkeys))
        lines.append("")

    triple = sorted(set(ok["M"]) & set(ok["MC"]) & set(ok["MPP"]))
    lines.append(f"## Triple-paired M/MC/MPP n={len(triple)}")
    for metric in ("exact", "normalized", "usable"):
        parts = []
        for arm in ARMS:
            count = sum(1 for key in triple if score_record(ok[arm][key])[metric])
            parts.append(f"{arm}={count}/{len(triple)} ({pct(count, len(triple))})")
        lines.append(f"{metric}: " + " ".join(parts))
    output_file.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_vendor_report(fable_out: Path, sol_out: Path, output_file: Path) -> None:
    fable_raw = load_run(fable_out)
    sol_raw = load_run(sol_out)
    fable_ok = {arm: ok_records(fable_raw[arm]) for arm in ARMS}
    sol_ok = {arm: ok_records(sol_raw[arm]) for arm in ARMS}
    lines = [
        "# Strict paired Fable-High vs GPT-5.6-Sol-High",
        "",
        "Pairing includes only canonical target keys where BOTH vendors have ok:true and a suggestedName for the same arm.",
        "",
    ]
    for arm in ARMS:
        keys = sorted(set(fable_ok[arm]) & set(sol_ok[arm]))
        lines.append(f"## Arm {arm}")
        lines.extend(paired_lines("overall", "Fable", "Sol", fable_ok[arm], sol_ok[arm], keys))
        lines.append("by dataset:")
        for dataset, subkeys in group_keys(keys, fable_ok[arm], "_dataset").items():
            lines.extend("  " + line for line in paired_lines(dataset, "Fable", "Sol", fable_ok[arm], sol_ok[arm], subkeys))
        lines.append("by corpus:")
        for corpus, subkeys in group_keys(keys, fable_ok[arm], "_corpus").items():
            lines.extend("  " + line for line in paired_lines(corpus, "Fable", "Sol", fable_ok[arm], sol_ok[arm], subkeys))
        lines.append("")
    output_file.write_text("\n".join(lines) + "\n", encoding="utf-8")


def compact_context(user_prompt: str) -> str:
    text = user_prompt.split("Respond with JSON only:", 1)[0].rstrip()
    marker = "Decompiled body of the target method"
    if len(text) <= 6500:
        return text
    head = text[:1400].rstrip()
    index = text.find(marker)
    if index >= 0:
        return head + "\n\n[...context truncated...]\n\n" + text[index:index + 4700].rstrip()
    return head + "\n\n[...context truncated...]\n\n" + text[-4700:].lstrip()


def load_contexts(prompt_root: Path, datasets: str, tracks: str, kinds: str) -> dict[str, dict]:
    contexts = {}
    wanted_tracks = set(split_csv(tracks))
    wanted_kinds = set(split_csv(kinds))
    for prefix in parse_datasets(datasets).values():
        directory = f"{prefix}_MC"
        for path in sorted((prompt_root / directory).glob("*-prompts.jsonl")):
            if track_of(path) not in wanted_tracks:
                continue
            with open(path, encoding="utf-8") as reader:
                for line in reader:
                    if not line.strip():
                        continue
                    row = json.loads(line)
                    if row.get("kind") not in wanted_kinds:
                        continue
                    contexts[key_from_prompt_row(row)] = {
                        "target": {key: row.get(key) for key in (
                            "jar", "kind", "slice", "obfOwner", "obfName", "obfDesc",
                            "localIndex", "expected", "acceptable"
                        )},
                        "context": compact_context(row["userPrompt"]),
                    }
    return contexts


def write_semantic_candidates(args: argparse.Namespace) -> None:
    contexts = load_contexts(args.prompt_root, args.datasets, args.tracks, args.kinds)
    fable_out = args.fable_out
    sol_out = args.sol_out
    outdir = args.out
    runs = {"fable": load_run(fable_out), "sol": load_run(sol_out)}
    all_items = []
    missing = []
    for vendor in ("fable", "sol"):
        for arm in ARMS:
            for key, record in sorted(runs[vendor][arm].items()):
                if not (record.get("ok") and record.get("suggestedName")):
                    continue
                scored = score_record(record)
                if scored["exact"] or scored["normalized"]:
                    continue
                context = contexts.get(key)
                if context is None:
                    missing.append((vendor, arm, key))
                    context = {"target": {}, "context": "(M+C prompt context not found)"}
                all_items.append({
                    "id": len(all_items),
                    "vendor": vendor,
                    "arm": arm,
                    "key": key,
                    "dataset": record["_dataset"],
                    "corpus": record["_corpus"],
                    "kind": record.get("kind"),
                    "expected": record.get("expected"),
                    "acceptable": record.get("acceptable") or [],
                    "suggested": record.get("suggestedName"),
                    "alternatives": record.get("alternatives") or [],
                    "score": scored,
                    "target": context["target"],
                    "context": context["context"],
                })

    required = []
    potential_cells = []
    by_lookup = {(item["vendor"], item["arm"], item["key"]): item for item in all_items}
    for arm in ARMS:
        for key in sorted(set(runs["fable"][arm]) | set(runs["sol"][arm])):
            statuses = {}
            possible_residual = True
            for vendor in ("fable", "sol"):
                record = runs[vendor][arm].get(key)
                if not record or not (record.get("ok") and record.get("suggestedName")):
                    statuses[vendor] = "invalid"
                elif score_record(record)["exact"] or score_record(record)["normalized"]:
                    statuses[vendor] = "auto_approved"
                    possible_residual = False
                else:
                    statuses[vendor] = "needs_judge"
            if possible_residual:
                potential_cells.append({"arm": arm, "key": key, "statuses": statuses})
                for vendor in ("fable", "sol"):
                    if statuses[vendor] == "needs_judge":
                        item = dict(by_lookup[(vendor, arm, key)])
                        item["judge_id"] = len(required)
                        required.append(item)

    with open(outdir / "semantic_judge_candidates.jsonl", "w", encoding="utf-8") as writer:
        for item in all_items:
            writer.write(json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n")
    with open(outdir / "semantic_judge_required.jsonl", "w", encoding="utf-8") as writer:
        for item in required:
            writer.write(json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n")
    with open(outdir / "semantic_judge_potential_residual_cells.jsonl", "w", encoding="utf-8") as writer:
        for item in potential_cells:
            writer.write(json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n")

    lines = [
        "# Semantic judge candidate preparation",
        f"contexts loaded: {len(contexts)}",
        f"judge candidates: {len(all_items)}",
        f"potential residual target cells: {len(potential_cells)}",
        f"required judge records: {len(required)}",
        f"missing contexts: {len(missing)}",
    ]
    for vendor in ("fable", "sol"):
        lines.append(f"## {vendor}")
        for arm in ARMS:
            rows = list(runs[vendor][arm].values())
            ok = [row for row in rows if row.get("ok") and row.get("suggestedName")]
            candidates = [item for item in all_items if item["vendor"] == vendor and item["arm"] == arm]
            lines.append(f"{arm}: files={len(rows)} ok={len(ok)} judge_candidates_non_exact_norm={len(candidates)}")
            lines.append("  candidates_by_corpus=" + json.dumps(dict(sorted(Counter(item["corpus"] for item in candidates).items()))))
    if missing:
        lines.append("## first missing contexts")
        lines.extend(str(row) for row in missing[:20])
    (outdir / "semantic_judge_candidates_summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fable-out", type=Path, required=True)
    parser.add_argument("--sol-out", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--prompt-root", type=Path)
    parser.add_argument("--datasets", default="obscure=batch,mc=mc_batch")
    parser.add_argument("--tracks", default="realistic")
    parser.add_argument("--kinds", default="METHOD")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    write_run_report("Fable-High", args.fable_out, args.out / "score_fable_high.txt")
    write_run_report("GPT-5.6-Sol-High", args.sol_out, args.out / "score_gpt_sol.txt")
    write_vendor_report(args.fable_out, args.sol_out, args.out / "paired_fable_vs_sol.txt")
    if args.prompt_root:
        write_semantic_candidates(args)


if __name__ == "__main__":
    main()
