import Foundation

/// The free-coin faucet, claimed silently exactly once per install.
///
/// A brand-new wallet starts at a zero balance and every AI call costs at least 1 aicoin, so
/// without this an app can do literally nothing until the user buys coins — a poor first run, and
/// an App Review minimum-functionality problem. CONTRACT.md's `POST /wallet/api/claim` mints the
/// starter coins, so it is fired once, in the background, as soon as the wallet exists: no
/// onboarding step, no button, no prompt. A reviewer just opens the app and it works.
///
/// Every documented outcome is swallowed on purpose. `claimFreeCoins` already folds the two `429`
/// denials (`cooldown`, `pool_exhausted`) into a non-throwing `ClaimResult(granted: false, …)`, and
/// a genuinely thrown error (offline, 5xx) is discarded here. In all of those cases the balance
/// simply stays at 0 and the existing `402` → `AICoinEventBus.insufficientBalance` →
/// `BuyAICoinSheet` path takes over; the user never sees a claim error.
public enum AICoinFaucet {
    /// Claims the faucet on first launch, and never again afterwards.
    ///
    /// The flag under `key` is written only once the server has actually answered — either a grant
    /// or a `429` denial. A claim that never reached the server (an offline first launch) leaves it
    /// unset so the next launch can retry, which is still at most one request per launch and never
    /// repeats once the faucet has given its verdict. That is all the client-side restraint the
    /// server's own 1-hour per-wallet cooldown and shared pool need.
    ///
    /// `disabled` (the `UITEST_AICOIN_DISABLE_FAUCET` hook) refuses before anything else, including
    /// the already-attempted check, so a test never so much as touches the flag. The faucet pool is
    /// shared across every wallet in production and finite, so an unattended UI-test run claiming
    /// from it would take free coins away from real first-time users.
    ///
    /// - Parameter key: The `UserDefaults` key the "already claimed" flag lives under. Apps that
    ///   shipped their own faucet before this package pass their existing key, so an update doesn't
    ///   look like a fresh install and claim a second time.
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
            let result = try await walletClient.claimFreeCoins(identity: identity)
            defaults.set(true, forKey: key)
            return result
        } catch {
            return nil
        }
    }
}
