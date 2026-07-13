#!/usr/bin/env bash
# 7B loop Teil B -- PROMPT_EXTENSION A/B on the DEV winner config (graph + conservative + cap16000).
# Larger n (api=150/jar = 300 api) for signal, per Codex/Grok "n>=200-300, paired win/loss". temp=0.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT"
B="$REPO_ROOT/enigma-llm-plugin/build/llm-evaluation"
DEV="$B/obfuscated-dev"
OUT="$B/dev-ext"
SP="${SEVENB_PROMPT_EXTENSION_DIR:-$SCRIPT_DIR}"

export ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1
export ENIGMA_LLM_MODEL=qwen2.5-coder-7b-instruct
export ENIGMA_LLM_API_KEY=lm-studio
export ENIGMA_LLM_TEMPERATURE=0
export ENIGMA_LLM_MAX_TOKENS=512
export ENIGMA_LLM_BENCH_SEED=7654321
export ENIGMA_LLM_BENCH_API=150
export ENIGMA_LLM_BENCH_PACKAGE=1
export ENIGMA_LLM_BENCH_PRIVATE=1
export ENIGMA_LLM_BENCH_PRESERVATION=1
export ENIGMA_LLM_BENCH_PARALLEL_UNITS=1
# winner config, held fixed across the A/B:
export ENIGMA_LLM_CONTEXT_BACKEND=graph
export ENIGMA_LLM_ANALYSIS_HINTS=conservative
export ENIGMA_LLM_MAX_PROMPT_CHARS=16000

run_cell() {
  local label="$1" extfile="$2"
  local dir="$OUT/$label"
  if [ -f "$dir/$ENIGMA_LLM_MODEL/gson-2.11.0-realistic-benchmark.jsonl" ] && \
     [ -f "$dir/$ENIGMA_LLM_MODEL/commons-lang3-3.14.0-realistic-benchmark.jsonl" ]; then
    echo ">> SKIP $label"; return 0; fi
  rm -rf "$dir"
  local ext=""
  [ -n "$extfile" ] && ext="$(cat "$extfile")"
  echo ">> CELL $label ext=${extfile:-none} $(date '+%H:%M:%S')"
  ENIGMA_LLM_PROMPT_EXTENSION="$ext" \
    ./gradlew --no-daemon --offline --console=plain --no-configuration-cache \
      :enigma-llm-plugin:benchmarkObfuscation -PobfDir="$DEV" -PresultsDir="$dir" \
      2>&1 | grep -viE '^(> Task|BUILD|Configuration|Deprecated|You can|See http|Endpoint|  results|  sample|  parallel)' || true
  for j in gson-2.11.0 commons-lang3-3.14.0; do
    [ -f "$dir/$ENIGMA_LLM_MODEL/$j-realistic-benchmark.jsonl" ] || { echo "!! MISSING $j in $label"; exit 3; }
  done
  echo ">> done $label $(date '+%H:%M:%S')"
}

run_cell none  ""
run_cell grok  "$SP/ext_grok.txt"
run_cell codex "$SP/ext_codex.txt"
echo "EXT A/B DONE $(date '+%H:%M:%S')"
