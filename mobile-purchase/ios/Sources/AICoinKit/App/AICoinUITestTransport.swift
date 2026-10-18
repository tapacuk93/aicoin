import Foundation

/// The offline stand-in for the network under `AICoinUITestHooks`. Answers the handful of wallet
/// endpoints the UI needs with canned, deterministic bodies and refuses everything else, so no UI
/// test can reach the proxy, a real AI provider, or a real coin balance.
///
/// Sits in two places at once in `AICoinAppRuntime`: as `WalletClient`'s transport (the wallet
/// endpoints below) and as `AICoinRouter`'s `underlying` (so a rewritten provider call lands here
/// instead of on the proxy).
///
/// The canned *AI* replies are the one app-specific part — a radio wants a segment with its three
/// header lines, a book wants a chapter — so they come from the app as `fakeAI` rather than being
/// guessed at here. Everything wallet- and IAP-shaped is the same for every app and lives here.
public struct AICoinUITestTransport: HTTPTransport {
    public let hooks: AICoinUITestHooks
    /// What this app's product ids start with: `<productIDPrefix>.aicoin.{small,medium,large,xl}`.
    public let productIDPrefix: String
    private let fakeAI: (@Sendable (URLRequest) -> (Data, URLResponse)?)?

    /// - Parameters:
    ///   - hooks: Which behaviours the launching test asked for.
    ///   - productIDPrefix: Used to build the stubbed `/iap/packages` list.
    ///   - fakeAI: The app's canned reply to a paid AI call, consulted only under
    ///     `UITEST_AICOIN_FAKE_AI`. Returning `nil` (or passing no closure) leaves that call
    ///     "offline", the same as without the hook. The closure may keep its own counter — consecutive
    ///     replies that differ are what let a test tell "the loop advanced" from "the same thing
    ///     re-rendered" — but it is called concurrently, so guard any state it keeps.
    public init(
        hooks: AICoinUITestHooks,
        productIDPrefix: String,
        fakeAI: (@Sendable (URLRequest) -> (Data, URLResponse)?)? = nil
    ) {
        self.hooks = hooks
        self.productIDPrefix = productIDPrefix
        self.fakeAI = fakeAI
    }

    /// Mirrors the proxy's seeded `iap.packages` tiers for this app's prefix. Hardcoded rather than
    /// fetched precisely because fetching is the thing being avoided; `usd_price_hint` is
    /// display-only copy anyway, and the real charged price always comes from StoreKit.
    public var packagesJSON: String {
        let tiers: [(String, Int, Double)] = [
            ("small", 50, 0.99), ("medium", 200, 2.99), ("large", 1000, 9.99), ("xl", 5000, 44.99),
        ]
        let entries = tiers.map { tier, coins, price in
            #"{"product_id":"\#(productIDPrefix).aicoin.\#(tier)","coins":\#(coins),"usd_price_hint":\#(price)}"#
        }
        return #"{"packages":[\#(entries.joined(separator: ","))]}"#
    }

    public func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        guard let url = request.url else { throw URLError(.badURL) }
        let path = url.path

        // The wallet's own endpoints come first: a live-signed claim or transfer carries
        // `X-Api-Key` too (it is the wallet address there), so testing for a paid call before
        // these would mistake a claim for an AI call.
        if path.hasPrefix("/wallet/api/balance/") {
            let address = String(path.dropFirst("/wallet/api/balance/".count))
            return Self.json(#"{"user_id":"\#(address)","balance":\#(hooks.balance ?? 0)}"#, status: 200, url: url)
        }
        if path == "/iap/packages" {
            return Self.json(packagesJSON, status: 200, url: url)
        }
        // Belt-and-braces: `AICoinFaucet` already refuses to claim when the hooks are active, so
        // reaching this at all is a bug. Answer with the faucet's own "pool exhausted" denial,
        // which `claimFreeCoins` folds into a non-throwing, non-granting result — never a mint.
        if path == "/wallet/api/claim" {
            return Self.json(#"{"granted":false,"reason":"pool_exhausted"}"#, status: 429, url: url)
        }
        // A UI test only ever asserts the send form exists, so an actual transfer arriving here is
        // likewise unexpected; refuse it rather than pretend coins moved.
        if path == "/wallet/api/transfer" {
            return Self.json(#"{"error":"insufficient balance"}"#, status: 400, url: url)
        }
        if path.hasPrefix("/wallet/") || path.hasPrefix("/iap/") {
            // Redeem, offer, price — nothing a test can meaningfully complete offline.
            throw URLError(.notConnectedToInternet)
        }

        // A paid AI call: either one `AICoinRouter` just rewrote onto the proxy (it adds `X-AI`),
        // or a call to one of the proxy's own capability endpoints (`/text`, `/image`, …), which
        // presents the wallet's API token as `X-Api-Key`.
        if request.value(forHTTPHeaderField: "X-AI") != nil || request.value(forHTTPHeaderField: "X-Api-Key") != nil {
            // Force-insufficient wins over fake replies: a test that asked for the 402 path wants
            // it for every paid call. The body is the balance gate's real shape, which the router
            // turns back into `AICoinError.insufficientBalance` and republishes on the event bus.
            if hooks.forceInsufficient {
                return Self.json(#"{"error":"insufficient aicoin balance","balance":0.0}"#, status: 402, url: url)
            }
            if hooks.fakeAI, let reply = fakeAI?(request) {
                return reply
            }
        }

        // Everything else — provider hosts the router didn't rewrite, Wikipedia, image downloads —
        // reads as "offline", which an app's call sites already have to fail soft on.
        throw URLError(.notConnectedToInternet)
    }

    /// A JSON `HTTPURLResponse` with `status` — public so an app's `fakeAI` closure can build its
    /// canned replies the same way.
    public static func json(_ body: String, status: Int, url: URL) -> (Data, URLResponse) {
        let response = HTTPURLResponse(
            url: url,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        return (Data(body.utf8), response)
    }
}
