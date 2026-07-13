#!/usr/bin/env bash
# Cache-off ablation: run the api benchmark once with the whole-jar duplicate-name
# dedup/tie-break DISABLED (ENIGMA_LLM_DISABLE_SUGGESTION_CACHE=1), same config as the
# existing cache-on graph_k1_raised pass (14b q6_k, graph backend, cap 16000, temp 0,
# seed 1234567) so the two are directly comparable: delta = the dedup's effect on
# exact recovery. Engine gate reviewed by Codex+Grok; default-off leaves product runs
# unchanged. Hardened dir stash/restore (guards) like run-temp02-stability.sh.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT" || { echo "!! cd failed"; exit 1; }
BR=enigma-llm-plugin/build/llm-evaluation/benchmark
SAFE=qwen2.5-coder-14b-instruct_q6_k
MODEL=qwen2.5-coder-14b-instruct@q6_k
DEST="${SAFE}_graph_k1_raised_cacheoff"
LOG=enigma-llm-plugin/build/llm-evaluation/cacheoff-logs
mkdir -p "$LOG"

for d in "${SAFE}__auto_saved_cacheoff" "$DEST"; do
	if [ -d "$BR/$d" ]; then echo "!! stale $BR/$d exists -- resolve first, aborting"; exit 1; fi
done

echo ">> rebuilding plugin (picks up the cache-off engine gate)"
./gradlew --no-daemon --offline --console=plain :enigma-llm-plugin:compileJava > "$LOG/build.log" 2>&1 \
	|| { echo "!! build failed (see $LOG/build.log)"; exit 1; }

echo ">> loading $MODEL --parallel 1"
SWITCH_MODEL="$MODEL" SWITCH_CTX=8192 ~/lmstudio-venv/bin/python - < enigma-llm-plugin/evaluation/switch_model.py > "$LOG/switch.log" 2>&1 \
	|| { echo "!! switch failed"; exit 1; }
grep -E "^loaded:|^error" "$LOG/switch.log" || true

# stash the clean AUTO headline dir (fatal if the move fails)
if [ -d "$BR/$SAFE" ]; then
	mv "$BR/$SAFE" "$BR/${SAFE}__auto_saved_cacheoff" || { echo "!! stash failed -- aborting"; exit 1; }
	echo ">> stashed clean AUTO dir"
fi

rm -rf "${BR:?}/${SAFE:?}"
echo ">> cache-off run starting ($(date '+%T'))"
set +e
ENIGMA_LLM_MODEL="$MODEL" ENIGMA_LLM_BASE_URL=http://127.0.0.1:1234/v1 ENIGMA_LLM_API_KEY=lm-studio \
	ENIGMA_LLM_DISABLE_SUGGESTION_CACHE=1 \
	ENIGMA_LLM_TEMPERATURE=0 ENIGMA_LLM_MAX_TOKENS=512 \
	ENIGMA_LLM_CONTEXT_BACKEND=graph ENIGMA_LLM_MAX_PROMPT_CHARS=16000 \
	ENIGMA_LLM_BENCH_API=100 ENIGMA_LLM_BENCH_PACKAGE=1 ENIGMA_LLM_BENCH_PRIVATE=1 ENIGMA_LLM_BENCH_PRESERVATION=1 \
	ENIGMA_LLM_BENCH_SEED=1234567 \
	./gradlew --no-daemon --offline --no-configuration-cache --console=plain :enigma-llm-plugin:benchmarkObfuscation > "$LOG/run.log" 2>&1
rc=$?
set -e
grep -aE "api\[|BUILD" "$LOG/run.log" | tail -6 || true

if [ "$rc" -eq 0 ] && [ -d "$BR/$SAFE" ]; then
	mv "$BR/$SAFE" "$BR/$DEST" && echo ">> cache-off output -> $DEST"
else
	echo "!! cache-off run failed (rc=$rc) or no output"
fi

# restore the clean AUTO dir only if its destination is free
if [ -d "$BR/${SAFE}__auto_saved_cacheoff" ]; then
	if [ -d "$BR/$SAFE" ]; then
		echo "!! $BR/$SAFE exists -- NOT restoring; clean AUTO kept at ${SAFE}__auto_saved_cacheoff"
	else
		mv "$BR/${SAFE}__auto_saved_cacheoff" "$BR/$SAFE" && echo ">> restored clean AUTO dir"
	fi
fi
echo "=== cache-off done ($(date '+%T')) ==="
