# aicoin monorepo — shared contract

Two independent projects that must interoperate over HTTP/TCP as specified below.
Do not deviate from field names/types/ports/flags here without updating this file.

## Layout

```
aicoin/            (this repo root)
  aicoin/           Go P2P blockchain node (module "aicoin")
  aicoin-proxy/     Java 11 + Netty reverse proxy (Gradle)
  e2e/              end-to-end test wiring both together
```

## aicoin (Go) — P2P blockchain node

Binary: `go run ./cmd/aicoind` (or built via `go build ./cmd/aicoind`).

CLI flags:
- `-http=:9944`   HTTP API listen address
- `-p2p=:9945`    P2P TCP listen address
- `-peers=host:port,...`  comma-separated bootstrap peer P2P addresses (optional)
- `-role=primary|follower` (default `primary`) — see "Roles & signing" below
- `-keyfile=aicoin-node.key` (primary only) — path to the node's persistent Ed25519 private key; generated on first run if the file doesn't exist
- `-trusted-pubkey=<hex>` (required when `-role=follower`) — the primary's Ed25519 public key, hex-encoded; a primary logs its own pubkey hex on startup for copy-paste into followers
- `-redis=host:port` (optional) — when set, persist/reload the chain via Redis (see "Persistence" below); unset = in-memory only (unchanged from before)
- `-decay-halflife-days=110.0` — price decay half-life in days (see "Derived state — price" below); default derived from a real, documented ~10x-per-year AI pricing decline rate, not an arbitrary guess

### Roles & signing — single source of truth, no PoW
There is exactly one legitimate writer: the **primary**. It holds an Ed25519 keypair and *signs* every block it appends — that signature (not proof-of-work) is what makes a block valid. **Followers** hold only the primary's public key (`-trusted-pubkey`), replicate the primary's signed chain via P2P, and reject all writes — there is no mining, no difficulty, no nonce, and no "longest valid chain from competing miners" scenario, because nobody but the primary can produce a chain whose blocks verify against the trusted pubkey.
- Write endpoints (`POST /events`, `/transfer`, `/free-coins/claim`) on a **follower** → `403 {"error":"this node is a read-only replica; write to the primary"}`. On a **primary**, they work as before, minus any mining step.
- A **primary** never replaces its own chain based on incoming P2P gossip/sync — it is authoritative by definition. A **follower** adopts a peer's chain if every block validates (see below) and it's longer than its own.
- `GET /health` now also reports `{"status":"ok","height":N,"role":"primary"|"follower","pubkey":"<hex>"}` — for a primary, `pubkey` is its own signing key's public half; for a follower, it's the `-trusted-pubkey` it's configured to verify against.

### Chain model
- `Block{Index int, Timestamp string(RFC3339), PrevHash string, Hash string, Signature string, Transactions []Transaction}` (no `Nonce` — that was PoW-only and is gone)
- `Transaction{Type:"event", UserID string, Provider string, CostUSD float64, Timestamp string(RFC3339)}`
- Genesis: index 0, `PrevHash` = 64 zero chars, no transactions, fixed timestamp, empty `Signature` — a well-known deterministic constant every node computes independently and accepts without signature checking (index 0 is always special-cased as valid).
- One transaction per block (no mempool batching) — simplest correct model, unchanged.
- `Hash = hex(SHA256(index|prevHash|timestamp|txJSON))` — a content hash, not a puzzle (no nonce search).
- For index >= 1: the primary computes `Hash`, then `Signature = hex(Ed25519.Sign(privateKey, sha256Digest))` (signs the raw 32-byte digest, not its hex string), and appends immediately — no search/delay.
- On `POST /events` (primary only): build the Transaction, seal+sign a new block on top of the local tip, append locally, broadcast to all connected peers, return the new block info.
- `ValidateBlock(block, prevBlock, trustedPubKey)`: index 0 → must exactly match the well-known genesis constant, always valid. index >= 1 → recompute `Hash` from the block's own fields and confirm it matches the stored value; confirm `PrevHash == prevBlock.Hash`; confirm `Ed25519.Verify(trustedPubKey, sha256Digest, signatureBytes)`. No difficulty/PoW check exists anymore.
- On a **follower** receiving a block from a peer: if it validates and links to the current tip → append + re-gossip. If it doesn't link but the peer's full chain (fetched via `chain_request`) is longer and every block validates against `trustedPubKey` → replace local chain. A **primary** ignores incoming chain-replacement attempts entirely (it only ever appends blocks it itself signs).

