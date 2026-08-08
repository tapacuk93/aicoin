import Foundation

/// Central place for the one thing every piece of this package needs to agree on: where the
/// aicoin-proxy server actually lives.
///
/// Note for anyone diffing this against the three apps' existing bespoke integrations: All
/// Languages Learner's `AICoinGateway` and Learn It's `AIcoinWalletRouter` both hardcode
/// `https://aicoin.oeaio.com` as the proxy host, and the standalone `AICoinWallet` prototype app's
/// `ProxyAPI` hardcodes yet a third guess, `https://proxy.aicoin.oeaio.com` — all predating this
/// task's clarification. Per this task's own brief, the actual proxy server is
/// **`https://apps.oeaio.com`**; `aicoin.oeaio.com` is the public marketing/docs site only. This
/// package uses the corrected host as its default; the three apps' existing hardcoded values are
/// stale placeholders that should be dropped once each app adopts this package.
public enum AICoinConfig {
    public static let defaultBaseURL = URL(string: "https://apps.oeaio.com")!
}
