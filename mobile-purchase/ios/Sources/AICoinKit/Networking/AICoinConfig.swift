import Foundation

/// Central place for the one thing every piece of this package needs to agree on: where the
/// aicoin-proxy server actually lives.
///
/// The production proxy is **`https://proxy.aicoin.oeaio.com`**; `aicoin.oeaio.com` is the public
/// marketing/docs site only, and `apps.oeaio.com` is the previous proxy host, kept resolving for
/// already-shipped builds but no longer the address anything should be written against.
///
/// Note for anyone diffing this against the three apps' existing bespoke integrations: All
/// Languages Learner's `AICoinGateway` and Learn It's `AIcoinWalletRouter` hardcoded
/// `https://aicoin.oeaio.com` as the proxy host. Those are stale placeholders that should be
/// dropped once each app adopts this package.
public enum AICoinConfig {
    public static let defaultBaseURL = URL(string: "https://proxy.aicoin.oeaio.com")!

    /// Whether the peer-transfer UI (`SendReceiveView`, and the "Send / Receive"
    /// tab that reaches it from `BuyAICoinSheet`) is offered at all.
    ///
    /// **Off, deliberately, for App Store builds.** Two App Review Guidelines
    /// land on it at once:
    ///
    /// - **3.1.5(b)(i) Wallets** — "Apps may facilitate virtual currency
    ///   storage, provided they are offered by developers enrolled as an
    ///   organization." `SendReceiveView` presents an address, a copy button, a
    ///   recipient field and an amount: a virtual-currency wallet by any
    ///   reviewer's reading, whatever the ledger is actually implemented as.
    /// - **3.1.1** — the only user-to-user movement the guidelines sanction is
    ///   *gifting* items eligible for in-app purchase, and even a gift "may not
    ///   be exchanged." Sending an arbitrary amount of an IAP-purchased
    ///   consumable to an arbitrary address is currency transmission, not
    ///   gifting.
    ///
    /// The buy side — consumable IAP, coins spent only inside the app — is
    /// unaffected and stays exactly as it was. The transfer code itself is
    /// left intact rather than deleted: the server endpoint
    /// (`POST /wallet/api/transfer`) is unchanged and still reachable from the
    /// web wallet, so this is the single switch to flip if the transfer story
    /// ever clears review (organization enrollment, reframed as gifting a
    /// fixed item).
    public static let isPeerTransferEnabled = false
}
