# aicoin-proxy

A reverse HTTP proxy built directly on Netty (no Spring, no web framework —
raw `ServerBootstrap`/`Bootstrap` channel pipelines for both the inbound
server and outbound upstream client). Clients call it at **exactly the same
path** a real LLM provider would use; a request header (`X-AI`) picks which
provider/upstream to use, and the proxy injects **its own** paid API key
into the forwarded request — clients never hold or send provider
credentials. The caller's aicoin wallet id doubles as their API key (sent
as `X-Api-Key`), which the proxy validates against the aicoin node — and
gates on a positive balance — before forwarding. It relays the upstream
response verbatim and asynchronously reports a cost event to the `aicoin`
chain node for every successful call. It also exposes three small
proxy-side endpoints: `GET /price`, `GET /free-coins/available`, and `GET
/health` — none of which require `X-Api-Key`.

This document mirrors the shared `CONTRACT.md` at the repo root but is
meant to stand on its own.

## Running

```
./gradlew run
```

Listens on `server.port` (default `8080`).

## Configuration

Config is YAML, loaded from the path in the `AICOIN_PROXY_CONFIG` env var if
set, otherwise from the bundled `src/main/resources/application.yaml`
(reproduced below). Every field can additionally be overridden by its own
env var, which always wins over both the YAML file and the hardcoded
default (env > YAML > default).

```yaml
server:
  port: 8080                      # AICOIN_PROXY_PORT
aicoin:
  eventsUrl: http://localhost:9944/events   # AICOIN_PROXY_AICOIN_EVENTS_URL
  priceUrl: http://localhost:9944/price     # AICOIN_PROXY_AICOIN_PRICE_URL
  balanceUrlBase: http://localhost:9944     # AICOIN_PROXY_AICOIN_BALANCE_URL_BASE (used as {balanceUrlBase}/balance/{walletId} for wallet-id validation)
providers:
  openai:
    baseUrl: https://api.openai.com          # AICOIN_PROXY_OPENAI_BASEURL
    apiKey: ""                               # AICOIN_PROXY_OPENAI_APIKEY  (proxy's own paid key, injected into every forwarded request)
    authHeader: Authorization                # AICOIN_PROXY_OPENAI_AUTHHEADER
    authPrefix: "Bearer "                    # AICOIN_PROXY_OPENAI_AUTHPREFIX
  anthropic:
    baseUrl: https://api.anthropic.com       # AICOIN_PROXY_ANTHROPIC_BASEURL
    apiKey: ""                               # AICOIN_PROXY_ANTHROPIC_APIKEY
    authHeader: x-api-key                    # AICOIN_PROXY_ANTHROPIC_AUTHHEADER
    authPrefix: ""                           # AICOIN_PROXY_ANTHROPIC_AUTHPREFIX
  google:
    baseUrl: https://generativelanguage.googleapis.com  # AICOIN_PROXY_GOOGLE_BASEURL
    apiKey: ""                               # AICOIN_PROXY_GOOGLE_APIKEY
    authAsQueryParam: true                   # AICOIN_PROXY_GOOGLE_AUTHASQUERYPARAM
    authQueryParamName: key                  # AICOIN_PROXY_GOOGLE_AUTHQUERYPARAMNAME
  mistral:
    baseUrl: https://api.mistral.ai          # AICOIN_PROXY_MISTRAL_BASEURL
    apiKey: ""                               # AICOIN_PROXY_MISTRAL_APIKEY
    authHeader: Authorization                # AICOIN_PROXY_MISTRAL_AUTHHEADER
    authPrefix: "Bearer "                    # AICOIN_PROXY_MISTRAL_AUTHPREFIX
  cohere:
    baseUrl: https://api.cohere.ai           # AICOIN_PROXY_COHERE_BASEURL
    apiKey: ""                               # AICOIN_PROXY_COHERE_APIKEY
    authHeader: Authorization                # AICOIN_PROXY_COHERE_AUTHHEADER
    authPrefix: "Bearer "                    # AICOIN_PROXY_COHERE_AUTHPREFIX
  elevenlabs:
    baseUrl: https://api.elevenlabs.io        # AICOIN_PROXY_ELEVENLABS_BASEURL
    apiKey: ""                               # AICOIN_PROXY_ELEVENLABS_APIKEY
    authHeader: xi-api-key                   # AICOIN_PROXY_ELEVENLABS_AUTHHEADER
    authPrefix: ""                           # AICOIN_PROXY_ELEVENLABS_AUTHPREFIX
  stability:
    baseUrl: https://api.stability.ai        # AICOIN_PROXY_STABILITY_BASEURL
    apiKey: ""                               # AICOIN_PROXY_STABILITY_APIKEY
    authHeader: Authorization                # AICOIN_PROXY_STABILITY_AUTHHEADER
    authPrefix: "Bearer "                    # AICOIN_PROXY_STABILITY_AUTHPREFIX
pricing:
  costPerTokenUsd: 0.000002       # AICOIN_PROXY_COST_PER_TOKEN_USD
  defaultCostUsdPerCall: 0.001    # AICOIN_PROXY_DEFAULT_COST_USD
freeCoins:
  counterFile: free-coins-counter.txt   # AICOIN_PROXY_FREE_COINS_COUNTER_FILE — bundled classpath/resource file, single integer, admin-managed via git push + CI redeploy
health:
  windowSize: 50                  # AICOIN_PROXY_HEALTH_WINDOW_SIZE — how many of each provider's most recent forwarded calls GET /health tracks
```