### P2P transport
Plain TCP, newline-delimited JSON envelopes: `{"type": "hello"|"block"|"chain_request"|"chain_response", "payload": ...}`.
On establishing a connection (outbound to a `-peers` entry, or inbound accept): send `hello` (payload = own p2p listen addr), then immediately request/respond with `chain_response` (payload = full chain) so nodes sync on startup.

### Derived state (recomputed from chain, not stored separately)
- **Coin acquisition is closed-set: free faucet claim, or peer transfer ("buy/sell") — that's it.** An `event` transaction (a priced AI-provider call) does **not** mint any aicoin by itself; it exists purely to feed the price formula below.

### Derived state — price (final formula, v2: smooth exponential decay)
**1 aicoin's price = a recency-weighted average of `CostUSD` across all `event` transactions ever — NOT divided by number of users.** Every event contributes `cost_usd * weight(age)`, where `age` = `now - event.Timestamp` (wall-clock "now" at query time; a negative age from clock skew/future timestamps clamps to `0`, giving `weight = 1.0`), and:

```
weight(age) = 2 ^ (-age_days / halfLifeDays)
```

a single continuous, smoothly-decreasing curve — no calendar buckets, no step-function jumps at hour/day/week/month boundaries. `halfLifeDays` is the CLI flag `-decay-halflife-days` (default **110.0**).

