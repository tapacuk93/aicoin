# aicoin

A minimal but real peer-to-peer blockchain node: actual SHA256 proof-of-work
mining, actual TCP networking between independently-running node
processes, and longest-valid-chain conflict resolution. No wallet
signatures (user IDs are plain strings), no BFT/advanced consensus.
Persistence is optional and Redis-backed (see "Persistence" below); with no
`-redis` flag it's pure in-memory, resetting on restart, exactly as before.

This document mirrors the shared contract at `../CONTRACT.md` but is
written to stand alone.

## Build & run

```
go build ./cmd/aicoind
./aicoind -http=:9944 -p2p=:9945 -difficulty=1
```

or directly:

```
go run ./cmd/aicoind -http=:9944 -p2p=:9945 -difficulty=1
```

or via Docker (see "Docker" below):

```
docker build -t aicoin .
docker run -p 9944:9944 -p 9945:9945 aicoin
```

### CLI flags

| Flag            | Default    | Meaning                                                                 |
|-----------------|------------|--------------------------------------------------------------------------|
| `-http`         | `:9944`    | HTTP API listen address                                                 |
| `-p2p`          | `:9945`    | P2P TCP listen address                                                  |
| `-peers`        | `""`       | Comma-separated bootstrap peer P2P addresses (`host:port,host:port,...`)|
| `-difficulty`   | `1`        | Number of required leading hex `0` chars in a block's PoW hash          |
| `-redis`        | `""`       | Optional `host:port` of a Redis server for chain persistence (see "Persistence" below); unset = pure in-memory |
| `-decay-hour`   | `1.0`      | `/price` weight for events in the same UTC hour as "now"                |
| `-decay-day`    | `0.5`      | `/price` weight for events in the same UTC day as "now"                 |
| `-decay-week`   | `0.25`     | `/price` weight for events in the same ISO calendar week as "now"       |
| `-decay-month`  | `0.125`    | `/price` weight for events in the same UTC month as "now"               |
| `-decay-year`   | `0.0625`   | `/price` weight for events in the same UTC year as "now"                |
| `-decay-older`  | `0.03125`  | `/price` weight for events from a prior UTC year                        |

Two-node example (node B bootstraps off node A):

```
./aicoind -http=:9944 -p2p=:9945 -difficulty=1 &
./aicoind -http=:9946 -p2p=:9947 -peers=127.0.0.1:9945 -difficulty=1 &
```

## Chain model

- `Block{index, timestamp, prev_hash, hash, nonce, transactions}` — JSON
  field names are snake_case (see below for exact shape).
- `Transaction` is a tagged union over `type`: `"event"` (`user_id`,
  `provider`, `cost_usd`, `timestamp`), `"free_claim"` (`user_id`,
  `timestamp`), or `"transfer"` (`from_user_id`, `to_user_id`, `amount`,
  `timestamp`). Fields not relevant to a transaction's `type` are omitted
  from its JSON.
- Genesis is block index 0, `prev_hash` is 64 `'0'` chars, no transactions,
  fixed nonce `0` and a fixed timestamp (`1970-01-01T00:00:00Z`) — every
  node derives byte-identical genesis independently, so it's never
  gossiped.
- Exactly one transaction per block (no mempool batching).
- PoW: `hash = hex(SHA256(index|prevHash|timestamp|txJSON|nonce))`, where
  `txJSON` is the JSON encoding of the block's transaction list. Valid iff
  the hash has at least `difficulty` leading hex `'0'` characters.
- `POST /events`: builds the transaction, mines a new block on top of the
  local tip, appends it locally, broadcasts it to all connected peers, and
  returns the new block's height/hash.
- On receiving a `block` from a peer: if it links to the local tip (right
  index + `prev_hash`) and its hash/PoW validate, append it and re-gossip
  to other peers (excluding the sender). If it doesn't link (e.g. we're
  behind, or there's a fork at our tip height), request the sender's full
  chain (`chain_request`); if that turns out to be longer and every block
  in it validates, replace the local chain (longest-valid-chain rule).

## P2P transport

Plain TCP, newline-delimited JSON envelopes:

```json
{"type": "hello" | "block" | "chain_request" | "chain_response", "payload": ...}
```

- `hello` — payload is the sender's own P2P listen address (string), i.e.
  exactly the value passed to that node's `-p2p` flag.
- `chain_request` — payload is `null`; asks the recipient to send back its
  full chain.