`baseUrl` may be `http://` (plain — used by tests/mock servers) or
`https://` (TLS via `SslContextBuilder.forClient()`).

Each provider is configured with either `authHeader`+`authPrefix` (the
common case: the apiKey goes into a request header, e.g. `Authorization:
Bearer <key>`, Anthropic's `x-api-key: <key>`, or ElevenLabs' `xi-api-key:
<key>`) **or** `authAsQueryParam`+`authQueryParamName` (Google: the apiKey
goes into a URL query parameter, `?key=<key>`).

`elevenlabs` and `stability` exist so client apps that call voice
(ElevenLabs) or image (Stability) generation, not just text, can route
through the wallet too — OpenAI's own image generation (DALL-E) already
goes through the existing `openai` entry, since it's the same
`api.openai.com` host.

## Routing — same path, header selects the provider, proxy owns the upstream key

The client calls the proxy at **exactly the same path** a real provider
would use (e.g. `POST /v1/chat/completions`) — only the domain changes to
the proxy's. There is no path-prefix stripping. A request header `X-AI:
<provider>` (one of `openai`, `anthropic`, `google`, `mistral`, `cohere`,
`elevenlabs`, `stability`, case-insensitive) tells the proxy which
upstream/config to use:

1. The proxy reads `X-AI` and looks up the matching `providers.<name>`
   config. A missing or unknown value returns:

   ```
   400 {"error":"missing or unknown X-AI header"}
   ```

2. The `X-AI` header is **removed** before forwarding — the upstream
   provider never sees it.
3. The **same method + same path/query + same body** is forwarded to
   `providers.<name>.baseUrl`, with all original client headers preserved
   *except*: `Host`/`Content-Length` (recomputed), the raw `Authorization`
   header, `X-Api-Key`, and whatever the client sent in that provider's
   configured `authHeader`. In their place, the proxy injects **its own**
   `apiKey` as that provider's paid credential — via `authHeader`+
   `authPrefix` (e.g. `Authorization: Bearer <key>`), or as a
   `authQueryParamName` query parameter when `authAsQueryParam` is true
   (e.g. Google's `?key=<key>`). The client needs no provider key of its
   own — only `X-Api-Key` (its aicoin wallet id) for billing identification
   and wallet validation, see "Auth" below. Any key/credential the client
   did send for that provider is discarded, never forwarded.

## Auth — wallet id IS the API key, gated on a positive balance

There is no separate provider key or API key concept for the client — the
caller's aicoin wallet id **doubles as their API key**. Every proxied
request (i.e. everything except `GET /price`, `GET /free-coins/available`,
and `GET /health`) requires a header:

```
X-Api-Key: <walletId>
```

Missing (or blank/whitespace-only) header:

```
401 {"error":"missing X-Api-Key (wallet id)"}
```

Before forwarding to the upstream AI provider, the proxy validates the
wallet id over plain HTTP (no subprocess — a Netty client `Bootstrap`, same
mechanism as `UpstreamForwarder`/`PriceForwarder`):

```
GET {aicoin.balanceUrlBase}/balance/{walletId}
```

- If that call **fails or times out** (aicoin node unreachable, connection
  refused, or no response within the read timeout), the proxy responds to
  the original client with:

  ```
  503 {"error":"could not validate wallet"}
  ```

  and does **not** forward the request to the AI provider — no upstream
  call is made and no cost event is ever emitted for it.
- If it **succeeds** (any 2xx response), the proxy reads the `balance`
  field from the response body and gates on it — this is deliberately a
  simple binary gate, not per-call metering: a successful call still
  doesn't debit anything from the balance (an `event` transaction still
  contributes 0 to balance, unchanged; that's documented behavior, not a
  bug):
  - `balance <= 0` (including negative, which shouldn't normally occur
    since a `/transfer` can't overdraw a wallet, but is treated the same
    defensively) — the wallet has never received a faucet claim/transfer,
    or has sent away everything it had:

    ```
    402 {"error":"insufficient aicoin balance","balance":<value>}
    ```

    and does **not** forward the request to the AI provider — no upstream
    call is made and no cost event is ever emitted for it, same as the
    401/503 cases above. Client apps integrating the wallet should treat
    `402` from this proxy as the one and only signal to fall back to the
    user's own provider key for that request.
  - `balance > 0` — the proxy proceeds with forwarding exactly as before.
    The aicoin node returns a balance (possibly `0`) for *any*
    syntactically-valid id, even one never used before, so the underlying
    validation call is a liveness/reachability check on the aicoin node,
    not a cryptographic identity check — that's a documented assumption,
    not a security guarantee. The validated `walletId` becomes the
    `user_id` in the eventual `/events` POST (see "Forwarding pipeline"
    below).

The old `X-User-Id` header is gone — `X-Api-Key` is now the only identity
mechanism, for both routing decisions (there are none left tied to it) and
billing.

## Forwarding pipeline

1. Inbound: `HttpServerCodec` + `HttpObjectAggregator` + a routing/forwarding
   handler (`ProxyFrontendHandler`).
2. Outbound: a **fresh** Netty client `Bootstrap` per request, connecting to
   the provider's host, with `HttpClientCodec` + `HttpObjectAggregator`
   (plus an `SslHandler` from `SslContextBuilder.forClient()` when the
   baseUrl is `https`).
3. The upstream's exact status/headers/body are written back to the client.
4. If the upstream status is 2xx, `cost_usd` is computed from the response
   body:
   - OpenAI-style: `usage.total_tokens`
   - Anthropic-style: `usage.input_tokens + usage.output_tokens`
   - Neither present/parseable: falls back to `pricing.defaultCostUsdPerCall`
   - Otherwise: `tokens * pricing.costPerTokenUsd`

   A fire-and-forget async `POST` is then sent to `aicoin.eventsUrl` with
   `{"user_id","provider","cost_usd"}`, where `user_id` is the wallet id
   already validated from the `X-Api-Key` header (see "Auth" above). This
   never blocks or affects the client-facing response; failures are logged
   and swallowed.
5. If the upstream connection fails, or the upstream returns a non-2xx
   status, no event is emitted. Non-2xx upstream responses are relayed to
   the client byte-for-byte (same as step 3).

## Additional proxy-side endpoints

- `GET /price` — forwards to `aicoin.priceUrl` and returns that JSON body
  verbatim, so callers don't need to know the aicoin node's address. If the
  upstream is unreachable: `502 {"error":"aicoin node unreachable"}`.
- `GET /free-coins/available` — reads `freeCoins.counterFile` fresh on every
  request (a bundled resource file containing a single integer, meant to be
  manually bumped by an operator via git push + CI redeploy) and returns
  `{"available": N}`. A missing or unparseable file resolves to
  `{"available": 0}`.
- `GET /health` — for each of the 7 configured providers (`openai`,
  `anthropic`, `google`, `mistral`, `cohere`, `elevenlabs`, `stability`),
  reports whether recent upstream calls have hit rate-limiting or budget
  errors. The proxy keeps, per provider, a rolling window of the last
  `health.windowSize` forwarded calls' upstream HTTP status codes (recorded
  regardless of whether the call was 2xx or not): `rateLimited` is `true`
  if any status in the window was `429`; `overBudget` is `true` if any was
  `402` or `403`; `healthy` is `!rateLimited && !overBudget`. Response:

  ```json
  {"providers":[
    {"name":"openai","healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"anthropic","healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"google","healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"mistral","healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"cohere","healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"elevenlabs","healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"stability","healthy":true,"rateLimited":false,"overBudget":false}
  ]}
  ```

  All 7 providers are always listed, in this stable order, even ones with
  zero forwarded calls so far — those default to
  `healthy:true`/`rateLimited:false`/`overBudget:false`.

## Tests

```
./gradlew test
```

JUnit5 pure-function tests, with no network/Netty server startup required:

- `ProviderRoutingTest` — `X-AI` header → provider resolution, including
  case-insensitivity and the missing/unknown case, across all 7 providers.
- `WalletValidationTest` — `X-Api-Key` header → wallet id extraction
  (missing/blank/whitespace-trimmed), the `{balanceUrlBase}/balance/{walletId}`
  URL construction (trailing-slash tolerance, path-segment encoding of
  special characters), the reachability decision (any 2xx status code →
  reachable; any other status, or no response at all — modeling a connect
  failure or read timeout → not reachable), parsing the `balance` field out
  of the response body, and the combined balance-gating decision: positive
  balance → proceed; zero, negative, or unparseable/missing balance on an
  otherwise-reachable node → the appropriate `402`/`503` outcome.
- `AuthInjectorTest` — auth-injection construction, both the header+prefix
  form (OpenAI/Anthropic/Mistral/Cohere/ElevenLabs/Stability-style) and the
  query-param form (Google-style).
- `CostCalculatorTest` — usage-JSON → `cost_usd` parsing, both OpenAI-style
  and Anthropic-style, plus the fallback-to-default case.
- `ProxyConfigTest` — config precedence (env var > YAML > default),
  covering `port`/`eventsUrl`/`priceUrl`/`balanceUrlBase`/
  `freeCoinsCounterFile` and each of the 7 providers'
  `baseUrl`/`apiKey`/`authHeader`/`authPrefix`/`authAsQueryParam`/
  `authQueryParamName`.
- `FreeCoinsCounterTest` — reading the free-coins counter fresh from a
  filesystem path or the bundled classpath resource, including the
  missing/unparseable → 0 cases.
- `ProviderHealthTrackerTest` — the rolling-window health computation:
  `rateLimited`/`overBudget`/`healthy` derivation from a sequence of
  synthetic status codes, per-provider independence, and window-eviction
  behavior once more than `windowSize` calls have been recorded for a
  provider.
- `HealthHandlerTest` — the `GET /health` JSON body always lists all 7
  providers, in the stable `openai, anthropic, google, mistral, cohere,
  elevenlabs, stability` order, defaulting to the all-clear state for
  providers with zero recorded calls, and reflecting recorded
  rate-limit/budget statuses for others.

## Assumptions made where the contract is ambiguous

- **JSON parsing.** The contract doesn't name a JSON library. Rather than
  add a second parsing dependency, `CostCalculator` parses response bodies
  with SnakeYAML (already required for config): valid JSON is valid YAML
  for the simple object/array/number shapes used here, so `new
  Yaml().load(jsonBody)` gives a plain `Map`/`List`/`Number` tree.
- **Connection failure to upstream.** The contract says to "relay the real
  error/status to the client" on connection failure, but there is no real
  HTTP status in that case (the connection never succeeded). That language
  is read as applying to actual non-2xx upstream *HTTP responses* (relayed
  byte-for-byte). For a connect/write failure, the proxy instead returns a
  synthetic `502 {"error":"..."}` to the client and emits no event. The
  same reasoning applies to `GET /price`'s "aicoin node unreachable" case.
- **baseUrl has no path component.** All example `baseUrl`s in the contract
  are bare `scheme://host[:port]` with no path. The forwarded upstream
  request URI is therefore exactly the inbound request's path+query
  (plus, when applicable, the appended auth query parameter), with no
  additional path-joining against the baseUrl.
