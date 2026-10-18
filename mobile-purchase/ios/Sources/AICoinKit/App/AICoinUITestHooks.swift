import Foundation

/// UI-test-only overrides for wallet state, read from the launch environment
/// (`UITEST_AICOIN_*`), so a UI test can pin the balance, keep off the faucet, drive the `402`
/// path, or run an app's generation loop offline.
///
/// **Why this has to exist.** Without it, a UI test launch is indistinguishable from a real one:
/// the app would mint a real Keychain wallet, silently claim from the real, shared, finite
/// production faucet (CONTRACT.md's pool is global across every wallet, so a CI run could drain
/// it), and route every provider call through the live proxy, spending real coins that were bought
/// with real money. None of that is acceptable from a test, so when any of these variables is set
/// `AICoinAppRuntime` re-points the whole wallet stack at `AICoinUITestTransport` and never
/// reaches the network.
///
/// A value parsed from one environment rather than loose statics, so the parsing can be checked
/// against any environment, not only the one the process happened to launch with. In a normal
/// production launch none of the variables are set, `isActive` is `false`, and every code path
/// that consults it is bypassed — the app behaves exactly as if this type didn't exist.
public struct AICoinUITestHooks: Sendable, Equatable {
    /// `UITEST_AICOIN_BALANCE=<double>` — the balance every `GET /wallet/api/balance/{address}`
    /// answers with, so the badge and the buy sheet show a known, asserted-on number instead of
    /// whatever a live wallet holds. A stub transport rather than a seeded `WalletBalanceStore`
    /// value because the store re-reads on every event and on foregrounding: a seeded number would
    /// be overwritten by the simulator wallet's real balance within a second.
    public static let balanceKey = "UITEST_AICOIN_BALANCE"

    /// `UITEST_AICOIN_DISABLE_FAUCET=1` — suppress the faucet claim entirely. **Every UI test
    /// should set this**, including ones that have nothing to do with AICoin: it is the single
    /// guard that keeps a test run off the live faucet.
    public static let disableFaucetKey = "UITEST_AICOIN_DISABLE_FAUCET"

    /// `UITEST_AICOIN_FORCE_INSUFFICIENT=1` — make every paid AI call come back `402`, exactly as
    /// the real balance gate does for an empty wallet, so the `402` →
    /// `AICoinEventBus.insufficientBalance` → `BuyAICoinSheet` path can be driven end to end
    /// without an empty real wallet.
    public static let forceInsufficientKey = "UITEST_AICOIN_FORCE_INSUFFICIENT"

    /// `UITEST_AICOIN_FAKE_AI=1` — answer paid AI calls with the app's own canned replies (the
    /// `fakeAI` closure given to `AICoinUITestTransport`) instead of refusing them as offline.
    /// Every other hook makes the app offline, which means no test could ever exercise the thing
    /// a generation loop is built around — fetching the next piece while the current one plays.
    /// The only alternative is a live run against the real proxy, which costs coins and can't run
    /// in CI at all.
    public static let fakeAIKey = "UITEST_AICOIN_FAKE_AI"

    public var balance: Double?
    public var faucetDisabled: Bool
    public var forceInsufficient: Bool
    public var fakeAI: Bool

    public init(environment: [String: String]) {
        balance = environment[Self.balanceKey].flatMap(Double.init)
        faucetDisabled = environment[Self.disableFaucetKey] == "1"
        forceInsufficient = environment[Self.forceInsufficientKey] == "1"
        fakeAI = environment[Self.fakeAIKey] == "1"
    }

    /// No hooks at all — what a production launch reads. Spelled out for previews and tests that
    /// must not depend on the environment the test runner happened to inherit.
    public static let inactive = AICoinUITestHooks(environment: [:])

    /// True when *any* hook is set — the signal to swap in the offline transport and an ephemeral
    /// wallet. Deliberately includes `faucetDisabled`, so the mandatory "don't touch the faucet"
    /// flag also, on its own, takes the whole app offline rather than only muting the one claim.
    public var isActive: Bool {
        balance != nil || faucetDisabled || forceInsufficient || fakeAI
    }
}
