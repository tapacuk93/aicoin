# aicoin

> **Status: draft / prototype.** This is a working proof-of-concept, not a
> production system — no real API keys are bundled, and wallet ids are
> plain strings with no cryptographic identity. See [CONTRACT.md](./CONTRACT.md)
> for the full spec, and `aicoin-proxy/README.md` for exhaustive detail.
> This file is the practical "how do I run this" guide.

Live landing page: [aicoin.oeaio.com](https://aicoin.oeaio.com) (source in [`site/`](./site/), a static page served by Caddy — automatic Let's Encrypt TLS, HTTP redirected to HTTPS).

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

## The command-line client

```
cd cli && go build -o ~/.local/bin/aicoin .
aicoin new && aicoin claim

cd ~/src/my-project
aicoin "why does the build fail on a clean checkout?" -f "*.go"
aicoin .    # or open a session here, where follow-up questions remember the last ones
```

A single Go binary, no dependencies outside the standard library. With no command word the whole
line is a question for the **panel**: every configured model answers it, an editor merges the
answers, and then all of them review the result until a round comes back with no comments. The files
in the working directory are listed for them by default and `-f` includes the ones you name, so a
question asked inside a project is answered about that project.

Ask it to change something rather than explain something, and the answer comes back as file
operations — shown as a plan, applied only when you say so, and never outside the directory you ran
it in. The proxy has no filesystem; the acting happens on your machine.

With a directory attached the call is **led** by one model rather than drafted by all of them: with
the files in front of it, four independent drafts are four re-readings of the same material, billed
four times, and they converge anyway — so one leads and the rest improve its answer round by round.

`aicoin single` switches to one model per question — the panel is one paid call per member per
round, which is the wrong price for a small request — and picks whichever model has carried the most
turns for you, measured from the consortium responses themselves. `aicoin ais` shows what each model
has cost you and what it failed, `aicoin multi` goes back.

It also does everything the wallet page does — create a wallet, check a balance, claim, transfer,
issue and revoke tokens — plus `ask` for a single model. While a call runs it shows the wallet and a
clock; when it lands it shows what the call took out of the balance:

```
◆◆◆◆◇◇◇◇◇◇  15 aicoin spent · 27 left
```

The answer goes to stdout and all of that to stderr, so it pipes. Full reference:
[`cli/README.md`](./cli/README.md).

## Using it

**Make an AI call through the proxy.** Same path as the real provider, `X-AI`
picks the upstream, and `X-Api-Key` carries an **API token** — open
`http://localhost:8080/wallet` in a browser, create a wallet, and click
"Issue API token" (default 7 days):

```
curl http://localhost:8080/v1/chat/completions \
  -H "X-AI: openai" \
  -H "X-Api-Key: <token from the wallet page>" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4","messages":[{"role":"user","content":"hi"}]}'
```

A token is `base64url(payload).base64url(signature)`, signed once by the
wallet's Ed25519 private key and self-verifying (the address embedded in it
*is* the verification key). A bare wallet address is **not** accepted on this
path and returns `401 {"error":"invalid API token"}` — signing is what proves
the call may spend that wallet's coins. Tokens can only spend; claiming free
coins and transferring require the private key itself, so a leaked token
can't drain a wallet. Revoke every outstanding one from the wallet page.

The proxy verifies the token, checks that wallet's balance against the
ledger, forwards the request to OpenAI using the proxy's *own* configured
key, relays the response back to you unchanged, and — in the background —
records the call's cost into the price history. `X-AI` also accepts
`anthropic`, `google`, `mistral`, `cohere`, `elevenlabs`, `stability`,
`kimi`.

**Ask every AI at once, and have them review the answer.** `POST /consortium`
sends one prompt to every configured provider, merges their answers into one,
and then has the whole panel review that answer round after round until a round
comes back with no comments:

```
curl http://localhost:8080/consortium \
  -H "X-Api-Key: <token from the wallet page>" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"What breaks first when a proxy meters billing per call?"}'
```

```json
{"answer":"...","settled":true,"stopped_reason":"clean","rounds":2,
 "panel":["anthropic","openai","google","kimi"],"editor":"anthropic",
 "calls":13,"coins_charged":15,"reviews":[...],"errors":[]}
```

Every turn is an ordinary paid call — a four-model panel over two rounds is 13
calls and is billed as 13 calls, which the response says out loud. It ends on a
clean round or at the configured round cap, whichever comes first; the cap is
what stops reviewers who can always find one more thing. A wallet that runs out
partway keeps the answer it paid for. Expect it to take minutes.
`aicoin-proxy/README.md` has the full behaviour, including what happens when a
panelist fails.

**Check the current price of 1 aicoin:**

```
curl http://localhost:8080/price
{"price_usd":0.0086,"total_spend_usd":0.52,"weighted_total":60.0,"half_life_days":110.0}
```

