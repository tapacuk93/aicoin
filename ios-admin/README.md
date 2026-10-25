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
- **Activity** - the latest movements across all wallets.
- **Settings** - the proxy address, the admin token, and marking wallets
  internal so their spend does not count against the budget.

Every write asks for confirmation first. The admin token
(`AICOIN_PROXY_ADMIN_TOKEN` on the server) is typed in once and kept in this
device's Keychain - never in the app, never in the repository.

## Build and install

```sh
cd ios-admin && xcodegen generate
xcodebuild build -project AICoinAdmin.xcodeproj -scheme AICoinAdmin -configuration Debug \
  -destination generic/platform=iOS -derivedDataPath build CODE_SIGNING_ALLOWED=NO
```

Then sign it with the team's wildcard development profile ("iOS Team
Provisioning Profile: *") and the Apple Development identity - embed the
profile as `embedded.mobileprovision`, sign with entitlements for
`HTS38ZPRVH.com.tarasmaslov.aicoinadmin` - and install with
`xcrun devicectl device install app --device <udid> build/Build/Products/Debug-iphoneos/AICoinAdmin.app`.
