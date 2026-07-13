#!/usr/bin/env python3
"""Write evaluation reports without live model calls.

This script only consumes committed JSONL and judge-verdict artifacts.
It does not call Codex, Claude, Grok, Gemini, LM Studio, or any HTTP endpoint.
Fresh model generation remains exposed through the dedicated generator tasks and
run scripts; this task is the cheap, deterministic "write the reports from
committed rows" entry point.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import shutil
import subprocess
import sys
import os
import tempfile

MODES = [
    "main-benchmark",
    "commercial-benchmark",
    "backend-matrix",
    "semantic-v2",
    "reference-ablation",
    "hypo-nearmiss",
    "fixed-jsonl",
    "report-only",
]


def run(command: list[str], out_file: Path, cwd: Path, env: dict[str, str] | None = None) -> None:
    proc = subprocess.run(command, cwd=cwd, text=True, capture_output=True, env=env)
    out_file.write_text(
        f"$ {' '.join(command)}\n\n"
        f"## stdout\n{proc.stdout}\n"
        f"## stderr\n{proc.stderr}\n"
        f"## exit={proc.returncode}\n",
        encoding="utf-8",
    )
    if proc.returncode != 0:
        raise SystemExit(f"command failed ({proc.returncode}); see {out_file}")


def confirm_targets(mode: str, targets: list[Path], overwrite: bool, dry_run: bool) -> None:
    existing = [path.resolve() for path in targets if path.exists()]
    complete = len(existing) == len(targets)
    if dry_run:
        status = "complete" if complete else ("partial" if existing else "missing")
        print(f"[dry-run] {mode}: target set is {status}")
        for path in targets:
            print(f"[dry-run]   {'exists ' if path.exists() else 'missing'} {path}")
        return
    if not existing:
        return
    label = "complete" if complete else "partial"
    message = (
        f"{mode}: {label} output already exists at the canonical target location.\n"
        + "\n".join(f"  {path}" for path in existing)
    )
    if overwrite:
        print(f"[overwrite confirmed by --overwrite]\n{message}")
        return
    if not sys.stdin.isatty():
        raise SystemExit(
            f"{message}\nRefusing to overwrite in non-interactive mode. "
            "Re-run interactively and answer the prompt, or pass --overwrite / -PreportOverwrite=true."
        )
    answer = input(f"{message}\nOverwrite these files? Type 'yes' to continue: ")
    if answer.strip().lower() != "yes":
        raise SystemExit("aborted without overwriting existing outputs")


def publish_outputs(generated: dict[Path, Path]) -> None:
    for target, source in generated.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(source), str(target))


def summarize_fixed_jsonl(evaluation_dir: Path, out_file: Path) -> None:
    lines = ["# Fixed JSONL evaluation summaries", ""]
    for path in sorted((evaluation_dir / "results").glob("*.jsonl")):
        rows = []
        with open(path, encoding="utf-8") as reader:
            for line in reader:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
        n = len(rows)
        exact = sum(1 for row in rows if row.get("exact"))
        usable = sum(1 for row in rows if row.get("usable"))
        accepted = sum(1 for row in rows if row.get("accepted"))
        failed = sum(1 for row in rows if row.get("error"))
        latencies = [row.get("latencyMillis") for row in rows if isinstance(row.get("latencyMillis"), int)]
        avg_latency = round(sum(latencies) / len(latencies), 1) if latencies else None
        by_kind = Counter(row.get("kind", "?") for row in rows)
        lines.append(
            f"- {path.name}: n={n} accepted={accepted} exact={exact} usable={usable} "
            f"failed={failed} avgLatencyMillis={avg_latency} kinds={dict(sorted(by_kind.items()))}"
        )
    out_file.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_report_only_notes(evaluation_dir: Path, out_file: Path) -> None:
    report_paths = [
        evaluation_dir / "semantic-judge" / "results_2026-07-08.txt",
        evaluation_dir / "semantic-judge" / "results-v2_2026-07-09.txt",
        evaluation_dir / "semantic-judge" / "reference-ablation-fullpool-v2_2026-07-09.txt",
        evaluation_dir / "semantic-judge" / "hypo-nearmiss-2026-07-10" / "agg_hypo_report.txt",
        evaluation_dir / "7b-loop" / "results_2026-07-08.txt",
    ]
    lines = [
        "# Report-only evaluation artifacts",
        "",
        "These historical outputs are committed as reports, but their raw per-target",
        "inputs are either already covered by another generated report in this",
        "directory, incomplete for a non-mutating replay, or not committed in this",
        "repo snapshot. They are therefore committed and citable; the notes below",
        "make the distinction explicit.",
        "",
    ]
    for path in report_paths:
        if not path.exists():
            continue
        lines.append(f"## {path.relative_to(evaluation_dir)}")
        lines.append("")
        lines.append(path.read_text(encoding="utf-8"))
        lines.append("")
    out_file.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def default_out_dir(mode: str, evaluation_dir: Path) -> Path:
    if mode in ("main-benchmark", "commercial-benchmark", "backend-matrix"):
        return evaluation_dir / "benchmark-raw-2026-07-11" / "reports" / mode
    if mode in ("semantic-v2", "reference-ablation"):
        return evaluation_dir / "semantic-judge"
    if mode == "hypo-nearmiss":
        return evaluation_dir / "semantic-judge" / "hypo-nearmiss-2026-07-10"
    if mode == "fixed-jsonl":
        return evaluation_dir / "results"
    if mode == "report-only":
        return evaluation_dir
    raise SystemExit(f"unknown mode: {mode}")


def write_mode(mode: str, out_dir: Path | None, resamples: int, overwrite: bool, dry_run: bool) -> None:
    evaluation_dir = Path(__file__).resolve().parent
    project_dir = evaluation_dir.parent
    repo_root = project_dir.parent
    benchmark_root = evaluation_dir / "benchmark-raw-2026-07-11"
    judge_dir = evaluation_dir / "semantic-judge"
    judge_prov = evaluation_dir / "judge-ablation-provenance-2026-07-11"
    hypo_dir = judge_dir / "hypo-nearmiss-2026-07-10"
    out_dir = (out_dir or default_out_dir(mode, evaluation_dir)).resolve()

    py = sys.executable
    generated: dict[Path, Path] = {}
    if mode == "main-benchmark":
        targets = [
            out_dir / "main-benchmark-summary.txt",
            out_dir / "main-benchmark-summary.json",
        ]
        confirm_targets(mode, targets, overwrite, dry_run)
        if dry_run:
            return
        out_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".write-main-", dir=out_dir) as tmp:
            tmp_dir = Path(tmp)
            run(
                [py, str(evaluation_dir / "aggregate_results.py"), str(benchmark_root / "benchmark"),
                 "--json", str(tmp_dir / "main-benchmark-summary.json"), "--resamples", str(resamples)],
                tmp_dir / "main-benchmark-summary.txt",
                repo_root,
            )
            generated = {target: tmp_dir / target.name for target in targets}
            publish_outputs(generated)
        print(f"wrote {mode} evaluation report output to {out_dir}")
        return
    elif mode == "commercial-benchmark":
        targets = [
            out_dir / "commercial-benchmark-summary.txt",
            out_dir / "commercial-benchmark-summary.json",
        ]
        confirm_targets(mode, targets, overwrite, dry_run)
        if dry_run:
            return
        out_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".write-commercial-", dir=out_dir) as tmp:
            tmp_dir = Path(tmp)
            run(
                [py, str(evaluation_dir / "aggregate_results.py"), str(benchmark_root / "commercial"),
                 "--json", str(tmp_dir / "commercial-benchmark-summary.json"), "--resamples", str(resamples)],
                tmp_dir / "commercial-benchmark-summary.txt",
                repo_root,
            )
            generated = {target: tmp_dir / target.name for target in targets}
            publish_outputs(generated)
        print(f"wrote {mode} evaluation report output to {out_dir}")
        return
    elif mode == "backend-matrix":
        prefixes = (
            "qwen2.5-coder-14b-instruct_q6_k",
            "qwen3-8b",
            "qwen3-coder-30b-a3b-instruct_iq4_xs",
        )
        targets = [out_dir / f"backend-matrix-{prefix}.txt" for prefix in prefixes]
        confirm_targets(mode, targets, overwrite, dry_run)
        if dry_run:
            return
        out_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".write-backend-", dir=out_dir) as tmp:
            tmp_dir = Path(tmp)
            for prefix in prefixes:
                run(
                    [py, str(evaluation_dir / "analyze_backend_matrix.py"), str(benchmark_root / "benchmark"), prefix],
                    tmp_dir / f"backend-matrix-{prefix}.txt",
                    repo_root,
                )
            generated = {target: tmp_dir / target.name for target in targets}
            publish_outputs(generated)
        print(f"wrote {mode} evaluation report output to {out_dir}")
        return
    elif mode == "semantic-v2":
        targets = [
            out_dir / "results-v2_current.txt",
        ]
        confirm_targets(mode, targets, overwrite, dry_run)
        if dry_run:
            return
        out_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".write-semantic-v2-", dir=out_dir) as tmp:
            tmp_dir = Path(tmp)
            env = dict(os.environ)
            env["JUDGE_DIR"] = str(judge_dir)
            env["BENCH_DIR"] = str(benchmark_root / "benchmark")
            run([py, str(judge_dir / "agg_v2.py")], tmp_dir / "results-v2_current.txt", repo_root, env=env)
            generated = {target: tmp_dir / target.name for target in targets}
            publish_outputs(generated)
        print(f"wrote {mode} evaluation report output to {out_dir}")
        return
    elif mode == "reference-ablation":
        targets = [
            out_dir / "reference-ablation-fullpool-rerun_2026-07-11.txt",
        ]
        confirm_targets(mode, targets, overwrite, dry_run)
        if dry_run:
            return
        out_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".write-reference-ablation-", dir=out_dir) as tmp:
            tmp_dir = Path(tmp)
            env = dict(os.environ)
            env["JUDGE_DIR"] = str(judge_prov)
            env["ABLFULL_GROK_FILE"] = str(judge_prov / "ablfull_grok_rerun.json")
            run([py, str(judge_dir / "ablfull_combine.py")], tmp_dir / targets[0].name, repo_root, env=env)
            generated = {target: tmp_dir / target.name for target in targets}
            publish_outputs(generated)
        print(f"wrote {mode} evaluation report output to {out_dir}")
        return
    elif mode == "hypo-nearmiss":
        targets = [
            out_dir / "agg_hypo_report.txt",
            out_dir / "agg_hypo_report.json",
        ]
        confirm_targets(mode, targets, overwrite, dry_run)
        if dry_run:
            return
        out_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".write-hypo-", dir=out_dir) as tmp:
            tmp_dir = Path(tmp)
            env = dict(os.environ)
            env["HYPO_AGG_OUT_DIR"] = str(tmp_dir)
            run([py, str(hypo_dir / "agg_hypo.py")], tmp_dir / "agg_hypo_stdout.txt", repo_root, env=env)
            generated = {target: tmp_dir / target.name for target in targets}
            publish_outputs(generated)
        print(f"wrote {mode} evaluation report output to {out_dir}")
        return
    elif mode == "fixed-jsonl":
        targets = [
            out_dir / "fixed-jsonl-summaries.txt",
        ]
        confirm_targets(mode, targets, overwrite, dry_run)
        if dry_run:
            return
        out_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".write-fixed-jsonl-", dir=out_dir) as tmp:
            tmp_dir = Path(tmp)
            summarize_fixed_jsonl(evaluation_dir, tmp_dir / "fixed-jsonl-summaries.txt")
            generated = {target: tmp_dir / target.name for target in targets}
            publish_outputs(generated)
        print(f"wrote {mode} evaluation report output to {out_dir}")
        return
    elif mode == "report-only":
        targets = [
            out_dir / "report-only-artifacts.txt",
        ]
        confirm_targets(mode, targets, overwrite, dry_run)
        if dry_run:
            return
        out_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".write-report-only-", dir=out_dir) as tmp:
            tmp_dir = Path(tmp)
            write_report_only_notes(evaluation_dir, tmp_dir / "report-only-artifacts.txt")
            generated = {target: tmp_dir / target.name for target in targets}
            publish_outputs(generated)
        print(f"wrote {mode} evaluation report output to {out_dir}")
        return
    else:
        raise SystemExit(f"unknown mode: {mode}")

    publish_outputs(generated)
    print(f"wrote {mode} evaluation report output to {out_dir}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path)
    parser.add_argument("--only", choices=MODES + ["all"], default="all")
    parser.add_argument("--resamples", type=int, default=5000)
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    out_dir = args.out.resolve() if args.out else None

    if args.only == "all":
        for mode in MODES:
            mode_out = out_dir / mode if out_dir else None
            write_mode(mode, mode_out, args.resamples, args.overwrite, args.dry_run)
    else:
        write_mode(args.only, out_dir, args.resamples, args.overwrite, args.dry_run)


if __name__ == "__main__":
    main()
