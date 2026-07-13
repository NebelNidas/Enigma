#!/usr/bin/env bash
# 7B loop: confirm the DEV winner (graph+conservative+cap16000 + Codex prompt-extension) on the xz HOLDOUT.
# Compares none vs codex-ext on xz (never touched during tuning). Paired win/loss.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT"
B="$REPO_ROOT/enigma-llm-plugin/build/llm-evaluation"
HOLD="$B/obfuscated-holdout"; OUT="$B/holdout-ext"
SP="${SEVENB_PROMPT_EXTENSION_DIR:-$SCRIPT_DIR}"
export ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 ENIGMA_LLM_MODEL=qwen2.5-coder-7b-instruct ENIGMA_LLM_API_KEY=lm-studio
export ENIGMA_LLM_TEMPERATURE=0 ENIGMA_LLM_MAX_TOKENS=512 ENIGMA_LLM_BENCH_SEED=7654321
export ENIGMA_LLM_BENCH_API=150 ENIGMA_LLM_BENCH_PACKAGE=1 ENIGMA_LLM_BENCH_PRIVATE=1 ENIGMA_LLM_BENCH_PRESERVATION=1 ENIGMA_LLM_BENCH_PARALLEL_UNITS=1
export ENIGMA_LLM_CONTEXT_BACKEND=graph ENIGMA_LLM_ANALYSIS_HINTS=conservative ENIGMA_LLM_MAX_PROMPT_CHARS=16000
run() { local label="$1" extf="$2"; local dir="$OUT/$label"; rm -rf "$dir"; local ext=""; [ -n "$extf" ] && ext="$(cat "$extf")";
  echo ">> HOLDOUT $label $(date '+%H:%M:%S')";
  ENIGMA_LLM_PROMPT_EXTENSION="$ext" ./gradlew --no-daemon --offline --console=plain --no-configuration-cache \
    :enigma-llm-plugin:benchmarkObfuscation -PobfDir="$HOLD" -PresultsDir="$dir" 2>&1 | grep -viE '^(> Task|BUILD|Config|Deprecat|You can|See http|Endpoint|  results|  sample|  parallel)' || true
  [ -f "$dir/$ENIGMA_LLM_MODEL/xz-1.9-realistic-benchmark.jsonl" ] || { echo "!! MISSING xz $label"; exit 3; }
  echo ">> done $label $(date '+%H:%M:%S')"; }
run none ""
run codex "$SP/ext_codex.txt"
echo "HOLDOUT DONE $(date '+%H:%M:%S')"
