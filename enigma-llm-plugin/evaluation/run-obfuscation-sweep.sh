#!/usr/bin/env bash
# Round-trip deobfuscation sweep driver (Track d / Phase C).
#
# Runs the LlmObfuscationBenchmarkHarness once per model in ROSTER. The Gradle
# harness runs HERE (laptop, java-17) and hits LM Studio on the PC over LAN; the
# resident model is switched on the PC via switch_model.py (unload-before-load,
# single 16GB GPU). Each model writes into its own results subdir
# (build/llm-evaluation/benchmark/<sanitized-model>/), so runs never clobber
# each other. Per-bucket seeded sampling (see the harness) keeps the sampled
# target set IDENTICAL across models and both tracks (paired comparisons).
#
# ABORT: Ctrl-C (or kill the process). Results are written incrementally per
# jar-track, and every completed model's subdir is final -- aborting only loses
# the in-progress jar-track. Re-running restarts from the first model
# (overwriting); reorder / trim ROSTER to resume where you stopped.
#
# Usage:  bash enigma-llm-plugin/evaluation/run-obfuscation-sweep.sh
set -u

BASE_URL="${ENIGMA_LLM_BASE_URL:-http://192.168.178.120:1234/v1}"
SSH_HOST="${SWEEP_SSH_HOST:-dk-pc}"
SWITCH_CTX="${SWEEP_CTX:-8192}"
SWITCH_PY="enigma-llm-plugin/evaluation/switch_model.py"
LOG_DIR="${SWEEP_LOG_DIR:-enigma-llm-plugin/build/llm-evaluation/sweep-logs}"
mkdir -p "$LOG_DIR"

# Per-bucket sample caps (per jar, per track). api is the headline slice.
export ENIGMA_LLM_BENCH_API="${ENIGMA_LLM_BENCH_API:-100}"
export ENIGMA_LLM_BENCH_PACKAGE="${ENIGMA_LLM_BENCH_PACKAGE:-30}"
export ENIGMA_LLM_BENCH_PRIVATE="${ENIGMA_LLM_BENCH_PRIVATE:-10}"
export ENIGMA_LLM_BENCH_PRESERVATION="${ENIGMA_LLM_BENCH_PRESERVATION:-25}"
export ENIGMA_LLM_BENCH_SEED="${ENIGMA_LLM_BENCH_SEED:-1234567}"
export ENIGMA_LLM_BASE_URL="$BASE_URL"
export ENIGMA_LLM_API_KEY="${ENIGMA_LLM_API_KEY:-lm-studio}"
export ENIGMA_LLM_TIMEOUT_SECONDS="${ENIGMA_LLM_TIMEOUT_SECONDS:-120}"
# Primary run is reproducible: temperature=0 (deterministic scoring), wider response budget so a chatty
# reasoning field never truncates the strict JSON (finish_reason=length would deflate recovery). The
# interactive product keeps its 0.2 / 384 defaults -- these override only the sweep. A small temp=0.2
# stability slice is run separately afterwards to quantify run-to-run jitter.
export ENIGMA_LLM_TEMPERATURE="${ENIGMA_LLM_TEMPERATURE:-0}"
export ENIGMA_LLM_MAX_TOKENS="${ENIGMA_LLM_MAX_TOKENS:-512}"

# Most-important-first so an early abort still leaves the essential models done.
ROSTER=(
	"qwen2.5-coder-14b-instruct@q6_k"      # primary headline (dense 14B coder)
	"deepseek-coder-v2-lite-instruct@q6_k" # cross-family coder
	"qwen2.5-coder-7b-instruct"            # same-family scaling floor
	"qwen3-14b"                            # 14B-class general, non-thinking (free under strict schema)
	"qwen3-coder-30b-a3b-instruct"         # MoE stretch (may not fit 16GB -> auto-skipped on failure)
)

echo "=== obfuscation sweep: ${#ROSTER[@]} models ==="
echo "endpoint=$BASE_URL ctx=$SWITCH_CTX temp=$ENIGMA_LLM_TEMPERATURE max_tokens=$ENIGMA_LLM_MAX_TOKENS"
echo "caps: api=$ENIGMA_LLM_BENCH_API pkg=$ENIGMA_LLM_BENCH_PACKAGE priv=$ENIGMA_LLM_BENCH_PRIVATE pres=$ENIGMA_LLM_BENCH_PRESERVATION seed=$ENIGMA_LLM_BENCH_SEED"
sweep_start=$(date +%s)

for model in "${ROSTER[@]}"; do
	echo
	echo "############################################################"
	echo "### MODEL: $model  ($(date '+%Y-%m-%d %H:%M:%S'))"
	echo "############################################################"
	safe=$(echo "$model" | tr -c 'A-Za-z0-9._-' '_')
	mlog="$LOG_DIR/$safe.log"

	# 1) Switch the resident model on the PC (unload-before-load).
	echo ">> switching resident model on $SSH_HOST ..."
	if ! ssh "$SSH_HOST" "SWITCH_MODEL=$model SWITCH_CTX=$SWITCH_CTX ~/lmstudio-venv/bin/python -" \
			< "$SWITCH_PY" 2>&1 | grep -vE '^source:' | tee "$mlog" | grep -q "^loaded: "; then
		echo "!! switch/load FAILED for $model -- skipping (likely does not fit VRAM). See $mlog"
		continue
	fi

	# 2) Ping the model so we do not waste an hour on a dead endpoint.
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

	# 3) Run the benchmark for this model (writes to its own per-model subdir).
	echo ">> benchmarkObfuscation ($model) ..."
	m_start=$(date +%s)
	ENIGMA_LLM_MODEL="$model" ./gradlew --no-daemon --console=plain \
		:enigma-llm-plugin:benchmarkObfuscation 2>&1 | tee -a "$mlog"
	rc=${PIPESTATUS[0]}
	m_end=$(date +%s)
	printf ">> %s done rc=%s in %dm%02ds\n" "$model" "$rc" $(( (m_end-m_start)/60 )) $(( (m_end-m_start)%60 ))
done

sweep_end=$(date +%s)
printf "\n=== sweep complete in %dh%02dm (%s) ===\n" \
	$(( (sweep_end-sweep_start)/3600 )) $(( ((sweep_end-sweep_start)%3600)/60 )) "$(date '+%Y-%m-%d %H:%M:%S')"
echo "results: enigma-llm-plugin/build/llm-evaluation/benchmark/<model>/  logs: $LOG_DIR"