**Why 110 days**: calibrated from a real, well-documented industry data point — AI inference/API pricing has fallen roughly **10x per year** across major providers (e.g. OpenAI's public per-token pricing dropped roughly 10x from the GPT-3.5-turbo era (early 2023) to GPT-4o-mini-class pricing (mid-2024)). A 10x-per-year decline implies a half-life of `365.25 * ln(2)/ln(10) ≈ 110 days`. This is a documented industry rule-of-thumb, not a precise proprietary dataset — see `aicoin/README.md`'s "Assumptions" for the full reasoning. The economic intuition: an old cost figure shouldn't count as much toward *today's* price precisely because AI got cheaper by roughly that much since it was recorded.

Named checkpoints (informational only — computed from the one formula above, not independently configurable), under the default half-life:

| age | weight |
|---|---|
| 1 hour | ≈ 1.000 |
| 1 day | ≈ 0.994 |
| 1 week | ≈ 0.957 |
| 1 month (30.44d) | ≈ 0.825 |
| 1 quarter (91.31d) | ≈ 0.563 |
| 1 year (365.25d) | ≈ 0.100 (by construction) |
| 5 years | ≈ 0.00001 |

`price_usd = Σ(weight(age_i) * cost_usd_i) / Σ(weight(age_i))` over all event transactions. Zero events → `price_usd = 0`.

### HTTP API (JSON)
- `POST /events` — body `{"user_id":"...","provider":"...","cost_usd":0.001,"timestamp":"...""}` (`timestamp` optional, server fills `now` if absent) → `200 {"height":N,"hash":"..."}`
- `GET /price` → `{"price_usd":..,"total_spend_usd":..,"weighted_total":..,"height":N,"half_life_days":110}` — `total_spend_usd` is the plain unweighted all-time sum (visibility only), `weighted_total` is `Σweight_i` (the formula's denominator, for debugging/verification), `half_life_days` is the configured decay half-life (for transparency/verification of the smooth-decay formula above)
- `GET /chain` → full chain as a JSON array of blocks
- `GET /peers` → list of connected peer P2P addresses
- `GET /balance/{user_id}` → `{"user_id":"...","balance":N}` — sum of `free_claim` mints (+1.0 each) and `transfer` txs (-Amount as sender, +Amount as recipient); `event` txs contribute 0
- `GET /health` → `{"status":"ok","height":N}`

### Free-coin faucet
- New transaction type: `Transaction{Type:"free_claim", UserID string, Timestamp string(RFC3339)}` — mints 1.0 aicoin to `UserID`, sealed+signed into a block exactly like an `event` tx (same signing/gossip pipeline, primary-only). `free_claim` transactions do **not** feed `/price` (only `event` transactions do, per the price formula above).
- `POST /free-coins/claim` — body `{"user_id":"..."}`. Look at the chain for the most recent `free_claim` tx with this `UserID`. If there is none, or its `Timestamp` is >= 1 hour in the past, mint a new `free_claim` tx (as above) and return `200 {"granted":true,"height":N,"hash":"...","next_eligible_at":"RFC3339"}`. Otherwise return `429 {"granted":false,"next_eligible_at":"RFC3339"}` (no more than 1 free coin per user per rolling hour).

### Peer transfer (buy/sell)
New transaction type `Transaction{Type:"transfer", FromUserID string, ToUserID string, Amount float64, Timestamp string(RFC3339)}` — mined like any other tx. Derived balance effect: `balances[FromUserID] -= Amount; balances[ToUserID] += Amount`. This is the *entire* buy/sell mechanism — no real money, no external payment rail: "buying" is just receiving a transfer, "selling" is sending one.
`POST /transfer` — body `{"from_user_id":"...","to_user_id":"...","amount":N}`. Validate `amount > 0` and current derived balance of `from_user_id >= amount`; if not, `400 {"error":"insufficient balance"}`. Otherwise mine the tx and return `200 {"height":N,"hash":"..."}`.

### Persistence (Redis stand-in for a real AWS in-memory store)
When `-redis=host:port` is set: on startup, `GET` key `aicoin:chain` from Redis — if present (a JSON array of blocks, same shape as `GET /chain`'s response), load it as the local chain instead of starting from genesis. After successfully appending any block (whether mined locally via an API call, or accepted from a peer via gossip), `SET aicoin:chain` to the full current chain JSON. When `-redis` is unset, behavior is unchanged (pure in-memory, resets on restart). This is explicitly a stand-in for a real AWS in-memory datastore (e.g. ElastiCache/MemoryDB) — same read/write shape, swappable later without changing the chain logic itself.

### Wallet CLI
Binary: `go run ./cmd/wallet -user=<id> [-node=http://localhost:9944] [-proxy=http://localhost:8080]` (defaults shown).
On start:
1. `GET {proxy}/free-coins/available` → `{"available": N}`.
2. If `N > 0`: `POST {node}/free-coins/claim {"user_id": <id>}`.
   - `granted:true` → print `"Claimed 1 free aicoin! New balance: <GET {node}/balance/{user}>"`.
   - `granted:false` (429) → print `"Not eligible yet — next free coin at <next_eligible_at>"`.
3. If `N == 0`: print `"No free coins available right now (proxy allowance is 0)."`
Also support a `-balance-only` flag that just prints `GET {node}/balance/{user}` without touching the faucet.

## aicoin-proxy (Java 11 + Netty, Gradle)

Run: `./gradlew run` (application plugin), Netty 4.1.x from Maven Central. Fully async — no framework (no Spring).

### Config
YAML file, path from env `AICOIN_PROXY_CONFIG` (default: bundled `application.yaml` with the values below). Every value overridable by env var for the e2e test:

```yaml
server:
  port: 8080                      # AICOIN_PROXY_PORT
aicoin:
  eventsUrl: http://localhost:9944/events   # AICOIN_PROXY_AICOIN_EVENTS_URL
  priceUrl: http://localhost:9944/price     # AICOIN_PROXY_AICOIN_PRICE_URL
  balanceUrlBase: http://localhost:9944     # AICOIN_PROXY_AICOIN_BALANCE_URL_BASE  (used as {balanceUrlBase}/balance/{walletId} for wallet-id validation)
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
pricing:
  costPerTokenUsd: 0.000002       # AICOIN_PROXY_COST_PER_TOKEN_USD
  defaultCostUsdPerCall: 0.001    # AICOIN_PROXY_DEFAULT_COST_USD
freeCoins:
  counterFile: free-coins-counter.txt   # AICOIN_PROXY_FREE_COINS_COUNTER_FILE — bundled classpath/resource file, single integer, admin-managed via git push + CI redeploy
```
`baseUrl` may be `http://` (plain, used by tests against a local mock) or `https://` (real providers, TLS client).

### Routing — same path, header selects the provider, proxy owns the upstream key
The client calls the proxy at **exactly the same path** the real provider would use (e.g. `POST /v1/chat/completions`) — only the domain changes to the proxy's. A request header `X-AI: <provider>` (one of `openai|anthropic|google|mistral|cohere`, case-insensitive) tells the proxy which upstream/config to use. The proxy:
1. Reads `X-AI`, looks up the matching `providers.<name>` config. Missing/unknown value → `400 {"error":"missing or unknown X-AI header"}`.
2. **Removes** the `X-AI` header (the upstream provider must never see it).
3. Forwards the **same method + same path/query + same body** to `providers.<name>.baseUrl`, with all original client headers preserved *except*: `Host`/`Content-Length` (recomputed) and whatever the client sent in `authHeader` for this provider (or the raw `Authorization`) — the proxy **overwrites/injects its own `apiKey`** as that provider's paid credential (via `authHeader`+`authPrefix`, or as a `authQueryParamName` query param when `authAsQueryParam` is true, e.g. Google).

### Auth — wallet id IS the API key
There is no separate provider key or API key concept for the client — **the caller's aicoin wallet id doubles as their API key.** Required header: `X-Api-Key: <walletId>` (replaces the old `X-User-Id`). Missing → `401 {"error":"missing X-Api-Key (wallet id)"}`.
Before forwarding, the proxy validates the wallet over plain HTTP (no subprocess): `GET {aicoin.balanceUrlBase}/balance/{walletId}` (new config, default `http://localhost:9944`, env `AICOIN_PROXY_AICOIN_BALANCE_URL_BASE`). If that call fails/times out (aicoin node unreachable) → `503 {"error":"could not validate wallet"}`. If it succeeds, proceed — note the aicoin node returns a balance (possibly 0) for *any* syntactically-valid id, even one never used before, so this call is a liveness/reachability check on the aicoin node, not a cryptographic identity check; that's a documented assumption, not a security guarantee. The validated `walletId` becomes the `user_id` in the `/events` POST — the old `X-User-Id` header is gone.

### Forwarding (non-streaming, full aggregation is fine)
1. Inbound: `HttpServerCodec` + `HttpObjectAggregator` → routing handler.
2. Outbound: new Netty client `Bootstrap` per request to upstream host, `HttpClientCodec` + `HttpObjectAggregator`, TLS via `SslContextBuilder.forClient()` when baseUrl is https.
3. Write the upstream's exact status/headers/body back to the client.
4. If upstream status is 2xx: compute `cost_usd` — parse JSON body for `usage.total_tokens` (OpenAI-style) or `usage.input_tokens`+`usage.output_tokens` (Anthropic-style); `cost_usd = tokens * pricing.costPerTokenUsd` if found, else `pricing.defaultCostUsdPerCall`. Fire-and-forget async `POST` to `aicoin.eventsUrl` with `{"user_id":<walletId>,"provider","cost_usd"}` — must never block or fail the client response; log+ignore errors.
5. Non-2xx/connection failure: relay the real error to the client, do not emit an event.

### Additional proxy-side endpoints
- `GET /price` — proxy forwards to `aicoin.priceUrl` and returns that JSON body verbatim to the client (so callers don't need to know the aicoin node's address). Upstream unreachable → `502 {"error":"aicoin node unreachable"}`.
- `GET /free-coins/available` — reads `freeCoins.counterFile` fresh on every request (bundled resource, manually bumped via git push + CI redeploy of the proxy) → `{"available": N}`. Missing/unparseable file → `{"available": 0}`.
- `GET /health` — for each configured provider (openai, anthropic, google, mistral, cohere), report whether recent upstream calls have hit rate-limiting or budget errors. Track, per provider, a rolling window of the last `health.windowSize` forwarded calls (config, default 50, env `AICOIN_PROXY_HEALTH_WINDOW_SIZE`): `rateLimited` = true if any upstream response in the window was HTTP 429; `overBudget` = true if any was HTTP 402 or 403; `healthy` = `!rateLimited && !overBudget`. Response: `{"providers":[{"name":"openai","healthy":true,"rateLimited":false,"overBudget":false}, ...]}` (all 5 providers always listed, even ones with zero calls so far — those default to `healthy:true`).

### Tests to include
- JUnit5 pure-function tests: `X-AI`→provider resolution (incl. missing/unknown → 400), auth-injection header/query-param construction per provider, usage-JSON→cost_usd parsing (no network needed).

## Docker / docker-compose
- `aicoin/Dockerfile` — multi-stage: build `aicoind` (and `wallet`) with the Go toolchain, copy the static binary/binaries into a minimal runtime base (e.g. `gcr.io/distroless/static` or `alpine`). Entrypoint runs `aicoind`, flags/ports configurable via `CMD`/env at `docker run` time.
- `aicoin-proxy/Dockerfile` — multi-stage: `./gradlew build` in a JDK-11 build stage, copy the `application` plugin's install output (`build/install/aicoin-proxy/`) into a JRE-11 runtime image. Entrypoint runs the generated start script.
- Repo-root `docker-compose.yml` (written after both Dockerfiles exist — not part of either project's own task): `redis` (official `redis:alpine` image), `aicoin-node` (built from `aicoin/Dockerfile`, `-role=primary -redis=redis:6379`), `aicoin-proxy` (built from `aicoin-proxy/Dockerfile`, pointed at `aicoin-node`).
