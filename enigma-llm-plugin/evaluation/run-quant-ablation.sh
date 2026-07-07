#!/usr/bin/env bash
# Quantization-ablation runner (Track d / Phase C, side study).
#
# Isolates the pure quantization effect on a single dense 14B coder by running
# qwen2.5-coder-14b at Q5_K_M under EXACTLY the config the primary sweep used for
# its Q6_K counterpart (same seed, caps, per-slot ctx, K=2, temp=0, max_tokens).
# The Q6_K result already exists from the primary sweep (same harness commit,
# same seed 1234567) in build/llm-evaluation/benchmark/qwen2.5-coder-14b-instruct_q6_k/,
# so this runs ONLY Q5_K_M into its own sibling subdir -- it does NOT clobber the
# Q6_K headline result. Compare the two with aggregate_results.py afterwards.
# Residual run-to-run jitter (K=2 perturbs the FP reduction order -> non-bit-exact)
# is bounded separately by the temp=0.2 stability slice; note that when reading a
# 1-2 target delta between the quants.
#
# Runs concurrently with a network-bound `lms get` download: GPU/inference vs. the
# 16 Mbit/s download do not contend. switch_model.py unloads the resident model and
# loads q5_k_m (10.51 GB, fits 16GB) -- independent of the download process.
#
# Usage (from fabric-enigma/):  bash enigma-llm-plugin/evaluation/run-quant-ablation.sh
set -u

BASE_URL="${ENIGMA_LLM_BASE_URL:-http://192.168.178.120:1234/v1}"
SSH_HOST="${SWEEP_SSH_HOST:-dk-pc}"
SWITCH_CTX="${SWEEP_CTX:-8192}"        # dense 14B: 8192 (same as the q6_k primary run)

SWITCH_PY="enigma-llm-plugin/evaluation/switch_model.py"
LOG_DIR="${SWEEP_LOG_DIR:-enigma-llm-plugin/build/llm-evaluation/quant-ablation-logs}"
mkdir -p "$LOG_DIR"

# Identical to the primary sweep so the comparison is apples-to-apples.
export ENIGMA_LLM_BENCH_API="${ENIGMA_LLM_BENCH_API:-100}"
export ENIGMA_LLM_BENCH_PACKAGE="${ENIGMA_LLM_BENCH_PACKAGE:-30}"
export ENIGMA_LLM_BENCH_PRIVATE="${ENIGMA_LLM_BENCH_PRIVATE:-10}"
export ENIGMA_LLM_BENCH_PRESERVATION="${ENIGMA_LLM_BENCH_PRESERVATION:-25}"
export ENIGMA_LLM_BENCH_SEED="${ENIGMA_LLM_BENCH_SEED:-1234567}"
export ENIGMA_LLM_BASE_URL="$BASE_URL"
export ENIGMA_LLM_API_KEY="${ENIGMA_LLM_API_KEY:-lm-studio}"
export ENIGMA_LLM_TIMEOUT_SECONDS="${ENIGMA_LLM_TIMEOUT_SECONDS:-120}"
export ENIGMA_LLM_TEMPERATURE="${ENIGMA_LLM_TEMPERATURE:-0}"
export ENIGMA_LLM_MAX_TOKENS="${ENIGMA_LLM_MAX_TOKENS:-512}"
export ENIGMA_LLM_BENCH_PARALLEL_UNITS="${ENIGMA_LLM_BENCH_PARALLEL_UNITS:-2}"

# Only the new quant; q6_k already lives in the benchmark dir from the primary sweep.
ROSTER=(
	"qwen2.5-coder-14b-instruct@q5_k_m"
)

echo "=== quant ablation: ${#ROSTER[@]} model(s) ==="
echo "endpoint=$BASE_URL ctx=$SWITCH_CTX temp=$ENIGMA_LLM_TEMPERATURE max_tokens=$ENIGMA_LLM_MAX_TOKENS"
echo "caps: api=$ENIGMA_LLM_BENCH_API pkg=$ENIGMA_LLM_BENCH_PACKAGE priv=$ENIGMA_LLM_BENCH_PRIVATE pres=$ENIGMA_LLM_BENCH_PRESERVATION seed=$ENIGMA_LLM_BENCH_SEED"
echo "compare against existing: build/llm-evaluation/benchmark/qwen2.5-coder-14b-instruct_q6_k/"
sweep_start=$(date +%s)

for model in "${ROSTER[@]}"; do
	echo
	echo "############################################################"
	echo "### MODEL: $model  ($(date '+%Y-%m-%d %H:%M:%S'))"
	echo "############################################################"
	safe=$(echo "$model" | tr -c 'A-Za-z0-9._-' '_')
	mlog="$LOG_DIR/$safe.log"
	ctx="$SWITCH_CTX"

	echo ">> switching resident model on $SSH_HOST (ctx=$ctx) ..."
	if ! ssh "$SSH_HOST" "SWITCH_MODEL=$model SWITCH_CTX=$ctx ~/lmstudio-venv/bin/python -" \
			< "$SWITCH_PY" 2>&1 | grep -vE '^source:' | tee "$mlog" | grep -q "^loaded: "; then
		echo "!! switch/load FAILED for $model -- skipping. See $mlog"
		continue
	fi

	echo ">> ping ..."
	reply=$(curl -s --max-time 90 "$BASE_URL/chat/completions" -H 'Content-Type: application/json' \
		-d "{\"model\":\"$model\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with one word: ok\"}],\"temperature\":0,\"max_tokens\":8}" \
		| python3 -c 'import sys,json
try:
    print(json.load(sys.stdin)["choices"][0]["message"]["content"].strip())
except Exception as e:
    print("PING_ERROR", e)' 2>&1)
	echo "   ping reply: $reply"
	if echo "$reply" | grep -q "PING_ERROR"; then
		echo "!! ping FAILED for $model -- skipping. See $mlog"
		continue
	fi

	echo ">> benchmarkObfuscation ($model) ..."
	m_start=$(date +%s)
	ENIGMA_LLM_MODEL="$model" ./gradlew --no-daemon --console=plain \
		:enigma-llm-plugin:benchmarkObfuscation 2>&1 | tee -a "$mlog"
	rc=${PIPESTATUS[0]}
	m_end=$(date +%s)
	printf ">> %s done rc=%s in %dm%02ds\n" "$model" "$rc" $(( (m_end-m_start)/60 )) $(( (m_end-m_start)%60 ))
done

sweep_end=$(date +%s)
printf "\n=== quant ablation complete in %dh%02dm (%s) ===\n" \
	$(( (sweep_end-sweep_start)/3600 )) $(( ((sweep_end-sweep_start)%3600)/60 )) "$(date '+%Y-%m-%d %H:%M:%S')"
echo "results: enigma-llm-plugin/build/llm-evaluation/benchmark/<model>/  logs: $LOG_DIR"
