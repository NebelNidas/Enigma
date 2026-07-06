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
        print(f"unloaded: {_unload_all(client)}")
        print(f"loading {target} (contextLength={ctx}) ...")
        t0 = time.monotonic()
        try:
            handle = client.llm.load_new_instance(target, config={"contextLength": ctx})
        except Exception:
            handle = client.llm.load_new_instance(target, config=lms.LlmLoadModelConfig(context_length=ctx))
        ident = handle.identifier
        print(f"loaded: {ident} in {time.monotonic() - t0:.1f}s")
        print(f"resident now: {[m.identifier for m in client.llm.list_loaded()]}")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
