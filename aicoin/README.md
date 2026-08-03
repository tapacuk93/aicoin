# aicoin

A minimal but real peer-to-peer blockchain node: a single signing
**primary** plus any number of read-only **follower** replicas, actual TCP
networking between independently-running node processes, and
longest-valid-chain replication (follower-side only — see "Roles &
signing" below). No wallet signatures on transactions themselves (user IDs
are plain strings), no BFT/advanced consensus, no proof-of-work.
Persistence is optional and Redis-backed (see "Persistence" below); with
no `-redis` flag it's pure in-memory, resetting on restart, exactly as
before.

This document mirrors the shared contract at `../CONTRACT.md` but is
written to stand alone.

## Build & run

```
go build ./cmd/aicoind
./aicoind -http=:9944 -p2p=:9945 -role=primary
```

or directly:

```
go run ./cmd/aicoind -http=:9944 -p2p=:9945 -role=primary
```

or via Docker (see "Docker" below):

```
docker build -t aicoin .
docker run -p 9944:9944 -p 9945:9945 aicoin
```

### CLI flags

| Flag               | Default            | Meaning                                                                 |
|--------------------|--------------------|--------------------------------------------------------------------------|
| `-http`            | `:9944`            | HTTP API listen address                                                 |
| `-p2p`             | `:9945`            | P2P TCP listen address                                                  |
| `-peers`           | `""`               | Comma-separated bootstrap peer P2P addresses (`host:port,host:port,...`)|
| `-role`            | `primary`          | Node role: `primary` or `follower` (see "Roles & signing" below)       |
| `-keyfile`         | `aicoin-node.key`  | Primary only: path to this node's persistent Ed25519 private key (generated on first run if it doesn't exist) |
| `-trusted-pubkey`  | `""`               | Required when `-role=follower`: the primary's Ed25519 public key, hex-encoded |
| `-redis`           | `""`               | Optional `host:port` of a Redis server for chain persistence (see "Persistence" below); unset = pure in-memory |
| `-decay-halflife-days` | `110.0`        | `/price` decay half-life in days: `weight(age) = 2^(-age_days/halfLifeDays)` (see "Derived state" below); default derived from a real, documented ~10x-per-year AI pricing decline rate |

