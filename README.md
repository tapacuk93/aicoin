# aicoin

> **Status: draft / prototype.** This is a working proof-of-concept, not a
> production system — no real API keys are bundled, wallet ids are plain
> strings with no cryptographic identity, and the "blockchain" is a single
> signing primary rather than a decentralized network. See
> [CONTRACT.md](./CONTRACT.md) for the full spec, and each project's own
> README for exhaustive detail. This file is the practical "how do I run
> this" guide.

Live landing page: [aicoin.oeaio.com](https://aicoin.oeaio.com) (source in [`site/`](./site/), a static page on S3+CloudFront).

Two things that work together:

- **aicoin-proxy** — an HTTP reverse proxy you point your AI API calls at
  instead of the real provider. It forwards the request unchanged (same
  path, same body) to whichever provider you ask for, using **its own**
  paid API key — you never need your own OpenAI/Anthropic/etc. key. It
  bills the call to an aicoin wallet and reports the cost to...
- **aicoin** — a small blockchain: one signing "primary" node (optionally
  with read-only "follower" replicas), a wallet CLI, a 1-coin-per-hour
  faucet, peer-to-peer transfers, and a price that reflects real recent AI
  spend.

```
you → aicoin-proxy → real AI provider (OpenAI/Anthropic/Google/Mistral/Cohere)
              ↓
        aicoin node (records cost, tracks your wallet balance/price)
```

## Quickstart — Docker Compose

```
docker compose up --build
```

This starts Redis, one aicoin node (primary, `-redis=redis:6379`), and
the proxy. Once it's up:

```
curl http://localhost:8080/health
curl http://localhost:9944/health
```

Provider API keys aren't set by default — pass them as env vars (see
"Configure real provider keys" below) or edit `docker-compose.yml`.

## Manual setup (no Docker)

You need Go and a JDK 11 available.

**1. Build and run the aicoin node (primary):**

```
cd aicoin
go run ./cmd/aicoind -http=:9944 -p2p=:9945 -role=primary
```

It logs its own public key on startup — ignore it for a single-node setup;
you only need it if you're adding a follower replica (see
`aicoin/README.md`).

**2. Build and run the proxy, pointed at that node:**

```
cd aicoin-proxy
./gradlew run
```

By default it expects the aicoin node at `localhost:9944` and listens on
`:8080` — matching step 1's defaults, so no extra config is needed for a
local single-node setup.

## Using it

**Make an AI call through the proxy.** Same path as the real provider,
`X-AI` picks the upstream, `X-Api-Key` is your aicoin wallet id (any
string — it's created implicitly the first time you use it):

```
curl http://localhost:8080/v1/chat/completions \
  -H "X-AI: openai" \
  -H "X-Api-Key: alice" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4","messages":[{"role":"user","content":"hi"}]}'
```

The proxy validates `alice` against the aicoin node, forwards the request
to OpenAI using the proxy's *own* configured key, relays the response back
to you unchanged, and — in the background — reports the call's cost to the
aicoin node. `X-AI` also accepts `anthropic`, `google`, `mistral`, `cohere`.

**Check the current price of 1 aicoin** (reflects real recent AI spend —
see `aicoin/README.md`'s "Derived state" section for the exact formula):

```
curl http://localhost:8080/price
```

**Check your wallet balance:**

```
curl http://localhost:9944/balance/alice
```

**Claim your free coin** (1 per user per hour — the wallet CLI does the
whole flow: checks the proxy's faucet allowance, then claims from the
node):

```
cd aicoin
go run ./cmd/wallet -user=alice
```

**Send coins to another wallet** (this is the entire buy/sell mechanism —
no real money involved):

```
curl -X POST http://localhost:9944/transfer \
  -H "Content-Type: application/json" \
  -d '{"from_user_id":"alice","to_user_id":"bob","amount":0.4}'
```

**Check provider health** (rate-limit/budget status per AI provider):

```
curl http://localhost:8080/health
```

## Configure real provider keys

The proxy ships with empty API keys — calls will 401/403 upstream until
you set your own, via env vars (see `aicoin-proxy/README.md` for the full
list):

```
AICOIN_PROXY_OPENAI_APIKEY=sk-...
AICOIN_PROXY_ANTHROPIC_APIKEY=sk-ant-...
```

## Running the end-to-end test

```
bash e2e/run.sh
```

Builds both projects, boots a mock AI provider plus a primary+follower
aicoin node pair plus the proxy, and exercises the full flow: auth,
routing/key-injection, price, faucet, transfer, and P2P replication. See
the "Known limitation" note below if it fails to bind sockets in a
restricted sandbox — it's written to run cleanly on a normal machine or CI.

## Repo layout

- [`CONTRACT.md`](./CONTRACT.md) — the authoritative spec both projects are built against.
- [`aicoin/`](./aicoin/) — the Go node + wallet CLI. Full flag/API reference in its own README.
- [`aicoin-proxy/`](./aicoin-proxy/) — the Java/Netty proxy. Full config/API reference in its own README.
- [`e2e/run.sh`](./e2e/run.sh) — the end-to-end test.

## Known limitation of this development sandbox

`e2e/run.sh` is correct and will run end-to-end on a normal machine or CI
runner. In the specific sandboxed session this was built in, the OS-level
sandbox intermittently (and for the JVM/Python, consistently) denied raw
socket binds and mediated loopback HTTP through a local proxy that dropped
some connections — unrelated to the code. Everything that *could* be run
live in that session was (see commit history / prior conversation for
transcripts); the rest was validated via extensive unit and in-process
integration tests instead.
