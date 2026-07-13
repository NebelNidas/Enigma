#!/usr/bin/env python3
"""Reproduce preserved evaluation reports without live model calls.

This script only consumes committed/preserved JSONL and judge-verdict artifacts.
It does not call Codex, Claude, Grok, Gemini, LM Studio, or any HTTP endpoint.
Fresh model generation remains exposed through the dedicated generator tasks and
run scripts; this task is the cheap, deterministic "can I re-derive the old
numbers from preserved rows?" entry point.
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


def is_build_llm_evaluation_path(path: Path) -> bool:
    parts = path.resolve().parts
    for idx in range(len(parts) - 1):
        if parts[idx] == "build" and parts[idx + 1] == "llm-evaluation":
            return True
    return False


def reset_out_dir(path: Path) -> None:
    path = path.resolve()
    if not is_build_llm_evaluation_path(path):
        raise SystemExit(f"refusing to clean output outside build/llm-evaluation: {path}")
    marker = path / ".reproduced-evaluation-owned"
    if path.exists():
        if any(path.iterdir()) and not marker.exists():
            raise SystemExit(
                f"refusing to clean unmarked non-empty output directory: {path}\n"
                "Use a fresh build/llm-evaluation subdirectory or remove/check it manually first."
            )
        shutil.rmtree(path)
    path.mkdir(parents=True)
    marker.write_text(
        "Owned by reproduce_preserved_reports.py; safe to regenerate offline derived reports here.\n",
        encoding="utf-8",
    )


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
        "# Report-only preserved artifacts",
        "",
        "These historical outputs are committed as reports, but their raw per-target",
        "inputs are either already reproduced by another generated report in this",
        "directory, incomplete for a non-mutating replay, or not committed in this",
        "repo snapshot. They are therefore preserved and citable; the notes below",
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


def write_manifest(out_dir: Path, mode: str, preserved: Path) -> None:
    manifest = {
        "mode": mode,
        "out": str(out_dir),
        "preserved_root": str(preserved),
        "reports": sorted(path.name for path in out_dir.iterdir() if path.is_file() and not path.name.startswith(".")),
        "live_model_calls": False,
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def reproduce_mode(mode: str, out_dir: Path, resamples: int) -> None:
    evaluation_dir = Path(__file__).resolve().parent
    project_dir = evaluation_dir.parent
    repo_root = project_dir.parent
    preserved = evaluation_dir / "benchmark-raw-2026-07-11"
    judge_dir = evaluation_dir / "semantic-judge"
    judge_prov = evaluation_dir / "judge-ablation-provenance-2026-07-11"
    hypo_dir = judge_dir / "hypo-nearmiss-2026-07-10"

    reset_out_dir(out_dir)

    py = sys.executable
    if mode == "main-benchmark":
        run(
            [py, str(evaluation_dir / "aggregate_results.py"), str(preserved / "benchmark"),
             "--json", str(out_dir / "aggregate-preserved-main.json"), "--resamples", str(resamples)],
            out_dir / "aggregate-preserved-main.txt",
            repo_root,
        )
    elif mode == "commercial-benchmark":
        run(
            [py, str(evaluation_dir / "aggregate_results.py"), str(preserved / "commercial"),
             "--json", str(out_dir / "aggregate-preserved-commercial.json"), "--resamples", str(resamples)],
            out_dir / "aggregate-preserved-commercial.txt",
            repo_root,
        )
    elif mode == "backend-matrix":
        for prefix in (
            "qwen2.5-coder-14b-instruct_q6_k",
            "qwen3-8b",
            "qwen3-coder-30b-a3b-instruct_iq4_xs",
        ):
            run(
                [py, str(evaluation_dir / "analyze_backend_matrix.py"), str(preserved / "benchmark"), prefix],
                out_dir / f"backend-matrix-{prefix}.txt",
                repo_root,
            )
    elif mode == "semantic-v2":
        env = dict(os.environ)
        env["JUDGE_DIR"] = str(judge_dir)
        env["BENCH_DIR"] = str(preserved / "benchmark")
        run([py, str(judge_dir / "agg_v2.py")], out_dir / "semantic-judge-v2.txt", repo_root, env=env)
    elif mode == "reference-ablation":
        env = dict(os.environ)
        env["JUDGE_DIR"] = str(judge_prov)
        env["ABLFULL_GROK_FILE"] = str(judge_prov / "ablfull_grok_rerun.json")
        run([py, str(judge_dir / "ablfull_combine.py")], out_dir / "reference-ablation-fullpool.txt", repo_root, env=env)
    elif mode == "hypo-nearmiss":
        env = dict(os.environ)
        env["HYPO_AGG_OUT_DIR"] = str(out_dir)
        run([py, str(hypo_dir / "agg_hypo.py")], out_dir / "hypo-nearmiss.txt", repo_root, env=env)
    elif mode == "fixed-jsonl":
        summarize_fixed_jsonl(evaluation_dir, out_dir / "fixed-jsonl-summaries.txt")
    elif mode == "report-only":
        write_report_only_notes(evaluation_dir, out_dir / "report-only-artifacts.txt")
    else:
        raise SystemExit(f"unknown mode: {mode}")

    write_manifest(out_dir, mode, preserved)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--only", choices=MODES + ["all"], default="all")
    parser.add_argument("--resamples", type=int, default=5000)
    args = parser.parse_args()

    out_dir = args.out.resolve()

    if args.only == "all":
        for mode in MODES:
            reproduce_mode(mode, out_dir / mode, args.resamples)
        print(f"wrote all preserved evaluation reproduction reports below {out_dir}")
    else:
        reproduce_mode(args.only, out_dir, args.resamples)
        print(f"wrote {args.only} preserved evaluation reproduction report to {out_dir}")


if __name__ == "__main__":
    main()
