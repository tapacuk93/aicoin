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
  username: ""                    # AICOIN_PROXY_REDIS_USERNAME (empty = no ACL username; production MemoryDB for Valkey requires ACL username+password auth, not just a password)
  password: ""                    # AICOIN_PROXY_REDIS_PASSWORD (empty = no AUTH)
  ssl: false                      # AICOIN_PROXY_REDIS_SSL (true for ElastiCache/MemoryDB in-transit encryption)
aicoin:
  decayHalflifeDays: 110.0        # AICOIN_PROXY_DECAY_HALFLIFE_DAYS
  freeClaimCooldownSeconds: 3600  # AICOIN_PROXY_FREE_CLAIM_COOLDOWN_SECONDS
  signatureSkewSeconds: 120       # AICOIN_PROXY_SIGNATURE_SKEW_SECONDS
  freeCoinsPoolSize: 5000         # AICOIN_PROXY_FREE_COINS_POOL_SIZE (total coins the faucet will ever give away)
  adminToken: ""                  # AICOIN_PROXY_ADMIN_TOKEN (empty = admin page/API disabled)
iap:
  acceptSandboxPurchases: false   # AICOIN_PROXY_IAP_ACCEPT_SANDBOX — production MUST leave this false
providers:
  openai:
    baseUrl: https://api.openai.com          # AICOIN_PROXY_OPENAI_BASEURL
    apiKey: ""                               # AICOIN_PROXY_OPENAI_APIKEY  (proxy's own paid key, injected into every forwarded request)
    authHeader: Authorization                # AICOIN_PROXY_OPENAI_AUTHHEADER
    authPrefix: "Bearer "                    # AICOIN_PROXY_OPENAI_AUTHPREFIX
    freePaths: ["GET /v1/models", "GET /v1/models/*"]   # AICOIN_PROXY_OPENAI_FREEPATHS
  anthropic:
    baseUrl: https://api.anthropic.com       # AICOIN_PROXY_ANTHROPIC_BASEURL
    apiKey: ""                               # AICOIN_PROXY_ANTHROPIC_APIKEY
    authHeader: x-api-key                    # AICOIN_PROXY_ANTHROPIC_AUTHHEADER
    authPrefix: ""                           # AICOIN_PROXY_ANTHROPIC_AUTHPREFIX
    freePaths: ["GET /v1/models", "GET /v1/models/*", "POST /v1/messages/count_tokens"]   # AICOIN_PROXY_ANTHROPIC_FREEPATHS
  google:
    baseUrl: https://generativelanguage.googleapis.com  # AICOIN_PROXY_GOOGLE_BASEURL
    apiKey: ""                               # AICOIN_PROXY_GOOGLE_APIKEY
    authAsQueryParam: true                   # AICOIN_PROXY_GOOGLE_AUTHASQUERYPARAM
    authQueryParamName: key                  # AICOIN_PROXY_GOOGLE_AUTHQUERYPARAMNAME
    freePaths: ["GET /v1/models", "GET /v1/models/*", "GET /v1beta/models", "GET /v1beta/models/*",
                "POST /v1/models/*:countTokens", "POST /v1beta/models/*:countTokens"]   # AICOIN_PROXY_GOOGLE_FREEPATHS
  mistral:
    baseUrl: https://api.mistral.ai          # AICOIN_PROXY_MISTRAL_BASEURL
    apiKey: ""                               # AICOIN_PROXY_MISTRAL_APIKEY
    authHeader: Authorization                # AICOIN_PROXY_MISTRAL_AUTHHEADER
    authPrefix: "Bearer "                    # AICOIN_PROXY_MISTRAL_AUTHPREFIX
    freePaths: ["GET /v1/models", "GET /v1/models/*"]   # AICOIN_PROXY_MISTRAL_FREEPATHS
  cohere:
    baseUrl: https://api.cohere.ai           # AICOIN_PROXY_COHERE_BASEURL
    apiKey: ""                               # AICOIN_PROXY_COHERE_APIKEY
    authHeader: Authorization                # AICOIN_PROXY_COHERE_AUTHHEADER
    authPrefix: "Bearer "                    # AICOIN_PROXY_COHERE_AUTHPREFIX
    freePaths: ["GET /v1/models", "GET /v1/models/*", "POST /v1/tokenize", "POST /v1/detokenize",
                "POST /v1/check-api-key"]     # AICOIN_PROXY_COHERE_FREEPATHS
  elevenlabs:
    baseUrl: https://api.elevenlabs.io        # AICOIN_PROXY_ELEVENLABS_BASEURL
    apiKey: ""                               # AICOIN_PROXY_ELEVENLABS_APIKEY
    authHeader: xi-api-key                   # AICOIN_PROXY_ELEVENLABS_AUTHHEADER
    authPrefix: ""                           # AICOIN_PROXY_ELEVENLABS_AUTHPREFIX
    freePaths: ["GET /v1/models", "GET /v1/voices", "GET /v1/voices/*", "GET /v1/user",
                "GET /v1/user/subscription"]  # AICOIN_PROXY_ELEVENLABS_FREEPATHS
  stability:
    baseUrl: https://api.stability.ai        # AICOIN_PROXY_STABILITY_BASEURL
    apiKey: ""                               # AICOIN_PROXY_STABILITY_APIKEY
    authHeader: Authorization                # AICOIN_PROXY_STABILITY_AUTHHEADER
    authPrefix: "Bearer "                    # AICOIN_PROXY_STABILITY_AUTHPREFIX
    freePaths: ["GET /v1/engines/list", "GET /v1/user/account", "GET /v1/user/balance"]   # AICOIN_PROXY_STABILITY_FREEPATHS
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

