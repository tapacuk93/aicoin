# aicoin — contract

A single project now: do not deviate from field names/types/ports/config
keys here without updating this file.

## Layout

```
aicoin/            (this repo root)
  aicoin-proxy/     Java 26 + Netty reverse proxy AND the coin ledger (Gradle)
  e2e/              end-to-end test
  site/             static landing page (aicoin.oeaio.com)
```

## aicoin-proxy (Java 26 + Netty, Gradle)

Run: `./gradlew run` (application plugin), Netty 4.1.x + Lettuce (Redis
client) from Maven Central. Fully async — no framework (no Spring).

One process does two jobs: reverse-proxies AI provider calls, and owns the
coin ledger (wallet balances, free-coin faucet, transfers, price) directly
against Redis. There is no separate node, no blockchain, no signing, no
primary/follower replication — this is a centralized ledger, priced off
real usage, not a decentralized currency. See "Ledger (Redis)" below for
the full data model.

### Config
YAML file, path from env `AICOIN_PROXY_CONFIG` (default: bundled `application.yaml` with the values below). Every value overridable by env var for the e2e test:

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
  adminToken: ""                  # AICOIN_PROXY_ADMIN_TOKEN (empty = admin page/API disabled)
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
```
`baseUrl` may be `http://` (plain, used by tests against a local mock) or `https://` (real providers, TLS client). `elevenlabs`/`stability` exist so client apps that call voice (ElevenLabs) or image (Stability) generation, not just text, can route through the wallet too — OpenAI's own image generation (DALL-E) already goes through the existing `openai` entry, since it's the same `api.openai.com` host.

### Routing — same path, header selects the provider, proxy owns the upstream key
The client calls the proxy at **exactly the same path** the real provider would use (e.g. `POST /v1/chat/completions`) — only the domain changes to the proxy's. A request header `X-AI: <provider>` (one of `openai|anthropic|google|mistral|cohere|elevenlabs|stability`, case-insensitive) tells the proxy which upstream/config to use. The proxy:
1. Reads `X-AI`, looks up the matching `providers.<name>` config. Missing/unknown value → `400 {"error":"missing or unknown X-AI header"}`.
2. **Removes** the `X-AI` header (the upstream provider must never see it).
3. Forwards the **same method + same path/query + same body** to `providers.<name>.baseUrl`, with all original client headers preserved *except*: `Host`/`Content-Length` (recomputed) and whatever the client sent in `authHeader` for this provider (or the raw `Authorization`) — the proxy **overwrites/injects its own `apiKey`** as that provider's paid credential (via `authHeader`+`authPrefix`, or as a `authQueryParamName` query param when `authAsQueryParam` is true, e.g. Google).

### Auth for wallet-management actions (live Ed25519 signature)
A wallet is a real Ed25519 keypair — the **address** (used both to receive transfers and to identify the signer of a request) is the hex-encoded raw 32-byte public key (64 hex chars). The private key never leaves the browser wallet page. `POST /wallet/api/claim`, `POST /wallet/api/transfer`, and `POST /wallet/api/revoke-tokens` each require three headers, signed fresh per request:
- `X-Api-Key: <address>` — 64 hex chars.
- `X-Api-Signature: <signature>` — 128 hex chars, the raw 64-byte `R‖S` Ed25519 signature (no DER wrapping — matches WebCrypto's `sign()` output and Java's `Signature.verify(byte[])` input directly).
- `X-Api-Timestamp: <epochMillis>` — checked against server time within `aicoin.signatureSkewSeconds` (default 120s), bounding replay risk without a nonce-tracking store (a documented, not hardened, trade-off).

Canonical signed message (one construction for all three actions):
```
address + "\n" + timestampMillis + "\n" + httpMethod + "\n" + requestPath + "\n" + hex(sha256(requestBody))
```
`requestPath` has no query string. Server-side, the address's raw 32 bytes are wrapped in the fixed 12-byte X.509 SubjectPublicKeyInfo DER prefix for Ed25519 (`302a300506032b6570032100`, per RFC 8410) to reconstruct a `PublicKey` via `KeyFactory.getInstance("Ed25519")`, then `Signature.getInstance("Ed25519").verify(...)` checks the signature — no manual pre-hashing, Ed25519's own internal hashing (RFC 8032) is what the JCA provider does. Any missing header, malformed hex, out-of-skew timestamp, or failed verification → `401 {"error":"<specific reason>"}`, never a `500`.

The verified address becomes the identity for that request — `POST /wallet/api/claim` needs no body (identity = the signer); `POST /wallet/api/transfer`'s body is `{"to_user_id":"<address>","amount":N}` only, `from` is always the verified signer, never trusted from the body; `POST /wallet/api/revoke-tokens` needs no body either.

