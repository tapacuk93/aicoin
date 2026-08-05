# aicoin-proxy

A reverse HTTP proxy built directly on Netty (no Spring, no web framework —
raw `ServerBootstrap`/`Bootstrap` channel pipelines for both the inbound
server and outbound upstream client). Clients call it at **exactly the same
path** a real LLM provider would use; a request header (`X-AI`) picks which
provider/upstream to use, and the proxy injects **its own** paid API key
into the forwarded request — clients never hold or send provider
credentials. A wallet is a real Ed25519 keypair: its address (the
hex-encoded public key) doubles as the identifier for receiving transfers
and, via a signed API token, as the identity behind AI-proxy calls. The
proxy validates that identity — and gates on a positive balance — before
forwarding. It relays the upstream response verbatim and asynchronously
records a cost event into the ledger for every successful call.

This same process **is** the coin ledger — wallet balances, the free-coin
faucet, peer transfers, the recency-weighted price, and API-token
verification/revocation all live here, backed directly by Redis
(`AicoinLedger`). There is no separate node process, no blockchain, no
chain-of-blocks signing, no replication — only the wallet-address
cryptography itself (`WalletSignature`) is real Ed25519. It also exposes
small proxy-side endpoints: `GET /price`, `GET /free-coins/available`, `GET
/health`, `GET /wallet` (a browser wallet UI), and `GET|POST
/wallet/api/*`.

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
  signatureSkewSeconds: 120       # AICOIN_PROXY_SIGNATURE_SKEW_SECONDS
  freeCoinsPoolSize: 100          # AICOIN_PROXY_FREE_COINS_POOL_SIZE
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
   own — only `X-Api-Key` (an API token, see "Auth" below) for billing
   identification and wallet validation. Any key/credential the client
   did send for that provider is discarded, never forwarded.

## Auth — two schemes, split by how sensitive/frequent the action is

A wallet is a real Ed25519 keypair. Its **address** — the hex-encoded raw
32-byte public key, 64 hex chars — is both the identifier other people send
transfers *to*, and the identity behind requests made *through* the proxy.
The private key never leaves the browser wallet page.

**Wallet-management actions** (`POST /wallet/api/claim`, `POST
/wallet/api/transfer`, `POST /wallet/api/revoke-tokens`) — rare, sensitive,
only ever done from the wallet page where the key is already in memory —
require a **live signature**, verified fresh per request:

```
X-Api-Key: <address, 64 hex chars>
X-Api-Signature: <signature, 128 hex chars — raw 64-byte Ed25519 R‖S, no DER>
X-Api-Timestamp: <epoch millis>
```

The signature covers a canonical message built server-side and re-derived
client-side identically:

```
address + "\n" + timestampMillis + "\n" + method + "\n" + path + "\n" + hex(sha256(body))
```

`path` has no query string. `X-Api-Timestamp` must be within
`aicoin.signatureSkewSeconds` (default 120s) of server time — bounds replay
risk without a nonce-tracking store, a documented, not hardened, trade-off.
Verification (`WalletSignature.verifyLive`) reconstructs a `PublicKey` from
the raw address by prepending the fixed 12-byte X.509 SubjectPublicKeyInfo
DER prefix for Ed25519 (`302a300506032b6570032100`, RFC 8410) and calling
`KeyFactory.getInstance("Ed25519")`, then `Signature.getInstance("Ed25519")
.verify(...)` — Java does its own internal RFC 8032 hashing, no manual
pre-hash needed. Any missing header, malformed hex, out-of-skew timestamp,
or failed verification → `401 {"error":"<specific reason>"}`, never a
`500`. The verified address is the only identity these actions trust — never
a body field (closes a real hole the old bearer-string design had, where
anyone could claim/transfer "as" any `user_id` string they typed into a
request body).

**AI-proxy calls** (the generic `X-AI`-routed path) — frequent, needs to
work from any HTTP client, not just the browser — use an **API token**
instead of live signing, since requiring every single request to be
individually signed would be impractical for scripts/SDKs/curl:

```
X-Api-Key: <base64url(payloadJson)>.<base64url(signature)>
```