- **`freeCoins.counterFile` resolution.** The contract describes it as "a
  bundled resource file", so the default value is read as a classpath
  resource name. To keep it overridable for local runs/tests (matching the
  "every value overridable by env var" rule in the Config section), the
  proxy first checks whether the configured value is an existing plain
  filesystem path, and only falls back to a classpath resource lookup if
  it isn't — the file is always re-read from scratch on every request
  either way, never cached.
- **`X-Api-Key` lookup** is case-insensitive (Netty's `HttpHeaders` are
  case-insensitive by default) and uses the first value if the header is
  repeated; the header value is trimmed before being used both as the
  wallet id and as the `/balance/{walletId}` path segment (which is also
  URL-path-encoded, in case a wallet id ever contains characters like `/`
  or spaces).
- **Wallet-check timeout.** The contract says "fails or times out" without
  naming a duration. `WalletValidator` uses a 5s TCP connect timeout (same
  order of magnitude as the other outbound calls in this project) plus a
  5s Netty `ReadTimeoutHandler` so a connected-but-hanging aicoin node
  still resolves to `503` rather than hanging the client request
  indefinitely.
- **A 2xx balance-check response with no parseable numeric `balance` field**
  is treated the same as an unreachable aicoin node (`503`), not as
  insufficient balance (`402`) — there's nothing to gate on in that case,
  and the contract's `GET /balance/{user_id}` shape always includes
  `balance`, so this should never happen against a compliant aicoin node;
  it's purely a defensive fallback. `balance <= 0` (including negative,
  which also shouldn't normally occur since `/transfer` can't overdraw a
  wallet) is always `402`, per the contract's explicit "treat the same
  defensively" instruction.