### Auth for AI-proxy calls (API tokens)
Requiring every single AI-proxy HTTP request to be individually signed would be impractical for scripts/SDKs/curl. Instead, the wallet page issues a **token** — signed once by the private key, then used many times as a plain `X-Api-Key` bearer value, with no signing needed at call time. Format: `<base64url(payloadJson)>.<base64url(signature)>` (JWT-shaped, self-verifying — the embedded address *is* the verification key, so only Ed25519 is ever used, no algorithm-confusion surface). Payload: `{"addr":"<64-hex address>","iat":<epochSeconds>,"exp":<epochSeconds>}`; the signature covers the exact `base64url(payload)` string bytes (sign the encoded form, not the raw JSON — no key-ordering ambiguity).

Issuance is entirely client-side — the server never sees the private key and has no "issue token" endpoint. The generic `X-AI`-routed proxy path accepts **only** tokens as `X-Api-Key` (bare addresses are not accepted here — those are for the live-signature scheme above): missing/malformed → `401`; on success, the same balance-gate logic below applies using the token's embedded address.

**Revocation**: `POST /wallet/api/revoke-tokens` (live-signed, see above) sets `aicoin:token-revoked-before:{address}` to the current time in Redis. Verifying a token additionally checks `iat > revokedBefore` (missing key = never revoked). A token authenticates AI-proxy calls only — it **cannot** claim free coins or transfer coins, since those require the raw private key. A leaked token can run up AI-proxy usage against the wallet's balance until revoked/expired; it cannot drain coins via transfer.

### Balance gate (applies after either auth scheme verifies an address)
**1 aicoin is worth 1 paid AI call — enforced, not just a tagline.** Before forwarding, the proxy atomically checks and debits exactly 1.0 aicoin from the verified address's balance (`AicoinLedger.debitForCall`, a single Redis Lua script — no separate read-then-write, so two concurrent calls can't both pass a stale check and overdraw). If that call fails/times out (Redis unreachable) → `503 {"error":"could not validate wallet"}`. Otherwise:
- Balance `< 1.0` → `402 {"error":"insufficient aicoin balance","balance":<value>}` — do not forward to the AI provider, no debit applied. Client apps integrating the wallet should treat `402` from this proxy as the one and only signal to fall back to the user's own provider key for that request (see each app's own integration notes for exactly how).
- Balance `>= 1.0` → the 1.0 is debited immediately and the call proceeds. If the upstream call then fails (non-2xx or connection failure), the debit is **refunded** — see "Forwarding" below — since the proxy was never actually billed by the real provider for a call that didn't complete. This is also the boundary that keeps *paid* calls (successful, billed, feeding the price formula) cleanly distinguished from *free* activity (a faucet claim mints coins but is never treated as a call; a failed call never counts as paid and its debit is reversed).

### Forwarding (non-streaming, full aggregation is fine)
1. Inbound: `HttpServerCodec` + `HttpObjectAggregator` → routing handler.
2. Outbound: new Netty client `Bootstrap` per request to upstream host, `HttpClientCodec` + `HttpObjectAggregator`, TLS via `SslContextBuilder.forClient()` when baseUrl is https.
3. Write the upstream's exact status/headers/body back to the client.
4. If upstream status is 2xx (a genuine paid call): compute `cost_usd` — parse JSON body for `usage.total_tokens` (OpenAI-style) or `usage.input_tokens`+`usage.output_tokens` (Anthropic-style); `cost_usd = tokens * pricing.costPerTokenUsd` if found, else `pricing.defaultCostUsdPerCall`. Record the event into the ledger in-process (fire-and-forget `ZADD`, see below) — must never block or fail the client response; log+ignore errors. The 1.0 aicoin debit from the balance gate stands.
5. Non-2xx/connection failure: relay the real error to the client (or a synthetic `502` for a connection failure — there is no real status to relay), do not record a price event, and **refund** the 1.0 aicoin debited before forwarding (`AicoinLedger.refund`) — the call never actually cost the proxy anything, so it shouldn't cost the wallet anything either.

### Ledger (Redis)

Backed by a single Redis instance — ElastiCache for Redis (with snapshotting
enabled for durability) in production, a plain `redis:7-alpine` container
locally/in e2e. No signing, no chain, no replication: this is a centralized
ledger, and the only two ways to *acquire* aicoin are the free faucet and a
peer transfer. A paid AI call *spends* exactly 1.0 aicoin (refunded if the
call fails) and separately feeds the price formula with its real USD cost —
two independent effects of the same event, never conflated with faucet
claims or transfers.

