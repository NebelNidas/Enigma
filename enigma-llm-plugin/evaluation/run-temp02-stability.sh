#!/usr/bin/env bash
# temp=0.2 stability slice: run the api benchmark 3x at temperature 0.2 on one
# reference model (14b q6_k, graph backend, raised cap) to quantify run-to-run
# jitter -- the noise floor against which model deltas are read. Same seed => same
# 300 api targets each run; temperature perturbs generation, so exact recoveries
# that flip between runs are the jitter. Non-api buckets minimised to 1 (the harness
# treats 0 as "all", so 0 would NOT skip them). Env-only; no harness change.
# Hardened after a past data-loss bug (Codex-reviewed): stash/restore of the clean
# AUTO dir is guarded so it can never be clobbered.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT" || { echo "!! cd failed"; exit 1; }
BR=enigma-llm-plugin/build/llm-evaluation/benchmark
SAFE=qwen2.5-coder-14b-instruct_q6_k
MODEL=qwen2.5-coder-14b-instruct@q6_k
LOG=enigma-llm-plugin/build/llm-evaluation/temp02-logs
mkdir -p "$LOG"

# preflight: refuse to run if any stash or run dir is already present
for d in "${SAFE}__auto_saved_temp02" "${SAFE}_temp02_run1" "${SAFE}_temp02_run2" "${SAFE}_temp02_run3"; do
	if [ -d "$BR/$d" ]; then echo "!! stale $BR/$d exists -- resolve first, aborting"; exit 1; fi
done

echo ">> loading $MODEL --parallel 1"
SWITCH_MODEL="$MODEL" SWITCH_CTX=8192 ~/lmstudio-venv/bin/python - < enigma-llm-plugin/evaluation/switch_model.py > "$LOG/switch.log" 2>&1 \
	|| { echo "!! switch failed (see $LOG/switch.log)"; exit 1; }
grep -E "^loaded:|^error" "$LOG/switch.log" || true

# stash the clean AUTO headline dir (fatal if the move fails)
if [ -d "$BR/$SAFE" ]; then
	mv "$BR/$SAFE" "$BR/${SAFE}__auto_saved_temp02" || { echo "!! stash failed -- aborting"; exit 1; }
	echo ">> stashed clean AUTO dir"
fi

ok=1
for i in 1 2 3; do
	rm -rf "${BR:?}/${SAFE:?}"
	echo ">> temp02 run $i starting ($(date '+%T'))"
	set +e
	ENIGMA_LLM_MODEL="$MODEL" ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 ENIGMA_LLM_API_KEY=lm-studio \
		ENIGMA_LLM_TEMPERATURE=0.2 ENIGMA_LLM_MAX_TOKENS=512 \
		ENIGMA_LLM_CONTEXT_BACKEND=graph ENIGMA_LLM_MAX_PROMPT_CHARS=16000 \
		ENIGMA_LLM_BENCH_API=100 ENIGMA_LLM_BENCH_PACKAGE=1 ENIGMA_LLM_BENCH_PRIVATE=1 ENIGMA_LLM_BENCH_PRESERVATION=1 \
		ENIGMA_LLM_BENCH_SEED=1234567 \
		./gradlew --no-daemon --offline --no-configuration-cache --console=plain :enigma-llm-plugin:benchmarkObfuscation > "$LOG/run$i.log" 2>&1
	rc=$?
	set -e
	grep -aE "api\[|BUILD" "$LOG/run$i.log" | tail -6 || true
	if [ "$rc" -ne 0 ] || [ ! -d "$BR/$SAFE" ]; then
		echo "!! run $i failed (rc=$rc) or produced no output -- stopping"; ok=0; break
	fi
	mv "$BR/$SAFE" "$BR/${SAFE}_temp02_run$i"
	echo ">> temp02 run $i done -> ${SAFE}_temp02_run$i"
done

# restore the clean AUTO dir only if its destination is free (never nest/overwrite)
if [ -d "$BR/${SAFE}__auto_saved_temp02" ]; then
	if [ -d "$BR/$SAFE" ]; then
		echo "!! $BR/$SAFE exists -- NOT restoring; clean AUTO kept at ${SAFE}__auto_saved_temp02"
	else
		mv "$BR/${SAFE}__auto_saved_temp02" "$BR/$SAFE" && echo ">> restored clean AUTO dir"
	fi
fi
echo "=== temp02 done (ok=$ok, $(date '+%T')) ==="
