# mobile-purchase

Client-side purchase → wallet-credit integration for [**aicoin-proxy**](../aicoin-proxy), one
directory per mobile platform. The server contract every platform implements is
[`CONTRACT.md`](../CONTRACT.md); nothing in here is platform-specific on the wire.

| Platform | Directory | Status |
| --- | --- | --- |
| iOS / macOS | [`ios/`](./ios) — the `AICoinKit` Swift package | shipping, used by all our apps |
| Android | `android/` | not built yet |

## What "mobile purchase" means here

Whatever the store, the flow is the same three steps, and only step 2 is platform-specific:

1. **Wallet identity** — generate an Ed25519 keypair in the platform's secure storage (iOS
   Keychain; Android Keystore), and sign wallet calls with it. The proxy never sees a private key.
2. **Store purchase** — buy a consumable coin package through the platform's own billing
   (StoreKit on iOS; Google Play Billing on Android) and get back the store's signed proof of
   purchase (a StoreKit 2 JWS; a Play purchase token).
3. **Redeem** — hand that proof to the proxy, which verifies it with the store's public keys and
   credits the wallet. Coins are then spent by routing AI-provider calls through the proxy.

What's for sale comes from the server at runtime, so changing it never requires a client release.
There are two layers: the **catalog** (`GET /iap/packages`) is which products exist and what each
costs, and the **current offer** (`GET /iap/offer`) is the single coin amount every app is actually
selling right now. A client displays the offer, re-checks it via `POST /iap/offer/check`
immediately before charging — which pins that amount so the user is credited what they were shown
even if it changes mid-purchase — and buys whichever fixed-price product the offer names for its
own app. Coins are decoupled from products entirely: a purchase credits the offer's amount, not
the catalog `coins` of the product charged.

The product IDs are per-app and per-store, so an Android build registers its own alongside the iOS
ones in `aicoin-proxy/src/main/resources/application.yaml`. Note a store's product-id alphabet may
not match a bundle ID: Apple forbids hyphens, so Learn It's `com.tarasmaslov.learn-it` bundle uses
`com.tarasmaslov.learnit.*` product IDs, and a client must derive the prefix rather than matching
its raw bundle ID (`AICoinProductID.prefix(forBundleID:)` in the iOS package).

## Adding Android later

Create `android/` as a sibling of `ios/` — a Gradle library module, not a fork of the Swift code.
The pieces worth mirroring one-for-one (same names, same responsibilities) are `WalletIdentity`,
`WalletSigner`, `WalletClient`, `AICoinRouter`, and `IAPManager`; `ios/README.md` documents what
each does and the exact wire format it produces. Two things to carry over rather than redesign:

- **The canonical signature message.** `CONTRACT.md` fixes the exact bytes signed for each wallet
  call; a mismatch fails server-side verification with no useful error. `WalletSignerTests` in
  `ios/` is the readable spec for it.
- **No bring-your-own-key fallback.** On a `402` the router surfaces a typed
  insufficient-balance error and the app prompts to buy coins; it never falls back to a personal
  provider key.

Redemption of a Play purchase token needs a server-side counterpart next to the existing Apple JWS
verifier (`aicoin-proxy/.../AppleJwsVerifier.java`) before the Android client can credit a wallet.
Carry over one lesson from the Apple side: a valid store signature proves a purchase is *genuine*,
never that it was *paid for*. The Apple verifier must reject Sandbox transactions (same signing
chain as real ones, mintable without limit from a free tester account) and refunded ones; a Play
verifier needs the equivalent checks on `purchaseState`/test purchases and voided orders, or the
same "spend coins nobody bought" hole reopens on Android.
