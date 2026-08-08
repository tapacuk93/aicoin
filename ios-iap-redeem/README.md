# AICoinKit

A local Swift Package providing everything an iOS/macOS app needs to integrate with
[**aicoin-proxy**](../aicoin-proxy) — the shared coin-ledger/AI-proxy server described in
[`CONTRACT.md`](../CONTRACT.md) — instead of hand-rolling wallet signing, provider routing, and IAP
redemption per app.

It replaces the bespoke, near-duplicate integration code that three apps
(**InfiniteAIRadio**, **All Languages Learner**, **Learn It**) each grew independently:
- All Languages Learner's `AICoinGateway`
- Learn It's `AIcoinWalletRouter` + `HTTPTransport`
- The crypto/keychain core of the standalone `AICoinWallet` prototype app (`../ios/AICoinWallet`)

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
    WalletClient.swift          balance / claim / transfer / revoke-tokens / iap packages / redeem
  IAP/
    AICoinPackage.swift         /iap/packages model + bundle-ID-prefix filtering
    IAPManager.swift            StoreKit 2 purchase flow + redemption
  UI/
    WalletBalanceStore.swift    ObservableObject balance holder, auto-refreshes on events
    CoinBalanceBadge.swift      Toolbar-sized balance badge
    BuyAICoinSheet.swift        "Buy AICoin" sheet (dynamic packages) + Send/Receive tab
    SendReceiveView.swift       Peer-transfer UI

Tests/AICoinKitTests/          XCTest, no network/Redis — pure logic + mock transports
```

## Adopting this package in an app

Each app adds it as a local Swift Package dependency by relative path (all three apps and this
package share a common parent directory, `~/src`):

```swift
// Package.swift, or Xcode > File > Add Package Dependencies... > Add Local...
.package(path: "../aicoin/ios-iap-redeem")
```

or, in an `.xcodeproj`/XcodeGen `project.yml`:

```yaml
packages:
  AICoinKit:
    path: ../aicoin/ios-iap-redeem
targets:
  YourApp:
    dependencies:
      - package: AICoinKit
```

Once this package (and the server it talks to) has stabilized, swap the local path for a git URL
(`.package(url: "https://github.com/tarasmaslov/aicoin-ios-iap-redeem", from: "1.0.0")`) with no
call-site changes required.

### Wiring it up (typical app startup)

```swift
import AICoinKit

// 1. Load or create the wallet identity (once, e.g. in your App/AppDelegate).
let keychainStore = WalletKeychainStore()
let identity = try keychainStore.loadOrCreateIdentity()

// 2. Wrap your existing HTTP transport so AI-provider calls route through the proxy.
let tokenCache = AICoinTokenCache(identity: identity)
let transport = AICoinRouter(underlying: URLSessionHTTPTransport(), tokenProvider: tokenCache.currentToken)
// Use `transport` (or decorate it further) everywhere you currently call
// api.anthropic.com / api.openai.com / api.elevenlabs.io / etc. directly.

// 3. Wallet reads/actions.
let walletClient = WalletClient()
let balance = try await walletClient.balance(address: identity.address)

// 4. IAP.
let iapManager = IAPManager(walletClient: walletClient)
await iapManager.loadPackages()
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
  `revokeTokens(identity:)`, `iapPackages()`, `redeemIAP(to:signedTransaction:)`, `price()`,
  `freeCoinsAvailable()`.
- `AICoinPackage` — `/iap/packages` model, plus `[AICoinPackage].filtered(byBundleIDPrefix:)`.
- `IAPManager` — `loadPackages()`, `purchase(_:address:)`, `startObservingUnfinishedTransactions(address:)`.
- `WalletBalanceStore`, `CoinBalanceBadge`, `BuyAICoinSheet`, `SendReceiveView` — SwiftUI layer.

## Design decisions made beyond CONTRACT.md's explicit spec

CONTRACT.md is authoritative on the server's wire format; several client-side questions were not
specified there and were decided as follows:

- **Proxy base URL**: CONTRACT.md's own task brief says the production proxy is
  `https://apps.oeaio.com` (with `aicoin.oeaio.com` being the separate marketing site). This
  package defaults to that. Note this *corrects* three different stale placeholder hosts found in
  the existing prior art (`aicoin.oeaio.com` in two apps' routers, `proxy.aicoin.oeaio.com` in the
  prototype wallet app) — none of which match the real deployed host.
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
  any product ID App Store Connect doesn't know about yet.
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