The wallet page issues this once (`{"addr":"<address>","iat":<epochSeconds>,
"exp":<epochSeconds>}`, signed client-side over the exact base64url-encoded
payload bytes — JWT-style, so there's no JSON key-ordering ambiguity); the
server never sees the private key and has no "issue token" endpoint.
Thereafter the token is used as a plain bearer `X-Api-Key` — no signing at
call time. `WalletSignature.verifyToken` checks the signature against the
embedded address, expiry, and revocation (`aicoin:token-revoked-before:
{address}` in Redis, bumped by `POST /wallet/api/revoke-tokens`). A token
authenticates AI-proxy calls only — it **cannot** claim free coins or
transfer coins, since it never has the raw private key. Missing/malformed/
expired/revoked → `401 {"error":"<specific reason>"}`.

Only one scheme is accepted per path: `/wallet/api/claim`, `/wallet/api/
transfer`, and `/wallet/api/revoke-tokens` require the live-signature
headers; the generic proxy path accepts only a token. `GET /wallet/api/
balance/{address}` requires neither — a public address's balance is
inherently public info, like a blockchain explorer.

Before forwarding to the upstream AI provider, once either scheme verifies
an address, the proxy atomically checks and debits **exactly 1.0 aicoin**
from that wallet's balance (`AicoinLedger.debitForCall`, a single Redis Lua
script — no separate read-then-write, so two concurrent calls can't both
pass a stale check and overdraw). **1 aicoin is worth 1 paid AI call —
enforced, not just a tagline**; this replaced an earlier binary
"balance > 0" gate that never actually debited anything.

- If the debit call **fails** (Redis connection error/timeout), the proxy
  responds to the original client with:

  ```
  503 {"error":"could not validate wallet"}
  ```

  and does **not** forward the request to the AI provider — no upstream
  call is made and no debit is applied.
- If the wallet's balance is **less than 1.0**, no debit is applied and the
  proxy responds:

  ```
  402 {"error":"insufficient aicoin balance","balance":<value>}
  ```

  and does **not** forward the request to the AI provider, same as the
  503 case above. Client apps integrating the wallet should treat `402`
  from this proxy as the one and only signal to fall back to the user's
  own provider key for that request.
- If the balance is **at least 1.0**, the 1.0 is debited immediately and the
  proxy forwards the request. If the upstream call then fails (non-2xx or a
  connection failure), the debit is **refunded** (`AicoinLedger.refund`) —
  see "Forwarding pipeline" below — since the proxy was never actually
  billed by the real provider for a call that didn't complete. This is also
  what keeps *paid* calls (successful, billed, feeding the price formula)
  cleanly separated from *free* activity: a faucet claim mints coins but is
  never treated as a call, and a failed call never counts as paid.

## How to use a token from a script

Generate a token from `GET /wallet` once (any expiry, default 7 days), then
use it exactly like a normal API key — no signing needed at call time:

```bash
curl https://proxy.aicoin.oeaio.com/v1/chat/completions \
  -H "X-AI: openai" \
  -H "X-Api-Key: <token from the wallet page>" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4","messages":[{"role":"user","content":"hi"}]}'
```

If the token is later compromised, click "Revoke all tokens" on the wallet
page — every token issued before that moment stops working immediately,
even if it hasn't expired yet.

## Forwarding pipeline

1. Inbound: `HttpServerCodec` + `HttpObjectAggregator` + a routing/forwarding
   handler (`ProxyFrontendHandler`).
2. Outbound: a **fresh** Netty client `Bootstrap` per request, connecting to
   the provider's host, with `HttpClientCodec` + `HttpObjectAggregator`
   (plus an `SslHandler` from `SslContextBuilder.forClient()` when the
   baseUrl is `https`).
3. The upstream's exact status/headers/body are written back to the client.
4. If the upstream status is 2xx (a genuine paid call), the 1.0 aicoin
   debited before forwarding stands, and `cost_usd` is computed from the
   response body:
   - OpenAI-style: `usage.total_tokens`
   - Anthropic-style: `usage.input_tokens + usage.output_tokens`
   - Neither present/parseable: falls back to `pricing.defaultCostUsdPerCall`
   - Otherwise: `tokens * pricing.costPerTokenUsd`

   `AicoinLedger.recordEvent` is then called in-process (a fire-and-forget
   `ZADD` into the price event log — a market-rate *signal*, independent of
   the fixed 1-aicoin-per-call spend above). This never blocks or affects
   the client-facing response; failures are logged and swallowed.
