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
- `-difficulty=1` number of required leading hex '0' chars in block hash (keep small so PoW is near-instant in tests)
- `-redis=host:port` (optional) — when set, persist/reload the chain via Redis (see "Persistence" below); unset = in-memory only (unchanged from before)
- `-decay-hour=1.0 -decay-day=0.5 -decay-week=0.25 -decay-month=0.125 -decay-year=0.0625 -decay-older=0.03125` — price recency-bucket weights (see "Derived state — price" below); defaults shown are a documented judgment call, not user-specified exact numbers

### Chain model
- `Block{Index int, Timestamp string(RFC3339), PrevHash string, Hash string, Nonce int, Transactions []Transaction}`
- `Transaction{Type:"event", UserID string, Provider string, CostUSD float64, Timestamp string(RFC3339)}`
- Genesis: index 0, `PrevHash` = 64 zero chars, no transactions, fixed nonce so all nodes derive the same genesis hash independently (no genesis broadcast needed).
- One transaction per block (no mempool batching) — simplest correct model.
- PoW: hash = hex(SHA256(index|prevHash|timestamp|txJSON|nonce)); valid iff it has >= `difficulty` leading '0' hex chars.
- On `POST /events`: build the Transaction, mine a new block on top of local tip, append locally, broadcast to all connected peers, return the new block info.
- On receiving a block from a peer: validate hash+PoW+`PrevHash` links to current tip → append + re-gossip to other peers. If it doesn't link but the peer's full chain (fetched via `chain_request`) is longer and every block validates, replace local chain (longest-valid-chain rule).

### P2P transport
Plain TCP, newline-delimited JSON envelopes: `{"type": "hello"|"block"|"chain_request"|"chain_response", "payload": ...}`.
On establishing a connection (outbound to a `-peers` entry, or inbound accept): send `hello` (payload = own p2p listen addr), then immediately request/respond with `chain_response` (payload = full chain) so nodes sync on startup.

### Derived state (recomputed from chain, not stored separately)
- **Coin acquisition is closed-set: free faucet claim, or peer transfer ("buy/sell") — that's it.** An `event` transaction (a priced AI-provider call) does **not** mint any aicoin by itself; it exists purely to feed the price formula below.

### Derived state — price (final formula)
**1 aicoin's price = a recency-weighted average of `CostUSD` across all `event` transactions ever — NOT divided by number of users.** Every event contributes `cost_usd * weight`, where `weight` depends on which UTC calendar bucket the event's `Timestamp` falls into, relative to "now" (wall-clock at query time). Buckets are evaluated top-to-bottom, first match wins, comparing calendar fields (year/month/ISO-week/day/hour) of the event's timestamp against "now"'s:
1. same UTC year+month+day+hour as now → weight `decay.hour` (default **1.0**)
2. else same UTC year+month+day as now → weight `decay.day` (default **0.5**)
3. else same UTC year + ISO calendar week as now → weight `decay.week` (default **0.25**)
4. else same UTC year+month as now → weight `decay.month` (default **0.125**)
5. else same UTC year as now → weight `decay.year` (default **0.0625**)
6. else (a prior year) → weight `decay.older` (default **0.03125**)

`price_usd = Σ(weight_i * cost_usd_i) / Σ(weight_i)` over all event transactions. Zero events → `price_usd = 0`. These six weights are the exact CLI flags above — the specific numbers are a documented default (monotonically halving per bucket), not dictated by the user beyond "older matters less"; tune later via flags if the decay curve needs adjusting.

### HTTP API (JSON)
- `POST /events` — body `{"user_id":"...","provider":"...","cost_usd":0.001,"timestamp":"...""}` (`timestamp` optional, server fills `now` if absent) → `200 {"height":N,"hash":"..."}`
- `GET /price` → `{"price_usd":..,"total_spend_usd":..,"weighted_total":..,"height":N}` — `total_spend_usd` is the plain unweighted all-time sum (visibility only), `weighted_total` is `Σweight_i` (the formula's denominator, for debugging/verification)
- `GET /chain` → full chain as a JSON array of blocks
- `GET /peers` → list of connected peer P2P addresses
- `GET /balance/{user_id}` → `{"user_id":"...","balance":N}` — sum of `free_claim` mints (+1.0 each) and `transfer` txs (-Amount as sender, +Amount as recipient); `event` txs contribute 0
- `GET /health` → `{"status":"ok","height":N}`

### Free-coin faucet
- New transaction type: `Transaction{Type:"free_claim", UserID string, Timestamp string(RFC3339)}` — mints 1.0 aicoin to `UserID`, mined into a block exactly like an `event` tx (goes through the same PoW/gossip pipeline). `free_claim` transactions do **not** feed `/price` (only `event` transactions do, per the price formula above).
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
- Repo-root `docker-compose.yml` (written after both Dockerfiles exist — not part of either project's own task): `redis` (official `redis:alpine` image), `aicoin-node` (built from `aicoin/Dockerfile`, `-redis=redis:6379`), `aicoin-proxy` (built from `aicoin-proxy/Dockerfile`, pointed at `aicoin-node`).
