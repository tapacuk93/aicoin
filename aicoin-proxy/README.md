# aicoin-proxy

A reverse HTTP proxy built directly on Netty (no Spring, no web framework —
raw `ServerBootstrap`/`Bootstrap` channel pipelines for both the inbound
server and outbound upstream client). Clients call it at **exactly the same
path** a real LLM provider would use; a request header (`X-AI`) picks which
provider/upstream to use, and the proxy injects **its own** paid API key
into the forwarded request — clients never hold or send provider
credentials. The caller's aicoin wallet id doubles as their API key (sent
as `X-Api-Key`), which the proxy validates against its own coin ledger — and
gates on a positive balance — before forwarding. It relays the upstream
response verbatim and asynchronously records a cost event into the ledger
for every successful call.

This same process **is** the coin ledger — wallet balances, the free-coin
faucet, peer transfers, and the recency-weighted price all live here,
backed directly by Redis (`AicoinLedger`). There is no separate node
process, no blockchain, no signing, no replication. It also exposes small
proxy-side endpoints: `GET /price`, `GET /free-coins/available`, `GET
/health`, `GET /wallet` (a browser wallet UI), and `GET|POST
/wallet/api/*` — none of which require `X-Api-Key`.

This document mirrors the shared `CONTRACT.md` at the repo root but is
meant to stand on its own.

## Running

You need a JDK 17 and a Redis (or Redis-compatible, e.g. Valkey) server
reachable at `redis.host`/`redis.port` (default `localhost:6379`).

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
redis:
  host: localhost                 # AICOIN_PROXY_REDIS_HOST
  port: 6379                      # AICOIN_PROXY_REDIS_PORT
  password: ""                    # AICOIN_PROXY_REDIS_PASSWORD (empty = no AUTH)
  ssl: false                      # AICOIN_PROXY_REDIS_SSL (true for ElastiCache in-transit encryption)
aicoin:
  decayHalflifeDays: 110.0        # AICOIN_PROXY_DECAY_HALFLIFE_DAYS
  freeClaimCooldownSeconds: 3600  # AICOIN_PROXY_FREE_CLAIM_COOLDOWN_SECONDS
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
`GET /health`, `GET /wallet`, and `/wallet/api/*`) requires a header:

```
X-Api-Key: <walletId>
```

Missing (or blank/whitespace-only) header:

```
401 {"error":"missing X-Api-Key (wallet id)"}
```

Before forwarding to the upstream AI provider, the proxy reads the wallet's
balance directly from the ledger (`AicoinLedger.getBalance`, an in-process
async Redis `GET` — no network hop to another service):

- If that call **fails** (Redis connection error/timeout), the proxy
  responds to the original client with:

  ```
  503 {"error":"could not validate wallet"}
  ```

  and does **not** forward the request to the AI provider — no upstream
  call is made and no cost event is ever recorded for it.
- If it **succeeds**, the proxy gates on the returned balance — this is
  deliberately a simple binary gate, not per-call metering: a successful
  call still doesn't debit anything from the balance (a priced event still
  contributes 0 to balance, unchanged; that's documented behavior, not a
  bug):
  - `balance <= 0` (including negative, which shouldn't normally occur
    since `/wallet/api/transfer` can't overdraw a wallet, but is treated the
    same defensively) — the wallet has never received a faucet claim/
    transfer, or has sent away everything it had:

    ```
    402 {"error":"insufficient aicoin balance","balance":<value>}
    ```

    and does **not** forward the request to the AI provider — no upstream
    call is made and no cost event is ever recorded for it, same as the
    401/503 cases above. Client apps integrating the wallet should treat
    `402` from this proxy as the one and only signal to fall back to the
    user's own provider key for that request.
  - `balance > 0` — the proxy proceeds with forwarding exactly as before.
    The ledger returns a balance (possibly `0`) for *any* syntactically-
    valid id, even one never used before, so there's no cryptographic
    identity check here — that's a documented assumption, not a security
    guarantee.

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

   `AicoinLedger.recordEvent` is then called in-process (a fire-and-forget
   `ZADD` into the price event log). This never blocks or affects the
   client-facing response; failures are logged and swallowed.
5. If the upstream connection fails, or the upstream returns a non-2xx
   status, no event is recorded. Non-2xx upstream responses are relayed to
   the client byte-for-byte (same as step 3).

## The ledger (`AicoinLedger`)

Backed by a single Redis instance — ElastiCache for Redis (snapshotting
enabled) in production, `redis:7-alpine` locally/in e2e. Full data model,
Lua-script atomicity rationale, and the price formula are documented in the
repo-root `CONTRACT.md`'s "Ledger (Redis)" section — this section covers
just the Java-side shape:

- `getBalance(userId, callback)` — async Redis `GET aicoin:balance:{userId}`.
- `claimFreeCoins(userId, cooldownSeconds, callback)` — runs a Lua `EVAL`
  script that atomically checks `aicoin:lastclaim:{userId}` against the
  cooldown and, if eligible, mints +1.0 into the balance.
- `transfer(from, to, amount, callback)` — runs a Lua `EVAL` script that
  atomically checks the sender's balance and, if sufficient, moves the
  amount.
- `recordEvent(provider, costUsd, timestamp)` — fire-and-forget `ZADD` into
  `aicoin:events` (member `costUsd|uuid`, score = epoch-millis).
- `computePrice(halfLifeDays, callback)` — `ZRANGE ... WITHSCORES` over
  `aicoin:events`, then folds the recency-weighted average via the pure
  `PriceCalculator.compute` (no Redis dependency, fully unit-tested).

All five operations are async (Lettuce's `RedisFuture`/`CompletableFuture`
API), matching the rest of this codebase's non-blocking Netty style — none
of them block an event-loop thread.

## Additional proxy-side endpoints

- `GET /price` — computed directly from the ledger, returns
  `{"price_usd":..,"total_spend_usd":..,"weighted_total":..,"half_life_days":110}`.
  Always includes `Access-Control-Allow-Origin: *` (public data, fetched
  cross-origin by the landing page at aicoin.oeaio.com).
- `GET /free-coins/available` — reads `freeCoins.counterFile` fresh on every
  request (a bundled resource file containing a single integer, meant to be
  manually bumped by an operator via git push + CI redeploy) and returns
  `{"available": N}`. A missing or unparseable file resolves to
  `{"available": 0}`. This is a separate, admin-managed system-wide
  allowance, distinct from the per-wallet 1-hour claim cooldown.
- `GET /health` — for each of the 7 configured providers (`openai`,
  `anthropic`, `google`, `mistral`, `cohere`, `elevenlabs`, `stability`),
  reports whether it has a real (non-empty) `apiKey` configured (`enabled`
  — this is what the landing page reads to show which AI backends are
  actually live) and whether recent upstream calls have hit rate-limiting
  or budget errors. The proxy keeps, per provider, a rolling window of the
  last `health.windowSize` forwarded calls' upstream HTTP status codes
  (recorded regardless of whether the call was 2xx or not): `rateLimited`
  is `true` if any status in the window was `429`; `overBudget` is `true`
  if any was `402` or `403`; `healthy` is `!rateLimited && !overBudget`.
  Always includes `Access-Control-Allow-Origin: *`, same as `/price`.
  Response:

  ```json
  {"providers":[
    {"name":"openai","enabled":true,"healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"anthropic","enabled":true,"healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"google","enabled":false,"healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"mistral","enabled":false,"healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"cohere","enabled":false,"healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"elevenlabs","enabled":true,"healthy":true,"rateLimited":false,"overBudget":false},
    {"name":"stability","enabled":true,"healthy":true,"rateLimited":false,"overBudget":false}
  ]}
  ```

  All 7 providers are always listed, in this stable order, even ones with
  zero forwarded calls so far — those default to
  `healthy:true`/`rateLimited:false`/`overBudget:false`; `enabled` reflects
  configuration, not traffic.
- `GET /wallet` — the browser wallet page (bundled `wallet.html`).
- `GET /wallet/api/balance/{walletId}`, `POST /wallet/api/claim`, `POST
  /wallet/api/transfer` — the wallet page's backing endpoints, all calling
  directly into `AicoinLedger`. See `CONTRACT.md`'s "Wallet web page"
  section for exact request/response shapes.

## Tests

```
./gradlew test
```

JUnit5 pure-function tests, with no network/Redis dependency required:

- `ProviderRoutingTest` — `X-AI` header → provider resolution, including
  case-insensitivity and the missing/unknown case, across all 7 providers.
- `WalletValidationTest` — `X-Api-Key` header → wallet id extraction
  (missing/blank/whitespace-trimmed), and the balance-gating decision:
  positive balance → proceed; zero/negative balance → `402`; a failed
  ledger lookup → `503`.
- `PriceCalculatorTest` — the recency-weighted price formula's checkpoint
  table (1 hour/day/week/month/quarter/year/5-years), negative-age
  clamping, the zero-events case, and custom half-life behavior.
- `AuthInjectorTest` — auth-injection construction, both the header+prefix
  form (OpenAI/Anthropic/Mistral/Cohere/ElevenLabs/Stability-style) and the
  query-param form (Google-style).
- `CostCalculatorTest` — usage-JSON → `cost_usd` parsing, both OpenAI-style
  and Anthropic-style, plus the fallback-to-default case.
