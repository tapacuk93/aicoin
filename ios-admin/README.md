# AICoin Admin

The operator's controls for aicoin-proxy, on the phone.

- **Overview** - the spend budget (ceiling, spent, remaining) and a one-tap
  raise (+$50, +$100, +$200 or any amount). The coin price, and every
  provider's health and latency.
- **Sales** - the live offer (set one, or close sales in every app) and the
  catalog, per app and tier. Catalog edits are refused while the budget is
  exhausted: the catalog is served empty then, and saving it would erase it.
- **Wallets** - every wallet by balance, its transactions, and crediting coins
  (a negative amount takes them back), with a reason.
- **Apps** - every app the proxy knows (configured ones and each app the IAP
  catalog sells for). An app has its own daily dollar cap or uses the common
  budget from Overview (the default). Today's spend against the cap with a
  progress bar, when it resets (midnight UTC), and the coins each app wallet
  holds (an empty one gets 402 whatever the cap). Edit a cap, add an app with
  its wallets, or give a capped app a **one-time top-up**: extra dollars for
  today only, on top of the cap, which stays as it is. See CONTRACT.md
  "Per-app daily caps".
- **Activity** (from Overview) - the latest movements across all wallets.
- **Settings** - the proxy address, the admin token, marking wallets
  internal so their spend does not count against the budget, and
  **Notifications**: which push alerts this device gets, a test push, and the
  recent alerts (CONTRACT.md "Admin alerts").

Every write asks for confirmation first. The admin token
(`AICOIN_PROXY_ADMIN_TOKEN` on the server) is typed in once and kept in this
device's Keychain - never in the app, never in the repository.

## Build and install

```sh
cd ios-admin && xcodegen generate
xcodebuild build -project AICoinAdmin.xcodeproj -scheme AICoinAdmin -configuration Debug \
  -destination generic/platform=iOS -derivedDataPath build CODE_SIGNING_ALLOWED=NO
```

Push needs the explicit App ID (`com.tarasmaslov.aicoinadmin`, Push
Notifications enabled), so the wildcard profile no longer works. Sign with the
development profile "AICoin Admin Dev Push" (it carries
`aps-environment=development` and the phone) and the Apple Development
identity - embed it as `embedded.mobileprovision`, sign with entitlements
`application-identifier`, `com.apple.developer.team-identifier`,
`get-task-allow` and `aps-environment=development` - and install with
`xcrun devicectl device install app --device <udid> build/Build/Products/Debug-iphoneos/AICoinAdmin.app`.
