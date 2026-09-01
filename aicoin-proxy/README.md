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

You need a GraalVM JDK and a Redis (or Redis-compatible, e.g. Valkey) server
reachable at `redis.host`/`redis.port` (default `localhost:6379`). `.java-version`
and `build.gradle`'s toolchain currently pin **GraalVM 25** — see "Docker"
below for why this isn't "GraalVM JDK 26" yet.

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
  username: ""                    # AICOIN_PROXY_REDIS_USERNAME (empty = no ACL username; production MemoryDB for Valkey requires ACL username+password auth, not just a password)
  password: ""                    # AICOIN_PROXY_REDIS_PASSWORD (empty = no AUTH)
  ssl: false                      # AICOIN_PROXY_REDIS_SSL (true for ElastiCache/MemoryDB in-transit encryption)
aicoin:
  decayHalflifeDays: 110.0        # AICOIN_PROXY_DECAY_HALFLIFE_DAYS
  freeClaimCooldownSeconds: 3600  # AICOIN_PROXY_FREE_CLAIM_COOLDOWN_SECONDS
  signatureSkewSeconds: 120       # AICOIN_PROXY_SIGNATURE_SKEW_SECONDS
  freeCoinsPoolSize: 5000         # AICOIN_PROXY_FREE_COINS_POOL_SIZE (total coins the faucet will ever give away)
  adminToken: ""                  # AICOIN_PROXY_ADMIN_TOKEN (empty = admin page/API disabled)
accessLog:
  path: logs/access.log          # AICOIN_PROXY_ACCESS_LOG_PATH ("none" disables; see "Access log")
  maxBytes: 10485760             # AICOIN_PROXY_ACCESS_LOG_MAX_BYTES — rotate at this size
  count: 5                       # AICOIN_PROXY_ACCESS_LOG_COUNT — files kept; maxBytes x count caps disk
iap:
  acceptSandboxPurchases: false   # AICOIN_PROXY_IAP_ACCEPT_SANDBOX — production MUST leave this false; see "IAP" below
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
  kimi:
    baseUrl: https://api.moonshot.ai          # AICOIN_PROXY_KIMI_BASEURL
    apiKey: ""                               # AICOIN_PROXY_KIMI_APIKEY
    authHeader: Authorization                # AICOIN_PROXY_KIMI_AUTHHEADER
    authPrefix: "Bearer "                    # AICOIN_PROXY_KIMI_AUTHPREFIX
    freePaths: ["GET /v1/models", "GET /v1/models/*"]   # AICOIN_PROXY_KIMI_FREEPATHS
consortium:                        # POST /consortium — see its own section below
  enabled: true                    # AICOIN_PROXY_CONSORTIUM_ENABLED (false serves 404)
  maxRounds: 3                     # AICOIN_PROXY_CONSORTIUM_MAX_ROUNDS
  maxOutputTokens: 4000            # AICOIN_PROXY_CONSORTIUM_MAX_OUTPUT_TOKENS
  maxContextChars: 60000           # AICOIN_PROXY_CONSORTIUM_MAX_CONTEXT_CHARS (the shared record)
  editor: ""                       # AICOIN_PROXY_CONSORTIUM_EDITOR (empty: the first panelist)
  models:                          # AICOIN_PROXY_CONSORTIUM_<PROVIDER>_MODEL
    anthropic: claude-sonnet-5
    openai: gpt-5
    google: gemini-3.5-flash
    mistral: mistral-large-latest
    kimi: kimi-k2.6
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

`kimi` is Moonshot's Kimi, reached on its OpenAI-compatible surface
(`POST /v1/chat/completions`, `Authorization: Bearer`), so it needs no
handling beyond its own `providers.kimi` entry. Its API host is still
`api.moonshot.ai` even though the platform docs now live at `kimi.ai`.

## Routing — same path, header selects the provider, proxy owns the upstream key