- `ProxyConfigTest` — config precedence (env var > YAML > default),
  covering `port`/`redis.*`/`aicoin.decayHalflifeDays`/
  `aicoin.freeClaimCooldownSeconds`/`freeCoinsCounterFile` and each of the
  7 providers' `baseUrl`/`apiKey`/`authHeader`/`authPrefix`/
  `authAsQueryParam`/`authQueryParamName`.
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
- `WalletPageHandlerTest` — `GET /wallet` serves the bundled HTML page
  verbatim.

`AicoinLedger`'s Lua-script atomicity (claim cooldown, transfer overdraft
check) requires a live Redis/Valkey connection to exercise end-to-end —
covered by `e2e/run.sh`, not the pure JUnit suite.

## Assumptions made where the contract is ambiguous

- **JSON parsing.** The contract doesn't name a JSON library. Rather than
  add a second parsing dependency, `CostCalculator` (and the wallet
  transfer/claim body parsing in `ProxyFrontendHandler`) parse request/
  response bodies with SnakeYAML (already required for config): valid JSON
  is valid YAML for the simple object/array/number shapes used here, so
  `new Yaml().load(jsonBody)` gives a plain `Map`/`List`/`Number` tree.
- **Connection failure to upstream.** The contract says to "relay the real
  error/status to the client" on connection failure, but there is no real
  HTTP status in that case (the connection never succeeded). That language
  is read as applying to actual non-2xx upstream *HTTP responses* (relayed
  byte-for-byte). For a connect/write failure, the proxy instead returns a
  synthetic `502 {"error":"..."}` to the client and records no event.
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
  repeated; the header value is trimmed before being used as the wallet id.
- **A ledger balance lookup that fails** (Redis connection error/timeout)
  is treated as unreachable (`503`), not as insufficient balance (`402`) —
  there's nothing to gate on in that case.
- **The `balance` field in a `402` response body** is rendered without a
  trailing `.0` when it's a whole number (e.g. `0`, not `0.0`), matching
  how a whole-number balance would typically be expected to render.
- **`GET /health` only records real upstream responses.** The contract says
  to record "every forwarded call" into a provider's rolling window; a
  connect/write failure to the upstream never produces a real HTTP status
  (the proxy synthesizes a `502` to the client for that case — see the
  "Connection failure to upstream" assumption above), so it is not fed into
  the window. Only genuine upstream responses — 2xx or not — are recorded,
  which is also exactly the status the health signals (`429`/`402`/`403`)
  are about.
- **Toolchain.** This project targets Java 17 (`java.toolchain.languageVersion
  = 17` in `build.gradle`), matching the repo-root `.java-version`
  (`17.0.16`).
- **Claim/transfer atomicity.** The contract doesn't mandate a specific
  Redis mechanism for the check-then-mutate operations in the ledger; Lua
  `EVAL` scripts were chosen because Redis executes a script atomically
  with respect to every other client, which is the simplest way to
  replicate the correctness a single-writer blockchain got "for free" from
  having exactly one legitimate writer.

## Docker

`Dockerfile` is a multi-stage build, per `CONTRACT.md`'s "Docker /
docker-compose" section:

1. A `eclipse-temurin:17-jdk` build stage runs `./gradlew installDist`,
   producing the `application` plugin's install output at
   `build/install/aicoin-proxy/` (a `bin/aicoin-proxy` start script plus a
   `lib/` of jars — everything needed to run, no Gradle at runtime).
2. Only that install output is copied onto an `eclipse-temurin:17-jre`
   runtime base. The entrypoint runs the generated start script,
   `/opt/aicoin-proxy/bin/aicoin-proxy`.

Nothing is baked into the image: `ProxyConfig` reads every setting from env
vars first (see "Configuration" above), so the same image is reconfigured
at `docker run` time, exactly as it would be when run directly.

Build:

```
docker build -t aicoin-proxy .
```

Run (defaults — expects Redis reachable at `localhost:6379`, which won't
resolve from inside the container without `--network`/`--add-host`
adjustments or `docker-compose`):

```
docker run --rm -p 8080:8080 aicoin-proxy
```

Run with env var overrides, e.g. pointing at a `redis` container by DNS
name on a shared Docker network, and supplying real provider keys:

```
docker run --rm -p 8080:8080 \
  -e AICOIN_PROXY_REDIS_HOST=redis \
  -e AICOIN_PROXY_REDIS_PORT=6379 \
  -e AICOIN_PROXY_OPENAI_APIKEY=sk-... \
  -e AICOIN_PROXY_ANTHROPIC_APIKEY=sk-ant-... \
  -e AICOIN_PROXY_PORT=8080 \
  aicoin-proxy
```