Primary + follower example (the follower bootstraps off the primary and
verifies its signed chain against the primary's pubkey):

```
./aicoind -http=:9944 -p2p=:9945 -role=primary -keyfile=aicoin-node.key
# stdout logs a line like:
#   aicoind: role=primary pubkey=93bbf305...fb5e (copy this into a follower's -trusted-pubkey)

./aicoind -http=:9946 -p2p=:9947 -role=follower \
  -trusted-pubkey=93bbf305...fb5e \
  -peers=127.0.0.1:9945
```

## Roles & signing

There is exactly one legitimate writer in this system: the **primary**.
It holds an Ed25519 keypair and *signs* every block it appends — that
signature (not proof-of-work, not a competing-miners race) is what makes a
block valid. **Followers** hold only the primary's public key
(`-trusted-pubkey`), replicate the primary's signed chain via P2P, and
reject all writes: there is no mining, no difficulty, no nonce, and no
"longest chain from competing miners" scenario, because nobody but the
primary can produce a chain whose blocks verify against the trusted
pubkey.

- **Primary** (`-role=primary`, the default): loads its Ed25519 private
  key from `-keyfile` (generating and saving a new one on first run if the
  file doesn't exist — raw 64-byte `crypto/ed25519.PrivateKey` bytes), logs
  its own public key hex to stdout on startup (for copy-pasting into a
  follower's `-trusted-pubkey`), seals+signs every new block itself, and
  **never** replaces its own chain based on incoming P2P gossip/sync —
  it's authoritative by construction.
- **Follower** (`-role=follower -trusted-pubkey=<hex>`): refuses to start
  if `-trusted-pubkey` is missing. Holds no private key, so it cannot
  seal/append any block on its own. All three write endpoints
  (`POST /events`, `/transfer`, `/free-coins/claim`) return
  `403 {"error":"this node is a read-only replica; write to the primary"}`.
  It replicates the primary's chain via P2P: a block that links to its
  local tip and validates is appended and re-gossiped; if its full local
  chain turns out to be shorter than a peer's, and every block in the
  peer's chain validates against the configured trusted pubkey, it adopts
  the peer's chain (longest-valid-chain rule).

## Chain model

- `Block{index, timestamp, prev_hash, hash, signature, transactions}` —
  JSON field names are snake_case (see below for exact shape). There is no
  `nonce` field — that was proof-of-work-only and no longer exists.
- `Transaction` is a tagged union over `type`: `"event"` (`user_id`,
  `provider`, `cost_usd`, `timestamp`), `"free_claim"` (`user_id`,
  `timestamp`), or `"transfer"` (`from_user_id`, `to_user_id`, `amount`,
  `timestamp`). Fields not relevant to a transaction's `type` are omitted
  from its JSON.
- Genesis is block index 0, `prev_hash` is 64 `'0'` chars, no transactions,
  empty `signature`, and a fixed timestamp (`1970-01-01T00:00:00Z`) —
  every node derives byte-identical genesis independently, so it's never
  gossiped, and it's always accepted as valid without any signature check.
- Exactly one transaction per block (no mempool batching), unchanged.
- `hash = hex(SHA256(index|prevHash|timestamp|txJSON))` — a plain content
  hash, not a puzzle. There is no nonce search: the hash is computed once.
- For every block with index >= 1, the primary computes `hash` as above,
  then signs the raw 32-byte SHA-256 digest (not the hex `hash` string)
  with its Ed25519 private key and hex-encodes the result into
  `signature`. This is a one-shot computation — no search/delay — replacing
  the old proof-of-work mining step one-for-one: the block is *sealed and
  signed*, then appended immediately.
- `POST /events` (primary only): builds the transaction, seals+signs a new
  block on top of the local tip, appends it locally, broadcasts it to all
  connected peers, and returns the new block's height/hash.
- `ValidateBlock(block, prev, trustedPubKey)`: index 0 must exactly match
  the well-known genesis constant (always valid, no signature check).
  Index >= 1: recompute `hash` from the block's own fields and confirm it
  matches the stored value; confirm `prev_hash == prev.hash`; confirm
  `ed25519.Verify(trustedPubKey, sha256Digest, signatureBytes)`. There is
  no difficulty/PoW check — that mechanism no longer exists.
- On receiving a `block` from a peer: a **follower** appends+re-gossips it
  if it links to the local tip (right index + `prev_hash`) and its
  hash/signature validate against the configured trusted pubkey; if it
  doesn't link (e.g. it's behind, or there's a fork), it requests the
  sender's full chain (`chain_request`) and, if that's longer and every
  block in it validates, replaces its local chain
  (longest-valid-chain rule). A **primary** ignores incoming `block`
  gossip and `chain_response` chain-replacement attempts entirely — see
  "Roles & signing" above.

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
- `block` — payload is a single sealed+signed/gossiped block.

On establishing a connection — whether outbound (dialing a `-peers` entry)
or inbound (accepting a connection) — a node sends `hello` immediately
followed by `chain_request`. The remote side replies to `chain_request`
with its own `chain_response`, so both nodes converge on startup (subject
to the role-based asymmetry above: only a follower ever actually adopts a
peer's chain from that response).

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
  Each event's `cost_usd` is weighted by a single smooth, continuous
  exponential decay curve over its age (no calendar buckets, no
  step-function jumps at hour/day/week/month/year boundaries):

  ```
  age_days   = (now - event.timestamp) in days   -- negative (clock skew or a
                                                      future timestamp) clamps to 0
  weight(age) = 2 ^ (-age_days / halfLifeDays)
  ```

  `halfLifeDays` is the `-decay-halflife-days` CLI flag, default **110.0**.

  **Why 110 days**: calibrated from a real, well-documented industry data
  point — AI inference/API pricing has fallen roughly **10x per year**
  across major providers (e.g. OpenAI's public per-token pricing dropped
  roughly 10x from the GPT-3.5-turbo era (early 2023) to GPT-4o-mini-class
  pricing (mid-2024)). A 10x-per-year decline implies a half-life of
  `365.25 * ln(2)/ln(10) ≈ 110 days` — see "Assumptions" below for how
  literally to take that figure. The economic intuition: an old cost figure
  shouldn't count as much toward *today's* price precisely because AI got
  cheaper by roughly that much since it was recorded.

  Named checkpoints (informational only — computed from the one formula
  above, not independently configurable), under the default half-life:

  | age | weight |
  |---|---|
  | 1 hour | ≈ 1.000 |
  | 1 day | ≈ 0.994 |
  | 1 week | ≈ 0.957 |
  | 1 month (30.44d) | ≈ 0.825 |
  | 1 quarter (91.31d) | ≈ 0.563 |
  | 1 year (365.25d) | ≈ 0.100 (by construction) |
  | 5 years | ≈ 0.00001 |

  ```
  price_usd = Σ(weight(age_i) * cost_usd_i) / Σ(weight(age_i))
  ```

  over all event transactions ever. `total_spend_usd` is the plain
  unweighted all-time sum of `cost_usd` (visibility only); `weighted_total`
  is `Σweight_i`, the formula's denominator (for debugging/verification);
  `half_life_days` is the configured decay half-life, echoed back for
  transparency. Zero events yields `price_usd = 0` rather than dividing by
  zero.

## Peer transfer (buy/sell)

A new transaction type moves already-minted aicoin between two users —
this is the *entire* buy/sell mechanism; there's no real money and no
external payment rail involved. "Buying" is just receiving a transfer,
"selling" is sending one:

```json
{"type": "transfer", "from_user_id": "alice", "to_user_id": "bob", "amount": 0.4, "timestamp": "2026-08-03T12:00:00Z"}
```

It's sealed+signed and gossiped through the exact same pipeline as any
other transaction, on the primary only. Its derived-balance effect:
`balances[from_user_id] -= amount; balances[to_user_id] += amount`.

### `POST /transfer`

Request:

```json
{"from_user_id": "alice", "to_user_id": "bob", "amount": 0.4}
```

On a follower, this returns `403` (see "Roles & signing" above). On a
primary, the server validates `amount > 0` and that `alice`'s current
derived balance is `>= amount`. If either check fails, nothing is sealed
and the response is `400`:

```json
{"error": "insufficient balance"}
```

Otherwise the transfer tx is sealed+signed and the response is `200`:

```json
{"height": 4, "hash": "a93162b1026a622ce8d70408a2e647fe400c7b3c9b4bb81806294f198c99ef30"}
```

## HTTP API

All bodies/responses are JSON. (`POST /transfer` is documented above under
"Peer transfer (buy/sell)"; `POST /free-coins/claim` is documented below
under "Free-coin faucet". All three write endpoints — `/events`,
`/transfer`, `/free-coins/claim` — return `403` on a follower.)

### `POST /events`

Request:

```json
{"user_id": "alice", "provider": "openai", "cost_usd": 0.001, "timestamp": "2026-08-03T12:00:00Z"}
```

`timestamp` is optional; if omitted the server fills in the current time
(UTC, RFC3339). `provider`/`cost_usd` are recorded as given (no upstream
validation against known providers). `user_id` is required. On a
follower, returns `403`.

Response `200` (primary only):

```json
{"height": 1, "hash": "ff90645666c462506741cc3d8e488883d48f8b69609172debcf0e21e93975bfb"}
```

`height` is the new block's index (genesis is height 0).

### `GET /price`

```json
{"price_usd": 0.00206060606, "total_spend_usd": 0.006, "weighted_total": 1.03125, "height": 2, "half_life_days": 110}
```

`price_usd` is the recency-weighted average from the "Derived state"
section above; `total_spend_usd` is the plain unweighted all-time sum of
`cost_usd`; `weighted_total` is the formula's denominator (`Σweight_i`);
`half_life_days` is the configured decay half-life (the `-decay-halflife-days`
flag's value), echoed back for transparency/verification of the smooth-decay
formula. `price_usd`/`total_spend_usd`/`weighted_total` are all `0` when
there are zero event transactions.

### `GET /chain`

Full chain as a JSON array of blocks:

```json
[
  {"index": 0, "timestamp": "1970-01-01T00:00:00Z", "prev_hash": "000...0", "hash": "0e45...", "signature": "", "transactions": []},
  {"index": 1, "timestamp": "2026-08-03T04:56:50Z", "prev_hash": "0e45...", "hash": "ff90...", "signature": "d224b402...ae0a1201", "transactions": [{"type": "event", "user_id": "alice", "provider": "openai", "cost_usd": 0.0042, "timestamp": "2026-08-03T04:56:50Z"}]}
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
{"status": "ok", "height": 1, "role": "primary", "pubkey": "93bbf305...fb5e"}
```

`role` is `"primary"` or `"follower"`. `pubkey` is this node's own
signing key's public half on a primary, or the configured
`-trusted-pubkey` on a follower.

## Free-coin faucet

A new transaction type mints aicoin directly, independent of the price
formula:

```json
{"type": "free_claim", "user_id": "alice", "timestamp": "2026-08-03T12:00:00Z"}
```

It's sealed and signed into a block and gossiped through the exact same
pipeline as an `"event"` transaction (primary only), mints 1.0 aicoin to
`user_id`, and is ignored entirely by `/price` (no `cost_usd`, doesn't
count toward `total_spend_usd` or `weighted_total`).

### `POST /free-coins/claim`

Request:

```json
{"user_id": "alice"}
```

On a follower, returns `403`. On a primary, the server scans the chain for
the most recent `free_claim` transaction belonging to `user_id`:

- If there is none, or its `timestamp` is >= 1 hour in the past, a new
  `free_claim` transaction is sealed+signed and the response is `200`:

  ```json
  {"granted": true, "height": 2, "hash": "00d373f9...", "next_eligible_at": "2026-08-03T13:00:00Z"}
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
   - `granted:true` → prints the height/hash of the newly sealed block,
     then the user's new balance via `GET {node}/balance/{user}`.
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
top-level `e2e` test that wires both projects together. The wallet talks
to whichever node it's pointed at via `-node` — pointing it at a follower
would make its faucet claim fail with `403`; point it at the primary.

## Persistence (optional, Redis-backed)

By default aicoind is pure in-memory: the chain resets on every restart.
Passing `-redis=host:port` enables persistence as a stand-in for a real
AWS in-memory datastore (e.g. ElastiCache/MemoryDB) — same read/write
shape, swappable later without touching the chain logic itself:

- On startup, the node `GET`s key `aicoin:chain` from Redis. If present
  (a JSON array of blocks, same shape as `GET /chain`'s response), it's
  loaded as the starting chain instead of genesis-only.
- After every successfully appended block — whether sealed locally via an
  API call (`/events`, `/free-coins/claim`, `/transfer`, primary only) or
  accepted from a peer via P2P gossip (follower only) — the node `SET`s
  `aicoin:chain` to the full current chain JSON.
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
./aicoind -http=:9944 -p2p=:9945 -role=primary -redis=localhost:6379
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
  -http=:19944 -p2p=:19945 -role=primary -keyfile=/data/aicoin-node.key -redis=redis:6379
```

Nothing about ports, roles, or keys is hardcoded in the image beyond the
`CMD` defaults, which merely mirror aicoind's own flag defaults — `-role`,
`-keyfile`, and `-trusted-pubkey` are all plain CLI args/env at run time,
same as every other flag.

## Package layout

- `cmd/aicoind` — CLI entrypoint; wires flags (including `-role`/
  `-keyfile`/`-trusted-pubkey`) to a `chain.Blockchain` (optionally backed
  by a `chain.ChainStore`), a `p2p.Node`, and an `api.Server`, then runs
  the P2P accept loop and the HTTP server. On a primary, loads or
  generates its Ed25519 signing key and logs its public half to stdout.
- `cmd/wallet` — standalone CLI client for the free-coin faucet and balance
  query (see "Wallet CLI" above).
- `internal/chain` — `Block`/`Transaction` types, Ed25519 signing
  (`Seal`, `ComputeHash`), the deterministic `Genesis`, chain validation
  (`ValidateBlock`, `ValidateChain`) against a trusted public key, the
  `ChainStore` persistence interface, and the thread-safe `Blockchain`
  (seal+append, append-from-peer, replace-if-longer, each persisting to
  the configured store if any).
- `internal/store` — `ChainStore` implementations: `Redis` (the only file
  importing a Redis client) and `InMemory` (a fake for tests).
- `internal/p2p` — the gossip protocol: envelope encoding, per-connection
  `Peer`, and the `Node` (role-aware) that accepts/dials connections,
  handshakes, dispatches incoming envelopes, and gossips/broadcasts blocks
  — gating chain replacement and block acceptance on `Role` per
  "Roles & signing" above.
- `internal/state` — derived-state computation (`Balance`, `Price`,
  `FaucetEligibility`, `Weight`) over a snapshot of the chain's
  blocks.
- `internal/api` — HTTP handlers implementing the endpoints above,
  including the follower write-rejection gate and `/health`'s role/pubkey
  fields.

## Testing

```
go build ./...
go vet ./...
go test ./...
```

`internal/chain` has unit tests covering: `Seal` produces a signature that
`ed25519.Verify` accepts (and rejects against an unrelated key); chain
validation (`ValidateBlock`/`ValidateChain`) rejects a block with a wrong
`prev_hash`, a tampered `hash`, a corrupted signature, or a signature from
the wrong key; genesis is always valid regardless of its `Signature`
field; `Blockchain.SealAndAppend` requires a signing key (i.e. fails on a
follower-shaped chain with no signer) and produces a non-empty signature
on a primary-shaped one; and the follower side of the longest-valid-chain
rule (`ReplaceIfLonger`) adopts a longer valid chain and rejects a
shorter-or-equal one.

`internal/state` has unit tests covering: the smooth exponential recency
decay formula (`Weight`), checked at controlled ages (0 days, 1 day, 7
days, 30.44 days, 91.31 days, 365.25 days, plus a negative/future-dated age)
against a hand-computed `2^(-age_days/halfLifeDays)`, and cross-checked
against the named-checkpoint table above under the default 110-day
half-life; a multi-event `Price` aggregate checked against a hand-computed
`Σ(weight(age_i) * cost_usd_i) / Σ(weight(age_i))`; a test proving a
non-default `-decay-halflife-days` value actually changes the computed
weights/price (proving the flag flows through); a test proving a negative
age (clock skew or a future timestamp) clamps to weight exactly `1.0`; and
an explicit zero-event case (`price_usd = 0`). Also: balance computation
proving `"event"` transactions contribute 0, `"free_claim"` transactions
contribute +1.0 each, and `"transfer"` transactions move `amount` from
sender to recipient (including several transfers netting out correctly for
the same user); and faucet eligibility (`FaucetEligibility`) against
synthetic `free_claim` transactions with controlled timestamps, covering
never-claimed-before, granted-after-1h, not-yet-eligible, and
most-recent-claim-wins-among-several cases.

`internal/api` has `httptest`-based unit tests (in-process, via
`httptest.NewRecorder`+`ServeHTTP` — no real sockets, so they run
unmodified even in sandboxes that deny TCP bind) covering: `POST /events`
seals+signs a block on a primary and grows the chain by exactly one block
carrying a non-empty signature; `POST /transfer`'s full flow (a successful
transfer moves balance correctly end-to-end, an insufficient-balance or
non-positive-amount transfer is rejected with `400` and provably does not
mutate the chain); all three write endpoints return
`403 {"error":"this node is a read-only replica; write to the primary"}`
on a follower without mutating its chain; and `GET /health` reports the
correct `role`/`pubkey` for both a primary and a follower.

`internal/store` has unit tests for the `ChainStore` contract against the
`InMemory` fake: load-then-save round-trip (with defensive-copy checks in
both directions), empty-store-means-genesis, and a full `Blockchain`
"restart" simulation (seal some blocks, construct a second `Blockchain`
against the same store, verify it picks up exactly where the first left
off).

`cmd/wallet` has unit tests for its pure decision/parsing functions
(`shouldAttemptClaim`, `parseAvailable`, `parseClaim`, `parseBalance`) with
no network access required.

`internal/p2p` has two kinds of tests:

- Two real-TCP integration tests (a primary and a follower `Node` on real
  loopback sockets: one gossiping a freshly-sealed block end-to-end from
  primary to follower, one syncing a 3-block head start purely via the
  startup `hello`/`chain_request`/`chain_response` handshake). Some
  sandboxed CI hosts deny raw `bind`/`listen` syscalls outright (even on
  loopback); on such hosts these two tests detect that specific failure
  and `t.Skip()` rather than fail, since it isn't something this package's
  code controls. On any host where socket binding is actually permitted
  they run for real and verify genuine cross-process-style TCP gossip and
  sync.
- Three in-process tests exercising the role-based chain-replacement
  asymmetry directly via `Node.dispatch` and an in-memory `net.Pipe()`
  connection (no real sockets at all, so these always run regardless of
  sandbox socket restrictions): a follower adopts a longer valid chain
  offered via a simulated `chain_response`; a primary does **not** adopt
  one under any circumstances, even a longer chain validly signed by its
  own trusted key; and a primary ignores incoming `block` gossip outright.

## Manual two-node smoke test

```
go build -o /tmp/aicoind ./cmd/aicoind

/tmp/aicoind -http=:19944 -p2p=:19945 -role=primary -keyfile=/tmp/primary.key &
# note the pubkey it logs, e.g. pubkey=93bbf305...fb5e

/tmp/aicoind -http=:19946 -p2p=:19947 -role=follower \
  -trusted-pubkey=<pubkey from above> -peers=127.0.0.1:19945 &

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

curl -s http://127.0.0.1:19944/price   # {"price_usd":...,"total_spend_usd":0.0042,"weighted_total":...,"height":4,"half_life_days":110}

curl -s http://127.0.0.1:19946/chain   # should show all the new blocks, propagated from the primary

curl -s -X POST http://127.0.0.1:19946/events \
  -H 'Content-Type: application/json' \
  -d '{"user_id":"alice","provider":"openai","cost_usd":0.01}'   # 403, read-only replica

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
   `signature`, `transactions`, `type`, `provider`.

2. **Genesis determinism.** "A well-known deterministic constant every
   node computes independently" is read as: a fixed constant timestamp
   (`1970-01-01T00:00:00Z`), no transactions, and an empty `signature` —
   the resulting hash is derived purely from those fixed fields, so every
   node arrives at the exact same genesis block without needing to
   generate or verify any signature for it (index 0 is always
   special-cased as valid in `ValidateBlock`).

3. **Signing key storage format.** The contract leaves the on-disk key
   format up to the implementation ("as raw bytes or PEM, your choice,
   just be consistent"). This implementation writes the raw 64-byte
   `crypto/ed25519.PrivateKey` encoding directly to `-keyfile` (mode
   `0600`) and reads it back the same way — no PEM wrapping, since nothing
   else needs to interoperate with the file's format.

4. **P2P handshake sequencing.** The contract says: send `hello`, "then
   immediately request/respond with `chain_response`". Read literally
   using both message types that the envelope's type enum actually lists
   (`chain_request` *and* `chain_response`, as distinct types): each side
   sends `hello` then `chain_request`; the recipient of a `chain_request`
   always replies with `chain_response` (its full chain) — even a primary
   responds to an inbound `chain_request` with its own chain (so followers
   can sync), even though a primary never *acts* on a `chain_response` it
   receives. This same `chain_request` → `chain_response` exchange is
   reused later whenever a received `block` doesn't link to a follower's
   local tip, per the contract's own "fetched via chain_request" phrasing
   for that case.

5. **`height` in `POST /events`'s response and `/health`.** Read as the
   new/current block's `Index` (genesis = height 0), consistent with
   `Block.Index` being the natural notion of "height" here.

6. **`GET /peers` address format.** Each node reports its *own*
   `-p2p` flag value verbatim in its `hello` payload, and that's what
   peers echo back for `/peers`. If a node is started with a host-less
   bind address (e.g. `-p2p=:9945`, bind-all), peers will see that literal
   string (e.g. `:9945`) rather than a resolved, dialable
   `host:port` — since the contract specifies the `hello` payload as "own
   p2p listen addr" (i.e. self-reported), not the observed remote address
   of the TCP connection. Operators who want `/peers` to show a dialable
   address should pass an explicit host in `-p2p` (e.g.
   `-p2p=127.0.0.1:9945`).

7. **Minimal request validation.** `POST /events` requires a non-empty
   `user_id` (400 otherwise) but does not validate `provider` against the
   known provider list from the proxy's contract — the blockchain node
   itself is provider-agnostic and just records whatever string it's
   given. This check runs regardless of role, but is moot on a follower
   since the write is rejected with `403` before it's reached.

8. **The "10x per year" AI-pricing-decline figure behind the 110-day
   default half-life is a widely-cited industry rule of thumb, not a
   dataset with exact primary-source numbers I have in hand.** It's
   supported by real, observable data points — e.g. OpenAI's public
   per-token pricing fell roughly an order of magnitude from the
   GPT-3.5-turbo era (early 2023) to GPT-4o-mini-class pricing (mid-2024) —
   but "roughly 10x per year across major providers" is a rounded
   characterization repeated across industry commentary, not a number
   derived here from a first-party pricing time series I've independently
   verified provider-by-provider. `365.25 * ln(2)/ln(10) ≈ 110 days` is an
   exact derivation *given* that 10x/year premise; the premise itself is the
   part that's a documented approximation. The half-life is a single CLI
   flag (`-decay-halflife-days`) precisely so this calibration can be
   revised without any code change if better data becomes available.

9. **`github.com/redis/go-redis/v9` pinned at `v9.5.1`**, not the newest
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

10. **TCP socket binding in this sandbox.** Real `bind`/`listen` syscalls
    are denied in the default sandboxed shell used for development here
    (observed as "operation not permitted" even for loopback addresses on
    explicit fixed ports) but are permitted when run with the sandbox
    disabled — used only to exercise the manual smoke test above; the
    unit/integration test suite itself degrades gracefully either way (see
    "Testing" above).
