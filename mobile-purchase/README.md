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

Package definitions (product IDs → coin amounts) come from the server at runtime
(`GET /iap/packages`), so a new package or price never requires a client release. The product IDs
are per-app and per-store, so an Android build registers its own alongside the iOS ones in
`aicoin-proxy/src/main/resources/application.yaml`.

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