### Paid targets vs free targets (applies before the balance gate)
Not every endpoint a provider exposes is one it bills the proxy for: model listings, ElevenLabs voice listings, token counting (Anthropic `/v1/messages/count_tokens`, Google `:countTokens`), and account/balance lookups cost the proxy nothing upstream. Each provider's `providers.<name>.freePaths` lists those **free targets**; every other endpoint is a **paid target** and goes through the balance gate below.

A free target is still a fully authenticated, fully forwarded call — valid API token required, `X-AI` required, the proxy's own paid key injected, response relayed verbatim, upstream status recorded for `GET /health`. The only difference is monetary: **no debit, no refund, no price event.** Consequences that matter to clients: a wallet at balance 0 can still list models/voices (only inference needs a coin), and `GET /price` is never inflated by `defaultCostUsdPerCall` for calls the proxy was never billed for.

Pattern syntax (`FreeTargets`): optional method, a space, then a path glob where `*` matches any run of characters — `GET /v1/models`, `GET /v1/models/*`, `POST /v1beta/models/*:countTokens`, or a bare `/v1/voices` to match any method. Methods compare case-insensitively, paths case-sensitively, query string excluded. Matching **fails closed**: a path containing a percent-escape or a `.`/`..` segment is always treated as paid, since the upstream would normalize `/v1/models/../chat/completions` onto a billed endpoint the glob would otherwise have freed. `AICOIN_PROXY_<PROVIDER>_FREEPATHS` overrides a provider's list (comma-separated); the literal value `none` bills every endpoint of that provider again. The bundled lists track what providers bill today — if one starts charging for a listed endpoint, remove it there.

### Balance gate (applies to a paid target, after either auth scheme verifies an address)
**A paid call costs at least 1 aicoin, and under metered billing costs what it cost to run — enforced, not just a tagline.** The gate up front is unchanged and always holds exactly 1.0 aicoin, so "one coin is enough to make one call" remains true and an empty wallet is refused before any provider is touched; when `pricing.metered` is on, the remainder is settled after the upstream answers (see "Forwarding"), because that answer is the only place the call's real cost exists. Before forwarding, the proxy atomically checks and debits exactly 1.0 aicoin from the verified address's balance (`AicoinLedger.debitForCall`, a single Redis Lua script — no separate read-then-write, so two concurrent calls can't both pass a stale check and overdraw). If that call fails/times out (Redis unreachable) → `503 {"error":"could not validate wallet"}`. Otherwise:
- Balance `< 1.0` → `402 {"error":"insufficient aicoin balance","balance":<value>}` — do not forward to the AI provider, no debit applied. Client apps integrating the wallet should treat `402` from this proxy as the one and only signal to fall back to the user's own provider key for that request (see each app's own integration notes for exactly how).
- Balance `>= 1.0` → the 1.0 is debited immediately and the call proceeds. If the upstream call then fails (non-2xx or connection failure), the debit is **refunded** — see "Forwarding" below — since the proxy was never actually billed by the real provider for a call that didn't complete. This is one of the two boundaries that keep *paid* calls (successful, billed, feeding the price formula) cleanly distinguished from *free* activity (a faucet claim mints coins but is never treated as a call; a failed call never counts as paid and its debit is reversed). The other is target-side: a call to a free target never reaches this gate at all (see above).