### How that price is calculated

`price_usd` is **what a call has actually been costing lately** — a
recency-weighted average over every paid call this proxy has ever forwarded.
Nothing about it is set by hand. (One coin in the wallet is always enough to
make one call, which is where reading it as "the price of a coin" comes from;
a long or expensive call is metered at more than one coin, so the two are the
same figure only for a typical call.)

Every successful billed call records one event carrying its real dollar
cost, computed from the provider's own reported usage at that provider and
model's configured rates (input and output priced separately, cache reads at
0.1x and writes at 1.25x of the input rate; providers that report no token
counts fall back to a flat per-call figure). The price is then the weighted
mean of those costs:

```
price_usd = Σ(weight(age_i) × cost_usd_i) / Σ(weight(age_i))

weight(age) = 2 ^ (-age_days / 110)
```

So a call recorded today counts fully, one from a month ago counts about
0.83, one from a year ago about 0.10. With no events recorded yet the price
is `0`.

**Why the 110-day half-life:** AI inference pricing has fallen roughly 10x
per year across the major providers, and a 10x annual decline is exactly a
half-life of `365.25 × ln2/ln10 ≈ 110` days. An old cost figure shouldn't
count as much toward today's price precisely because AI got that much
cheaper since it was recorded. It's a continuous curve — no calendar
buckets, so the price never jumps at a day or month boundary. Tune it with
`aicoin.decayHalflifeDays`.

The other two fields are there to check the arithmetic: `total_spend_usd` is
the plain unweighted all-time sum, and `weighted_total` is the formula's
denominator (`Σweight`), which also doubles as "how much real signal is
behind this number" — the offer pricing refuses to run below 50.

**This price is observational, not a tariff.** It doesn't decide what a call
charges. That uses a separate fixed rate, `pricing.coinValueUsd` (default
`$0.009`, the per-coin price of the largest coin pack — the cheapest a coin
is ever sold for, so metering against it never under-charges a bulk buyer). A
call costs `ceil(cost_usd / coinValueUsd)` coins, never less than 1 and never
more than 100. Where `price_usd` *does* have teeth is deciding what to sell:
it converts an offer's coin amount into the USD price point users are charged
(see "See what's on sale" below).

`GET /price/history?points=N` replays the same formula at N past timestamps
to show how the number got where it is. Full derivation and the weight table
are under "Price (final formula...)" in `CONTRACT.md`'s "Ledger (Redis)"
section.

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

**See what's on sale**, and set it. One number — the coins every app is
selling right now — which each app displays, re-checks immediately before
charging, and buys the matching fixed-price product for:

```
curl http://localhost:8080/iap/offer

AICOIN_PROXY_ADMIN_TOKEN=<token> \
  aicoin-proxy/scripts/set-coin-offer.sh 350
```

The server prices those coins off the live `/price` signal and picks the
cheapest of the four fixed price points that covers them, rounding up. On a
fresh instance there's no price signal yet, so it refuses to guess — name a
point yourself with `--price 9.99`. Also `--show`, `--close`, and `--url`;
see `aicoin-proxy/README.md`'s IAP section for the full model.

**Check provider health** (rate-limit/budget status per AI provider):

```
curl http://localhost:8080/health
```

## Integrating aicoin into your own app

There are two levels of integration, and the first is useful on its own —
you do not have to sell anything to use aicoin.

### Level 1 — wallet only (no purchases, no App Store setup)

Your app gets a wallet, routes its AI calls through the proxy, and users fund
it from the free faucet or a transfer from another wallet. Nothing is sold,
so there is no App Store Connect work, no review risk, and no server-side
registration.

What you get: **no provider API key in your app.** The proxy holds the paid
OpenAI/Anthropic/Google/Mistral/Cohere/ElevenLabs/Stability keys and injects
them server-side, so your binary ships with no secret to extract, and you can
add or switch providers without a client release.

On iOS/macOS, `AICoinKit` (in [`mobile-purchase/ios/`](./mobile-purchase/ios/))
is a local Swift package that does the whole thing:

```swift
.package(path: "../aicoin/mobile-purchase/ios")

let identity = try WalletKeychainStore().loadOrCreateIdentity()  // Ed25519, stays in the Keychain
let tokens = AICoinTokenCache(identity: identity)                // signs + auto-renews the token
let transport = AICoinRouter(underlying: URLSession.shared,
                             tokenProvider: { tokens.currentToken() })
// point your existing provider SDK/URLSession calls at `transport` and you're done
```

`AICoinRouter` conforms to `HTTPTransport`, so it wraps a `URLSession`: it
rewrites requests aimed at a known AI host onto the proxy and attaches the
token, and passes everything else straight through untouched.

