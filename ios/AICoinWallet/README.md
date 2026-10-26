# AICoin Wallet (iOS)

A native SwiftUI mirror of `aicoin-proxy/src/main/resources/wallet.html`:
generate or import an Ed25519 wallet, view balance/price, claim free coins,
send coins to another address, and issue/revoke AI-proxy API tokens — talking
to the same production proxy at `https://proxy.aicoin.oeaio.com`. Bundle ID
`com.oeaio.aicoin.wallet`.

See the repo-root `CONTRACT.md` for the exact wire protocol this app
implements (canonical live-signature message, token format, response
shapes) — `AICoinWallet/Services/WalletSigner.swift` and `ProxyAPI.swift`
are the client-side counterparts of
`aicoin-proxy/src/main/java/com/aicoin/proxy/WalletSignature.java` and
`ProxyFrontendHandler.java`.

## Layout

- `AICoinWallet/Models/WalletKeys.swift` — the Ed25519 keypair (CryptoKit
  `Curve25519.Signing`), address derivation, and the 128-hex-char backup
  blob format (seed + public key, same layout the browser wallet uses).
- `AICoinWallet/Models/WalletStore.swift` — owns the open wallet and screen
  state (connect/backup/wallet), the SwiftUI equivalent of wallet.html's
  top-level `wallet` variable.
- `AICoinWallet/Services/KeychainStore.swift` — persists the 32-byte seed in
  the iOS Keychain (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`); the
  native equivalent of the browser wallet's `localStorage`.
- `AICoinWallet/Services/WalletSigner.swift` — canonical live-signature
  message construction + signing, and client-side API token issuance.
- `AICoinWallet/Services/ProxyAPI.swift` — the networking layer against
  `/price`, `/free-coins/available`, `/wallet/api/balance/{address}`,
  `/wallet/api/claim`, `/wallet/api/transfer`, `/wallet/api/revoke-tokens`,
  `/wallet/api/reputation/{address}`, `/price/history`, and the
  `/wallet/api/authorize`/`grants` routes.
- `AICoinWallet/Models/WalletModel.swift` — what the wallet screen shows
  (balance, price, faucet pool, standing), refreshed together.
- `AICoinWallet/Views/` — native SwiftUI screens in system styles (light and
  dark): `OnboardingView` (create/import), `BackupView` (one-time key reveal),
  `WalletView` (balance, Send/Receive/Scan, faucet while the pool lasts),
  `SendView`, `ReceiveView`, `PriceView` (Swift Charts over `/price/history`),
  `StandingView`, `AuthorisedServicesView` + `AuthorisationRequestView`,
  `APITokensView`, `PrivateKeyView`, and `ScannerSheet` (camera QR).

## Building

Requires Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`) — the `.xcodeproj` is generated from `project.yml`
and is not committed.

```sh
cd ios/AICoinWallet
xcodegen generate
open AICoinWallet.xcodeproj   # or build/test from the command line below
```

```sh
xcodebuild -project AICoinWallet.xcodeproj -scheme AICoinWallet \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build

xcodebuild -project AICoinWallet.xcodeproj -scheme AICoinWallet \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test
```

`AICoinWalletTests` covers the crypto layer (keypair generation, backup
round-trip, canonical-message/signature construction, Keychain round-trip)
with no network dependency. `AICoinWalletUITests` drives the real app in
the simulator, including one test that performs a genuine live-signed claim
against the production proxy and asserts the balance updates — a real
interop check between this app's CryptoKit signing and the Java server's
`Signature.verify`, not just unit-tested logic in isolation.

## Notes

- The proxy base URL is a hardcoded constant in `ProxyAPI.swift`
  (`https://proxy.aicoin.oeaio.com`) — matching this project's existing
  "draft/prototype" posture elsewhere (no environment switcher yet).
- The app icon and accent color are placeholders (a solid dark swatch
  matching the wallet's background) — replace
  `AICoinWallet/Assets.xcassets/AppIcon.appiconset/icon-1024.png` with a
  real icon before shipping.