5. If the upstream connection fails, or the upstream returns a non-2xx
   status, no price event is recorded, and the 1.0 aicoin debited before
   forwarding is **refunded** (`AicoinLedger.refund`) — the call never
   actually cost the proxy anything, so it shouldn't cost the wallet
   anything either. Non-2xx upstream responses are still relayed to the
   client byte-for-byte (same as step 3); connection failures get a
   synthetic `502` (there's no real status to relay).

## The ledger (`AicoinLedger`)

Backed by a single Redis instance — ElastiCache for Redis (snapshotting
enabled) in production, `redis:7-alpine` locally/in e2e. Full data model,
Lua-script atomicity rationale, and the price formula are documented in the
repo-root `CONTRACT.md`'s "Ledger (Redis)" section — this section covers
just the Java-side shape:

- `getBalance(address, callback)` — async Redis `GET aicoin:balance:{address}`.
- `debitForCall(address, amount, callback)` — runs a Lua `EVAL` script that
  atomically checks the balance and, if `>= amount` (always `1.0` in
  practice — see `ProxyFrontendHandler.CALL_COST_AICOIN`), debits it before
  the proxy forwards to a real provider.
- `refund(address, amount)` — fire-and-forget `INCRBYFLOAT` reversing a
  `debitForCall` when the upstream call it paid for didn't actually succeed.
- `claimFreeCoins(address, cooldownSeconds, poolSize, claimAmount, callback)`
  — runs a Lua `EVAL` script that atomically checks both the per-wallet
  cooldown (`aicoin:lastclaim:{address}`) and the shared pool
  (`aicoin:free-coins-remaining`, lazily initialized to `poolSize`) and, if
  both allow it, mints `claimAmount` into the balance and decrements the
  pool by the same amount — always the fixed
  `ProxyFrontendHandler.FREE_CLAIM_AMOUNT_AICOIN` (10) in practice, never a
  partial grant.
- `getFreeCoinsRemaining(poolSize, callback)` — async Redis `GET` on the
  shared pool key, defaulting to `poolSize` if never initialized.
- `transfer(from, to, amount, callback)` — runs a Lua `EVAL` script that
  atomically checks the sender's balance and, if sufficient, moves the
  amount.
- `recordEvent(provider, costUsd, timestamp)` — fire-and-forget `ZADD` into
  `aicoin:events` (member `costUsd|uuid`, score = epoch-millis) — fed only
  by genuine 2xx paid calls, never by claims/transfers/failed calls.
- `computePrice(halfLifeDays, callback)` — `ZRANGE ... WITHSCORES` over
  `aicoin:events`, then folds the recency-weighted average via the pure
  `PriceCalculator.compute` (no Redis dependency, fully unit-tested).
- `revokeTokensBefore(address, nowMillis, callback)` — Redis `SET
  aicoin:token-revoked-before:{address} nowMillis`.
- `getTokenRevokedBefore(address, callback)` — Redis `GET`, `Optional.empty`
  parsed as "never revoked."

All operations are async (Lettuce's `RedisFuture`/`CompletableFuture`
API), matching the rest of this codebase's non-blocking Netty style — none
of them block an event-loop thread.

## Additional proxy-side endpoints

- `GET /price` — computed directly from the ledger, returns
  `{"price_usd":..,"total_spend_usd":..,"weighted_total":..,"half_life_days":110}`.
  Always includes `Access-Control-Allow-Origin: *` (public data, fetched
  cross-origin by the landing page at aicoin.oeaio.com).
- `GET /free-coins/available` — the real, live remaining count in the
  shared Redis-backed pool (`AicoinLedger.getFreeCoinsRemaining`) — the same
  counter `POST /wallet/api/claim` atomically decrements, not a static
  admin-managed file. Returns `{"available": N}`; a ledger-lookup failure
  resolves to `{"available": 0}`.
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
- `GET /wallet` — the browser wallet page (bundled `wallet.html`): generate
  or import an Ed25519 keypair, view address/balance/price, claim/transfer
  (live-signed), and issue/revoke API tokens.
- `GET /wallet/api/balance/{address}` — unsigned, backed directly by
  `AicoinLedger.getBalance`.
- `POST /wallet/api/claim`, `POST /wallet/api/transfer`, `POST
  /wallet/api/revoke-tokens` — live-signed, see "Auth" above for the exact
  header/canonical-message spec.

## Tests

```
./gradlew test
```

JUnit5 pure-function tests, with no network/Redis dependency required:

- `ProviderRoutingTest` — `X-AI` header → provider resolution, including
  case-insensitivity and the missing/unknown case, across all 7 providers.
- `WalletValidationTest` — `X-Api-Key` header extraction
  (missing/blank/whitespace-trimmed) — pure logic only; the balance gate is
  now the atomic `AicoinLedger.debitForCall`, which needs a live Redis
  connection (covered by `e2e/run.sh`, see below).
- `WalletSignatureTest` — against genuinely-generated Ed25519 keypairs: the
  live-signature canonical message is deterministic and tamper-sensitive
  (any one of address/timestamp/method/path/body byte breaks verification),
  clock-skew accept/reject boundaries, malformed hex rejected cleanly; the
  token scheme's valid/expired/tampered/wrong-key/revoked-before cases.
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
  `aicoin.freeClaimCooldownSeconds`/`aicoin.signatureSkewSeconds`/
  `aicoin.freeCoinsPoolSize` and each of the 7 providers'
  `baseUrl`/`apiKey`/`authHeader`/`authPrefix`/`authAsQueryParam`/
  `authQueryParamName`.
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

`AicoinLedger`'s Lua-script atomicity (claim cooldown + shared pool, transfer
overdraft, per-call debit/refund) requires a live Redis/Valkey connection to
exercise end-to-end — covered by `e2e/run.sh`, which also runs one real paid
call through every one of the 7 configured providers (against a local mock
upstream) to confirm each provider's specific auth injection and the 1-aicoin
debit both work correctly, not just OpenAI's.

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
- **Refund-on-failure is fire-and-forget.** `AicoinLedger.refund` issues a
  plain `INCRBYFLOAT` with no read-back/callback wired to the response
  path — the client has already been told the call failed by the time the
  refund lands, so there's nothing useful to do with a refund failure
  except log it; it never blocks or changes the error response the client
  receives.
