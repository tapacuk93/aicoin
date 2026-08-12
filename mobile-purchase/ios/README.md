# AICoinKit

A local Swift Package providing everything an iOS/macOS app needs to integrate with
[**aicoin-proxy**](../../aicoin-proxy) — the shared coin-ledger/AI-proxy server described in
[`CONTRACT.md`](../../CONTRACT.md) — instead of hand-rolling wallet signing, provider routing, and IAP
redemption per app.

It replaces the bespoke, near-duplicate integration code that three apps
(**InfiniteAIRadio**, **All Languages Learner**, **Learn It**) each grew independently:
- All Languages Learner's `AICoinGateway`
- Learn It's `AIcoinWalletRouter` + `HTTPTransport`
- The crypto/keychain core of the standalone `AICoinWallet` prototype app (`../../ios/AICoinWallet`)

This package generalizes the real, working logic from all three into one place, and makes one
deliberate behavior change from the two apps' existing routers: **there is no "use your own API
key" fallback**. On a `402` (insufficient balance), the router throws a typed error instead of
silently retrying against a personal key — apps built on this package are expected to prompt the
user to buy more AICoin instead.

## What's in it

```
Sources/AICoinKit/
  Core/
    WalletIdentity.swift        Ed25519 keypair, address, backup/import
    WalletKeychainStore.swift   Keychain-backed persistence for the private key
    WalletSigner.swift          Canonical message + live-signed headers + API token issuance
  Networking/
    HTTPTransport.swift         The seam every AI-provider call goes through
    AICoinConfig.swift          Default proxy base URL
    AICoinError.swift           Typed errors (insufficientBalance, missingToken, ...)
    AICoinEventBus.swift        Combine bus: paidCallSucceeded / insufficientBalance / purchaseCredited
    AICoinRouter.swift          Host-routing decorator: known AI hosts -> proxy, with token auth
    AICoinTokenCache.swift      Caches/refreshes an issued API token for AICoinRouter
  Wallet/
    WalletModels.swift          Request/response DTOs (internal)
    WalletClient.swift          balance / claim / transfer / revoke-tokens / offer / packages / redeem
  IAP/
    AICoinPackage.swift         /iap/packages catalog model + bundle-ID-prefix filtering
    AICoinOffer.swift           /iap/offer model + per-app product selection
    IAPManager.swift            StoreKit 2 purchase flow + offer pinning + redemption
  UI/
    WalletBalanceStore.swift    ObservableObject balance holder, auto-refreshes on events
    CoinBalanceBadge.swift      Toolbar-sized balance badge
    BuyAICoinSheet.swift        "Buy AICoin" sheet (server-set offer) + Send/Receive tab
    SendReceiveView.swift       Peer-transfer UI

Tests/AICoinKitTests/          XCTest, no network/Redis — pure logic + mock transports
```

## Adopting this package in an app

Each app adds it as a local Swift Package dependency by relative path (all three apps and this
package share a common parent directory, `~/src`):

```swift
// Package.swift, or Xcode > File > Add Package Dependencies... > Add Local...
.package(path: "../aicoin/mobile-purchase/ios")
```

or, in an `.xcodeproj`/XcodeGen `project.yml`:

```yaml
packages:
  AICoinKit:
    path: ../aicoin/mobile-purchase/ios
targets:
  YourApp:
    dependencies:
      - package: AICoinKit
```

Once this package (and the server it talks to) has stabilized, swap the local path for a git URL
(`.package(url: "https://github.com/tarasmaslov/aicoin-mobile-purchase", from: "1.0.0")`) with no
call-site changes required.

### Wiring it up (typical app startup)

```swift
import AICoinKit

// 1. Load or create the wallet identity (once, e.g. in your App/AppDelegate).
let keychainStore = WalletKeychainStore()
let identity = try keychainStore.loadOrCreateIdentity()

// 2. Wrap your existing HTTP transport so AI-provider calls route through the proxy.
let tokenCache = AICoinTokenCache(identity: identity)
// `URLSession` conforms to `HTTPTransport` directly (see HTTPTransport.swift), so pass it as-is —
// there is no separate wrapper type to construct.
let transport = AICoinRouter(underlying: URLSession.shared, tokenProvider: tokenCache.currentToken)
// Use `transport` (or decorate it further) everywhere you currently call
// api.anthropic.com / api.openai.com / api.elevenlabs.io / etc. directly.

// 3. Wallet reads/actions.
let walletClient = WalletClient()
let balance = try await walletClient.balance(address: identity.address)

// 4. IAP.
let iapManager = IAPManager(walletClient: walletClient)
await iapManager.loadOffer()      // the one amount on sale right now
await iapManager.loadPackages()   // pre-offer fallback, for a server with no offer set
iapManager.startObservingUnfinishedTransactions(address: identity.address)

// 5. UI.
let balanceStore = WalletBalanceStore(address: identity.address, walletClient: walletClient)
// .toolbar { CoinBalanceBadge(store: balanceStore) { showBuySheet = true } }
// .sheet(isPresented: $showBuySheet) {
//     BuyAICoinSheet(iapManager: iapManager, walletStore: balanceStore, identity: identity)
// }
```