### Forwarding (non-streaming, full aggregation is fine)
1. Inbound: `HttpServerCodec` + `HttpObjectAggregator` → routing handler.
2. Outbound: new Netty client `Bootstrap` per request to upstream host, `HttpClientCodec` + `HttpObjectAggregator`, TLS via `SslContextBuilder.forClient()` when baseUrl is https.
3. Write the upstream's exact status/headers/body back to the client.
4. If upstream status is 2xx (a genuine paid call): decompress the body if the upstream sent it compressed (clients send `Accept-Encoding: gzip` by default, and gzip carries no readable usage), then compute `cost_usd` from the provider's own usage figures at that provider's and model's configured rates — input and output priced separately, cache reads and writes at 0.1x and 1.25x of input, falling back to a per-call figure for providers that report no tokens (see `pricing.providers`). Record the event into the ledger in-process (fire-and-forget `ZADD`, see below) — must never block or fail the client response; log+ignore errors. The 1.0 aicoin debit from the balance gate stands; when `pricing.metered` is on, `ceil(cost_usd / pricing.coinValueUsd)` coins are owed in total (floor 1, capped per call) and the difference is settled then. Settlement cannot refuse — the response has already gone out — so it takes what the wallet holds, floors at zero rather than going negative, and records any shortfall on the ledger entry. Every billed response carries `X-Aicoin-Charged` with the coins taken, metered or not.
5. Non-2xx/connection failure: relay the real error to the client (or a synthetic `502` for a connection failure — there is no real status to relay), do not record a price event, and **refund** the 1.0 aicoin debited before forwarding (`AicoinLedger.refund`) — the call never actually cost the proxy anything, so it shouldn't cost the wallet anything either.
6. Free target: `UpstreamForwarder` is handed a call cost of `0`, so steps 4 and 5's *money* handling is skipped in both directions — nothing was debited, so nothing is refunded on failure, and no price event is recorded on success. Relaying and `/health` recording are unchanged.

### Ledger (Redis)

Backed by a single Redis instance — ElastiCache for Redis (with snapshotting
enabled for durability) in production, a plain `redis:7-alpine` container
locally/in e2e. No signing, no chain, no replication: this is a centralized
ledger, and the only two ways to *acquire* aicoin are the free faucet and a
peer transfer. A paid AI call *spends* exactly 1.0 aicoin (refunded if the
call fails) and separately feeds the price formula with its real USD cost —
two independent effects of the same event, never conflated with faucet
claims or transfers.

