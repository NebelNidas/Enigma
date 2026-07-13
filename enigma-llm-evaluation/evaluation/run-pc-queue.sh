#!/usr/bin/env bash
# ============================================================================
# Autonomous GPU queue -- runs UNATTENDED on the PC (dk-pc) while the laptop is
# away. Everything is localhost (no LAN, no flaky internet); the Gradle build is
# offline from the copied ~/.gradle caches; model switching is LOCAL (venv python,
# no ssh). The launcher wraps this in `systemd-inhibit` so the box will not
# idle-suspend mid-run.
#
# Ordered MOST-VALUABLE-FIRST so an early stop still leaves the essential results:
#   1) backend matrix (14b q6_k)  -> AUTO-routing optimality + truncation decomposition (the headline)
#   2) qwen3-8b full benchmark    -> small/fast, another scaling point
#   3) 30B iq4_xs full benchmark  -> MoE stretch on the freshly downloaded weights (slow, CPU spill)
#   4) 30B q3_k_m full benchmark  -> 30B quant comparison (optional tail)
#
# Each single-model run writes to its own build/llm-evaluation/benchmark/<model>/
# subdir (never clobbers). The matrix sub-script manages its own dirs (stash/restore).
#
# Usage (detached, from anywhere):
#   setsid systemd-inhibit --what=sleep:idle --why="enigma GPU queue" \
#     bash ~/Dev-Env/Digitalisierungskolleg/fabric-enigma/enigma-llm-evaluation/evaluation/run-pc-queue.sh \
#     >~/pc-queue.out 2>&1 &
set -u

cd "$(dirname "$0")/../.." || exit 1   # -> fabric-enigma
SWITCH_PY="enigma-llm-evaluation/evaluation/switch_model.py"
Q_LOG_DIR="enigma-llm-evaluation/build/llm-evaluation/pc-queue-logs"
mkdir -p "$Q_LOG_DIR"
MASTER="$Q_LOG_DIR/queue.log"

# --- shared endpoint / sampling env (identical to the sweep so target sets match) ---
export ENIGMA_LLM_BASE_URL="http://127.0.0.1:1234/v1"
export SWEEP_SSH_HOST="localhost"          # for the matrix sub-script's ssh switch
export ENIGMA_LLM_API_KEY="lm-studio"
export ENIGMA_LLM_TIMEOUT_SECONDS="180"
export ENIGMA_LLM_TEMPERATURE="0"
export ENIGMA_LLM_MAX_TOKENS="512"
export ENIGMA_LLM_BENCH_API="100"
export ENIGMA_LLM_BENCH_PACKAGE="30"
export ENIGMA_LLM_BENCH_PRIVATE="10"
export ENIGMA_LLM_BENCH_PRESERVATION="25"
export ENIGMA_LLM_BENCH_SEED="1234567"

log(){ echo "[$(date '+%F %T')] $*" | tee -a "$MASTER"; }

switch_model(){ # model ctx  -> local unload/load via SDK
  SWITCH_MODEL="$1" SWITCH_CTX="$2" ~/lmstudio-venv/bin/python - < "$SWITCH_PY" 2>&1 \
    | grep -vE '^source:' | tee -a "$MASTER" | grep -q "^loaded: "
}

ping_model(){ # model
  curl -s --max-time 90 "$ENIGMA_LLM_BASE_URL/chat/completions" -H 'Content-Type: application/json' \
    -d "{\"model\":\"$1\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with one word: ok\"}],\"temperature\":0,\"max_tokens\":8}" \
    | python3 -c 'import sys,json
try: print(json.load(sys.stdin)["choices"][0]["message"]["content"].strip())
except Exception as e: print("PING_ERROR",e)' 2>&1
}

run_model(){ # model ctx K
  local model="$1" ctx="$2" kpar="$3"
  local safe; safe=$(echo "$model" | tr -c 'A-Za-z0-9._-' '_')
  log "### MODEL $model (ctx=$ctx K=$kpar)"
  if ! switch_model "$model" "$ctx"; then log "!! load FAILED $model -- skipping"; return 1; fi
  local pr; pr=$(ping_model "$model"); log "ping: $pr"
  if echo "$pr" | grep -q PING_ERROR; then log "!! ping FAILED $model -- skipping"; return 1; fi
  local t0; t0=$(date +%s)
  ENIGMA_LLM_MODEL="$model" ENIGMA_LLM_BENCH_PARALLEL_UNITS="$kpar" \
    ./gradlew --no-daemon --console=plain :enigma-llm-evaluation:benchmarkObfuscation 2>&1 \
    | tee "$Q_LOG_DIR/bench_${safe}.log" | grep -E "^(commons|gson|xz|BUILD|Endpoint)" | tee -a "$MASTER"
  log ">> $model done rc=${PIPESTATUS[0]} in $(( ($(date +%s)-t0)/60 ))m"
}

log "======================= PC QUEUE START ======================="
java -version 2>&1 | head -1 | tee -a "$MASTER"
q0=$(date +%s)

# ---- 1) BACKEND MATRIX (headline) -- self-contained, dir-safe (stash/restore) ----
log "### ITEM 1/4: backend matrix (14b q6_k)"
bash enigma-llm-evaluation/evaluation/run-backend-ablation.sh 2>&1 \
  | tee "$Q_LOG_DIR/backend-matrix.log" | grep -E "^(###|>>|===|!!|commons|gson|xz)" | tee -a "$MASTER"
log ">> matrix item done"

# ---- 2) qwen3-8b (fast scaling point) ----
# K=1: models load with --parallel 1 (full n_ctx per request), so K>1 only serializes.
log "### ITEM 2/4: qwen3-8b"
run_model "qwen3-8b" 8192 1

# ---- 3) 30B iq4_xs (MoE stretch, freshly downloaded; K=1 -- CPU spill is compute-bound) ----
log "### ITEM 3/4: qwen3-coder-30b-a3b-instruct@iq4_xs"
run_model "qwen3-coder-30b-a3b-instruct@iq4_xs" 8192 1

# ---- 4) 30B q3_k_m (30B quant comparison; optional tail) ----
log "### ITEM 4/4: qwen3-coder-30b-a3b-instruct@q3_k_m"
run_model "qwen3-coder-30b-a3b-instruct@q3_k_m" 8192 1

log "======================= PC QUEUE COMPLETE in $(( ($(date +%s)-q0)/3600 ))h$(( (($(date +%s)-q0)%3600)/60 ))m ======================="
log "analyze matrix:  python3 enigma-llm-evaluation/evaluation/analyze_backend_matrix.py enigma-llm-evaluation/build/llm-evaluation/benchmark qwen2.5-coder-14b-instruct_q6_k"
