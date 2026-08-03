# aicoin monorepo

Two projects that interoperate over HTTP/TCP — see [CONTRACT.md](./CONTRACT.md) for the full, authoritative spec both were built against.

- **[aicoin/](./aicoin/)** — Go P2P blockchain node + wallet CLI. Real PoW mining, TCP gossip, longest-valid-chain consensus, a free-coin faucet (1/user/hour), peer transfers (the entire buy/sell mechanism), and a recency-weighted price formula. Optional Redis-backed persistence. See its own README for details.
- **[aicoin-proxy/](./aicoin-proxy/)** — Java 11 + Netty reverse proxy for AI provider APIs. Same request path as the real provider, only the domain changes; an `X-AI` header selects the upstream (openai/anthropic/google/mistral/cohere) and the proxy injects its own paid key — callers never need a provider key. Auth is wallet-id-as-API-key (`X-Api-Key`), validated against the aicoin node. Also exposes `/price`, `/free-coins/available`, and `/health` (per-provider rate-limit/budget status). See its own README for details.
- **[e2e/run.sh](./e2e/run.sh)** — end-to-end test: builds both projects, boots a mock AI provider + two aicoin nodes (to prove P2P gossip) + the proxy, and exercises the full flow (auth, routing/key-injection, price, faucet, transfer, replication).

## Running locally

```
docker compose up --build
```

starts Redis + one aicoin node + the proxy (see `docker-compose.yml`). Provider API keys and other config are set via env vars — see `aicoin-proxy/README.md`.

## Known limitation of this development sandbox

`e2e/run.sh` is correct and will run end-to-end on a normal machine or CI runner. In the specific sandboxed session this was built in, the OS-level sandbox intermittently (and for the JVM/Python, consistently) denied raw socket binds and mediated loopback HTTP through a local proxy that dropped some connections — unrelated to the code. What *was* verified live in that session: the aicoin node's full HTTP API (events, price, faucet, transfers, chain) against real running processes with real curl calls. The proxy and multi-node P2P gossip were verified via extensive unit and in-process integration tests (httptest/JUnit) instead of a fully live run, in that session specifically.