Keys, namespaced under `aicoin:`:

| Concept | Key | Type | Notes |
|---|---|---|---|
| Price event log | `aicoin:events` | ZSET | member = `<costUsd>\|<uuid>` (uuid keeps members unique since ZSET dedupes by member), score = event epoch-millis timestamp — fed **only** by genuine 2xx paid calls, never by claims/transfers/failed calls |
| Wallet balance | `aicoin:balance:{address}` | String (float) | `INCRBYFLOAT` for claim mints, transfers, and call refunds (all plain atomic ops); check-then-debit for claims/transfers/call-debits uses the Lua scripts below |
| Last free-claim time | `aicoin:lastclaim:{address}` | String (epoch-millis) | read/written only inside the claim Lua script below |
| Free-coins pool remaining | `aicoin:free-coins-remaining` | String (int) | shared across every wallet, lazily initialized to `aicoin.freeCoinsPoolSize` on first-ever claim; read/written only inside the claim Lua script |
| Token revocation | `aicoin:token-revoked-before:{address}` | String (epoch-millis) | set by `POST /wallet/api/revoke-tokens`; missing = never revoked |
| Known wallets | `aicoin:known-wallets` | SET | every address ever seen as a claim recipient, transfer party, or call payer — `SADD`ed inside the same Lua script as the mutation it accompanies. Powers the admin page's wallet list; nothing else reads it |
| Transaction log | `aicoin:tx:{address}` | LIST | one JSON object per claim/transfer/debit/refund touching this address, appended (`RPUSH`) inside the same script as the balance mutation, capped to the most recent 200 (`LTRIM`) — see "Admin page" below for the exact shape |

**Price (final formula, v2: smooth exponential decay)** — unchanged math from
before, now computed by fetching the full event log rather than folding
over a chain. **1 aicoin's price = a recency-weighted average of `cost_usd`
across every priced event ever recorded — NOT divided by number of users.**
Every event contributes `cost_usd * weight(age)`, where `age` = `now -
event.timestamp` (wall-clock "now" at query time; a negative age from clock
skew/future timestamps clamps to `0`, giving `weight = 1.0`), and:

```
weight(age) = 2 ^ (-age_days / halfLifeDays)
```

a single continuous, smoothly-decreasing curve — no calendar buckets, no
step-function jumps at hour/day/week/month boundaries. `halfLifeDays` is the
config key `aicoin.decayHalflifeDays` (default **110.0**).

**Why 110 days**: calibrated from a real, well-documented industry data
point — AI inference/API pricing has fallen roughly **10x per year** across
major providers (e.g. OpenAI's public per-token pricing dropped roughly 10x
from the GPT-3.5-turbo era (early 2023) to GPT-4o-mini-class pricing (mid-
2024)). A 10x-per-year decline implies a half-life of `365.25 *
ln(2)/ln(10) ≈ 110 days`. This is a documented industry rule-of-thumb, not a
precise proprietary dataset. The economic intuition: an old cost figure
shouldn't count as much toward *today's* price precisely because AI got
cheaper by roughly that much since it was recorded.

Named checkpoints (informational only — computed from the one formula above), under the default half-life:

| age | weight |
|---|---|
| 1 hour | ≈ 1.000 |
| 1 day | ≈ 0.994 |
| 1 week | ≈ 0.957 |
| 1 month (30.44d) | ≈ 0.825 |
| 1 quarter (91.31d) | ≈ 0.563 |
| 1 year (365.25d) | ≈ 0.100 (by construction) |
| 5 years | ≈ 0.00001 |

`price_usd = Σ(weight(age_i) * cost_usd_i) / Σ(weight(age_i))` over every
event. Zero events → `price_usd = 0`. Implemented as a pure function
(`PriceCalculator`) over the events fetched from Redis, so it's unit-testable
without a live Redis connection. This is purely a market-rate *signal*, distinct
from the fixed 1-aicoin-per-call spending rate above — a call always costs
1.0 aicoin regardless of what the price formula currently reports.

**Atomicity**: claim, transfer, and the per-call debit are all check-then-mutate
operations that must be atomic against concurrent calls for the same wallet
(or the same shared pool), or two concurrent claims could both mint/double-count
the pool, two concurrent transfers could both pass a stale balance check and
overdraw, or two concurrent calls could both debit past zero. All three run
as single Redis Lua `EVAL` scripts, which Redis executes atomically with
respect to every other client:
- **Claim**: read `lastclaim:{address}`; if `now - lastclaim < cooldownMillis`
  return not-eligible (`reason:"cooldown"`). Else read the shared pool
  counter (lazily initialized to `aicoin.freeCoinsPoolSize` if never set);
  if `< freeClaimAmount` (10 aicoin) return not-eligible
  (`reason:"pool_exhausted"`) without touching balance/cooldown — a claim
  always grants the full amount or nothing, never a partial top-up of
  whatever's left in the pool. Else `SET lastclaim now` +
  `INCRBYFLOAT balance freeClaimAmount` + decrement the pool counter by
  the same amount, and return granted.