Drop in `CoinBalanceBadge` for a balance display, `SendReceiveView` for
transfers, and catch `AICoinError.insufficientBalance` (the proxy's `402`) to
prompt the user. There is deliberately no bring-your-own-key fallback.

**Any other platform** works too — none of this is iOS-specific on the wire.
Generate an Ed25519 keypair, issue a token by signing the payload described
in the "Using it" section above, and send it as `X-Api-Key`. The wallet
endpoints (`/wallet/api/balance/{address}`, `claim`, `transfer`) are plain
JSON over HTTP. `CONTRACT.md` fixes the exact bytes to sign; the Swift
`WalletSignerTests` are the readable spec for it, and
`mobile-purchase/README.md` describes what an Android port would mirror.

### Level 2 — wallet + in-app purchase (sell coins)

Everything above, plus users buying coins with real money. Add
`BuyAICoinSheet` and `IAPManager` and the client side is essentially done —
what's on sale is server-driven, so changing it never needs an app release:

```swift
await iapManager.loadOffer()                       // the one amount on sale
try await iapManager.purchaseCurrentOffer(         // re-checks, pins, buys, redeems
    address: identity.address, confirmedCoins: offer.coins)
```

This level is **not** self-service today, and that is a deliberate property
of a shared ledger rather than an oversight — every coin sold has to be
backed by a purchase this server can verify. Adding an app requires three
server-side changes:

1. Its real bundle ID added to `KNOWN_BUNDLE_IDS` in `IapPackages.java`.
   Redemption rejects any bundle ID not on that allowlist, however valid the
   Apple signature.
2. Its products added to `iap.packages` (config or the live catalog via
   `scripts/set-coin-packages.sh`), using the
   `<bundle-id>.aicoin.{small,medium,large,xl}` scheme. Note Apple forbids
   hyphens in product IDs, so a hyphenated bundle ID drops it there and only
   there.
3. Those same product IDs registered as consumables in your App Store Connect
   account, at the **fixed** prices the catalog lists — the offer model maps
   coin amounts onto those price points, so a product repriced behind the
   server's back sells coins at the wrong price.

If you'd rather not coordinate that, run your own instance: it's one Java
process plus Redis (`docker compose up --build`), and then the allowlist and
catalog are simply yours to edit.

### Which to pick

| | Wallet only | Wallet + IAP |
| --- | --- | --- |
| Provider keys in your app | none | none |
| App Store Connect setup | none | 4 consumables per app |
| Changes to this server | none | bundle ID + catalog |
| How users get coins | faucet, transfers | that, plus buying |
| Good for | tools, scripts, internal apps, trying it out | shipping consumer apps |

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

Builds the proxy, boots a mock AI provider and a Redis (a local
`redis-server` if there is one on `PATH`, otherwise a `redis:7-alpine`
container), and exercises the full flow: auth, routing and key injection per
provider, metered billing, price, faucet, transfer, free targets, and a
consortium call from drafts through review rounds — including one that runs
the wallet dry mid-call.

## Repo layout

- [`CONTRACT.md`](./CONTRACT.md) — the authoritative spec this project is built against.
- [`aicoin-proxy/`](./aicoin-proxy/) — the Java/Netty proxy and the Redis-backed coin ledger. Full flag/API/config reference in its own README. Admin CLIs in `scripts/`: `set-coin-offer.sh` (**the** one that changes what users can buy right now), `set-coin-amounts.sh` and `set-coin-packages.sh` (the underlying product catalog), and `adjust-iap-prices.sh` (hourly cron job that re-derives product prices from the live `/price` signal — report-only while an offer is live, since the offer model needs those price points to stay fixed).
- [`mobile-purchase/`](./mobile-purchase/) — client-side purchase → wallet-credit integration, one directory per platform: [`ios/`](./mobile-purchase/ios/) is the `AICoinKit` Swift package every app here builds on (previously `ios-iap-redeem/`); `android/` is where a Play Billing counterpart would go.
- [`e2e/run.sh`](./e2e/run.sh) — the end-to-end test.
- [`cli/`](./cli/) — the `aicoin` command-line wallet and AI client (Go, no dependencies). Wallet management, `ask`, and `consortium`; talks to any aicoin-proxy over the same HTTP API the wallet page uses.
- [`site/`](./site/) — the static landing page deployed at aicoin.oeaio.com. The Caddy config that serves it lives on the host, not in this repo; DNS is a Route 53 zone for `oeaio.com` whose `proxy.aicoin`, `aicoin`, `apps`, `www`, and apex A records all point at the same server. `proxy.aicoin` is the proxy's canonical host; `apps` is the previous one, kept resolving so already-shipped app builds keep working.
- [`ios/AICoinWallet/`](./ios/AICoinWallet/) — a native SwiftUI wallet app (bundle `com.oeaio.aicoin.wallet`) mirroring `wallet.html`'s capabilities.