- **The `balance` field in a `402` response body** is rendered from
  whatever numeric type SnakeYAML parsed out of the upstream JSON
  (`Integer`/`Long`/`Double`), formatted without a trailing `.0` when it's
  a whole number — matching how Go's `encoding/json` would itself render a
  whole-number `float64` balance (e.g. `0`, not `0.0`).
- **`GET /health` only records real upstream responses.** The contract says
  to record "every forwarded call" into a provider's rolling window; a
  connect/write failure to the upstream never produces a real HTTP status
  (the proxy synthesizes a `502` to the client for that case — see the
  "Connection failure to upstream" assumption above), so it is not fed into
  the window. Only genuine upstream responses — 2xx or not — are recorded,
  which is also exactly the status the health signals (`429`/`402`/`403`)
  are about.
- **Toolchain.** This project targets Java 11 per `CONTRACT.md`
  (`java.toolchain.languageVersion = 11` in `build.gradle`), matching the
  repo-root `.java-version` (`11.0.29`). The Gradle wrapper itself is
  pinned to Gradle 9.6.1, which requires JVM 17+ just to launch its own
  daemon (unrelated to the Java level of the compiled/tested code); the
  local `aicoin-proxy/.java-version` is therefore set to `17.0.16` so
  `./gradlew` can run, while the toolchain block makes Gradle compile and
  execute the actual application/tests on the auto-detected JDK 11
  installation (confirmed via `--info`: "Compiling with toolchain
  '.../applejdk-11.0.29.7.1.jdk/Contents/Home'", and compiled `.class`
  files report major version 55 = Java 11).

