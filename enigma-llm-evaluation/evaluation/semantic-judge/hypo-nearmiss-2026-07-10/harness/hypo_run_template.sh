#!/usr/bin/env bash
# Quality crossover: how far DOWN the commercial ladder do you go to match the best LOCAL model on
# obscure Hypo (local_30b = 15% exact)? Test the WEAKEST commercial model (Haiku) at low AND high effort.
# Gated on sonnet_hypo (both use the claude CLI -> no concurrent claude, avoids the rate-limit collapse).
set -uo pipefail
SP="/tmp/claude-1000/-home-julian-Dev-Env-Digitalisierungskolleg/32e22798-6bde-488a-b047-83d0956e3383/scratchpad"
CP="$(cat "$SP/harness_cp.txt")"
GSON="/home/julian/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
LOG="$SP/haiku_hypo.log"; : > "$LOG"; PORT=8797
OBF="$SP/hypo-obf-model"

echo "[$(date +%H:%M:%S)] waiting for SONNET_HYPO_DONE (free the claude CLI)..." | tee -a "$LOG"
for i in $(seq 1 360); do grep -q SONNET_HYPO_DONE "$SP/sonnet_hypo.log" 2>/dev/null && break; sleep 15; done
echo "[$(date +%H:%M:%S)] claude free; bridge on $PORT" | tee -a "$LOG"
java -cp "$SP/bridgeclasses:$GSON" cuchaz.enigma.llm.bridge.BridgeMain --provider cli --port $PORT >>"$LOG" 2>&1 &
BP=$!; for i in $(seq 1 20); do curl -sf "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && break; sleep 1; done
echo "[$(date +%H:%M:%S)] bridge pid=$BP up" | tee -a "$LOG"

for eff in low high; do
  dir="$SP/hypo-results/claude_haiku_$eff"; rm -rf "$dir"; mkdir -p "$dir"
  echo "[$(date +%H:%M:%S)] RUN hypo-model claude:haiku:$eff -> $dir" | tee -a "$LOG"
  ENIGMA_LLM_BASE_URL="http://127.0.0.1:$PORT/v1" ENIGMA_LLM_MODEL="claude:haiku:$eff" ENIGMA_LLM_API_KEY="bridge" \
  ENIGMA_LLM_TEMPERATURE=0 ENIGMA_LLM_MAX_TOKENS=512 ENIGMA_LLM_BENCH_SEED=1234567 \
  ENIGMA_LLM_BENCH_API=100 ENIGMA_LLM_BENCH_PACKAGE=5 ENIGMA_LLM_BENCH_PRIVATE=3 ENIGMA_LLM_BENCH_PRESERVATION=3 \
  ENIGMA_LLM_BENCH_PARALLEL_UNITS=1 ENIGMA_LLM_CONTEXT_BACKEND=auto ENIGMA_LLM_MAX_PROMPT_CHARS=8000 \
    java -cp "$CP" cuchaz.enigma.llm.LlmObfuscationBenchmarkHarness "$OBF" "$dir" > "$dir/run.out" 2>&1
  echo "[$(date +%H:%M:%S)] done haiku:$eff rc=$?" | tee -a "$LOG"
  grep -hE '^hypo-model' "$dir"/*/*-realistic-benchmark.jsonl >/dev/null 2>&1
  tail -n1 "$dir/run.out" 2>/dev/null | tr '\r' '\n' | grep 'api\[' | tail -1 | tee -a "$LOG"
done
kill $BP 2>/dev/null
echo "[$(date +%H:%M:%S)] HAIKU_HYPO_DONE" | tee -a "$LOG"
