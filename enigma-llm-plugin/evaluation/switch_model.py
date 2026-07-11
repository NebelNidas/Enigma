#!/usr/bin/env python3
"""Switch the model loaded in LM Studio for a benchmark run.

Runs on the machine hosting LM Studio (the "PC") using the lmstudio Python SDK,
which is installed in ~/lmstudio-venv (python3.12). The repo is not checked out
on the PC, so this script is normally invoked by piping it over stdin with the
parameters passed as environment variables, e.g.:

    ssh dk-pc "SWITCH_MODEL=qwen3-8b SWITCH_CTX=16384 ~/lmstudio-venv/bin/python -" \
        < enigma-llm-plugin/evaluation/switch_model.py

Actions (chosen via SWITCH_MODEL / first CLI arg):
    <model-key>     unload every loaded LLM, then load <model-key> with the
                    requested context length; prints the loaded identifier.
    --list          print downloaded + loaded models, then exit.
    --unload-all    unload every loaded LLM, then exit.

Environment / args:
    SWITCH_MODEL    target model key (or pass as argv[1]).
    SWITCH_CTX      context length in tokens for the load (default 16384).

Non-thinking note: qwen3 hybrid models (qwen3-14b, qwen3-8b) do NOT need a
`/no_think` switch here. The Java evaluation harness sends a strict json_schema
response_format, whose grammar constrains generation from the first token, so
the model cannot emit a <think> block at all -- reasoning is confined to the
bounded `reasoning` JSON field. This was verified empirically; see the HANDOFF.
This script therefore only manages *which* model is resident, not its sampling.

Downloaded models are never deleted -- only loaded/unloaded.
"""
import os
import sys
import time

import lmstudio as lms


def _target():
    if len(sys.argv) > 1 and sys.argv[1].strip():
        return sys.argv[1].strip()
    return os.environ.get("SWITCH_MODEL", "").strip()


def _context_length():
    raw = os.environ.get("SWITCH_CTX", "").strip()
    if not raw:
        return 16384
    return int(raw)


def _parallel():
    # llama.cpp/LM Studio partitions the KV cache into `--parallel` static slots, each
    # capped at ~n_ctx/slots -- this is a LOAD-TIME setting, NOT controlled by client
    # concurrency (verified empirically + Codex/Grok, 2026-07-07). Default 1 => one slot
    # gets the FULL n_ctx, so large prompts are not silently server-truncated. The SDK
    # LlmLoadModelConfig has no parallelism field, so we load via the `lms` CLI instead.
    raw = os.environ.get("SWITCH_PARALLEL", "").strip()
    if not raw:
        return 1
    return int(raw)


def _gpu():
    # GPU offload ratio passed straight to `lms load --gpu`: "off", "max", or a 0.0-1.0
    # fraction of layers to place on the GPU. When unset we omit the flag and let LM Studio
    # auto-decide. Needed for models that ALMOST fit VRAM (e.g. gemma-4-31b Q4_K_M = 18.7 GB
    # weights + 1.2 GB mmproj on a 16 GB card): the auto-heuristic offloads too aggressively
    # and the Vulkan queue OOM-crashes mid-run, so we cap the ratio explicitly.
    return os.environ.get("SWITCH_GPU", "").strip()


def _unload_all(client):
    unloaded = []
    for m in client.llm.list_loaded():
        ident = m.identifier
        client.llm.unload(ident)
        unloaded.append(ident)
    return unloaded


def main():
    target = _target()

    with lms.Client() as client:
        if target == "--list":
            print("downloaded:")
            for m in client.llm.list_downloaded():
                print("  ", getattr(m, "model_key", m))
            print("loaded:")
            for m in client.llm.list_loaded():
                print("  ", m.identifier)
            return 0

        if target == "--unload-all":
            print("unloaded:", _unload_all(client))
            return 0

        if not target:
            print("ERROR: no target model (set SWITCH_MODEL or pass as argv[1])", file=sys.stderr)
            return 2

        ctx = _context_length()
        par = _parallel()
        gpu = _gpu()
        import subprocess
        lms_bin = os.path.expanduser("~/.lmstudio/bin/lms")
        # Unload via the CLI, not the SDK: `lms unload --all` reliably clears suffixed (":2")
        # and JIT-spawned instances that client.llm.list_loaded() can miss, avoiding a stale
        # parallel-N instance shadowing the freshly loaded one under the same model id.
        subprocess.run([lms_bin, "unload", "--all"], capture_output=True, text=True)
        print(f"unloaded: (lms unload --all)")
        print(f"loading {target} (contextLength={ctx}, parallel={par}, gpu={gpu or 'auto'}) via lms CLI ...")
        t0 = time.monotonic()
        # The SDK cannot set the parallel slot count (no field on LlmLoadModelConfig), and
        # that count determines the per-request context ceiling (n_ctx/slots). So load via
        # the `lms` CLI, which exposes --parallel. --parallel 1 => full n_ctx per request.
        load_cmd = [lms_bin, "load", target, "--context-length", str(ctx), "--parallel", str(par), "-y"]
        if gpu:
            load_cmd += ["--gpu", gpu]
        proc = subprocess.run(load_cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            sys.stderr.write(proc.stdout[-800:] + "\n" + proc.stderr[-800:] + "\n")
            print(f"error: lms load failed for {target} (rc={proc.returncode})")
            return 1
        loaded = [m.identifier for m in client.llm.list_loaded()]
        ident = next((i for i in loaded if i == target), loaded[0] if loaded else target)
        print(f"loaded: {ident} in {time.monotonic() - t0:.1f}s")
        print(f"resident now: {loaded}")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