- **`X-Api-Key` lookup** is case-insensitive (Netty's `HttpHeaders` are
  case-insensitive by default) and uses the first value if the header is
  repeated; the header value is trimmed before use — either as a live-
  signature address (`/wallet/api/*`) or an API token (generic proxy path).
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
- **Claim/transfer/per-call-debit atomicity.** The contract doesn't mandate a
  specific Redis mechanism for the check-then-mutate operations in the
  ledger (free-coins pool decrement + cooldown, transfer overdraft check,
  per-call debit-before-forward); Lua `EVAL` scripts were chosen because
  Redis executes a script atomically with respect to every other client,
  which is the simplest way to replicate the correctness a single-writer
  blockchain got "for free" from having exactly one legitimate writer.
- **No nonce-tracking replay store for live signatures.** A `±120s` (default)
  clock-skew window bounds how long a captured live-signed request could be
  replayed, rather than a per-signature nonce ledger — a documented,
  intentionally lightweight trade-off consistent with this project's
  draft/prototype posture elsewhere (see CONTRACT.md). Token-based AI-proxy
  auth doesn't have this concern at all, since a token is meant to be
  reused many times by design; its own risk (a leaked token) is mitigated
  by expiry + the revocation endpoint instead.
- **Token payload parsing** reuses the same SnakeYAML-as-JSON-parser
  approach as the rest of this codebase (see "JSON parsing" above) rather
  than adding a JWT library — the token format is JWT-*shaped* but
  deliberately not a general JWT implementation (no `alg` field, no
  algorithm negotiation): the embedded address is always the verification
  key and Ed25519 is the only signature scheme ever used, so there's no
  algorithm-confusion surface to defend against.

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
