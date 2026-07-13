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
# Usage:  bash enigma-llm-evaluation/evaluation/run-obfuscation-sweep.sh
set -u

BASE_URL="${ENIGMA_LLM_BASE_URL:-http://192.168.178.120:1234/v1}"
SSH_HOST="${SWEEP_SSH_HOST:-dk-pc}"
SWITCH_CTX="${SWEEP_CTX:-8192}"        # default load ctx; per-model override below

# Per-model context length. K=2 splits the one KV pool -> each request gets ~ctx/K
# tokens; the prompt (8000-char cap) must FIT that per-slot budget or the server
# returns HTTP 400 "Context size has been exceeded". The 8000-char cap is ~3300
# tokens on qwen's tokenizer (fits 8192/2=4096 with headroom, ~0 errors) but the
# heavier deepseek tokenizer blows past 4096/slot (measured: 127x context-exceeded).
# Fix is per-model ctx, NOT a smaller char-cap (would strip information from every
# model to satisfy one tokenizer) and NOT per-model K (a throughput knob, not a fit
# knob): give each model enough ctx that it receives the FULL prompt. deepseek-v2-lite
# uses MLA (tiny KV cache) so ctx=16384 loads fine on 16GB -> 8192/slot; the dense
# qwen-14B would OOM at 16384, so it stays at 8192. Fairness: every model sees the
# same prompt text; only the token count differs by tokenizer. load_ctx is logged
# per model, and each model is verified to reach 0 context-exceeded errors -- do NOT
# compare error rates across models with different per-slot budgets. (declare -A is
# not exportable: the lookup happens in the main loop and $ctx is interpolated into
# the ssh switch command, never exported to a child shell.)
declare -A MODEL_CTX=(
	["deepseek-coder-v2-lite-instruct@q6_k"]=16384  # heavy tokenizer + MLA -> needs & affords 16384
)

SWITCH_PY="enigma-llm-evaluation/evaluation/switch_model.py"
LOG_DIR="${SWEEP_LOG_DIR:-enigma-llm-evaluation/build/llm-evaluation/sweep-logs}"
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
# Score the 6 independent jar-track units concurrently to feed the multi-slot LM Studio server (the single
# sequential client left the GPU idle between requests). Each unit stays STRICTLY sequential internally (its
# per-unit accumulator drives the order-dependent dedup); only whole units overlap. K=2, NOT 3: llama.cpp
# shares one KV pool across concurrently-active sequences, so K concurrent requests each get ctx/K tokens.
# A 14B is VRAM-capped near ctx=8192 (more -> OOM), and the real prompts reach ~3300 tokens; K=3 gives only
# ~2730/slot -> "Context size exceeded" (measured: 45/66 errors), K=2 gives ~4096/slot -> 0 errors. This
# sacrifices bit-identical greedy output (concurrent batching perturbs the FP reduction order) for through-
# put -- an explicit, user-approved trade; the sequential model-1 run is kept as a seq-vs-parallel reference.
export ENIGMA_LLM_BENCH_PARALLEL_UNITS="${ENIGMA_LLM_BENCH_PARALLEL_UNITS:-2}"

# Most-important-first so an early abort still leaves the essential models done.
ROSTER=(
	"qwen2.5-coder-14b-instruct@q6_k"      # primary headline (dense 14B coder)
	"deepseek-coder-v2-lite-instruct@q6_k" # cross-family coder
	"qwen2.5-coder-7b-instruct"            # same-family scaling floor
	"qwen3-14b"                            # 14B-class general, non-thinking (free under strict schema)
	"qwen3-coder-30b-a3b-instruct"         # MoE stretch (may not fit 16GB -> auto-skipped on failure)
)

echo "=== obfuscation sweep: ${#ROSTER[@]} models ==="
echo "endpoint=$BASE_URL ctx=$SWITCH_CTX(default) temp=$ENIGMA_LLM_TEMPERATURE max_tokens=$ENIGMA_LLM_MAX_TOKENS"
for m in "${!MODEL_CTX[@]}"; do echo "  ctx override: $m -> ${MODEL_CTX[$m]}"; done
echo "caps: api=$ENIGMA_LLM_BENCH_API pkg=$ENIGMA_LLM_BENCH_PACKAGE priv=$ENIGMA_LLM_BENCH_PRIVATE pres=$ENIGMA_LLM_BENCH_PRESERVATION seed=$ENIGMA_LLM_BENCH_SEED"
sweep_start=$(date +%s)

for model in "${ROSTER[@]}"; do
	echo
	echo "############################################################"
	echo "### MODEL: $model  ($(date '+%Y-%m-%d %H:%M:%S'))"
	echo "############################################################"
	safe=$(echo "$model" | tr -c 'A-Za-z0-9._-' '_')
	mlog="$LOG_DIR/$safe.log"

	# Per-model load ctx (default SWITCH_CTX). Validate numeric > 0: a stray non-number
	# or an accidental 0 in MODEL_CTX would NOT fall through :- (only unset/empty does),
	# so guard it explicitly rather than ship a bad contextLength to the loader.
	ctx="${MODEL_CTX[$model]:-$SWITCH_CTX}"
	if ! [[ "$ctx" =~ ^[1-9][0-9]*$ ]]; then
		echo "!! invalid ctx '$ctx' for $model -- falling back to $SWITCH_CTX"
		ctx="$SWITCH_CTX"
	fi

	# 1) Switch the resident model on the PC (unload-before-load).
	echo ">> switching resident model on $SSH_HOST (ctx=$ctx) ..."
	if ! ssh "$SSH_HOST" "SWITCH_MODEL=$model SWITCH_CTX=$ctx ~/lmstudio-venv/bin/python -" \
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
		:enigma-llm-evaluation:benchmarkObfuscation 2>&1 | tee -a "$mlog"
	rc=${PIPESTATUS[0]}
	m_end=$(date +%s)
	printf ">> %s done rc=%s in %dm%02ds\n" "$model" "$rc" $(( (m_end-m_start)/60 )) $(( (m_end-m_start)%60 ))
done

sweep_end=$(date +%s)
printf "\n=== sweep complete in %dh%02dm (%s) ===\n" \
	$(( (sweep_end-sweep_start)/3600 )) $(( ((sweep_end-sweep_start)%3600)/60 )) "$(date '+%Y-%m-%d %H:%M:%S')"
echo "results: enigma-llm-evaluation/build/llm-evaluation/benchmark/<model>/  logs: $LOG_DIR"