- **Transfer**: read `balance:{from}`; if `< amount` return insufficient;
  else `INCRBYFLOAT balance:from -amount` + `INCRBYFLOAT balance:to amount`.
- **Call debit**: read `balance:{address}`; if `< 1.0` return insufficient
  (with the current balance, for the `402` body); else `INCRBYFLOAT balance
  -1.0` and return success. A failed upstream call reverses this with a
  plain `INCRBYFLOAT balance +1.0` (no script needed for a pure increment).

### Free-coin faucet
`POST /wallet/api/claim` — live-signed, no body needed. Runs the claim script
above against both the per-wallet cooldown (`aicoin.freeClaimCooldownSeconds`,
default 3600 = 1 hour) and the shared pool (`aicoin.freeCoinsPoolSize`,
default 100 — a single pool shared across *every* wallet, not a per-wallet
allowance). A successful claim mints a fixed `freeClaimAmount` of 10 aicoin
(hardcoded, not configurable — the "up to 10 free coins per wallet per hour"
rate limit is *this* fixed amount plus the hour-long cooldown, not a
separate rolling counter). Granted →
`200 {"granted":true,"amount":10,"next_eligible_at":"RFC3339"}`.
Not yet eligible → `429 {"granted":false,"reason":"cooldown","next_eligible_at":"RFC3339"}`.
Pool exhausted → `429 {"granted":false,"reason":"pool_exhausted"}` — this can
reject a wallet that has never claimed before, since the constraint is
global, not per-wallet; the cooldown check always runs first, so a
still-on-cooldown wallet gets `reason:"cooldown"` even if the pool also
happens to be empty.

### Peer transfer (buy/sell)
`POST /wallet/api/transfer` — live-signed, body `{"to_user_id":"<address>","amount":N}` (`from` is always the verified signer). Runs the transfer script above. Validates `amount > 0` and current balance of the signer `>= amount`; if not, `400 {"error":"insufficient balance"}`. This is the *entire* buy/sell mechanism — no real money, no external payment rail: "buying" is just receiving a transfer, "selling" is sending one.

### Additional proxy-side endpoints
- `GET /price` → `{"price_usd":..,"total_spend_usd":..,"weighted_total":..,"half_life_days":110}` computed directly from the ledger — `total_spend_usd` is the plain unweighted all-time sum (visibility only), `weighted_total` is `Σweight_i` (the formula's denominator, for debugging/verification), `half_life_days` is the configured decay half-life. Always includes `Access-Control-Allow-Origin: *` — this is public, read-only data fetched cross-origin by the landing page at aicoin.oeaio.com (a separate origin from the proxy).
- `GET /free-coins/available` → `{"available": N}` — the real, live remaining count in the shared Redis-backed pool (`AicoinLedger.getFreeCoinsRemaining`), the same counter `POST /wallet/api/claim` atomically decrements. Not a static admin-managed file — this number is authoritative and changes in real time as wallets claim. A ledger-lookup failure resolves to `{"available": 0}`.
- `GET /health` — for each configured provider (openai, anthropic, google, mistral, cohere, elevenlabs, stability), report whether recent upstream calls have hit rate-limiting or budget errors, and whether the proxy has a real (non-empty) `apiKey` configured for it at all (`enabled`) — this is what the landing page reads to show which AI backends are actually live. Track, per provider, a rolling window of the last `health.windowSize` forwarded calls (config, default 50, env `AICOIN_PROXY_HEALTH_WINDOW_SIZE`): `rateLimited` = true if any upstream response in the window was HTTP 429; `overBudget` = true if any was HTTP 402 or 403; `healthy` = `!rateLimited && !overBudget`. Response: `{"providers":[{"name":"openai","enabled":true,"healthy":true,"rateLimited":false,"overBudget":false}, ...]}` (all providers always listed, even ones with zero calls so far — those default to `healthy:true`/`enabled` reflects config regardless of traffic). Always includes `Access-Control-Allow-Origin: *`, same as `/price`, since the landing page fetches it cross-origin too.
- `GET /price/history?points=N` (default 60, max 500) → `{"half_life_days":110,"points":[{"at":epochMillis,"price_usd":N}, ...]}` — reconstructs how `price_usd` arrived at its current value: `N` evenly-spaced samples between the earliest recorded event and now, each computed by re-running the exact same price formula as if `now` were that sample's timestamp, using only the events that had actually happened by then (a naive re-use of the full event list per sample would let a "future" event's clock-skew-clamped weight of 1.0 leak into a past sample). Zero events → `{"points":[]}`, not an error. Always includes `Access-Control-Allow-Origin: *`, same as `/price`.
- `GET /price/chart` — a bundled static page: current price plus a canvas-drawn line graph of `/price/history`, with buttons to change the sample count. No auth, same posture as `/wallet`/`/admin`'s page markup.

