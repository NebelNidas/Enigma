# Subscription Bridge Design

This document sketches how Enigma can support subscription-backed coding models
without embedding provider web-login automation in the Enigma plugin itself.

## Goal

Keep the Enigma plugin provider-neutral:

```text
Fabric Enigma plugin
  -> OpenAI-compatible HTTP endpoint
     -> optional local bridge process
        -> official provider CLI/app-server/auth flow
```

The plugin already targets `/v1/chat/completions` and discovers models through
`GET /v1/models`. A subscription bridge should implement the same two endpoints
so it can be selected through `LLM -> API endpoint and model...` like LM Studio.

## Why Not Direct Web Subscription Auth In Enigma?

Device-code or browser login is not the hard part. The hard part is the
post-login contract:

- registered OAuth client identity,
- allowed scopes and redirect URIs,
- token refresh and local credential storage,
- workspace and subscription entitlement checks,
- provider-specific model selection,
- request, streaming, rate-limit, and error protocols,
- terms around using subscription credentials from third-party tooling.

First-party tools such as Codex and Claude Code can do this because the provider
owns the OAuth client and the backend protocol. Enigma should not read browser
cookies, scrape web apps, or directly reuse private credential caches.

## OpenCode-Like Shape

OpenCode's useful pattern is the provider layer, not hidden web scraping:

- `/connect`-style provider setup,
- credentials stored locally outside project repos,
- provider config with custom `baseURL`,
- model picker populated from the configured provider,
- special subscription integrations only where the provider/tooling supports it.

For Enigma, the equivalent should be split into two layers:

1. The Enigma plugin stays simple and only speaks OpenAI-compatible HTTP.
2. A separate bridge owns provider-specific auth and translates to the Enigma
   contract.

## Bridge Contract

Minimum HTTP surface:

```http
GET /v1/models
```

Response:

```json
{
  "object": "list",
  "data": [
    { "id": "codex:gpt-5.4", "object": "model" }
  ]
}
```

```http
POST /v1/chat/completions
Content-Type: application/json
```

Request subset:

```json
{
  "model": "codex:gpt-5.4",
  "messages": [
    { "role": "system", "content": "..." },
    { "role": "user", "content": "..." }
  ],
  "temperature": 0.2
}
```

Response subset:

```json
{
  "choices": [
    {
      "message": {
        "content": "{\"suggestedName\":\"itemCount\",\"alternatives\":[],\"confidence\":0.8,\"reasoning\":\"...\"}"
      }
    }
  ]
}
```

The bridge does not need to support the full OpenAI API. It only needs enough
for Enigma's naming request shape.

## Provider Strategy

### 1. Codex Bridge First

Preferred first subscription-backed bridge:

```text
enigma-llm-bridge
  -> starts or connects to `codex app-server`
  -> user runs `codex login` separately
  -> bridge exposes `/v1/models`
  -> bridge maps chat completion requests to Codex thread/turn requests
```

Reasons:

- `codex login` is the official ChatGPT subscription auth path for Codex.
- `codex app-server` is the documented local integration surface for rich
  Codex clients.
- Enigma does not need to handle OpenAI tokens or ChatGPT cookies.

Implemented prototype behavior:

- The bridge starts `codex app-server` over stdio for each request.
- The bridge initializes the app-server connection, starts a fresh Codex thread,
  starts one turn, collects `item/agentMessage/delta`, and returns the final
  assistant text through the OpenAI-compatible response shape.
- The bridge asks Codex for its model list through `model/list` and prefixes
  returned IDs as `codex:...`.
- Enigma still enforces strict JSON parsing and validation after the bridge
  returns text.
- The first adapter has request timeout handling; full cancellation propagation
  from Enigma batch jobs to Codex turns is still future work.
- Keep model IDs explicit, for example `codex:gpt-5.4`.

### 2. Claude Code Bridge Later

Claude Code has first-party subscription login, but a bridge must be checked
more carefully before implementation:

- use only documented CLI/SDK behavior,
- do not read Claude credential stores directly,
- verify that using Claude Code as a local bridge for another GUI is allowed,
- keep it optional and separate from the Enigma plugin.

