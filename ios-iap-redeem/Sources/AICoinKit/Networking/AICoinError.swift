import Foundation

/// Errors surfaced by `AICoinRouter` and `WalletClient`. `insufficientBalance` is the one signal
/// CONTRACT.md's balance gate documents (`402 {"error":"insufficient aicoin balance","balance":N}`)
/// — per this task's brief, apps built on this package have **no bring-your-own-key fallback**
/// anymore, so this is a typed error meant to be caught by app UI and turned into a "buy more
/// AICoin" prompt (see `BuyAICoinSheet`), not silently retried against a user-supplied key.
public enum AICoinError: Error, LocalizedError, Sendable, Equatable {
    /// The wallet's balance was too low to cover this call (< 1.0 aicoin). `balance`, when the
    /// proxy's error body parsed successfully, is the wallet's actual balance at the time.
    case insufficientBalance(balance: Double?)

    /// A known AI-provider host was called, but no valid API token was available to route it
    /// through the proxy. Since apps built on this package are not expected to hold a personal
    /// provider key to fall back to, this means the wallet identity/token hasn't been set up yet
    /// — a configuration problem, not a per-call balance problem.
    case missingToken

    /// Any other non-2xx response from the aicoin server, with its `error` field if the body
    /// parsed as JSON.
    case server(status: Int, message: String?)

    /// The response body wasn't parseable JSON where JSON was expected.
    case malformedResponse

    public var errorDescription: String? {
        switch self {
        case .insufficientBalance(let balance):
            if let balance {
                return "Your AICoin balance (\(balance.formatted(.number.precision(.fractionLength(0...2))))) is too low for this call. Buy more AICoin to continue."
            }
            return "Your AICoin balance is too low for this call. Buy more AICoin to continue."
        case .missingToken:
            return "No AICoin wallet is set up for AI calls yet."
        case .server(let status, let message):
            return message ?? "AICoin server error (\(status))."
        case .malformedResponse:
            return "Received an unexpected response from the AICoin server."
        }
    }
}