- `chain_response` — payload is the sender's full chain (JSON array of
  blocks).
- `block` — payload is a single mined/gossiped block.

On establishing a connection — whether outbound (dialing a `-peers` entry)
or inbound (accepting a connection) — a node sends `hello` immediately
followed by `chain_request`. The remote side replies to `chain_request`
with its own `chain_response`, so both nodes converge to the same
(longest-valid) chain right after connecting, with no separate startup
sync step needed.

## Derived state

Nothing beyond the chain itself is stored; every query recomputes from the
in-memory (or Redis-loaded) block list:

- **Coin acquisition is closed-set: free faucet claim, or peer transfer
  ("buy/sell") — that's it.** An `"event"` transaction (a priced
  AI-provider call) does **not** mint any aicoin by itself; it exists
  purely to feed the price formula below. `balances[user_id] += 1.0` per
  `"free_claim"` transaction belonging to that user (see "Free-coin
  faucet" below), and `balances[from_user_id] -= amount` /
  `balances[to_user_id] += amount` per `"transfer"` transaction (see "Peer
  transfer (buy/sell)" below) — summed over the whole chain.
- **Price is a recency-weighted average of `cost_usd` across every
  `"event"` transaction ever recorded — not divided by number of users.**
  Each event's `cost_usd` is weighted by which UTC calendar bucket its
  `timestamp` falls into relative to "now" (wall-clock at query time).
  Buckets are checked top-to-bottom, first match wins, comparing UTC
  calendar fields of the event's timestamp against "now"'s:

  1. same UTC year+month+day+hour as now → `decay.hour` (default **1.0**)
  2. else same UTC year+month+day as now → `decay.day` (default **0.5**)
  3. else same UTC year + ISO calendar week as now → `decay.week` (default **0.25**)
  4. else same UTC year+month as now → `decay.month` (default **0.125**)
  5. else same UTC year as now → `decay.year` (default **0.0625**)
  6. else (a prior year) → `decay.older` (default **0.03125**)

  ```
  price_usd = Σ(weight_i * cost_usd_i) / Σ(weight_i)
  ```

  over all event transactions ever. `total_spend_usd` is the plain
  unweighted all-time sum of `cost_usd` (visibility only); `weighted_total`
  is `Σweight_i`, the formula's denominator (for debugging/verification).
  Zero events (or, degenerately, a `weighted_total` of exactly 0 because
  every configured weight for the buckets actually hit happens to be 0)
  yields `price_usd = 0` rather than dividing by zero.

## Peer transfer (buy/sell)

A new transaction type moves already-minted aicoin between two users —
this is the *entire* buy/sell mechanism; there's no real money and no
external payment rail involved. "Buying" is just receiving a transfer,
"selling" is sending one:

```json
{"type": "transfer", "from_user_id": "alice", "to_user_id": "bob", "amount": 0.4, "timestamp": "2026-08-03T12:00:00Z"}
```

It mines/gossips through the exact same PoW/P2P pipeline as any other
transaction. Its derived-balance effect: `balances[from_user_id] -= amount;
balances[to_user_id] += amount`.

### `POST /transfer`

Request:

```json
{"from_user_id": "alice", "to_user_id": "bob", "amount": 0.4}
```

The server validates `amount > 0` and that `alice`'s current derived
balance is `>= amount`. If either check fails, nothing is mined and the
response is `400`:

```json
{"error": "insufficient balance"}
```

Otherwise the transfer tx is mined and the response is `200`:

```json
{"height": 4, "hash": "00cfa60c..."}
```

## HTTP API

All bodies/responses are JSON. (`POST /transfer` is documented above under
"Peer transfer (buy/sell)"; `POST /free-coins/claim` is documented below
under "Free-coin faucet".)

### `POST /events`

Request:

```json
{"user_id": "alice", "provider": "openai", "cost_usd": 0.001, "timestamp": "2026-08-03T12:00:00Z"}
```

`timestamp` is optional; if omitted the server fills in the current time
(UTC, RFC3339). `provider`/`cost_usd` are recorded as given (no upstream
validation against known providers). `user_id` is required.

Response `200`:

```json
{"height": 1, "hash": "0008a368121c0eddb8551477a43658fed277b623913618d6bc73b144d3ebf06a"}
```

`height` is the new block's index (genesis is height 0).

### `GET /price`

```json
{"price_usd": 0.00206060606, "total_spend_usd": 0.006, "weighted_total": 1.03125, "height": 2}
```

`price_usd` is the recency-weighted average from the "Derived state"
section above; `total_spend_usd` is the plain unweighted all-time sum of
`cost_usd`; `weighted_total` is the formula's denominator (`Σweight_i`).
All three are `0` when there are zero event transactions.

### `GET /chain`

Full chain as a JSON array of blocks:

```json
[
  {"index": 0, "timestamp": "1970-01-01T00:00:00Z", "prev_hash": "000...0", "hash": "6b4c...", "nonce": 0, "transactions": []},
  {"index": 1, "timestamp": "2026-08-03T02:40:30Z", "prev_hash": "6b4c...", "hash": "0008...", "nonce": 29, "transactions": [{"type": "event", "user_id": "alice", "provider": "openai", "cost_usd": 0.0042, "timestamp": "2026-08-03T02:40:30Z"}]}
]
```

### `GET /peers`

```json
["127.0.0.1:9947"]
```

List of currently-connected peers' self-reported P2P listen addresses (see
"Assumptions" below for a caveat on this).

### `GET /balance/{user_id}`

```json
{"user_id": "alice", "balance": 1}
```

Balance = (count of `"free_claim"` transactions for `user_id`) + (sum of
`"transfer"` transactions where `user_id` is `to_user_id`) - (sum of
`"transfer"` transactions where `user_id` is `from_user_id`) — see
"Free-coin faucet" and "Peer transfer (buy/sell)" above. `"event"`
transactions never contribute.

### `GET /health`

```json
{"status": "ok", "height": 1}
```

## Free-coin faucet

A new transaction type mints aicoin directly, independent of the price
formula:

```json
{"type": "free_claim", "user_id": "alice", "timestamp": "2026-08-03T12:00:00Z"}
```

It mines/gossips through the exact same PoW/P2P pipeline as an `"event"`
transaction, mints 1.0 aicoin to `user_id`, and is ignored entirely by
`/price` (no `cost_usd`, doesn't count toward `total_spend_usd` or
`weighted_total`).

### `POST /free-coins/claim`

Request:

```json
{"user_id": "alice"}
```

The server scans the chain for the most recent `free_claim` transaction
belonging to `user_id`:

- If there is none, or its `timestamp` is >= 1 hour in the past, a new
  `free_claim` transaction is mined and the response is `200`:

  ```json
  {"granted": true, "height": 2, "hash": "0bbc...", "next_eligible_at": "2026-08-03T13:00:00Z"}
  ```

- Otherwise (claimed less than 1 hour ago), no coin is minted and the
  response is `429`:

  ```json
  {"granted": false, "next_eligible_at": "2026-08-03T12:45:00Z"}
  ```

  (`next_eligible_at` here is the previous claim's timestamp + 1 hour.)

No more than one free coin per user per rolling hour.

## Wallet CLI

`cmd/wallet` is a small standalone client for the faucet + balance query.
Run it with:

```
go run ./cmd/wallet -user=<id> [-node=http://localhost:9944] [-proxy=http://localhost:8080] [-balance-only]
```

| Flag             | Default                  | Meaning                                             |
|------------------|---------------------------|------------------------------------------------------|
| `-user`          | *(required)*              | User id to act on                                    |
| `-node`          | `http://localhost:9944`   | aicoin node base URL                                 |
| `-proxy`         | `http://localhost:8080`   | aicoin-proxy base URL (source of faucet allowance)    |
| `-balance-only`  | `false`                   | Skip the faucet; just print the current balance      |

Default behavior (no `-balance-only`):

1. `GET {proxy}/free-coins/available` → `{"available": N}`.
2. If `N > 0`: `POST {node}/free-coins/claim {"user_id": <id>}`.
   - `granted:true` → prints the height/hash of the newly mined block, then
     the user's new balance via `GET {node}/balance/{user}`.
   - `granted:false` (429) → prints the `next_eligible_at` time.
3. If `N == 0`: prints that no free coins are available right now (proxy
   allowance is 0) and exits without touching the faucet.

With `-balance-only`, the faucet/proxy step is skipped entirely; the CLI
just prints `GET {node}/balance/{user}`.

The wallet's decision of whether to attempt a claim (`available > 0`) and
its response parsing are pure functions (`shouldAttemptClaim`,
`parseAvailable`, `parseClaim`, `parseBalance` in `cmd/wallet/main.go`),
unit-tested in `cmd/wallet/main_test.go` without any network access. A full
live run against a real `aicoin-proxy` is exercised separately by the
top-level `e2e` test that wires both projects together.

## Persistence (optional, Redis-backed)

By default aicoind is pure in-memory: the chain resets on every restart.
Passing `-redis=host:port` enables persistence as a stand-in for a real
AWS in-memory datastore (e.g. ElastiCache/MemoryDB) — same read/write
shape, swappable later without touching the chain logic itself:

- On startup, the node `GET`s key `aicoin:chain` from Redis. If present
  (a JSON array of blocks, same shape as `GET /chain`'s response), it's
  loaded as the starting chain instead of genesis-only.
- After every successfully appended block — whether mined locally via an
  API call (`/events`, `/free-coins/claim`, `/transfer`) or accepted from a
  peer via P2P gossip — the node `SET`s `aicoin:chain` to the full current
  chain JSON.
- With `-redis` unset, none of this runs; behavior is unchanged (in-memory
  only).

This is implemented behind a small `chain.ChainStore` interface
(`Load() ([]Block, error)`, `Save([]Block) error`) in `internal/chain`, so
the Redis dependency (`github.com/redis/go-redis/v9`) is confined to a
single file, `internal/store/redis.go`. `internal/store/memory.go` provides
an in-memory fake implementing the same interface, used by
`internal/store/store_test.go`'s unit tests (load-then-save round-trip,
empty-store-means-genesis, and a full `Blockchain` restart simulation) —
useful in sandboxes where a live Redis server isn't reachable, since the
`Blockchain` code path that talks to `ChainStore` is exercised identically
either way.

Example:

```
./aicoind -http=:9944 -p2p=:9945 -redis=localhost:6379
```

## Docker

```
docker build -t aicoin .
docker run -p 9944:9944 -p 9945:9945 aicoin
```

The `Dockerfile` is a multi-stage build: a `golang:1.22-alpine` stage
compiles static (`CGO_ENABLED=0`) `aicoind` and `wallet` binaries from
`./cmd/aicoind` and `./cmd/wallet`, which are then copied into a minimal
`gcr.io/distroless/static-debian12` runtime image. The entrypoint runs
`aicoind`; every CLI flag above is overridable at `docker run` time by
passing extra arguments (they replace the default `CMD`, e.g.
`-http=:9944 -p2p=:9945`) or, for host/port mapping, via `-p`:

```
docker run -p 19944:19944 -p 19945:19945 aicoin \
  -http=:19944 -p2p=:19945 -difficulty=2 -redis=redis:6379
```

Nothing about ports or flags is hardcoded in the image beyond the
`CMD` defaults, which merely mirror aicoind's own flag defaults.

## Package layout

- `cmd/aicoind` — CLI entrypoint; wires flags to a `chain.Blockchain`
  (optionally backed by a `chain.ChainStore`), a `p2p.Node`, and an
  `api.Server`, then runs the P2P accept loop and the HTTP server.
- `cmd/wallet` — standalone CLI client for the free-coin faucet and balance
  query (see "Wallet CLI" above).
- `internal/chain` — `Block`/`Transaction` types, PoW (`Mine`,
  `ComputeHash`, `MeetsDifficulty`), the deterministic `Genesis`, chain
  validation (`ValidateBlock`, `ValidateChain`), the `ChainStore`
  persistence interface, and the thread-safe `Blockchain` (mine+append,
  append-from-peer, replace-if-longer, each persisting to the configured
  store if any).
- `internal/store` — `ChainStore` implementations: `Redis` (the only file
  importing a Redis client) and `InMemory` (a fake for tests).
- `internal/p2p` — the gossip protocol: envelope encoding, per-connection
  `Peer`, and the `Node` that accepts/dials connections, handshakes,
  dispatches incoming envelopes, and gossips/broadcasts blocks.
- `internal/state` — derived-state computation (`Balance`, `Price`,
  `FaucetEligibility`, `DecayWeights`) over a snapshot of the chain's
  blocks.
- `internal/api` — HTTP handlers implementing the endpoints above.

## Testing

```
go build ./...
go vet ./...
go test ./...
```

`internal/chain` has unit tests covering: PoW mining produces a hash
satisfying the configured difficulty (and that the reported nonce actually
reproduces it); chain validation rejects blocks with a wrong `prev_hash`, a
hash that doesn't match its own fields (invalid PoW/tampering), or a hash
not meeting the configured difficulty.

`internal/state` has unit tests covering: the recency-weighted price
formula, using synthetic events with controlled timestamps constructed to
land in each of the 6 decay buckets (hour/day/week/month/year/older)
relative to a fixed, test-controlled "now", checked against a
hand-computed `Σ(weight_i * cost_usd_i) / Σ(weight_i)` using the exact same
weights (plus a test proving custom (non-default) weights actually flow
through, and an explicit zero-event case); balance computation proving
`"event"` transactions contribute 0, `"free_claim"` transactions contribute
+1.0 each, and `"transfer"` transactions move `amount` from sender to
recipient (including several transfers netting out correctly for the same
user); and faucet eligibility (`FaucetEligibility`) against synthetic
`free_claim` transactions with controlled timestamps, covering
never-claimed-before, granted-after-1h, not-yet-eligible, and
most-recent-claim-wins-among-several cases.

`internal/api` has `httptest`-based unit tests (in-process, via
`httptest.NewRecorder`+`ServeHTTP` — no real sockets, so they run
unmodified even in sandboxes that deny TCP bind) covering `POST /transfer`:
a successful transfer moves balance correctly end-to-end (`/free-coins/claim`
→ `/transfer` → `/balance` before/after), an insufficient-balance transfer
is rejected with `400 {"error":"insufficient balance"}` and provably does
not mutate the chain (height and block count unchanged), and amount<=0 is
rejected the same way.

`internal/store` has unit tests for the `ChainStore` contract against the
`InMemory` fake: load-then-save round-trip (with defensive-copy checks in
both directions), empty-store-means-genesis, and a full `Blockchain`
"restart" simulation (mine some blocks, construct a second `Blockchain`
against the same store, verify it picks up exactly where the first left
off).

`cmd/wallet` has unit tests for its pure decision/parsing functions
(`shouldAttemptClaim`, `parseAvailable`, `parseClaim`, `parseBalance`) with
no network access required.

`internal/p2p` has two real-TCP integration tests (two `Node`s on real
loopback sockets: one gossiping a freshly-mined block end-to-end, one
syncing a 3-block head start purely via the startup `hello`/
`chain_request`/`chain_response` handshake). Some sandboxed CI hosts deny
raw `bind`/`listen` syscalls outright (even on loopback); on such hosts
these two tests detect that specific failure and `t.Skip()` rather than
fail, since it isn't something this package's code controls. On any host
where socket binding is actually permitted they run for real and verify
genuine cross-process-style TCP gossip and sync.

## Manual two-node smoke test

```
go build -o /tmp/aicoind ./cmd/aicoind

/tmp/aicoind -http=:19944 -p2p=:19945 -difficulty=1 &
/tmp/aicoind -http=:19946 -p2p=:19947 -peers=127.0.0.1:19945 -difficulty=1 &

curl -s -X POST http://127.0.0.1:19944/events \
  -H 'Content-Type: application/json' \
  -d '{"user_id":"alice","provider":"openai","cost_usd":0.0042}'

curl -s http://127.0.0.1:19944/balance/alice   # {"user_id":"alice","balance":0} -- events never mint

curl -s -X POST http://127.0.0.1:19944/free-coins/claim \
  -H 'Content-Type: application/json' \
  -d '{"user_id":"alice"}'                     # 200, granted:true, height/hash of the new block

curl -s -X POST http://127.0.0.1:19944/free-coins/claim \
  -H 'Content-Type: application/json' \
  -d '{"user_id":"alice"}'                     # 429, granted:false (already claimed this hour)

curl -s http://127.0.0.1:19944/balance/alice   # {"user_id":"alice","balance":1}

curl -s -X POST http://127.0.0.1:19944/transfer \
  -H 'Content-Type: application/json' \
  -d '{"from_user_id":"alice","to_user_id":"bob","amount":0.4}'  # 200, height/hash of the new block

curl -s http://127.0.0.1:19944/balance/alice   # {"user_id":"alice","balance":0.6}
curl -s http://127.0.0.1:19944/balance/bob     # {"user_id":"bob","balance":0.4}

curl -s http://127.0.0.1:19944/price   # {"price_usd":...,"total_spend_usd":0.0042,"weighted_total":...,"height":4}

curl -s http://127.0.0.1:19946/chain   # should show all the new blocks, propagated from node A

kill %1 %2
```

## Assumptions made for ambiguous parts of CONTRACT.md

CONTRACT.md is explicit about most shapes but leaves a few things open;
the literal reading taken here:

1. **JSON field names.** The contract gives Go-style field names
   (`Index`, `PrevHash`, `UserID`, ...) without spelling out JSON tags.
   Since every field the contract *does* give a JSON example for uses
   snake_case (`user_id`, `cost_usd`, `total_spend_usd`, `weighted_total`,
   `price_usd`, `from_user_id`, `to_user_id`, `amount`), all other fields
   follow the same convention: `index`, `timestamp`, `prev_hash`, `hash`,
   `nonce`, `transactions`, `type`, `provider`.

2. **Genesis determinism.** "Fixed nonce so all nodes derive the same
   genesis hash independently" is read as: nonce `0`, a fixed constant
   timestamp (`1970-01-01T00:00:00Z`), and no transactions — the resulting
   hash is *not* required to itself satisfy the configured difficulty
   (mining is skipped for genesis by construction), since it's never
   validated as a "received" block, only compared by value.

3. **P2P handshake sequencing.** The contract says: send `hello`, "then
   immediately request/respond with `chain_response`". Read literally
   using both message types that the envelope's type enum actually lists
   (`chain_request` *and* `chain_response`, as distinct types): each side
   sends `hello` then `chain_request`; the recipient of a `chain_request`
   always replies with `chain_response` (its full chain). This same
   `chain_request` → `chain_response` exchange is reused later whenever a
   received `block` doesn't link to the local tip, per the contract's own
   "fetched via chain_request" phrasing for that case.

4. **`height` in `POST /events`'s response and `/health`.** Read as the
   new/current block's `Index` (genesis = height 0), consistent with
   `Block.Index` being the natural notion of "height" here.

5. **`GET /peers` address format.** Each node reports its *own*
   `-p2p` flag value verbatim in its `hello` payload, and that's what
   peers echo back for `/peers`. If a node is started with a host-less
   bind address (e.g. `-p2p=:9945`, bind-all), peers will see that literal
   string (e.g. `:9945`) rather than a resolved, dialable
   `host:port` — since the contract specifies the `hello` payload as "own
   p2p listen addr" (i.e. self-reported), not the observed remote address
   of the TCP connection. Operators who want `/peers` to show a dialable
   address should pass an explicit host in `-p2p` (e.g.
   `-p2p=127.0.0.1:9945`).

6. **Minimal request validation.** `POST /events` requires a non-empty
   `user_id` (400 otherwise) but does not validate `provider` against the
   known provider list from the proxy's contract — the blockchain node
   itself is provider-agnostic and just records whatever string it's
   given.

7. **"Same UTC year + ISO calendar week" uses the ISO week-year, not the
   calendar year.** Go's `time.Time.ISOWeek()` returns `(isoYear, week)`,
   where `isoYear` can differ from `.Year()` for a handful of dates right
   around New Year's (the ISO week containing Jan 1st can belong to the
   previous year, or the last few days of December can belong to next
   year's week 1). The "week" bucket compares `ISOWeek()`'s pair directly
   against "now"'s, which is the precise, boundary-correct reading of
   "same ISO calendar week" — the alternative (comparing `.Year()` and
   week-number separately) would misclassify events right at a
   year/week boundary.

8. **`github.com/redis/go-redis/v9` pinned at `v9.5.1`**, not the newest
   release. Newer `v9.x` releases pull in `go.uber.org/atomic` as a real
   (non-test) dependency of the connection pool; that module's vanity
   import path (`go.uber.org/atomic`) isn't fetchable through this
   sandbox's network egress rules, while `v9.5.1` doesn't depend on it at
   all and resolves entirely through `github.com/...` module paths. This
   is a well-established, still-current-API version of the client (the
   `redis.Client`/`redis.Options`/`Get`/`Set` surface used in
   `internal/store/redis.go` has been stable across the whole v9 line), so
   pinning it isn't a functionality compromise — see "Persistence" above
   for why a live Redis server couldn't be exercised end-to-end in this
   particular sandbox either (this repo's Redis usage is otherwise
   untouched by that constraint: it'll talk to any real Redis exactly the
   same way once one is reachable).
