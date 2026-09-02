# aicoin

**A prepaid coin that buys AI calls.** Point your OpenAI/Anthropic/Gemini/Kimi client at
`aicoin-proxy` instead of the provider, and it forwards the request untouched using **its own** paid
key. Your app ships no API keys. Each call is metered against a wallet balance and priced from what
the call actually cost upstream.

Live: [aicoin.oeaio.com](https://aicoin.oeaio.com) · proxy at `proxy.aicoin.oeaio.com` ·
[CONTRACT.md](./CONTRACT.md) is the spec everything here is built against.

```
your app ──► aicoin-proxy ──► OpenAI / Anthropic / Google / Mistral / Kimi / ElevenLabs / Stability
                  │              (the proxy's key, never yours)
                  └──► Redis — wallet balances, transaction log, price history
```

## The idea

A wallet holds coins. A call costs what it cost: the proxy reads the provider's own usage figures
off the response, converts to coins at that provider's and model's real rates, and takes them —
rounded up, floored at one. A coin is worth `pricing.coinValueUsd` (default $0.009), and
`GET /price` reports a recency-weighted average of what calls have actually cost, so the number
tracks real spend rather than a figure someone picked.

Everything a wallet can do is signed by an Ed25519 keypair it owns. Claiming, transferring and
revoking need the private key; ordinary AI calls use a token the wallet issues for itself, which can
spend coins but cannot move them. The proxy never sees a private key, and there is no "issue token"
endpoint for it to leak.

## Try it

```bash
cd cli && go build -o ~/.local/bin/aicoin .

aicoin new && aicoin claim          # a wallet, and the faucet's grant
cd ~/src/my-project
aicoin "why does the build fail on a clean checkout?" -f "*.go"
```

With no command word, the question goes to the **whole panel**: every configured model answers, an
editor merges the answers, and then all of them review the result until a round comes back with no
comments. The files in the working directory are listed for them; `-f` includes the ones you name.

```bash
aicoin .                    # a session here: ask, follow up, it remembers
aicoin single               # one model per question — a panel is one call per member per round
aicoin ais                  # which models you have used, and what each cost
```

It can act, not just answer — "create a .env", "run the tests" — as a plan you approve before
anything is written or run. Anything after `$$` on a line is withheld from the models entirely and
substituted back on your machine. Full reference: [`cli/README.md`](./cli/README.md).

## Run your own

```bash
docker compose up --build          # Redis + the proxy on :8080
curl localhost:8080/health
```

Or without Docker: a GraalVM JDK (25, pinned in `.java-version`) and a Redis on `:6379`, then
`cd aicoin-proxy && ./gradlew run`.

Provider keys are empty until you set them, one env var each — `AICOIN_PROXY_OPENAI_APIKEY`,
`AICOIN_PROXY_ANTHROPIC_APIKEY`, and so on. `GET /health` reports which ones are configured, which
is what the landing page reads.

## What the proxy does

**Routing.** You call the proxy at exactly the path the real provider uses, with `X-AI:` naming the
provider (`openai`, `anthropic`, `google`, `mistral`, `cohere`, `elevenlabs`, `stability`, `kimi`).
The body is forwarded unchanged; the proxy injects its own credential the way that provider expects
it — a header, a prefix, or a query parameter.

```bash
curl proxy.aicoin.oeaio.com/v1/chat/completions \
  -H "X-AI: openai" -H "X-Api-Key: <token from /wallet>" \
  -d '{"model":"gpt-5","messages":[{"role":"user","content":"hi"}]}'
```

**Billing.** One coin is held before the call, so an empty wallet is refused before a provider is
touched and one coin is always enough to make one call. When the response comes back, the rest of
what it really cost is settled. A failed call is refunded — it cost the proxy nothing, so it costs
the wallet nothing. Model and voice listings, token counting and account lookups are *free targets*:
forwarded with the proxy's key, billed to nobody. Every billed response carries `X-Aicoin-Charged`.

**A consortium.** `POST /consortium` is the one endpoint that originates calls rather than
forwarding one: a request goes to every configured model, an editor merges the answers, and the
panel reviews the result round after round until nobody objects. A large context is *led* by one
model instead — four independent drafts over the same directory are four re-readings of it, billed
four times. Every turn is an ordinary paid call, and the response says how many it made and what
each model cost.

`mode: "poll"` is the same panel without the converging. Everybody answers once, nothing is
merged or reviewed, and every answer comes back attributed to the model that wrote it. It is for
questions that are decisions rather than prose — should this ship, is this correct — where the
disagreement *is* the product: three models saying yes and one saying no is a different fact from
a paragraph that reads as though they agreed, and a caller that needs to tell "everyone refused"
from "the panel was split" cannot get that out of a merged answer.

## Paying offline

A wallet can turn balance into **bearer notes** — signed strings it preloads while it has a network,
and hands over later with no network on either side:

```bash
aicoin note load 50     # online, once
aicoin note pay 15      # offline: prints notes and their fingerprints
aicoin note accept ...  # offline: "✓ genuine · 10 aicoin · from 00c0759c…"
aicoin note sync        # online again: credited
```

The coins leave the issuer's balance at load time, so they cannot be spent twice. What offline
hand-off cannot establish is whether a note has already been given to someone else — redemption is
therefore first-come, and the second person to try is told so plainly. It is a bearer instrument
with the properties of one; every note names its issuer, and every step is in both transaction logs.

## Where coins come from

- **The faucet** — `POST /wallet/api/claim`, a fixed grant per wallet per hour, from a shared pool
  that runs out.
- **A purchase** — an Apple StoreKit transaction, verified against Apple's certificate chain and
  redeemed exactly once. `GET /iap/offer` is what the apps sell right now; the price ladder is
  re-derived from the live `/price` signal.
- **The operator** — `POST /admin/credit`, admin-token only. Nothing backs these coins beyond a
  willingness to pay for the calls they buy, which is why each one is written into the wallet's
  transaction log as what it is.
- **Another wallet** — a signed transfer, or a bearer note handed over offline.

A spend ceiling (`POST /admin/budget`) bounds what the operator can be billed upstream: when
production spend reaches it the paywall goes empty, while calls for coins already sold keep working
— those are paid for.

## Building on it

Two levels, and the first needs nothing from anybody:

| | Wallet only | Wallet + in-app purchase |
| --- | --- | --- |
| Provider keys in your app | none | none |
| App Store Connect setup | none | four consumables per app |
| Changes to this server | none | your bundle ID and catalog |
| Coins come from | the faucet, transfers | that, plus buying |
| Suits | tools, scripts, internal apps | shipping consumer apps |

[`mobile-purchase/ios/`](./mobile-purchase/ios/) is the `AICoinKit` Swift package the apps build
on: it re-routes any request bound for a known provider host through the proxy, and turns a `402`
into a typed error a paywall can catch.

## The pieces

| | |
| --- | --- |
| [`CONTRACT.md`](./CONTRACT.md) | the authoritative spec — read this before changing behaviour |
| [`aicoin-proxy/`](./aicoin-proxy/) | the Java/Netty proxy and the Redis ledger, plus admin scripts |
| [`cli/`](./cli/) | `aicoin`, the Go command-line wallet and AI client |
| [`mobile-purchase/ios/`](./mobile-purchase/ios/) | `AICoinKit` — routing, purchases, wallet UI |
| [`ios/AICoinWallet/`](./ios/AICoinWallet/) | a native SwiftUI wallet app |
| [`site/`](./site/) | the landing page at aicoin.oeaio.com |
| [`e2e/run.sh`](./e2e/run.sh) | the end-to-end test |

## Testing

```bash
cd aicoin-proxy && ./gradlew test    # routing, auth, pricing, the ledger's rules
cd cli && go test ./...              # signatures, actions, secrets, the command parser
bash e2e/run.sh                      # the whole flow against a real Redis and a mock provider
```

The end-to-end run boots a mock provider and a Redis — a local `redis-server` if there is one,
otherwise a container — and covers auth, per-provider key injection, metered billing, the price
signal, the faucet, transfers, free targets, admin credit, and a consortium from drafts through
review rounds, including one that runs the wallet dry mid-call.

## Status

Deployed and in use, with the shape of a project that grew from a prototype. Worth knowing before
you rely on it:

- **The ledger is centralized.** One Redis, one operator, no chain and no replication. Balances are
  atomic — every mutation is a Lua script — but they are a database, not a blockchain.
- **Production currently accepts StoreKit *sandbox* purchases**, a deliberate deviation recorded in
  CONTRACT.md so TestFlight builds can buy coins. It also means a free sandbox tester account can
  mint genuine-looking purchases; the spend budget is what bounds the damage. It goes back off when
  TestFlight validation is done.
- **Coin prices are averages, not quotes.** `/price` reports what calls have cost through this
  proxy; it is not a market rate, and there is no market.
- **`mistral` and `cohere` are configured but keyless** in the live deployment, so `/health` reports
  them disabled and the panel skips them.
