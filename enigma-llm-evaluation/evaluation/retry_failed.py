#!/usr/bin/env python3
"""Targeted re-run of infra-errored benchmark rows.

Two phases, driven separately so the GPU/network step in between is the real harness:

  1. build-manifest  scan ONE model's result dir, select rows whose error is a *retryable* infra
                     failure (timeout / context-overflow / 5xx), and emit a manifest JSONL keyed on
                     {jar, track, kind, obfOwner, obfName, obfDesc, localIndex}. LLM-quality failures
                     (invalid-identifier, name-collision) are NOT retryable and are excluded.

  2. merge           after the harness re-ran those symbols (ENIGMA_LLM_BENCH_RETRY_MANIFEST=<manifest>
                     into a fresh --retry dir), splice the fresh rows back over the errored ones into a
                     NEW --out dir. The canonical/preserved raw is never modified.

Error taxonomy mirrors the 2026-07-12 diagnosis (see LLM_ENIGMA_JOURNAL). A run that is mostly errored
(e.g. the superseded claude_sonnet_high bridge-502 run) is refused by build-manifest unless --force,
since it should be discarded, not row-retried.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

# --- error taxonomy (matches the diagnosis) --------------------------------------------------------

RETRYABLE = "retryable-infra"
LLM_QUALITY = "llm-quality"       # legit failure, retry won't help deterministically
UNKNOWN = "unknown-error"         # unclassified — NOT auto-retried (could be a harness/plugin/validation bug)

# A whole run this errored is systemically broken/superseded (e.g. claude_sonnet_high at 94%), not
# row-retryable — build-manifest refuses it without --force. A merely-bad run (grok ~44%) is fine to retry.
DEAD_RUN_FRACTION = 0.80


def classify(error: str) -> str:
    """Bucket an error message. Empty/None -> '' (clean row). Only EXPLICITLY-known transient infra is
    RETRYABLE; anything unrecognised is UNKNOWN and left out of the manifest, so a new harness/plugin/
    validation bug is never silently re-billed as if it were a timeout."""
    if not error:
        return ""
    e = error
    if ("Invalid Java identifier suggested" in e or "already held by a higher model-score" in e
            or "already preferred by an equal-score" in e
            or "Suggested name repeats the owner class name" in e
            or "did not contain content" in e):  # reasoning-model empty content = config, not a row retry
        return LLM_QUALITY
    if ("HttpTimeoutException" in e or "request timed out" in e
            or "Context size has been exceeded" in e
            or "HTTP 500" in e or "HTTP 502" in e or "HTTP 503" in e
            or "produced no suggestedName JSON" in e  # a transient bridge 502 payload
            or "ConnectException" in e or "Connection refused" in e or "Connection reset" in e
            or "GOAWAY" in e or "reset by peer" in e):
        return RETRYABLE
    return UNKNOWN


def track_of(filename: str) -> str | None:
    stem = filename[:-len("-benchmark.jsonl")] if filename.endswith("-benchmark.jsonl") else filename
    if stem.endswith("-realistic"):
        return "realistic"
    if stem.endswith("-structure-only"):
        return "structure-only"
    return None


def sym_key(row: dict) -> tuple:
    return (row["kind"], row["obfOwner"], row["obfName"], row["obfDesc"], row.get("localIndex", -1))


# --- phase 1: build-manifest -----------------------------------------------------------------------

def build_manifest(results_dir: Path, out: Path, force: bool) -> int:
    files = sorted(results_dir.glob("*-benchmark.jsonl"))
    if not files:
        sys.exit(f"no *-benchmark.jsonl under {results_dir}")

    manifest = []
    cats = Counter()
    per_track = Counter()
    total_rows = 0
    total_err = 0

    for f in files:
        track = track_of(f.name)
        if track is None:
            print(f"  ! skip (cannot parse track): {f.name}", file=sys.stderr)
            continue
        with f.open() as fh:
            for lineno, line in enumerate(fh, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError as ex:
                    print(f"  ! skip (malformed JSON, {f.name}:{lineno}): {ex}", file=sys.stderr)
                    continue
                total_rows += 1
                err = row.get("error")
                if not err:
                    continue
                total_err += 1
                c = classify(str(err))
                cats[c] += 1
                if c != RETRYABLE:
                    continue
                manifest.append({
                    "jar": row["jar"],
                    "track": track,
                    "kind": row["kind"],
                    "obfOwner": row["obfOwner"],
                    "obfName": row["obfName"],
                    "obfDesc": row["obfDesc"],
                    "localIndex": row.get("localIndex", -1),
                })
                per_track[f"{row['jar']}::{track}"] += 1

    if total_rows == 0:
        print(f"no rows under {results_dir} — nothing to do.")
        return 0
    err_frac = total_err / total_rows
    print(f"scanned {total_rows} rows, {total_err} errored ({100 * err_frac:.1f}%)")
    for c, n in cats.most_common():
        print(f"  {n:5d}  {c}")
    if err_frac >= DEAD_RUN_FRACTION and not force:
        sys.exit(f"\nREFUSED: run is {100 * err_frac:.0f}% errored (>= {100 * DEAD_RUN_FRACTION:.0f}%) — it "
                 f"looks systemically broken/superseded, not row-retryable. Re-run with --force to override.")
    if not manifest:
        print("\nno retryable rows — nothing to do.")
        return 0

    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w") as fh:
        for m in manifest:
            fh.write(json.dumps(m) + "\n")
    print(f"\nwrote {len(manifest)} retry targets -> {out}")
    print("per unit:")
    for unit, n in sorted(per_track.items()):
        print(f"  {n:4d}  {unit}")
    return len(manifest)


# --- phase 2: merge --------------------------------------------------------------------------------

def merge(canonical_dir: Path, retry_dir: Path, out_dir: Path) -> None:
    # Never let the patched output land on top of the preserved raw or the retry dir.
    out_r = out_dir.resolve()
    if out_r == canonical_dir.resolve() or out_r == retry_dir.resolve():
        sys.exit("refusing: --out must differ from --canonical and --retry "
                 "(the preserved raw must never be overwritten)")
    can_files = sorted(canonical_dir.glob("*-benchmark.jsonl"))
    if not can_files:
        sys.exit(f"no *-benchmark.jsonl under {canonical_dir}")
    out_dir.mkdir(parents=True, exist_ok=True)

    tot_recovered = tot_still = tot_missing = tot_collisions = 0

    for cf in can_files:
        rf = retry_dir / cf.name
        retry_rows: dict[tuple, dict] = {}
        if rf.exists():
            with rf.open() as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        r = json.loads(line)
                    except json.JSONDecodeError as ex:
                        print(f"  ! skip retry row (malformed, {rf.name}): {ex}", file=sys.stderr)
                        continue
                    retry_rows[sym_key(r)] = r

        can_rows = []
        with cf.open() as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    can_rows.append(json.loads(line))
                except json.JSONDecodeError as ex:
                    print(f"  ! skip canonical row (malformed, {cf.name}): {ex}", file=sys.stderr)

        # Names already held by CLEAN canonical rows, scoped by (owner, kind) so identical simple names in
        # DIFFERENT classes don't false-collide. This re-imposes the per-unit dedup the isolated retry run
        # could not see: a retried row duplicating a name a clean canonical row already holds must NOT be
        # credited (a full run's plugin would have rejected the lower-scored duplicate).
        claimed = {(r["obfOwner"], r["kind"], r["suggested"])
                   for r in can_rows if not r.get("error") and r.get("suggested")}

        out_rows = []
        recovered = still = missing = collisions = 0
        for r in can_rows:
            if r.get("error") and sym_key(r) in retry_rows:
                fresh = retry_rows[sym_key(r)]
                if not fresh.get("error"):
                    scope = (fresh["obfOwner"], fresh["kind"], fresh.get("suggested"))
                    if fresh.get("suggested") and scope in claimed:
                        # collides with a clean canonical name in the same class -> keep it errored
                        collided = dict(fresh)
                        collided["suggested"] = None
                        collided["error"] = ("RetryDedup: suggested name collides with a name already held by "
                                             "a clean canonical row in the same class; kept as error.")
                        collided["exact"] = collided["normalized"] = collided["usable"] = False
                        out_rows.append(collided)
                        collisions += 1
                        continue
                    if fresh.get("suggested"):
                        claimed.add(scope)  # a recovered name now holds the slot too
                    out_rows.append(fresh)
                    recovered += 1
                    continue
                still += 1  # retry also errored -> keep the retried row (fresher error) but count it
                out_rows.append(fresh)
                continue
            if r.get("error") and classify(str(r.get("error"))) == RETRYABLE and sym_key(r) not in retry_rows:
                missing += 1  # was in scope but no fresh row produced
            out_rows.append(r)

        with (out_dir / cf.name).open("w") as fh:
            for r in out_rows:
                fh.write(json.dumps(r) + "\n")

        if recovered or still or missing:
            print(f"{cf.name}: recovered={recovered} still-errored={still} "
                  f"missing-from-retry={missing} name-collisions={collisions}")
        tot_recovered += recovered
        tot_still += still
        tot_missing += missing
        tot_collisions += collisions

    print(f"\nTOTAL recovered={tot_recovered} still-errored={tot_still} "
          f"missing-from-retry={tot_missing} name-collisions={tot_collisions}")
    if tot_missing:
        print(f"WARNING: {tot_missing} retryable canonical row(s) had NO fresh retry row — the patched dir is "
              f"INCOMPLETE. Re-run the retry harness for the missing units before treating this as final.")
    print(f"patched results -> {out_dir}  (canonical raw untouched)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    bm = sub.add_parser("build-manifest", help="scan one model's result dir -> retry manifest")
    bm.add_argument("--results", type=Path, required=True, help="a model's benchmark result dir")
    bm.add_argument("--out", type=Path, required=True, help="manifest JSONL to write")
    bm.add_argument("--force", action="store_true", help="build even if the run is mostly dead-5xx")

    mg = sub.add_parser("merge", help="splice fresh retry rows over errored canonical rows")
    mg.add_argument("--canonical", type=Path, required=True, help="original (preserved) result dir")
    mg.add_argument("--retry", type=Path, required=True, help="dir the retry harness run wrote")
    mg.add_argument("--out", type=Path, required=True, help="new patched result dir")

    args = ap.parse_args()
    if args.cmd == "build-manifest":
        build_manifest(args.results, args.out, args.force)
    else:
        merge(args.canonical, args.retry, args.out)


if __name__ == "__main__":
    main()
