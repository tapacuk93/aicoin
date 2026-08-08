# aicoin

> **Status: draft / prototype.** This is a working proof-of-concept, not a
> production system — no real API keys are bundled, and wallet ids are
> plain strings with no cryptographic identity. See [CONTRACT.md](./CONTRACT.md)
> for the full spec, and `aicoin-proxy/README.md` for exhaustive detail.
> This file is the practical "how do I run this" guide.

Live landing page: [aicoin.oeaio.com](https://aicoin.oeaio.com) (source in [`site/`](./site/), a static page on S3+CloudFront).

One thing: **aicoin-proxy** — an HTTP reverse proxy you point your AI API
calls at instead of the real provider. It forwards the request unchanged
(same path, same body) to whichever provider you ask for, using **its own**
paid API key — you never need your own OpenAI/Anthropic/etc. key. It bills
the call to an aicoin wallet and tracks a coin price that reflects real
recent AI spend — both the proxying and the coin ledger (wallet balances,
free-coin faucet, transfers, price) live in this one Java process, backed by
Redis.

```
you → aicoin-proxy → real AI provider (OpenAI/Anthropic/Google/Mistral/Cohere)
              ↓
        Redis (wallet balances, price history)
```

## Quickstart — Docker Compose

```
docker compose up --build
```

This starts a Redis container (snapshotting enabled for persistence) and the
proxy pointed at it. Once it's up:

```
curl http://localhost:8080/health
curl http://localhost:8080/price
```

Provider API keys aren't set by default — pass them as env vars (see
"Configure real provider keys" below) or edit `docker-compose.yml`.

## Manual setup (no Docker)

You need a GraalVM JDK and a local Redis (or Redis-compatible, e.g. Valkey)
server. The Docker image and `build.gradle`'s toolchain currently pin
**GraalVM 25** (see `.java-version`) — CONTRACT.md's "Docker / docker-compose"
section explains why this isn't "GraalVM JDK 26" yet (GraalVM hasn't
published a JDK 26 build as of this writing).

**1. Start Redis:**

```
redis-server --port 6379
```

**2. Build and run the proxy, pointed at it:**

```
cd aicoin-proxy
./gradlew run
```

By default it expects Redis at `localhost:6379` and listens on `:8080` —
matching step 1's defaults, so no extra config is needed for a local setup.

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

The proxy checks `alice`'s balance against the ledger, forwards the request
to OpenAI using the proxy's *own* configured key, relays the response back
to you unchanged, and — in the background — records the call's cost into the
price history. `X-AI` also accepts `anthropic`, `google`, `mistral`,
`cohere`, `elevenlabs`, `stability`.

**Check the current price of 1 aicoin** (reflects real recent AI spend —
see `CONTRACT.md`'s "Ledger (Redis)" section for the exact formula):

```
curl http://localhost:8080/price
```

**Manage a wallet in the browser** — check balance, claim your hourly free
coin, and send coins to another wallet, all from one page:

```
open http://localhost:8080/wallet
```

Or drive the same three endpoints directly:

```
curl http://localhost:8080/wallet/api/balance/alice
curl -X POST http://localhost:8080/wallet/api/claim \
  -H "Content-Type: application/json" -d '{"user_id":"alice"}'
curl -X POST http://localhost:8080/wallet/api/transfer \
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

Builds the proxy, boots a mock AI provider plus a Redis container plus the
proxy, and exercises the full flow: auth, routing/key-injection, price,
faucet, transfer.

## Repo layout

- [`CONTRACT.md`](./CONTRACT.md) — the authoritative spec this project is built against.
- [`aicoin-proxy/`](./aicoin-proxy/) — the Java/Netty proxy and the Redis-backed coin ledger. Full flag/API/config reference in its own README. Includes `scripts/set-coin-packages.sh` (admin CLI to change IAP coin packages) and `scripts/adjust-iap-prices.sh` (hourly cron job that re-derives IAP package prices from the live `/price` signal).
- [`e2e/run.sh`](./e2e/run.sh) — the end-to-end test.
- [`site/`](./site/) — the static landing page deployed at aicoin.oeaio.com.
- [`ios/AICoinWallet/`](./ios/AICoinWallet/) — a native SwiftUI wallet app (bundle `com.oeaio.aicoin.wallet`) mirroring `wallet.html`'s capabilities.
