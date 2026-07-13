#!/usr/bin/env bash
# 7B improvement loop -- DEV grid (Codex+Grok reviewed): backend x hints = 9 cells on gson+commons
# (realistic api=50/jar), temperature=0, --parallel 1, localhost. HOLDOUT (xz) is NEVER run here.
# Primary metric later = api usable; guardrails = exact not materially worse, length-error / preservation
# no regression. Each cell writes dev-bench/<cell>/<model>/*-realistic-benchmark.jsonl.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT"
# -PobfDir/-PresultsDir are file()-resolved relative to the enigma-llm-plugin SUBPROJECT, so pass ABSOLUTE.
B="$REPO_ROOT/enigma-llm-plugin/build/llm-evaluation"
DEV="$B/obfuscated-dev"
OUT="$B/dev-bench"

export ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1
export ENIGMA_LLM_MODEL=qwen2.5-coder-7b-instruct
export ENIGMA_LLM_API_KEY=lm-studio
export ENIGMA_LLM_TEMPERATURE=0
export ENIGMA_LLM_MAX_TOKENS=512
export ENIGMA_LLM_BENCH_SEED=7654321
export ENIGMA_LLM_BENCH_API=50
export ENIGMA_LLM_BENCH_PACKAGE=1
export ENIGMA_LLM_BENCH_PRIVATE=1
export ENIGMA_LLM_BENCH_PRESERVATION=1
export ENIGMA_LLM_BENCH_PARALLEL_UNITS=1

run_cell() {
  local backend="$1" hints="$2" cap="$3" label="$4"
  local dir="$OUT/$label"
  if [ -f "$dir/$ENIGMA_LLM_MODEL/gson-2.11.0-realistic-benchmark.jsonl" ] && \
     [ -f "$dir/$ENIGMA_LLM_MODEL/commons-lang3-3.14.0-realistic-benchmark.jsonl" ]; then
    echo ">> SKIP $label (already present)"; return 0
  fi
  rm -rf "$dir"
  echo ">> CELL $label : backend=$backend hints=$hints cap=$cap  $(date '+%H:%M:%S')"
  ENIGMA_LLM_CONTEXT_BACKEND="$backend" \
  ENIGMA_LLM_ANALYSIS_HINTS="$hints" \
  ENIGMA_LLM_MAX_PROMPT_CHARS="$cap" \
    ./gradlew --no-daemon --offline --console=plain --no-configuration-cache \
      :enigma-llm-plugin:benchmarkObfuscation \
      -PobfDir="$DEV" -PresultsDir="$dir" 2>&1 | grep -viE '^(> Task|BUILD|Configuration|Deprecated|You can use|See http|Endpoint|  results|  sample|  parallel)' || true
  # verify output landed
  for j in gson-2.11.0 commons-lang3-3.14.0; do
    if [ ! -f "$dir/$ENIGMA_LLM_MODEL/$j-realistic-benchmark.jsonl" ]; then
      echo "!! MISSING output for $j in $label -- ABORT"; exit 3
    fi
  done
  echo ">> done $label $(date '+%H:%M:%S')"
}

# 9-cell grid. graph cells use the raised cap 16000 (graph truncates at 8000); owner/auto use 8000.
for hints in off conservative all; do
  run_cell owner "$hints" 8000  "owner_${hints}"
  run_cell auto  "$hints" 8000  "auto_${hints}"
  run_cell graph "$hints" 16000 "graph_${hints}"
done
echo "ALL CELLS DONE $(date '+%H:%M:%S')"
