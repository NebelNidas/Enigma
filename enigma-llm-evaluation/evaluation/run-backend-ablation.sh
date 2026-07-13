#!/usr/bin/env bash
# Forced-backend matrix (Track d / Phase C) -- answers TWO questions on identical targets:
#
#  (Q1, the headline) Is AUTO's per-target routing optimal? Every pass logs `autoBackend` (what AUTO
#      would pick). Join the owner-best pass with the graph-best pass per target: for each target compare
#      owner-recovery vs graph-recovery and check whether the empirically better backend matches
#      autoBackend. Divergence = AUTO leaves recovery on the table. (User's idea.) FAIRNESS: graph must
#      run UNtruncated for this verdict, else graph loses only to the char cap, not to backend quality --
#      hence the raised client cap on the graph-best pass.
#
#  (Q2, the mechanism) How much of the owner>graph gap is TRUNCATION vs intrinsic backend quality?
#      Decompose with three graph passes on the same targets:
#        truncation_total   = recovery(graph K1 cap-raised) - recovery(graph K2 cap-8000)
#        client-cap slice   = recovery(graph K1 cap-raised) - recovery(graph K1 cap-8000)
#        server-ovfl slice  = recovery(graph K1 cap-8000)   - recovery(graph K2 cap-8000)
#      intrinsic backend gap (both untruncated) = recovery(owner K1) - recovery(graph K1 cap-raised)
#
# Reviewed by Codex + Grok (2026-07-07): per-backend K is a valid POLICY point but NOT a clean causal
# comparison (changes backend AND slot size); the 8000-char client cap is K-INDEPENDENT so K=1 alone is
# NOT "untruncated" -> a raised cap is required; lanes must be PHASE-SEPARATED (one shared KV pool, per-slot
# ctx is instantaneous = ctx/active-sequences-now) so passes run strictly sequentially, never overlapping.
#
# Deterministic where it matters: the three K=1 passes are single-stream (no concurrent FP-reduction jitter).
# The one K=2 pass exists only to quantify slot pressure. Model is loaded ONCE and reused for all passes
# (K and cap are harness-side env, no reload). The existing AUTO q6_k headline dir is stashed and restored
# by an EXIT trap so nothing is clobbered.
#
# Cap-raised = 24000 chars ~= 7300 tokens on qwen's tokenizer, fits the full 8192 ctx at K=1 with headroom
# for the response. Graph prompts above that (rare; check the logged promptChars) still cap -- a small residual.
#
# Usage (from fabric-enigma/):  bash enigma-llm-evaluation/evaluation/run-backend-ablation.sh
set -u

BASE_URL="${ENIGMA_LLM_BASE_URL:-http://192.168.178.120:1234/v1}"
SSH_HOST="${SWEEP_SSH_HOST:-dk-pc}"
LOAD_CTX="${SWEEP_CTX:-8192}"
MODEL="${BACKEND_ABLATION_MODEL:-qwen2.5-coder-14b-instruct@q6_k}"
# Sized so prompt + ~600 tok system/schema + 512 tok response FITS the 8192 ctx: 24000 chars (~7300 tok)
# overflowed ~26% of graph targets with HTTP 400 (Codex+Grok: that biases graph exactly on the most-
# connected symbols). 16000 chars fits for the vast majority; the analyzer reports residual overflows
# (still-too-big graph prompts) as a SEPARATE bucket, excluded from the owner-vs-graph fairness verdict.
RAISED_CAP="${BACKEND_ABLATION_RAISED_CAP:-16000}"

SWITCH_PY="enigma-llm-evaluation/evaluation/switch_model.py"
BENCH_DIR="enigma-llm-evaluation/build/llm-evaluation/benchmark"
LOG_DIR="${SWEEP_LOG_DIR:-enigma-llm-evaluation/build/llm-evaluation/backend-ablation-logs}"
mkdir -p "$LOG_DIR"

# Identical sampling to the headline sweep so target sets match across passes (paired join).
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