### Wallet web page
- `GET /wallet` — serves a bundled static HTML/CSS/JS page (single file, no build step) that lets a user manage a wallet directly in the browser: generate a new Ed25519 keypair or import one from a backup blob (private key material stays client-side, in-memory + `localStorage`), view its address/balance/the current price, claim the free-coin faucet, transfer coins to another address, issue an API token (with a selectable expiry), and revoke all previously issued tokens. No auth on the page itself — same posture as everything else here.
- The page's JS only calls same-origin, relative paths — no CORS needed. Backing endpoints, all backed directly by the ledger:
  - `GET /wallet/api/balance/{address}` → `{"user_id":"...","balance":N}` — unsigned; a public address's balance is inherently public info, like a blockchain explorer.
  - `POST /wallet/api/claim`, `POST /wallet/api/transfer`, `POST /wallet/api/revoke-tokens` → live-signed, see "Auth for wallet-management actions" above.
- The balance-lookup endpoint is checked before the generic `X-AI`-header routing/auth logic (same as `/price`, `/free-coins/available`, `/health`) — it needs no auth, since it's how a wallet checks its own state before ever claiming a free coin.

### Admin page
Every other endpoint here is either public read-only data or gated by proving
control of one specific wallet — this is the one exception: an operator
surface that reveals **every** known wallet's balance and full transaction
history, so it needs its own, separate auth.
- `GET /admin` — serves a bundled static HTML/CSS/JS page (single file, no
  build step), same posture as `GET /wallet`: no auth on the page markup
  itself, just an input for the admin token and a table. The page stores the
  token in `localStorage` and sends it as `X-Admin-Token` on every data call.
- `GET /admin/wallets` → `{"wallets":[{"address":"...","balance":N,"transaction_count":N}, ...]}`,
  every address in `aicoin:known-wallets`, sorted by balance descending.
- `GET /admin/wallets/{address}/transactions` → `{"address":"...","transactions":[{...}, ...]}`,
  the full contents of `aicoin:tx:{address}` (see the Ledger table above),
  most-recent first. Each entry is one of:
  - `{"type":"claim","amount":N,"balance_after":N,"at":epochMillis}`
  - `{"type":"transfer_out"|"transfer_in","amount":N,"counterparty":"<address>","balance_after":N,"at":epochMillis}`
  - `{"type":"debit"|"refund","amount":N,"provider":"<name>","balance_after":N,"at":epochMillis}`
- **Auth**: both data endpoints require a header `X-Admin-Token: <token>`
  matching `aicoin.adminToken` (env `AICOIN_PROXY_ADMIN_TOKEN`), compared in
  constant time. That config value defaults to empty, which disables this
  entire surface — both endpoints respond `503 {"error":"admin disabled"}`
  regardless of any header — so a freshly deployed instance never exposes
  every wallet's balance by accident; an operator must deliberately set the
  token. A missing/wrong token (once a real token is configured) is `401`.

### Tests to include
- JUnit5 pure-function tests: `X-AI`→provider resolution (incl. missing/unknown → 400), auth-injection header/query-param construction per provider, usage-JSON→cost_usd parsing, the price-weight formula and its checkpoint table, the Ed25519 live-signature/token verification logic (valid/tampered/expired/revoked cases, against genuinely-generated keypairs), and the admin token's constant-time comparison + address-format validation — no network/Redis needed.

## Docker / docker-compose
- `aicoin-proxy/Dockerfile` — multi-stage: `./gradlew build` in a JDK-26 build stage, copy the `application` plugin's install output (`build/install/aicoin-proxy/`) into a JRE-26 runtime image. Entrypoint runs the generated start script.
- Repo-root `docker-compose.yml`: `redis` (`redis:7-alpine`, `--save 60 1000` snapshotting), `aicoin-proxy` (built from `aicoin-proxy/Dockerfile`, pointed at `redis` via `AICOIN_PROXY_REDIS_HOST`/`_PORT`). Production points those env vars at a real ElastiCache endpoint instead.