## Docker

`Dockerfile` is a multi-stage build, per `CONTRACT.md`'s "Docker /
docker-compose" section:

1. A `eclipse-temurin:11-jdk` build stage runs `./gradlew installDist`,
   producing the `application` plugin's install output at
   `build/install/aicoin-proxy/` (a `bin/aicoin-proxy` start script plus a
   `lib/` of jars — everything needed to run, no Gradle at runtime).
2. Only that install output is copied onto an `eclipse-temurin:11-jre`
   runtime base. The entrypoint runs the generated start script,
   `/opt/aicoin-proxy/bin/aicoin-proxy`.

Nothing is baked into the image: `ProxyConfig` reads every setting from env
vars first (see "Configuration" above), so the same image is reconfigured
at `docker run` time, exactly as it would be when run directly.

Build:

```
docker build -t aicoin-proxy .
```

Run (defaults — expects an aicoin node reachable at `localhost:9944`, which
won't resolve from inside the container without `--network`/`--add-host`
adjustments or `docker-compose`):

```
docker run --rm -p 8080:8080 aicoin-proxy
```

Run with env var overrides, e.g. pointing at an `aicoin-node` container by
DNS name on a shared Docker network, and supplying real provider keys:

```
docker run --rm -p 8080:8080 \
  -e AICOIN_PROXY_AICOIN_EVENTS_URL=http://aicoin-node:9944/events \
  -e AICOIN_PROXY_AICOIN_PRICE_URL=http://aicoin-node:9944/price \
  -e AICOIN_PROXY_AICOIN_BALANCE_URL_BASE=http://aicoin-node:9944 \
  -e AICOIN_PROXY_OPENAI_APIKEY=sk-... \
  -e AICOIN_PROXY_ANTHROPIC_APIKEY=sk-ant-... \
  -e AICOIN_PROXY_PORT=8080 \
  aicoin-proxy
```

