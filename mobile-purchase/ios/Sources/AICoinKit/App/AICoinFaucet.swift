import Foundation

/// The welcome: 100 coins a new user starts with, claimed silently, once per wallet.
///
/// A brand-new wallet starts at a zero balance and every AI call costs coins, so without this an
/// app can do literally nothing until the user buys some — a poor first run, and an App Review
/// minimum-functionality problem. CONTRACT.md's `POST /wallet/api/welcome` grants a wallet 100
/// coins the first time it asks and never again, so it is fired in the background as soon as the
/// wallet is settled (`AICoinAppStore.start()` reconciles with the user's other devices first): no
/// onboarding step, no button, no prompt. The coins land in the balance the app already shows.
///
/// "Once" is the server's to decide, per wallet. A reinstall, or a second device that picks up the
/// shared wallet through iCloud Keychain, has no flag here and asks again; the server answers
/// `already_claimed` and nothing is granted. Before this, the faucet's `POST /wallet/api/claim`
/// was used, which after its hour's cooldown granted the same wallet another 100 from the shared
/// pool whenever the pool held coins.
///
/// Every outcome is swallowed on purpose. A denial or an error leaves the balance as it is, and the
/// existing `402` → `AICoinEventBus.insufficientBalance` → `BuyAICoinSheet` path takes over; the
/// user never sees a claim error.
public enum AICoinFaucet {
    /// Claims the wallet's welcome unless this install already has the server's verdict.
    ///
    /// The flag under `key` is written only on a verdict about this wallet: a grant, or
    /// `already_claimed`. Anything else — `welcome_limit` (the day's welcomes are used up), a
    /// `5xx`, an offline launch, or a proxy too old to have the endpoint (`401`) — leaves it unset,
    /// so the next launch asks again: one request a launch at most, and never once settled.
    ///
    /// `disabled` (the `UITEST_AICOIN_DISABLE_FAUCET` hook) refuses before anything else, including
    /// the already-settled check, so a test never so much as touches the flag: an unattended
    /// UI-test run must not take real coins.
    ///
    /// - Parameter key: The `UserDefaults` key the "settled" flag lives under —
    ///   `AICoinAppRuntime.Configuration.welcomeKey`.
    /// - Returns: The server's verdict, or `nil` when the claim was skipped or failed. Callers use
    ///   it only to decide whether refreshing the balance is worthwhile.
    @discardableResult
    public static func claimIfNeeded(
        walletClient: WalletClient,
        identity: WalletIdentity,
        defaults: UserDefaults = .standard,
        key: String,
        disabled: Bool = false
    ) async -> ClaimResult? {
        guard !disabled else { return nil }
        guard !defaults.bool(forKey: key) else { return nil }
        do {
            let result = try await walletClient.claimWelcome(identity: identity)
            if result.granted || result.reason == "already_claimed" {
                defaults.set(true, forKey: key)
            }
            return result
        } catch {
            return nil
        }
    }
}