### Handling the balance gate

```swift
do {
    let (data, response) = try await transport.data(for: request)
    // ...
} catch AICoinError.insufficientBalance {
    showBuySheet = true   // there is no personal-key fallback in this design — see below
}
```

## The buy flow (the current-offer model)

What's on sale is one server-set number, the same in every app — not a list of tiers the user picks
from. `BuyAICoinSheet` renders it as a single button.

1. **Display** — `loadOffer()` fetches `GET /iap/offer` and resolves this app's product for it into
   `offerProduct` (StoreKit's localized `displayPrice` is what's shown; the offer's `usdPrice` only
   stands in until StoreKit resolves). A nil `offer` means sales are closed, which is an empty
   state, not an error.
2. **Re-check, then charge** — `purchaseCurrentOffer(address:confirmedCoins:)` calls
   `POST /iap/offer/check` *first* and purchases what that returns, not the possibly-stale number
   on screen. Pass the displayed amount as `confirmedCoins` and a mid-flight change throws
   `AICoinPurchaseError.offerChanged(from:to:)` having charged nothing, so the user re-confirms
   against the new number instead of being billed for something they didn't agree to.
3. **Redeem against the pin** — the check's `offer_id` travels with the JWS to
   `/wallet/api/redeem-iap`, which is what makes the credit equal what the user was shown even if
   the offer moved during Apple's sheet. The pin is also persisted locally against the StoreKit
   transaction id (`OfferPinStore`), so a purchase interrupted by the app being killed still
   redeems at the shown amount when `startObservingUnfinishedTransactions(address:)` picks the
   transaction up on a later launch. Server-side pins expire (15 min), after which redemption
   falls back to the live offer — the same path a client that never pinned takes.

`loadPackages()` and `purchase(_:address:)` remain as the pre-offer path, used only when the server
has no offer set. Both credit via the catalog's `coins` for the purchased product.

## Public API surface

- `WalletIdentity` — `generate()`, `init(seed:)`, `.address`, `.seed`, `.backupBlob`,
  `importing(backupBlob:)`.
- `WalletKeychainStore` — `loadIdentity()`, `createIdentity()`, `loadOrCreateIdentity()`,
  `save(_:)`, `deleteIdentity()`. Supports a shared Keychain `accessGroup` for apps that want one
  balance across all three (see "Design decisions" below).
- `WalletSigner` — `canonicalMessage(...)`, `signLiveRequest(...)`, `applyLiveSignature(to:identity:)`,
  `issueAPIToken(identity:validFor:)`, `decodeTokenPayload(_:)`, `verifyTokenSignature(_:)`.
- `HTTPTransport` — the protocol seam (`URLSession` already conforms).
- `AICoinRouter` — `init(underlying:baseURL:eventBus:tokenProvider:)`, conforms to `HTTPTransport`.
- `AICoinTokenCache` — `currentToken()`, `invalidate()`.
- `AICoinError` / `AICoinPurchaseError` — typed errors.
- `AICoinEvent` / `AICoinEventBus` — `.paidCallSucceeded`, `.insufficientBalance`, `.purchaseCredited`.
- `WalletClient` — `balance(address:)`, `claimFreeCoins(identity:)`, `transfer(to:amount:identity:)`,
  `revokeTokens(identity:)`, `iapPackages()`, `redeemIAP(to:signedTransaction:offerID:)`, `price()`,
  `freeCoinsAvailable()`, `currentOffer()`, `checkOffer()`.
- `AICoinPackage` — `/iap/packages` model, plus `[AICoinPackage].filtered(byBundleIDPrefix:)`.
- `AICoinOffer` — `/iap/offer` model, plus `productID(forBundleID:)`; `AICoinProductID.prefix(forBundleID:)`
  derives the product-id prefix from a bundle ID (they differ for Learn It — Apple forbids the hyphen).
- `IAPManager` — `loadOffer()`, `purchaseCurrentOffer(address:confirmedCoins:)`, `loadPackages()`,
  `purchase(_:address:)`, `startObservingUnfinishedTransactions(address:)`.
- `WalletBalanceStore`, `CoinBalanceBadge`, `BuyAICoinSheet`, `SendReceiveView` — SwiftUI layer.

## Design decisions made beyond CONTRACT.md's explicit spec

CONTRACT.md is authoritative on the server's wire format; several client-side questions were not
specified there and were decided as follows:

- **Proxy base URL**: the production proxy is `https://proxy.aicoin.oeaio.com` (with
  `aicoin.oeaio.com` being the separate marketing site, and `apps.oeaio.com` the previous proxy
  host, still resolving for already-shipped builds). This package defaults to that. Note this
  *corrects* the stale placeholder host found in the existing prior art (`aicoin.oeaio.com` in two
  apps' routers), which was never a real proxy.
- **No bring-your-own-key fallback**: `AICoinRouter` throws `AICoinError.insufficientBalance` on
  402 rather than retrying against a personal key, and throws `AICoinError.missingToken` if no
  token is configured for a known AI-provider host at all (rather than silently passing through to
  the real provider). This is a deliberate behavior change from both existing apps' routers, per
  this task's brief that apps should no longer carry a personal-key fallback.
- **Token caching**: CONTRACT.md specifies token *issuance* as a pure local signing operation with
  no server round-trip, but doesn't say how often a client should re-issue one. `AICoinTokenCache`
  issues a 30-day token by default and re-issues 5 minutes before expiry — reissuing on every call
  would be wasteful for no security benefit.
- **Keychain scope / cross-app wallet sharing**: CONTRACT.md is silent on whether the three apps
  share one wallet/balance or each has its own. `WalletKeychainStore` defaults to a private,
  per-app Keychain item (each app gets its own independent wallet), but accepts an `accessGroup`
  parameter so the three apps *can* share one identity (and therefore one balance) if they're
  configured with a common Keychain Sharing entitlement in each app's target — that Xcode-level
  entitlement setup is outside this package's scope and is a decision for whoever integrates it
  per app.
- **Transfer success response shape**: CONTRACT.md documents `/wallet/api/transfer`'s `400`
  error body but not its `200` success body. `WalletClient.transfer` optimistically decodes an
  optional `balance` field and tolerates its absence — treat `TransferResult.balance` as advisory
  until confirmed against the deployed server.
- **`RedeemResult`/`PriceResult`/etc. field names**: translated from CONTRACT.md's documented
  snake_case JSON via explicit `CodingKeys`, not `JSONDecoder.keyDecodingStrategy`, so a decoding
  mismatch fails at the specific model rather than silently reinterpreting unrelated JSON elsewhere.
- **Hex/base64url helpers kept internal, not public `Data` extensions**: each of the three apps'
  existing code already declares its own `Data.hexString`-shaped extensions. Making this package's
  equivalents `public` would risk "ambiguous use" compile errors in a consuming app that also
  imports its own. Everything this package needs to expose is already surfaced as a typed `String`
  property (e.g. `WalletIdentity.address`) instead.
- **`ObservableObject`/`@Published`, not `@Observable`**: the `Observation` framework's `@Observable`
  macro requires iOS 17/macOS 14. This package's deployment target is pinned to iOS 16/macOS 13 —
  a hard constraint from InfiniteAIRadio's `project.yml` — so `WalletBalanceStore` and `IAPManager`
  use the older, fully iOS-16-compatible `ObservableObject` pattern instead. No API in this package
  required raising the deployment target above iOS 16/macOS 13.

## Known TODOs / things to confirm once the server is live

- `WalletClient.transfer`'s success response shape is a best guess (see above) — confirm against
  the deployed `/wallet/api/transfer` and tighten `TransferResult` if it turns out to return more.
- The three apps' actual product IDs for IAP packages must be registered in App Store Connect
  matching CONTRACT.md's `com.tarasmaslov.<app>.aicoin.{small,medium,large,xl}` scheme before
  `Product.products(for:)` will resolve them to real `Product`s — until then, `IAPManager
  .loadPackages()` will populate `packages` (from the server) but `products` will stay empty for
  any product ID App Store Connect doesn't know about yet, and `loadOffer()` will set `offer`
  while leaving `offerProduct` nil (which disables the buy button rather than charging blind).
- Under the offer model those four products must sit at **fixed** prices in App Store Connect —
  the server maps an offer's coin amount onto whichever point covers it, so a product repriced
  behind the server's back silently sells coins at the wrong price. `adjust-iap-prices.sh` enforces
  this server-side by refusing to apply prices while an offer is live.
- End-to-end redemption hasn't been exercised against a real StoreKit purchase, because the server
  now (correctly) rejects Sandbox transactions by default. Testing the full purchase → credit path
  needs a non-production proxy with `AICOIN_PROXY_IAP_ACCEPT_SANDBOX=true`; never set that on the
  live deployment, since sandbox purchases are free and unlimited.
- No SwiftUI preview/screenshot testing was done — the views compile and their logic is covered
  indirectly through `IAPManager`/`WalletClient` tests, but visual polish is left to each app,
  since they're expected to skin these to match their own look.

## Running tests

```
swift test
```

If `xcode-select` points at a bare Command Line Tools install (no `XCTest.framework`), point at a
full Xcode install for this one invocation instead of changing the system-wide default:

```
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test
```