### 3. OpenCode-Compatible Bridge Or Provider

An OpenCode-like integration is useful if OpenCode exposes a stable local server
or if a provider configured in OpenCode can be re-exposed as OpenAI-compatible
HTTP. The Enigma side should still only see `/v1/models` and
`/v1/chat/completions`.

## Suggested Implementation Milestones

1. **Documented contract**
   - Keep the Enigma plugin OpenAI-compatible.
   - Keep dynamic model discovery through `/v1/models`.
   - Document subscription bridges as optional sidecars.

2. **Bridge skeleton** (implemented as `:enigma-llm-bridge`)
   - Add a tiny standalone process, outside the Enigma plugin.
   - Implement `/healthz`, `/v1/models`, and `/v1/chat/completions`.
   - Start with a fake provider for tests.

3. **Codex adapter**
   - Require the user to run `codex login`.
   - Spawn or connect to `codex app-server`.
   - Translate one Enigma request to one Codex turn.
   - Return only the JSON content expected by Enigma.

4. **Evaluation**
   - Compare local LM Studio, Codex subscription bridge, and optionally hosted
     API providers on the same JSONL set.
   - Record latency and failure modes separately from naming quality.

## Security Notes

- Store bridge credentials outside the repository.
- Prefer provider CLIs and OS keyrings over raw token files.
- Bind local bridges to `127.0.0.1` by default.
- Add an explicit bearer token if the bridge ever listens on a LAN interface.
- Never copy or parse ChatGPT/Claude browser cookies.

## Current Prototype

The repository contains a standalone `enigma-llm-bridge` subproject. It is
intentionally separate from `enigma-llm-plugin` so Swing code does not own
provider authentication.

Run a local fake bridge for contract testing:

```sh
./gradlew :enigma-llm-bridge:run --args="--provider fake --port 8787"
```

Point Enigma at it:

```sh
ENIGMA_LLM_BASE_URL=http://127.0.0.1:8787/v1
```

The fake provider exposes one model through `/v1/models` and returns valid
Enigma suggestion JSON through `/v1/chat/completions`.

Run the Codex bridge after logging in with Codex:

```sh
codex login
./gradlew :enigma-llm-bridge:run --args="--provider codex --port 8787"
```

The Codex adapter starts `codex app-server`, creates a fresh thread/turn for
each Enigma chat-completion request, and returns the assistant text as the
OpenAI-compatible message content. The adapter also uses Codex `model/list` for
`/v1/models`; `--codex-models` is only a fallback if the app-server cannot list
models. This first adapter keeps session reuse, streaming optimization, and
Enigma-to-Codex cancellation propagation out of scope.

Debug a Codex bridge request:

```sh
./gradlew :enigma-llm-bridge:run --args="--provider codex --port 8787 --codex-trace"
```

`--codex-trace` prints app-server JSON-RPC lines to stderr and is meant for
local debugging only.

Configuration:

- `ENIGMA_LLM_BRIDGE_HOST`, default `127.0.0.1`
- `ENIGMA_LLM_BRIDGE_PORT`, default `8787`
- `ENIGMA_LLM_BRIDGE_PROVIDER`, default `fake`
- `ENIGMA_LLM_BRIDGE_AUTH_TOKEN`, optional bearer token for `/v1/*`
- `ENIGMA_LLM_BRIDGE_CODEX_COMMAND`, default `codex`
- `ENIGMA_LLM_BRIDGE_CODEX_MODELS`, default `codex:gpt-5.4`
- `ENIGMA_LLM_BRIDGE_CODEX_TIMEOUT_SECONDS`, default `180`
- `ENIGMA_LLM_BRIDGE_CODEX_TRACE`, default `false`

Equivalent CLI flags are available as `--host`, `--port`, `--provider`,
`--auth-token`, `--codex-command`, `--codex-models`, and
`--codex-timeout-seconds`. Add `--codex-trace` to enable app-server trace
logging.

Live smoke status:

- `GET /v1/models` has been verified against a logged-in local Codex CLI and
  returned real Codex model IDs.
- `POST /v1/chat/completions` has been verified against `codex:gpt-5.3-codex-spark`
  with a minimal JSON-only prompt.