# Mirror the harness's Java sanitizeModel() EXACTLY: strip() + replaceAll("[^A-Za-z0-9._-]+","_").
# NOT `echo | tr`: echo's trailing newline became a trailing "_" (…q6_k_ vs Java's …q6_k), so auto_dir
# never matched the real output dir and every stash/move silently no-op'd -> passes overwrote each other
# (root cause of the 2026-07-07 data loss; confirmed by Codex+Grok). sed collapses runs like Java's "+".
safe=$(printf '%s' "$MODEL" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//; s/[^A-Za-z0-9._-]+/_/g')
auto_dir="$BENCH_DIR/$safe"
auto_bak="$BENCH_DIR/${safe}__auto_backup"

restore_auto() {
	if [ -d "$auto_bak" ]; then
		rm -rf "$auto_dir" 2>/dev/null
		mv "$auto_bak" "$auto_dir" && echo ">> restored AUTO headline dir -> $auto_dir"
	fi
}
trap restore_auto EXIT

# Pass matrix: "label backend K cap".  Ordered so Q1 (owner-best, graph-best) is answered FIRST; an early
# abort still leaves the AUTO-optimality join possible. Q2's extra graph passes come after.
# CORRECTED DESIGN (2026-07-07): the model is loaded ONCE with --parallel 1 (switch_model.py
# default) => each request gets the FULL n_ctx (8192), so NOTHING is server-truncated. Client
# concurrency (K) does NOT change the per-request context ceiling -- that is set only by the
# load-time --parallel slot count (verified empirically + Codex/Grok). The old "graph_k2_cap8000"
# slot-pressure pass was therefore void (client K=2 does not shrink per-slot) and is dropped.
# Remaining 3 passes, all K=1 sequential:
#   owner_k1         : owner  backend, cap 8000  -> untruncated owner baseline
#   graph_k1_raised  : graph  backend, cap 24000 -> untruncated graph (the AUTO-optimality arm)
#   graph_k1_cap8000 : graph  backend, cap 8000  -> client-cap truncation delta vs raised
PASSES=(
	"owner_k1          owner  1  8000"
	"graph_k1_raised   graph  1  $RAISED_CAP"
	"graph_k1_cap8000  graph  1  8000"
)

echo "=== forced-backend MATRIX: model=$MODEL  raised_cap=$RAISED_CAP ==="
echo "endpoint=$BASE_URL load_ctx=$LOAD_CTX temp=$ENIGMA_LLM_TEMPERATURE seed=$ENIGMA_LLM_BENCH_SEED"
echo "caps: api=$ENIGMA_LLM_BENCH_API pkg=$ENIGMA_LLM_BENCH_PACKAGE priv=$ENIGMA_LLM_BENCH_PRIVATE pres=$ENIGMA_LLM_BENCH_PRESERVATION"
for p in "${PASSES[@]}"; do echo "  pass: $p"; done
ab_start=$(date +%s)

echo ">> safe=[$safe]  auto_dir=$auto_dir"
# A leftover auto_bak means a PREVIOUS matrix run was interrupted before its EXIT restore ran, so it holds
# the ONLY copy of the AUTO headline. Do NOT silently rm it (the old code did) -- that would destroy the
# headline. Abort and let the operator resolve it. (Codex+Grok flagged this.)
if [ -d "$auto_bak" ]; then
	echo "!! stale backup exists: $auto_bak (interrupted prior run?). Restore/remove it manually, then re-run. Aborting."
	exit 1
fi
# Stash the AUTO headline so the forced passes cannot overwrite it.
if [ -d "$auto_dir" ]; then
	mv "$auto_dir" "$auto_bak" && echo ">> stashed AUTO result -> $auto_bak"
else
	echo ">> NOTE: no AUTO headline dir at $auto_dir -- nothing to stash/restore (Q1 join uses the forced passes only)."
fi

# Load the model ONCE (reused for every pass; K and cap are harness-side).
echo ">> switching resident model on $SSH_HOST (ctx=$LOAD_CTX) ..."
if ! ssh "$SSH_HOST" "SWITCH_MODEL=$MODEL SWITCH_CTX=$LOAD_CTX ~/lmstudio-venv/bin/python -" \
		< "$SWITCH_PY" 2>&1 | grep -vE '^source:' | tee "$LOG_DIR/switch.log" | grep -q "^loaded: "; then
	echo "!! switch/load FAILED for $MODEL -- aborting. See $LOG_DIR/switch.log"; exit 1
fi
reply=$(curl -s --max-time 90 "$BASE_URL/chat/completions" -H 'Content-Type: application/json' \
	-d "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with one word: ok\"}],\"temperature\":0,\"max_tokens\":8}" \
	| python3 -c 'import sys,json
try:
    print(json.load(sys.stdin)["choices"][0]["message"]["content"].strip())
except Exception as e:
    print("PING_ERROR", e)' 2>&1)
echo "   ping reply: $reply"
echo "$reply" | grep -q "PING_ERROR" && { echo "!! ping FAILED -- aborting"; exit 1; }

for spec in "${PASSES[@]}"; do
	read -r label backend kpar cap <<< "$spec"
	echo
	echo "############################################################"
	echo "### PASS: $label  (backend=$backend K=$kpar cap=$cap)  ($(date '+%Y-%m-%d %H:%M:%S'))"
	echo "############################################################"
	rm -rf "$auto_dir"   # clean target subdir for this pass
	dest="$BENCH_DIR/${safe}_${label}"
	if [ -d "$dest" ] && [ -z "${FORCE:-}" ]; then
		echo "!! $dest already exists -- refusing to overwrite (set FORCE=1 to replace). Aborting."; exit 1
	fi
	p_start=$(date +%s)
	# --no-configuration-cache: cheap insurance that the per-pass env (backend/cap/K) is honoured, not a
	# frozen configuration-cache snapshot (Codex+Grok). Negligible vs local LLM inference cost.
	ENIGMA_LLM_MODEL="$MODEL" \
	ENIGMA_LLM_CONTEXT_BACKEND="$backend" \
	ENIGMA_LLM_BENCH_PARALLEL_UNITS="$kpar" \
	ENIGMA_LLM_MAX_PROMPT_CHARS="$cap" \
		./gradlew --no-daemon --no-configuration-cache --console=plain :enigma-llm-evaluation:benchmarkObfuscation 2>&1 \
		| tee "$LOG_DIR/${safe}_${label}.log"
	rc=${PIPESTATUS[0]}
	p_end=$(date +%s)
	# Abort on a failed pass -- do NOT move partial output as if it were a clean pass (Codex+Grok).
	if [ "$rc" -ne 0 ]; then
		echo "!! $label gradle FAILED rc=$rc -- not moving output. See $LOG_DIR/${safe}_${label}.log. Aborting."; exit "$rc"
	fi
	# Verify the harness actually produced results (dir + expected benchmark jsonl). This is exactly the
	# silent-loss signature -- turn it from a warning into a hard abort.
	nfiles=$(find "$auto_dir" -maxdepth 1 -name '*-benchmark.jsonl' 2>/dev/null | wc -l)
	if [ ! -d "$auto_dir" ] || [ "$nfiles" -lt 1 ]; then
		echo "!! $label produced NO benchmark output at $auto_dir (nfiles=$nfiles) -- ABORTING (silent-loss guard). Check that bash safe=[$safe] matches the harness sanitizeModel dir."; exit 1
	fi
	rm -rf "$dest"; mv "$auto_dir" "$dest"
	printf 'label=%s backend=%s K=%s cap=%s model=%s date=%s files=%s\n' \
		"$label" "$backend" "$kpar" "$cap" "$MODEL" "$(date '+%F %T')" "$nfiles" > "$dest/PASS_MANIFEST.txt"
	printf ">> %-16s rc=%s in %dm%02ds (%d files) -> %s\n" "$label" "$rc" $(( (p_end-p_start)/60 )) $(( (p_end-p_start)%60 )) "$nfiles" "$dest"
done

# trap restores the AUTO dir here.
ab_end=$(date +%s)
printf "\n=== backend matrix complete in %dh%02dm (%s) ===\n" \
	$(( (ab_end-ab_start)/3600 )) $(( ((ab_end-ab_start)%3600)/60 )) "$(date '+%Y-%m-%d %H:%M:%S')"
echo "analyze:  python3 enigma-llm-evaluation/evaluation/analyze_backend_matrix.py $BENCH_DIR $safe"