The client calls the proxy at **exactly the same path** a real provider
would use (e.g. `POST /v1/chat/completions`) — only the domain changes to
the proxy's. There is no path-prefix stripping. A request header `X-AI:
<provider>` (one of `openai`, `anthropic`, `google`, `mistral`, `cohere`,
`elevenlabs`, `stability`, `kimi`, case-insensitive) tells the proxy which
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

## Paid targets vs free targets

Not every endpoint a provider exposes is one it bills the proxy for. Listing
models, listing ElevenLabs voices, counting tokens (Anthropic's
`/v1/messages/count_tokens`, Google's `:countTokens`), reading an account
balance — those cost the proxy nothing upstream, so they cost the wallet
nothing here. Each provider's `freePaths` names them; anything not matched is
a **paid target** and goes through the full balance gate below.

A free target is still a real forwarded call: it needs a valid API token, the
`X-AI` header, and it goes out with the proxy's own paid credential injected,
exactly like a paid one. What's different is only the money: **no debit, no
refund, and no price event.** Recording `defaultCostUsdPerCall` for a model
listing would inflate `GET /price` with spend that never happened. It also
means an app with a zero balance can still list models or voices — only the
inference call itself needs a coin. (`GET /health` still records the upstream
status either way; provider health is about reachability, not billing.)

Pattern syntax is an optional method, a space, then a path glob where `*`
matches any run of characters:

```
GET /v1/models                     exact path, GET only
GET /v1/models/*                   anything under /v1/models
POST /v1beta/models/*:countTokens  Google's countTokens on any model
/v1/voices                         any method
```

Methods compare case-insensitively, paths case-sensitively; the query string
is never part of the match. Matching **fails closed**: a path containing a
percent-escape or a `.`/`..` segment is treated as paid no matter what the
patterns say, since an upstream would normalize
`/v1/models/../chat/completions` onto a billed endpoint that the glob alone
would have waved through for free.

Set `AICOIN_PROXY_<PROVIDER>_FREEPATHS` to a comma-separated pattern list to
override a provider's defaults, or to the literal `none` to bill every one of
its endpoints again. (An empty env var means "unset" everywhere else in this
config, hence the `none` sentinel.) These defaults track what the providers
bill today — if a provider starts charging for one of them, drop it from that
provider's list.

## Balance gate

Before forwarding a **paid target** to the upstream AI provider, once either
scheme verifies an address, the proxy atomically checks and debits **exactly 1.0 aicoin**
from that wallet's balance (`AicoinLedger.debitForCall`, a single Redis Lua
script — no separate read-then-write, so two concurrent calls can't both
pass a stale check and overdraw). **A paid call costs at least 1 aicoin, and what it cost to run when metered —
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
  billed by the real provider for a call that didn't complete. This is one of
  the two boundaries that keep *paid* calls (successful, billed, feeding the
  price formula) cleanly separated from *free* activity: a faucet claim mints
  coins but is never treated as a call, and a failed call never counts as
  paid. The other is target-side — a call to a `freePaths` endpoint never
  reaches this gate at all (see "Paid targets vs free targets" above).

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

## Access log

One JSON object per line, per request, to a size-rotated file — the thing whose
absence makes a report like "the ElevenLabs request timed out" unanswerable
after the fact.

```
{"at":"2026-08-12T16:43:59.608Z","method":"POST","path":"/v1/text-to-speech/x/with-timestamps",
 "provider":"elevenlabs","wallet":"281c9103...","status":-1,"req_bytes":52,"resp_bytes":0,
 "duration_ms":4963,"coins":"","outcome":"client_gone"}
```

JSON Lines so a line survives being grepped out of context and the file can be
fed straight to `jq`:

```bash
# slowest calls
jq -s 'sort_by(-.duration_ms) | .[:20]' logs/access.log.0
# requests that never got a response — clients that gave up waiting
jq 'select(.outcome == "client_gone")' logs/access.log.0
# spend by provider
jq -r 'select(.coins != "") | .provider' logs/access.log.0 | sort | uniq -c
```

`AccessLogHandler` sits at the head of the inbound pipeline rather than at each
place that sends a response, because there are many of those (auth rejections,
the balance gate, synthetic upstream errors, the wallet page, real proxied
responses) and a log that silently misses whichever one a future change adds is
worse than none.

`status:-1` with `outcome:"client_gone"` is a request the client abandoned
before any response existed — it produces no status code anywhere else, so
without this line it leaves **no trace at all**. That is the shape of every
client-side "request timed out" report.

**Never written:** the `X-Api-Key` token (it can spend the wallet's coins, so a
leaked log would otherwise be a leaked wallet), any provider API key, request
and response bodies, and the query string — Google's credential is injected as
a query parameter, and a redaction rule that must stay correct as providers
change is a worse bet than never writing the query at all. The wallet *address*
is written in full: it is public by design and is the only way to attribute
usage.

Rotation is by size (`maxBytes` per file, `count` files, oldest overwritten),
so `maxBytes × count` is a hard ceiling on disk — the default caps the whole
log at 50MB, which matters on a 20GB Lightsail volume shared with Redis
snapshots. Set `AICOIN_PROXY_ACCESS_LOG_PATH=none` to turn logging off; an
*empty* env value means "unset" and leaves the default in place. Writes go to a
single daemon thread with a bounded queue, so file I/O never runs on an event
loop and a slow disk drops log lines rather than stalling requests. An
unwritable path is reported once at startup and then ignored — logging must
never be a reason to stop serving traffic.

Under Docker the log lives on a bind mount (`./logs`), since otherwise every
redeploy discards exactly the history worth reading after an incident.

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
6. A **free target** (step 4/5's money handling only): nothing was debited
   before forwarding, so nothing is refunded on failure and no price event is
   recorded on success — `UpstreamForwarder` is handed a call cost of `0` and
   skips both. Relaying, header handling, and `/health` recording are
   identical to any other call.

## The ledger (`AicoinLedger`)

Backed by a single Redis instance — ElastiCache for Redis (snapshotting
enabled) in production, `redis:7-alpine` locally/in e2e. Full data model,
Lua-script atomicity rationale, and the price formula are documented in the
repo-root `CONTRACT.md`'s "Ledger (Redis)" section — this section covers
just the Java-side shape:

- `getBalance(address, callback)` — async Redis `GET aicoin:balance:{address}`.
- `debitForCall(address, amount, provider, callback)` — runs a Lua `EVAL`
  script that atomically checks the balance and, if `>= amount` (always
  `1.0` in practice — see `ProxyFrontendHandler.CALL_COST_AICOIN`), debits
  it, `SADD`s the address into `aicoin:known-wallets`, and appends a
  `debit` entry to `aicoin:tx:{address}` — all before the proxy forwards to
  a real provider.
- `refund(address, amount, provider)` — a Lua `EVAL` script (not a plain
  `INCRBYFLOAT` — it also needs to append the `refund` transaction-log
  entry atomically with the balance reversal) undoing a `debitForCall` when
  the upstream call it paid for didn't actually succeed; fire-and-forget,
  same contract as `recordEvent`.
- `claimFreeCoins(address, cooldownSeconds, poolSize, claimAmount, callback)`
  — runs a Lua `EVAL` script that atomically checks both the per-wallet
  cooldown (`aicoin:lastclaim:{address}`) and the shared pool
  (`aicoin:free-coins-remaining`, lazily initialized to `poolSize`) and, if
  both allow it, mints `claimAmount` into the balance, decrements the pool
  by the same amount, `SADD`s the address into `aicoin:known-wallets`, and
  appends a `claim` entry to `aicoin:tx:{address}` — always the fixed
  `ProxyFrontendHandler.FREE_CLAIM_AMOUNT_AICOIN` (10) in practice, never a
  partial grant.
- `getFreeCoinsRemaining(poolSize, callback)` — async Redis `GET` on the
  shared pool key, defaulting to `poolSize` if never initialized.
- `transfer(from, to, amount, callback)` — runs a Lua `EVAL` script that
  atomically checks the sender's balance and, if sufficient, moves the
  amount, `SADD`s both addresses into `aicoin:known-wallets`, and appends a
  `transfer_out`/`transfer_in` entry to each side's transaction log.
- `recordEvent(provider, costUsd, timestamp)` — fire-and-forget `ZADD` into
  `aicoin:events` (member `costUsd|uuid`, score = epoch-millis) — fed only
  by genuine 2xx paid calls, never by claims/transfers/failed calls or by
  free targets.
- `computePrice(halfLifeDays, callback)` — `ZRANGE ... WITHSCORES` over
  `aicoin:events`, then folds the recency-weighted average via the pure
  `PriceCalculator.compute` (no Redis dependency, fully unit-tested).
- `computePriceHistory(numPoints, halfLifeDays, callback)` — fetches
  `aicoin:events` once, then calls `PriceCalculator.compute` once per
  sample point with the event list filtered to `timestamp <= sampleTime`
  (skipping that filter would let a "future" event's clock-skew-clamped
  weight of 1.0 leak into a past sample). Backs `GET /price/history`.
- `revokeTokensBefore(address, nowMillis, callback)` — Redis `SET
  aicoin:token-revoked-before:{address} nowMillis`.
- `getTokenRevokedBefore(address, callback)` — Redis `GET`, `Optional.empty`
  parsed as "never revoked."
- `listWalletSummaries(callback)` — `SMEMBERS aicoin:known-wallets`, then
  for each address a pipelined `GET` balance + `LLEN` transaction-log-length
  combined via `CompletableFuture.thenCombine`/`allOf`; sorted by balance
  descending. Backs `GET /admin/wallets`.
- `getTransactions(address, callback)` — `LRANGE aicoin:tx:{address} 0 -1`,
  reversed to most-recent-first. Backs `GET /admin/wallets/{address}/transactions`.
- `getIapPackages(seedJsonIfUnset, callback)` — Redis `GET aicoin:iap-packages`;
  if unset, `SETNX`s `seedJsonIfUnset` (the config's `iap.packages` list,
  pre-rendered by `IapPackages.seedJson`) then re-`GET`s to resolve any race
  with a concurrent first-ever seeder or admin write. Backs `GET /iap/packages`.
- `setIapPackages(packagesJson, callback)` — a plain Redis `SET` (already
  atomic for a single unconditional write, no Lua script needed). Backs
  `POST /admin/iap/packages`.
- `getOffer(callback)` / `setOffer(offerJson, callback)` — Redis `GET`/`SET` on
  `aicoin:offer`. Unlike the packages list there is **no config seed**: an unset
  key is a real state ("nothing is on sale"), reported as a present-but-empty
  value rather than conjured from config. Back `GET /iap/offer` and
  `POST /admin/iap/offer`.
- `putOfferPin(offerId, pinJson, ttlSeconds, callback)` / `getOfferPin(offerId, callback)`
  — `SETEX`/`GET` on `aicoin:offer-pin:{offerId}`. The TTL is set atomically with
  the write, never after, since expiry is the only thing bounding how long a pin
  can be hoarded. An expired or never-issued id reads back present-but-empty, not
  as a failure — redemption falls back to the live offer for it, the same path a
  client too old to pin takes. Back `POST /iap/offer/check`.
- `redeemIap(transactionId, address, productId, coins, callback)` — runs a
  Lua `EVAL` script that `SETNX`s `aicoin:iap-redeemed:{transactionId}`; if
  it was already set (a StoreKit retry), returns the current balance
  unchanged; else credits `coins` via `INCRBYFLOAT`, `SADD`s the address into
  `aicoin:known-wallets`, and appends an `iap` entry to `aicoin:tx:{address}`.
  Backs `POST /wallet/api/redeem-iap`.

All operations are async (Lettuce's `RedisFuture`/`CompletableFuture`
API), matching the rest of this codebase's non-blocking Netty style — none
of them block an event-loop thread.

## Consortium — one request, every AI, reviewed until nobody objects

`POST /consortium` is the one endpoint where this proxy *writes* provider
requests rather than forwarding somebody else's. The client sends a prompt;
every configured AI answers it; the answers are merged into one; then every
AI reviews that answer, round after round, until a round comes back with no
comments.

```
curl -X POST http://localhost:8080/consortium \
  -H "X-Api-Key: $AICOIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"prompt":"What breaks first when a proxy meters billing per call?"}'
```

```json
{"answer":"...","settled":true,"stopped_reason":"clean","rounds":2,
 "panel":["anthropic","openai","google","kimi"],"editor":"anthropic",
 "calls":13,"coins_charged":15,
 "reviews":[{"round":1,"provider":"openai","clean":false,"comments":"..."}],
 "errors":[]}
```

Body fields, all but `prompt` optional: `context` (background the whole panel
sees), `providers` (narrow the panel), `editor`, `max_rounds` (may only lower
`consortium.maxRounds`), `include_transcript` (also return every draft).

**Every turn sees the same shared record**: the request, the caller's
`context`, the drafts attributed to the model that wrote each, every answer
the editor produced, and every round of comments — naming who objected and
who cleared it — then one line saying what this turn is to do with it.
Without it a panel is a queue of strangers: reviewers repeat objections the
editor already fixed, and nobody can tell a settled point from a new one.

The record is capped at `consortium.maxContextChars` (default 60,000),
because it goes to every panelist on every round and every character is
billed as input. Past the cap the oldest rounds are dropped and the text
says so; the request, the caller's `context` and the answer under discussion
never are.

**The panel** is every provider this proxy knows a chat shape for —
`anthropic`, `openai`, `google`, `mistral`, `kimi` — that this deployment
has both a key and a `consortium.models.<provider>` entry for. `elevenlabs`
and `stability` are not chat APIs; `cohere` is one whose shape is not
implemented here. A caller can narrow the panel, never extend it. No panel
at all is a `503`.

**The rounds:** draft (every panelist, independently) → merge (the editor,
skipped when there is only one draft) → review (every panelist replies
either exactly `NO COMMENTS` or with a list of problems) → revise (the
editor applies the comments) → review again. It stops on a clean round
(`settled: true`) or at `consortium.maxRounds` (`settled: false`,
`stopped_reason: "round_limit"`).

The cap is what makes it terminate. Reviewers asked to find fault can always
find some, so "until no more comments" without a bound is an unbounded
spend, and every round is one paid call per panelist.

`NO COMMENTS` is read strictly — the whole reply, ignoring case, whitespace
and markdown emphasis, must be that phrase. "No comments, though section 2
is wrong" is comments. The strictness costs an extra round when a reviewer
rambles; the other reading ships an answer a reviewer just objected to.

**Billing is not special.** Every turn is an ordinary paid call: one aicoin
held before it, the metered remainder settled from that provider's own
reported usage, a refund when the provider never answered. Four panelists
over two rounds is 13 calls, billed as 13 calls; `calls` and
`coins_charged` say so, and the response carries the same
`X-Aicoin-Charged` header a single proxied call does.

**Partial results come back rather than being thrown away.** A wallet that
runs dry mid-call stops there and returns the best answer so far
(`stopped_reason: "insufficient_balance"`); only a call that cannot afford
its first turn gets `402`. A panelist that errors, times out, or returns a
2xx with no text in it (a reasoning model can spend its whole output cap
thinking) is dropped from that round, recorded in `errors`, and the rest
carry on. Only if *nobody* drafts is it a `502`. A client that disconnects
stops the rounds at the next boundary — the turns already in flight are
paid for and cannot be recalled.

A consortium call takes minutes, not seconds, and holds one HTTP connection
open for the whole of it.

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
- `GET /health` — for each of the 8 configured providers (`openai`,
  `anthropic`, `google`, `mistral`, `cohere`, `elevenlabs`, `stability`,
  `kimi`),
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
- `GET /price/history?points=N` (default 60, max 500) — reconstructs the
  price series: `N` evenly-spaced samples between the earliest recorded
  event and now, each re-running `PriceCalculator.compute` as if `now` were
  that sample's timestamp, filtered to only the events that had actually
  happened by then (`AicoinLedger.computePriceHistory`). Returns
  `{"half_life_days":110,"points":[{"at":epochMillis,"price_usd":N}, ...]}`;
  zero events → `{"points":[]}`. Always includes
  `Access-Control-Allow-Origin: *`, same as `/price`.
- `GET /price/chart` — the bundled price-chart page (`price-chart.html`):
  current price plus a canvas-drawn line graph of `/price/history`, with
  buttons to switch the sample count.
- `GET /wallet` — the browser wallet page (bundled `wallet.html`): generate
  or import an Ed25519 keypair, view address/balance/price, claim/transfer
  (live-signed), and issue/revoke API tokens.
- `GET /wallet/api/balance/{address}` — unsigned, backed directly by
  `AicoinLedger.getBalance`.
- `POST /wallet/api/claim`, `POST /wallet/api/transfer`, `POST
  /wallet/api/revoke-tokens` — live-signed, see "Auth" above for the exact
  header/canonical-message spec.
- `GET /admin` — the operator admin page (bundled `admin.html`): lists every
  known wallet's balance and lets you drill into one wallet's full
  transaction log. Unlike everything else above, its two data endpoints
  require an `X-Admin-Token` header matching `aicoin.adminToken` (empty by
  default, which disables the whole surface with a `503` — see
  `AdminHandler`):
  - `GET /admin/wallets` → `{"wallets":[{"address":"...","balance":N,"transaction_count":N}, ...]}`,
    sorted by balance descending, backed by `AicoinLedger.listWalletSummaries`.
  - `GET /admin/wallets/{address}/transactions` → `{"address":"...","transactions":[{...}, ...]}`,
    most-recent first, backed by `AicoinLedger.getTransactions` — each entry
    is the raw JSON object the mutating Lua script `cjson.encode`d at the
    time (`type` one of `claim`/`transfer_out`/`transfer_in`/`debit`/`refund`/`iap`,
    plus `amount`/`balance_after`/`at`, and one of `counterparty`, `provider`,
    or `product_id` depending on `type`).
  - `POST /admin/iap/packages` — same `X-Admin-Token` gating as the two
    endpoints above (`IapPackagesHandler`, mirroring `AdminHandler`'s posture
    exactly). Body: a JSON array shaped like `GET /iap/packages`'s
    `packages` field. Validates every entry has a non-empty `product_id` and
    a positive integer `coins` (`IapPackages.validate`) before atomically
    overwriting `aicoin:iap-packages`; `400` with a specific reason on
    validation failure. See `scripts/set-coin-packages.sh` for the CLI
    wrapper around this endpoint.

## IAP: buying aicoin with real money (App Store)

The free faucet remains for onboarding; in-app purchase is the actual
monetization path — see CONTRACT.md's "AICoin pricing" and "IAP: buying
aicoin with real money" sections for the full design/pricing rationale.

There are two layers here, and they answer different questions. The
**catalog** (`/iap/packages`) says which products exist and what each costs.
The **current offer** (`/iap/offer`) says what is actually for sale right
now — one coin amount, identical in every app. Under the offer model the four
products stop being coin tiers and become four *fixed price points*: the
offer picks whichever one covers what its coins are worth, and a purchase
credits the offer's amount, not the `coins` on that product's catalog entry.

### The catalog

- `GET /iap/packages` → `{"packages":[{"product_id":"...","coins":N,"usd_price_hint":N}, ...]}`
  — public, `Access-Control-Allow-Origin: *`, same posture as `GET /price`.
  Backed by the single Redis string `aicoin:iap-packages`, lazily seeded from
  config's `iap.packages` (12 entries: 4 price points × the 3 client apps' real
  bundle ids) the first time this is ever called against a fresh instance.
- `POST /admin/iap/packages` — `X-Admin-Token` gated, atomically overwrites the
  list. Admin CLIs: `scripts/set-coin-packages.sh <packages.json>` (replaces the
  whole array) and `scripts/set-coin-amounts.sh --small 100 --xl 6000` (rewrites
  only the tiers you name, across every app, with a before/after table and
  `--dry-run`).

### The current offer

- `GET /iap/offer` → `{"offer":{"coins":350,"tier":"large","usd_price":9.99,"product_ids":[...],"set_at":<ms>}}`
  — public + CORS. Backed by the Redis string `aicoin:offer`. **Not** seeded
  from config: an unset key means "nothing is on sale", served as
  `{"offer":null}` and rendered by clients as an empty paywall rather than a
  failed fetch. `product_ids` carries one product per app; a client picks its
  own by prefix.
- `POST /admin/iap/offer` — `X-Admin-Token` gated. `{"coins":N}` prices `N`
  against the live `/price` signal; `{"coins":N,"usd_price":P}` puts it on a
  named price point; `{"coins":0}` closes sales. Admin CLI:
  **`scripts/set-coin-offer.sh 350`** (also `--price`, `--close`, `--show`,
  `--url`). This is the one place "how much aicoin all users can buy right
  now" is set.
- `POST /iap/offer/check` → the same offer plus `{"offer_id":"o_<32 hex>","expires_in":900}`,
  recorded at `aicoin:offer-pin:{offer_id}` with a matching Redis TTL. Apps call
  this immediately before opening Apple's purchase sheet; the pin is what makes
  redemption credit **what the user was shown**, even if the offer changed
  mid-purchase. Public and unauthenticated like the rest of the buy path — a pin
  only promises coins in exchange for a genuine Apple-signed purchase, so
  minting one grants nothing, and the TTL bounds hoarding.

**Pricing rounds up, not to nearest** (`CoinOffer`). The target is the same
formula the repricer uses (`coins × price_usd × 1.5 / 0.7`), and the offer takes
the *cheapest price point that covers it*: 350 coins at a $0.0086 signal
raw-price to $6.45 and therefore sell at **$9.99**, not at the nearer $2.99,
which would sell them for under half their computed worth. This deliberately
differs from `AppStorePriceRounding.roundToNearestTier`, which rounds against
its own denser ladder and serves the per-product repricer this model
supersedes.

**Two `409` refusals, both deliberate.** (1) *Thin signal*: `price_usd` averages
recorded paid calls, so a fresh deploy reports `0.0` and every amount would
collapse onto the cheapest point — selling 5,000 coins for $0.99. Below
`price_usd > 0` **and** `weighted_total >= 50` the server refuses and asks for an
explicit `usd_price`. (2) *Above the ceiling*: if no price point covers the
amount, that is an error rather than a silent clamp to the top tier, which would
sell the excess for nothing.

**The price points must stay fixed while an offer is live.** The mapping is only
sound while the catalog's prices match what App Store Connect actually charges —
a repriced `.large` at $6.99 would silently discount every offer resolving to it.
`scripts/adjust-iap-prices.sh` therefore checks `GET /iap/offer` first and
degrades to report-only whenever an offer exists, whatever was asked for.
### Redeeming

- `POST /wallet/api/redeem-iap` — body `{"to_user_id":"<address>","signed_transaction":"<StoreKit2 Transaction.jwsRepresentation>"}`,
  plus an optional `"offer_id"` from `/iap/offer/check`.
  No live wallet signature required (crediting a wallet can't harm anyone,
  same reasoning as the free faucet) — the entire security burden is on
  `AppleJwsVerifier` proving the *purchase* is real:
  1. Verifies `signed_transaction` as a genuine Apple JWS: parses the
     compact-serialization header/payload/signature, checks `alg` is
     `ES256`, decodes the header's `x5c` certificate chain, checks every
     certificate's validity window, verifies each certificate's signature up
     to the next one in the chain and the last one up to the bundled Apple
     Root CA - G3 (`src/main/resources/apple-root-ca-g3.der` — genuinely
     Apple's real, publicly published root certificate, fetched from
     `https://www.apple.com/certificateauthority/AppleRootCA-G3.cer`; see
     `AppleJwsVerifier`'s class javadoc for its SHA-256 fingerprint and a
     re-verification pointer), then converts the JWS's raw R‖S ES256
     signature to ASN.1 DER (`AppleJwsVerifier.joseToDer`, since Apple's JWS
     format and Java's `Signature.verify` disagree on encoding) and verifies
     it against the leaf certificate's public key.
  2. Extracts `bundleId`/`productId`/`transactionId`/`quantity`/`environment`/
     `revocationDate` from the verified payload. `400` if `bundleId` isn't one
     of the three known apps (`IapPackages.isKnownBundleId`), or if nothing
     sells `productId` (step 3).
     - **`environment` must be `Production`.** A signature proves a transaction
       is genuine, *not* that anyone paid for it: Apple signs Sandbox
       transactions with the same certificate chain, so they verify
       identically, and a free sandbox tester account can mint them without
       limit. This field is the only thing separating the two, and therefore
       the only barrier between a tester account and unbounded free coins.
       Rejected unless `iap.acceptSandboxPurchases` is on — non-production
       deployments only. An absent `environment` is treated as `Production`;
       defaulting the other way would reject every real purchase if Apple ever
       renamed the field. `AppleJwsVerifierTest` deliberately asserts that a
       sandbox JWS *is* cryptographically genuine, since that is exactly why
       the check has to exist.
     - **A `revocationDate` means refunded/revoked** and is rejected: the money
       went back to the buyer, so the coins must not go out. This only catches a
       refund landing *before* redemption — coins already credited are not
       clawed back, which would need App Store Server Notifications V2 (not
       implemented).
  3. Decides what one unit is worth, in this order, each step a defensible
     amount so a failed lookup falls through rather than refusing to credit a
     genuine purchase — only the last step's failure is fatal:
     **(a)** the pin, when `offer_id` names a live one whose `product_ids`
     include this product — the exact amount the user was shown;
     **(b)** the live offer, when it sells this product — the path for a client
     too old to send an `offer_id`, or one whose pin expired during a slow
     StoreKit redelivery; **(c)** the catalog's static `coins`, reached only
     when the purchased product isn't the one on offer at all (a paywall cached
     from before an offer change). A product matching none of the three is
     `400 unknown productId`.
  4. Idempotency + credit run as one Lua script (`AicoinLedger.redeemIap`) —
     see "The ledger" above. A retried/already-redeemed `transactionId` is a
     safe `200` no-op with `"credited":0` and the current (unchanged)
     balance, never an error, since StoreKit retries delivery of unfinished
     transactions.
  5. Response: `200 {"credited":N,"balance":N}`.

## Tests

```
./gradlew test
```

JUnit5 pure-function tests, with no network/Redis dependency required:

- `ProviderRoutingTest` — `X-AI` header → provider resolution, including
  case-insensitivity and the missing/unknown case, across every provider.
- `ChatAdapterTest` — the consortium's per-provider chat shapes: Anthropic's
  top-level `system`, OpenAI's `max_completion_tokens` (its newer models
  reject `max_tokens` outright), Gemini's model-in-the-path and `parts[]`,
  and reading the assistant's text back out of each — including a 2xx that
  carries no text at all, and a Gemini `thought` part, which is the model's
  scratchpad and not its answer.
- `ConsortiumPromptsTest` — the `NO COMMENTS` reading. Strict on purpose:
  "no comments, but X is wrong" is comments.
- `SharedContextTest` — the record every turn is given: the request and the
  current answer survive whatever else is trimmed, contributions stay
  attributed, and the oldest rounds go first when it hits the cap.
- `ConsortiumHandlerTest` — panel and editor selection: only chat-capable
  providers with a key and a model, in a stable order, narrowable by the
  caller but never extendable.
- `JsonTest` — the escape that carries model output into a provider request
  body and into this proxy's own response.
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
  `aicoin.freeCoinsPoolSize`/`aicoin.adminToken`/`redis.username` and each of
  the 7 providers' `baseUrl`/`apiKey`/`authHeader`/`authPrefix`/
  `authAsQueryParam`/`authQueryParamName`; also the bundled `iap.packages`
  seed list (12 entries, all 3 apps × 4 tiers).
- `AicoinLedgerTest` — `AicoinLedger.buildRedisUri`'s pure branching between
  no-auth/password-only/ACL-username+password, and that host/port/SSL are
  preserved regardless of auth mode (the rest of `AicoinLedger` needs a live
  Redis connection, covered by `e2e/run.sh`).
- `IapPackagesTest` — the `aicoin:iap-packages` JSON rendering (config seed
  → JSON, `Entry` list → JSON), `POST /admin/iap/packages` body validation
  (non-empty `product_id`, positive integer `coins`, optional
  `usd_price_hint`), by-`product_id` lookup, and the three known bundle ids.
- `CoinOfferTest` — the pure offer math: collapsing the 12-entry catalog into
  its four price points (including a tier whose apps disagree on price taking
  the highest, since under-pricing is the failure that costs money), the
  round-**up** resolution and the worked $6.45 → $9.99 example, refusing an
  amount no point covers instead of clamping to the top tier, and the JSON
  round-trip `aicoin:offer` and the pins are stored as.
- `AppleJwsVerifierTest` — a **genuinely-generated** test certificate chain
  (a hand-rolled minimal X.509 v1 certificate builder using only the JDK's
  own `Signature`/`KeyPairGenerator`/`CertificateFactory` — no bouncycastle
  or other crypto library dependency) exercises the real verification logic:
  a valid 2-level chain (root → intermediate → leaf) verifies and extracts
  `bundleId`/`productId`/`transactionId`/`quantity`; a tampered payload or
  signature, an expired or not-yet-valid certificate, a chain not rooted in
  the given trusted root, an unsupported `alg`, and various malformed JWS
  shapes are all rejected with a specific reason. It also pins the security
  property the sandbox check rests on: a `"environment":"Sandbox"` transaction
  verifies *just as genuinely* as a production one, so only the field
  distinguishes them; an absent `environment` reads as `Production`; and a
  payload carrying `revocationDate` is flagged revoked. Also confirms the bundled
  `apple-root-ca-g3.der` resource loads and is genuinely Apple's real Root
  CA - G3 (asserts its subject DN). The real Apple root's authenticity is
  documented, not re-tested here — see `AppleJwsVerifier`'s class javadoc
  for its SHA-256 fingerprint.
- `AppStorePriceRoundingTest` — the "Automatic price adjustment" job's
  price-point rounding math (`coins * price_usd * 1.5 / 0.7`, nearest-tier
  rounding against the standard `$X.99` ladder), including where it does and
  doesn't reproduce CONTRACT.md's illustrative launch-price table exactly
  (see the note added to that table and this class's javadoc).
- `AdminHandlerTest` — address-format validation (exactly 64 hex chars,
  upper/lowercase both accepted, wrong length/non-hex rejected) and the
  admin-token constant-time comparison (exact match only; the actual
  `X-Admin-Token` header check + the empty-token-disables-everything
  behavior need a live request/config, covered by `e2e/run.sh`).
- `PriceChartHandlerTest` — the `?points=` query-param parsing: defaults
  when absent, clamps below the minimum (2) and above the maximum (500),
  falls back to the default on non-numeric input.
- `ProviderHealthTrackerTest` — the rolling-window health computation:
  `rateLimited`/`overBudget`/`healthy` derivation from a sequence of
  synthetic status codes, per-provider independence, and window-eviction
  behavior once more than `windowSize` calls have been recorded for a
  provider.
- `HealthHandlerTest` — the `GET /health` JSON body always lists all 8
  providers, in the stable `openai, anthropic, google, mistral, cohere,
  elevenlabs, stability, kimi` order, defaulting to the all-clear state for
  providers with zero recorded calls, and reflecting recorded
  rate-limit/budget statuses for others.
- `WalletPageHandlerTest` — `GET /wallet` serves the bundled HTML page
  verbatim.

`AicoinLedger`'s Lua-script atomicity (claim cooldown + shared pool, transfer
overdraft, per-call debit/refund, and the known-wallets/transaction-log
bookkeeping riding along with each) requires a live Redis/Valkey connection
to exercise end-to-end — covered by `e2e/run.sh`, which also runs one real
paid call through every one of the 7 configured providers (against a local
mock upstream) to confirm each provider's specific auth injection and the
1-aicoin debit both work correctly, not just OpenAI's, and exercises the
admin endpoints' auth gating plus the resulting wallet list/transaction log
against real claim/debit/refund activity.

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
- **Toolchain.** This project targets GraalVM 25 (`java.toolchain.languageVersion
  = 25` in `build.gradle`), matching the repo-root `.java-version` (`25.0.3`)
  and the Dockerfile's `ghcr.io/graalvm/graalvm-community:25` base image —
  **not** GraalVM JDK 26, despite CONTRACT.md's Docker section originally
  calling for it and the rest of this codebase's "Java 26" framing: as of
  this writing GraalVM has not published a JDK 26 build at all (verified
  live against `ghcr.io/graalvm/graalvm-community`'s tag list and
  graalvm.org's release calendar — see the note added to CONTRACT.md's
  "Docker / docker-compose" section). All three of `build.gradle`'s
  toolchain, both `.java-version` files, and the Dockerfile's image tag must
  move together — bump them to 26 together the moment GraalVM ships that
  build; running JDK-26-targeted bytecode on a JDK 25 JVM fails with
  `UnsupportedClassVersionError` at startup, so these can never drift apart.
- **Claim/transfer/per-call-debit atomicity.** The contract doesn't mandate a
  specific Redis mechanism for the check-then-mutate operations in the
  ledger (free-coins pool decrement + cooldown, transfer overdraft check,
  per-call debit-before-forward); Lua `EVAL` scripts were chosen because
  Redis executes a script atomically with respect to every other client,
  which is the simplest way to replicate the correctness a single-writer
  blockchain got "for free" from having exactly one legitimate writer. The
  known-wallets `SADD` and transaction-log `RPUSH`/`LTRIM` the admin page
  reads from ride along inside those same scripts, for the same reason — a
  balance mutation and its bookkeeping must never be observably split.
- **Transaction log is capped, not archived.** `aicoin:tx:{address}` is
  `LTRIM`med to the 200 most recent entries on every write; there's no
  pagination or cold-storage archive for anything older. Fine for a
  draft/prototype's admin page, not for a wallet with genuinely high
  transaction volume.
- **cjson for the transaction-log entries, not the SnakeYAML-as-JSON
  convention used elsewhere in this codebase.** Redis's embedded Lua
  interpreter ships `cjson` built in; using it inside the Lua scripts to
  build each transaction-log entry (rather than string-concatenating JSON
  by hand, as the claim/pool responses do in Java) is simpler and can't
  produce malformed JSON from an unescaped value. `AdminHandler` then
  passes those entries through to the client byte-for-byte rather than
  re-parsing and re-serializing them.
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
- **The Apple Root CA - G3 resource is real, not a placeholder.**
  `src/main/resources/apple-root-ca-g3.der` was fetched directly from
  `https://www.apple.com/certificateauthority/AppleRootCA-G3.cer` and its
  SHA-256 fingerprint matches Apple's own published value (see
  `AppleJwsVerifier`'s class javadoc) — this project does have outbound
  network access in its build environment, so there was no need to stub this
  out; re-verify the fingerprint against that URL if this resource is ever
  regenerated.
- **`POST /wallet/api/redeem-iap`'s `"credited"` field on an idempotent
  replay** is `0`, not the package's coin amount — the response *shape*
  matches a fresh credit (`{"credited":N,"balance":N}`, per CONTRACT.md),
  but since a replay genuinely credits nothing, `credited:0` seemed more
  honest than repeating the amount as if it had been credited again. The
  `balance` field is always the wallet's real current balance either way.
- **The IAP price-tier ladder** (`AppStorePriceRounding.PRICE_TIERS`,
  mirrored in `scripts/adjust-iap-prices.sh`) is a representative subset of
  Apple's real USD price points, not the complete ~100+-tier list across
  every currency — sufficient to round the range this formula actually
  produces for the launch coin amounts. See the note added to CONTRACT.md's
  "Recommended launch packages" section for where this mechanical rule
  diverges from that table's illustrative (hand-picked) example prices.
- **The App Store Connect API call in `scripts/adjust-iap-prices.sh`'s
  `update_asc_price()` is intentionally left as a documented stub, not
  wired in.** The ES256-signed bearer JWT construction (`build_asc_jwt`) is
  real and complete — Apple's App Store Connect API auth spec is stable and
  well-documented. The actual price-schedule mutation is not, because (a) it
  requires resolving a territory-specific *price point id* via a separate
  authenticated lookup this script doesn't perform, and (b) the exact
  current request body shape for `inAppPurchasePriceSchedules` is the part
  of Apple's API most likely to have drifted since this was written —
  getting it wrong risks silently mispricing a live product. The script logs
  exactly what it *would* do instead of guessing; see the comment directly
  above `update_asc_price` in that file for what's specifically missing.

## Docker

`Dockerfile` is a multi-stage build, per `CONTRACT.md`'s "Docker /
docker-compose" section:

1. A `ghcr.io/graalvm/graalvm-community:25` build stage runs `./gradlew
   installDist`, producing the `application` plugin's install output at
   `build/install/aicoin-proxy/` (a `bin/aicoin-proxy` start script plus a
   `lib/` of jars — everything needed to run, no Gradle at runtime).
2. Only that install output is copied onto the same GraalVM image as the
   runtime base (GraalVM Community doesn't ship a separate JDK/JRE split the
   way eclipse-temurin does). The entrypoint runs the generated start script,
   `/opt/aicoin-proxy/bin/aicoin-proxy`.

**Why GraalVM 25, not "GraalVM JDK 26":** CONTRACT.md originally called for
GraalVM JDK 26 specifically (matching the rest of the codebase's Java 26
baseline), but GraalVM has not published a JDK 26 build as of this writing —
verified against `ghcr.io/graalvm/graalvm-community`'s live tag list (no `26`
tag) and graalvm.org's release calendar (no JDK 26 entry, even planned). This
Dockerfile, `build.gradle`'s toolchain, and both `.java-version` files are
pinned together to the latest real, published GraalVM major line (25) — all
three must move together (running JDK-26-targeted bytecode on a JDK 25 JVM
fails with `UnsupportedClassVersionError` at startup) — and should be bumped
to 26 together the moment GraalVM actually ships that build.

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