**Key naming — every key carries the fixed Redis Cluster hash tag `{aicoin}`**, i.e. the real key names are `aicoin:{aicoin}:balance:<address>`, `aicoin:{aicoin}:events`, and so on. The table below omits the tag for readability; the code (`AicoinLedger.TAG`) is authoritative. This is **not** cosmetic: production runs on AWS MemoryDB, which is *always* cluster-mode even at a single shard, and Redis Cluster rejects any multi-key command whose keys span different hash slots with `CROSSSLOT` — regardless of those slots living on the same node. Every atomic operation here is inherently multi-key (a claim touches balance + lastclaim + the shared pool + known-wallets + the tx log; a transfer touches *two different wallets'* balances), so per-wallet tagging would not suffice — cross-wallet transfers would still cross slots. One fixed tag for the whole ledger is the only scheme keeping all of them single-slot. The trade-off is deliberate: a centralized ledger confined to one slot costs nothing today, but it cannot be spread across shards — scaling out would mean re-sharding the key scheme and giving up cross-wallet atomicity, a far larger change than simply using a bigger node.

Keys, namespaced under `aicoin:` (each additionally carrying the `{aicoin}` hash tag described above):

| Concept | Key | Type | Notes |
|---|---|---|---|
| Price event log | `aicoin:events` | ZSET | member = `<costUsd>\|<uuid>` (uuid keeps members unique since ZSET dedupes by member), score = event epoch-millis timestamp — fed **only** by genuine 2xx paid calls, never by claims/transfers/failed calls or free targets |
| Wallet balance | `aicoin:balance:{address}` | String (float) | `INCRBYFLOAT` for claim mints, transfers, and call refunds (all plain atomic ops); check-then-debit for claims/transfers/call-debits uses the Lua scripts below |
| Last free-claim time | `aicoin:lastclaim:{address}` | String (epoch-millis) | read/written only inside the claim Lua script below |
| Free-coins pool remaining | `aicoin:free-coins-remaining` | String (int) | shared across every wallet, lazily initialized to `aicoin.freeCoinsPoolSize` on first-ever claim; read/written only inside the claim Lua script |
| Token revocation | `aicoin:token-revoked-before:{address}` | String (epoch-millis) | set by `POST /wallet/api/revoke-tokens`; missing = never revoked |
| Known wallets | `aicoin:known-wallets` | SET | every address ever seen as a claim recipient, transfer party, or call payer — `SADD`ed inside the same Lua script as the mutation it accompanies. Powers the admin page's wallet list; nothing else reads it |
| Transaction log | `aicoin:tx:{address}` | LIST | one JSON object per claim/transfer/debit/refund/iap touching this address, appended (`RPUSH`) inside the same script as the balance mutation, capped to the most recent 200 (`LTRIM`) — see "Admin page" below for the exact shape |
| IAP coin packages | `aicoin:iap-packages` | String (JSON array) | the current `GET /iap/packages` list; lazily seeded from config's `iap.packages` on first read if unset, atomically overwritten by `POST /admin/iap/packages` — see "IAP: buying aicoin with real money" below |
| Current offer | `aicoin:offer` | String (JSON object) | the single coin amount every app is selling right now, served by `GET /iap/offer` and written by `POST /admin/iap/offer`. **Not** seeded from config — unset means "nothing is on sale", which is a real state, not a missing one. See "The current offer" below |
| Pinned offer | `aicoin:offer-pin:{offerId}` | String (JSON object) | one per `POST /iap/offer/check`, written with a 900s TTL — the amount promised to an app about to open Apple's purchase sheet, honoured by `redeem-iap` even after the live offer changes. Expiry is the only cleanup; nothing deletes these explicitly |
| IAP redemption idempotency marker | `aicoin:iap-redeemed:{transactionId}` | String (`"1"`) | `SETNX`-ed inside the same Lua script that credits the balance in `POST /wallet/api/redeem-iap`, so a StoreKit retry of an already-finished transaction is a safe no-op, never a double-credit |

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

**Atomicity**: claim, transfer, the per-call debit, and IAP redemption are all
check-then-mutate operations that must be atomic against concurrent calls for
the same wallet (or the same shared pool), or two concurrent claims could
both mint/double-count the pool, two concurrent transfers could both pass a
stale balance check and overdraw, two concurrent calls could both debit past
zero, or two concurrent/retried IAP redemptions of the same `transactionId`
could double-credit. All four run as single Redis Lua `EVAL` scripts, which
Redis executes atomically with respect to every other client:
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
- **IAP redemption**: `SETNX iap-redeemed:{transactionId}`; if it was already
  set (a replay), return the current balance unchanged, no credit applied.
  Else `INCRBYFLOAT balance:{to_user_id} coins*quantity` and return the new
  balance — same script, so the idempotency marker and the credit can never
  observably diverge.

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
  - `{"type":"iap","amount":N,"product_id":"<id>","balance_after":N,"at":epochMillis}`
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
- `aicoin-proxy/Dockerfile` — multi-stage: build stage on **GraalVM** (not vanilla OpenJDK), copy the `application` plugin's install output (`build/install/aicoin-proxy/`) into a matching GraalVM runtime image. Entrypoint runs the generated start script. GraalVM chosen for a smaller heap footprint on the cheap single-vCPU production host (see "Production hosting" below); full ahead-of-time `native-image` is a future option, not required now, since the app plugin's script + GraalVM JIT already meets the cost/perf bar.
  **Version note (corrected from an earlier draft of this doc):** this originally called for "GraalVM JDK 26" specifically, but as of this writing (Aug 2026) GraalVM has not published a JDK 26 build at all — verified against `ghcr.io/graalvm/graalvm-community`'s live tag list (no `26` tag; latest is the 25.x line) and graalvm.org's release calendar (no JDK 26 entry, even planned). `aicoin-proxy/Dockerfile`, `build.gradle`'s toolchain, and both `.java-version` files are pinned together to the latest real, published GraalVM major line (currently **25**, e.g. `ghcr.io/graalvm/graalvm-community:25`) instead of a nonexistent tag — bump all of them to `26` together the moment GraalVM actually ships that build.
- Repo-root `docker-compose.yml`: `redis` (`redis:7-alpine`, `--save 60 1000` snapshotting), `aicoin-proxy` (built from `aicoin-proxy/Dockerfile`, pointed at `redis` via `AICOIN_PROXY_REDIS_HOST`/`_PORT`). Local/e2e only — plain Redis snapshotting is fine for a throwaway dev container. Production points those env vars at a real MemoryDB endpoint instead (see below), never at ElastiCache.

## Production hosting decision

**Persistence: MemoryDB for Valkey, not ElastiCache.** ElastiCache's only durability mechanism is periodic RDB snapshots — a crash between snapshots loses every write since the last one, which is unacceptable for a ledger that *is* real user funds. MemoryDB durably commits every write to a multi-AZ distributed transaction log before acknowledging it — genuinely "all-time persistent," not just periodically persisted — while remaining an in-memory store, so read/write latency stays effectively as fast as ElastiCache (sub-millisecond). Within that "actually durable" tier, Valkey is chosen over MemoryDB's Redis-OSS-compatible option because AWS prices Valkey lower for the same node class with no functional loss (Lettuce speaks RESP either way). Cheapest viable topology: **one `db.t4g.small` node, zero replicas** (~$0.060/hr ≈ **$44/mo** in us-east-1) — MemoryDB's durability comes from the transaction-log service itself, not from having a replica, so a replica only buys HA/read-scaling, not durability, and isn't needed at this traffic level. `redis.ssl: true` is required against MemoryDB (in-transit TLS is not optional). MemoryDB for Valkey also requires ACL username+password auth rather than a bare password — `redis.username`/`AICOIN_PROXY_REDIS_USERNAME` (empty by default, matching every local/e2e Redis, which has no ACL configured) must be set to the configured ACL username in production; `AicoinLedger` uses Lettuce's `RedisURI.Builder.withAuthentication(username, password)` instead of `withPassword(...)` whenever a non-empty username is present.

**Compute: a single Lightsail Linux VM, not ECS/Fargate/App Runner/Lightsail Container Service.** All of those managed-container options carry either a per-vCPU-second premium or a higher fixed floor (Container Service nano ≈ $7/mo, App Runner's baseline is pricier still) than simply running Docker Engine directly on the cheapest Lightsail VM with a public IPv4 (the $5/mo "nano" plan: 512MB/2vCPU/20GB SSD/1TB transfer — the $3.50/mo tier is IPv6-only, which risks unreachability for clients on IPv4-only networks, so it's not used). `docker compose up -d` on that box runs `aicoin-proxy` plus a small Caddy container in front of it for automatic Let's Encrypt TLS + reverse proxy to `proxy.aicoin.oeaio.com`. Total steady-state infra cost: **≈ $44 (MemoryDB) + $5 (Lightsail) + ~$1 (Route53 hosted zone + query volume) ≈ $50/mo**, before AI provider spend itself (which is pass-through, funded by aicoin sales).

## AICoin pricing (IAP packages) and provider prediction

**1 aicoin is fixed at exactly 1 paid AI call, always** (see Balance gate above) — the *market* `/price` (real recency-weighted USD cost) is a separate, informational signal used only to decide what to *charge* for a package of coins, never to change the 1-coin-per-call spend rate.

**Cost-per-call estimate**: none of the three client apps default to the cheapest possible model — all three market themselves around Anthropic Claude specifically (Infinite AI Radio's tagline is "powered by Claude"; it and All Languages Learner both wire `ClaudeClient` as their one dedicated/primary text client; Learn Its' `AIProviderRegistry` lists Anthropic alongside OpenAI-compatible options but Claude is the natural default for the same reason). **Prediction: Anthropic Claude will be the most-selected provider in aggregate usage** — it's the only provider all three apps are built and marketed around; the others (OpenAI/Gemini/Mistral/etc.) exist as user-chosen alternatives, not defaults. Blended call cost, assuming a Claude Haiku-class model and the token mix these apps actually generate (a several-hundred-word passage/lesson/monologue segment, ~1,000–2,000 tokens total in+out): **≈ $0.003–0.006/call** — above the proxy's `defaultCostUsdPerCall` fallback (0.001, deliberately conservative) and consistent with `costPerTokenUsd` (0.000002/token × ~1,500 tokens ≈ $0.003). Once real traffic flows, `/price` supersedes this estimate automatically — it's a live number, not a fixed config.

**Recommended launch packages** (coins fixed, USD price derived as `coins × cost_per_call × (1 + feeMargin) / (1 − appleCut)`, `appleCut = 0.30` flat for consumable IAP regardless of app age, `feeMargin = 0.50`, then rounded to a normal-looking App Store price point), using the $0.004/call midpoint of the estimate above:

| Package | Coins | Raw formula | Launch price |
|---|---|---|---|
| Small | 50 | $0.43 | **$0.99** |
| Medium | 200 | $1.71 | **$2.99** |
| Large | 1,000 | $8.57 | **$9.99** |
| XL | 5,000 | $42.86 | **$44.99** |

These are *starting* prices, seeded into `aicoin:iap-packages` at first boot and adjustable at any time via the admin script (see "IAP coin packages" below) or automatically (see "Automatic price adjustment" below) as real `/price` data replaces the estimate.

**Rounding rule note (added for implementation clarity):** the four launch prices above ($0.99/$2.99/$9.99/$44.99) are the literal, hardcoded seed values in `iap.packages`/`application.yaml` — they are not recomputed at runtime and always match this table exactly regardless of any rounding function. The *mechanical* "round to a normal-looking App Store price point" rule used by the automatic-adjustment job below (`AppStorePriceRounding.java`, mirrored in `adjust-iap-prices.sh`) is nearest-tier rounding against a standard ladder of `$X.99` price points. Applied to this table's own raw values, nearest-tier rounding reproduces the Small ($0.43→$0.99) and XL ($42.86→$44.99) rows exactly, but would round Medium and Large one tier lower than shown here ($1.71→$1.99, not $2.99; $8.57→$8.99, not $9.99) — those two entries were chosen as clean, memorable launch numbers rather than mechanically derived. This is a documented, intentional discrepancy between the illustrative table above and the mechanical rule the automatic job actually runs going forward; it only matters once real `/price` data starts moving prices away from these seed values.

## IAP: buying aicoin with real money (App Store)

There are now exactly two ways to *acquire* aicoin: the existing peer transfer (unchanged), and a real in-app purchase. The free faucet remains for onboarding; IAP is the actual monetization path.

### Coin packages (server-configured, client-agnostic)
- `GET /iap/packages` → `{"packages":[{"product_id":"...","coins":N,"usd_price_hint":N}, ...]}` — public, `Access-Control-Allow-Origin: *`, same posture as `/price`. Backed by a single Redis string key `aicoin:iap-packages` (JSON array), lazily seeded from the config's `iap.packages` YAML list (below) on first read if unset. Every client app fetches this list at launch/paywall-open time instead of hardcoding coin amounts — "number of coins available to buy varies depending on what's set on the server" is this one key, nothing client-side. `usd_price_hint` is informational display copy only; the *actual* charged price is always whatever App Store Connect has configured for that `product_id` in each app, since Apple — not this server — collects payment.
- Because Apple in-app purchase product IDs are scoped to one app each, the same four coin tiers exist as **separate product IDs per app**, all listed in the one `aicoin:iap-packages` JSON array (12 entries total: 4 tiers × 3 apps): `com.tarasmaslov.infiniteairadio.aicoin.{small,medium,large,xl}`, `com.tarasmaslov.alllanguageslearner.aicoin.{small,medium,large,xl}`, `com.tarasmaslov.learnit.aicoin.{small,medium,large,xl}`. A client only ever needs the subset whose `product_id` starts with its own app's coin-package prefix (below) — **not necessarily its real bundle ID string**: Learn Its' actual bundle ID is `com.tarasmaslov.learn-it` (with a hyphen), but Apple in-app purchase product IDs may only contain alphanumerics/underscores/periods — no hyphen — so its packages use `com.tarasmaslov.learnit` (hyphen dropped) as the product-id prefix instead. This affects only the product-id string; `POST /wallet/api/redeem-iap`'s bundle-id allowlist check is against the real, hyphenated `com.tarasmaslov.learn-it` from Apple's verified JWS payload, which is unaffected.
- Config seed (YAML, mirrors the launch prices above):
  ```yaml
  iap:
    packages:
      - { productId: com.tarasmaslov.infiniteairadio.aicoin.small,  coins: 50,   usdPriceHint: 0.99 }
      - { productId: com.tarasmaslov.infiniteairadio.aicoin.medium, coins: 200,  usdPriceHint: 2.99 }
      - { productId: com.tarasmaslov.infiniteairadio.aicoin.large,  coins: 1000, usdPriceHint: 9.99 }
      - { productId: com.tarasmaslov.infiniteairadio.aicoin.xl,     coins: 5000, usdPriceHint: 44.99 }
      # ...same four tiers, repeated for alllanguageslearner and learn-it bundle IDs
  ```
- **Admin script**: `aicoin-proxy/scripts/set-coin-packages.sh <packages.json>` — reads a JSON file in the same shape as `GET /iap/packages`'s `packages` array and `PUT`s it to `POST /admin/iap/packages` (`X-Admin-Token` gated, same posture as the rest of `/admin/*`), which atomically overwrites `aicoin:iap-packages` after validating every entry has a non-empty `product_id` and a positive integer `coins`. This is the one place "currently available coins" is set — a single source of truth every app reads.
- **Tier-level wrapper**: `aicoin-proxy/scripts/set-coin-amounts.sh --small 100 --xl 6000` — the everyday way to change what all users can currently buy, without hand-writing all twelve entries. It `GET`s the live `/iap/packages`, rewrites `coins` only for the tiers named (`--small/--medium/--large/--xl`, or `--tier <suffix>=N` for any other suffix; `--app <product-id-prefix>` narrows to one app, default all three), carries `product_id` and `usd_price_hint` through untouched, prints a full before/after table, and hands the result to `set-coin-packages.sh` — so `POST /admin/iap/packages` stays the single write path and its server-side validation stays the authority. `--dry-run` previews without writing; a run that changes nothing exits 0 without a write. Prices are the other script's job (see "Automatic price adjustment") — this one never touches them. **Note:** once an offer is live (see "The current offer"), a catalog entry's `coins` is only the last-resort fallback for a product that isn't currently on offer — `set-coin-offer.sh` is what changes what users actually buy.

### The current offer

The packages list above is the **catalog**: which products exist and what each one costs. What is actually *for sale* is a single number — **the current offer** — set by the operator and identical in every app. An app displays that one amount, re-checks it immediately before charging, and buys whichever product covers it. Coin amounts are decoupled from products entirely: the four products are four **fixed price points**, and the offer is what a purchase at one of them credits. A `.large` purchase credits the offer's amount, not the `coins` on `.large`'s catalog entry.

- `GET /iap/offer` → `{"offer":{"coins":350,"tier":"large","usd_price":9.99,"product_ids":["com.tarasmaslov.infiniteairadio.aicoin.large", ...],"set_at":<epochMillis>}}` — public, `Access-Control-Allow-Origin: *`, same posture as `/price`. Backed by the Redis string `aicoin:offer`. Unlike `aicoin:iap-packages` there is **no config seed**: an unset key means "nothing is on sale", served as `{"offer":null}` and rendered by clients as an empty paywall, not as a failed fetch. `product_ids` lists one product per app at that price point; a client picks its own by prefix.
- `POST /admin/iap/offer` — `X-Admin-Token` gated. Body `{"coins":N}` prices `N` against the live `/price` signal; `{"coins":N,"usd_price":P}` puts it on a named price point instead; `{"coins":0}` closes sales. Admin script: **`aicoin-proxy/scripts/set-coin-offer.sh 350`** (`--price 9.99`, `--close`, `--show`, `--url`). This is the one place "how much aicoin all users can buy right now" is set.
- **Pricing rounds up, not to nearest.** The target price is the same formula the repricer uses (`coins × price_usd × 1.5 / 0.7`), and the offer takes the **cheapest price point that covers it**. 350 coins at a $0.0086 signal raw-price to $6.45 and therefore sell at **$9.99**, not at the nearer $2.99 — nearest-tier rounding would sell those coins for under half their computed worth. This deliberately differs from `AppStorePriceRounding.roundToNearestTier`, which rounds against its own denser ladder and exists for the per-product repricer this model supersedes. Rounding up can only overcharge relative to the raw target; that excess is margin, and margin is the safe direction.
- **Two refusals, both `409`, both deliberate.** (1) *Thin signal*: `price_usd` averages recorded paid calls, so a fresh deploy reports `0.0` and every amount would collapse onto the cheapest point — selling 5,000 coins for $0.99. Below `price_usd > 0` **and** `weighted_total >= 50` (the same guard `adjust-iap-prices.sh` applies) the server refuses to price an offer and says to pass an explicit `usd_price`. (2) *Above the ceiling*: if no price point covers the amount, that is an error rather than a silent clamp to the top tier, since clamping would sell the excess coins for nothing.
- **The four price points must stay fixed while an offer is live.** The whole mapping is only sound while the catalog's prices match what App Store Connect actually charges — a repriced `.large` at $6.99 would silently discount every offer that resolved to `.large`. `adjust-iap-prices.sh` therefore checks `GET /iap/offer` first and degrades to report-only whenever an offer exists, no matter what was asked for (it still reports, since drifting targets are the signal that the fixed points want a human to re-pick them).

### Pinning an offer across the purchase

`POST /iap/offer/check` is the re-check an app makes *immediately before* opening Apple's purchase sheet. It returns the offer as it stands at that instant plus `{"offer_id":"o_<32 hex>","expires_in":900}`, and records that amount at `aicoin:offer-pin:{offer_id}` with a matching Redis TTL. Public and unauthenticated like the rest of the buy path: a pin is only a promise to credit N coins in exchange for a genuine Apple-signed purchase of a specific product, so minting one grants nothing on its own, and the TTL bounds hoarding.

The check narrows the race but cannot close it — Apple's sheet takes seconds, and StoreKit can redeliver an unfinished transaction days later. The pin is what closes it: redemption credits **what the user was shown**, even if the operator changed the offer in between. `POST /admin/iap/offer` deliberately does not invalidate outstanding pins; someone mid-checkout when the offer changed (or when sales closed) still gets what they agreed to.

### Redeeming a purchase (`POST /wallet/api/redeem-iap`)
Body: `{"to_user_id":"<address>","signed_transaction":"<StoreKit2 Transaction.jwsRepresentation>"}`, plus an optional `"offer_id"` from `POST /iap/offer/check`. No live wallet signature required — crediting a wallet can't harm anyone, same reasoning as the free faucet, so the security burden is entirely on proving the *purchase* is real, not on proving control of the destination address:
1. Verify `signed_transaction` as a genuine Apple JWS: validate the x5c certificate chain up to Apple's bundled Root CA - G3 (no network call needed — this is why it's secure server-to-server without a shared secret: Apple's signature is the trust anchor, not a bearer secret that could leak), and check it hasn't expired.
2. Extract `bundleId`, `productId`, `transactionId`, `quantity`, `environment` and `revocationDate` from the verified payload. Reject (`400`) if `bundleId` isn't one of the three known apps, or `productId` isn't in the current `aicoin:iap-packages` list.
   - **`environment` must be `Production`.** A signature proves a transaction is genuine, *not* that anyone paid for it: Apple signs Sandbox transactions with the same certificate chain, so they verify identically, and a free sandbox tester account can mint them without limit. This field is the only thing that separates the two, and it is therefore the barrier between a tester account and unbounded free coins. Sandbox transactions are rejected unless `iap.acceptSandboxPurchases` (env `AICOIN_PROXY_IAP_ACCEPT_SANDBOX`, default **false**) is on, which only a non-production deployment may do. An absent/unreadable `environment` is treated as `Production` — defaulting the other way would reject every real purchase if Apple ever renamed the field.
   - **A `revocationDate` means refunded or revoked**, and is rejected: the money went back to the buyer, so the coins must not go out. Note this only catches a refund that happened *before* redemption — coins already credited are not clawed back, which needs App Store Server Notifications V2 (not implemented; see below).
3. Idempotency: `SETNX aicoin:iap-redeemed:{transactionId} 1` (Lua-atomic alongside the credit, same shape as the other ledger scripts) — a `transactionId` that's already been redeemed is a no-op `200` (not an error — StoreKit retries delivery of unfinished transactions, so this must be safely repeatable), never a double-credit.
4. Decide what one unit is worth, in this order — each step is a defensible amount, so a failed lookup falls through to the next rather than refusing to credit a genuine purchase; only the last step's failure is fatal, since by then nothing is left to credit from: **(a) the pin**, when `offer_id` names a live one whose `product_ids` include this product — the exact amount the user was shown; **(b) the live offer**, when it sells this product — the path for a client too old to send an `offer_id`, or one whose pin expired during a slow redelivery; **(c) the catalog's static `coins`** for that product, reached only when the purchased product isn't the one on offer at all (a paywall cached from before an offer change), and the only remaining statement of what it was sold as. A product that matches none of the three is `400 unknown productId`.
5. Credit `to_user_id`'s balance by `coins * quantity`, log a `{"type":"iap","amount":N,"product_id":"...","balance_after":N,"at":epochMillis}` entry into `aicoin:tx:{to_user_id}`, `SADD` into `aicoin:known-wallets`.
6. Response: `200 {"credited":N,"balance":N}` (or the already-redeemed no-op's current balance, same shape).
This endpoint is why HTTPS between every app and `proxy.aicoin.oeaio.com` is non-negotiable in production (TLS via the Lightsail Caddy front-end) — the JWS itself is tamper-evident, but the wallet address it's credited to travels in the clear otherwise.

## Automatic price adjustment

A small scheduled job (cron on the Lightsail host, `aicoin-proxy/scripts/adjust-iap-prices.sh`, hourly) re-derives each package's target USD price from the *current* `/price` signal (not the launch estimate above) using the same formula (`coins × price_usd × 1.5 / 0.7`, rounded to the nearest App Store price point), and — where the App Store Connect API supports it (manual price schedules on an existing in-app purchase's `inAppPurchasePriceSchedule`) — pushes the new price point automatically via the App Store Connect API, one call per product ID, only when the computed price point differs from the currently-scheduled one (avoids no-op API churn/rate limits). Coin *amounts* per package never change automatically, only price — keeps the on-screen "50 coins" etc. stable for users while the $ cost of that package tracks real AI spend plus the fixed fee margin.

**Safety guards (both mandatory — an automated repricer that can run away is worse than no repricer):**
1. **Minimum signal.** `price_usd` is an average over recorded paid calls, so a fresh deploy (or a wiped ledger) reports `0.0` — and the raw formula would then collapse *every* package to the cheapest tier, selling a 5,000-coin pack for $0.99. The script refuses to apply anything unless `price_usd > 0` **and** `weighted_total >= MIN_WEIGHTED_EVENTS` (default 50). Below that it degrades to report-only, even when `--apply` was passed, and logs why.
2. **Circuit breaker.** Even with a healthy signal, any single product whose target price is more than `MAX_CHANGE_FACTOR` (default 2.0) away from its current price in either direction is skipped and logged for human review, rather than applied.

`--apply` is opt-in; the default invocation is read-only and just logs what it would do. The App Store Connect write itself lives in `aicoin-proxy/scripts/asc_price_updater.py` (resolves productId → IAP resource id → per-territory price point, then POSTs an `inAppPurchasePriceSchedules` with `startDate: null`), and it no-ops when the product is already at the target price so hourly runs don't churn Apple's rate limits.
