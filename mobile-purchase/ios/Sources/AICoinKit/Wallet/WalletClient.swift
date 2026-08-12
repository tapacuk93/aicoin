import Foundation

/// Thin typed wrapper over the endpoints in CONTRACT.md's "Wallet web page" and "Auth for AI-proxy
/// calls" sections: balance lookup, the free-coin faucet, peer transfer, token revocation, IAP
/// package listing, and purchase redemption. Every live-signed call (`claimFreeCoins`, `transfer`,
/// `revokeTokens`) builds its own fresh `WalletSigner` headers per CONTRACT.md's replay-window
/// design — there is no session/cookie state to hold here.
public struct WalletClient: Sendable {
    public let baseURL: URL
    public let transport: any HTTPTransport

    public init(baseURL: URL = AICoinConfig.defaultBaseURL, transport: any HTTPTransport = URLSession.shared) {
        self.baseURL = baseURL
        self.transport = transport
    }

    // MARK: - Unsigned reads

    /// `GET /wallet/api/balance/{address}` — unsigned; a public address's balance is public info,
    /// per CONTRACT.md, "like a blockchain explorer."
    public func balance(address: String) async throws -> Double {
        let request = URLRequest(url: Self.endpointURL(baseURL, "/wallet/api/balance/\(address)"))
        let (data, response) = try await transport.data(for: request)
        try Self.requireOK(response, data)
        return try JSONDecoder().decode(BalanceResponseBody.self, from: data).balance
    }

    /// `GET /iap/packages` — the full, unfiltered list across all three apps' bundle IDs. Use
    /// `IAPManager`, or filter this yourself by `productId.hasPrefix(yourBundleID)`, to narrow to
    /// the calling app's own packages.
    public func iapPackages() async throws -> [AICoinPackage] {
        let request = URLRequest(url: Self.endpointURL(baseURL, "/iap/packages"))
        let (data, response) = try await transport.data(for: request)
        try Self.requireOK(response, data)
        return try JSONDecoder().decode(AICoinPackagesResponse.self, from: data).packages
    }

    /// `GET /iap/offer` — the single coin amount every app is selling right now, or nil when the
    /// operator has closed sales. This is what a paywall displays; see `checkOffer()` for the
    /// re-check that must happen immediately before charging.
    public func currentOffer() async throws -> AICoinOffer? {
        let request = URLRequest(url: Self.endpointURL(baseURL, "/iap/offer"))
        let (data, response) = try await transport.data(for: request)
        try Self.requireOK(response, data)
        return try JSONDecoder().decode(AICoinOfferResponse.self, from: data).offer
    }

    /// `POST /iap/offer/check` — re-reads the offer and pins it, returning the `offerID` to hand
    /// back to `redeemIAP`. Call this immediately before opening Apple's purchase sheet: it is
    /// what guarantees the user is charged for, and credited with, the amount they were shown,
    /// even if the operator changes the offer mid-purchase. Returns nil if sales have closed.
    public func checkOffer() async throws -> AICoinPinnedOffer? {
        var request = URLRequest(url: Self.endpointURL(baseURL, "/iap/offer/check"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data("{}".utf8)
        let (data, response) = try await transport.data(for: request)
        try Self.requireOK(response, data)
        let decoded = try JSONDecoder().decode(AICoinOfferCheckResponse.self, from: data)
        guard let offer = decoded.offer, let offerID = decoded.offerID else { return nil }
        return AICoinPinnedOffer(offer: offer, offerID: offerID, expiresIn: decoded.expiresIn ?? 0)
    }

    /// `GET /price` — informational market-rate signal only; never changes the fixed
    /// 1-aicoin-per-call spend rate.
    public func price() async throws -> PriceResult {
        let request = URLRequest(url: Self.endpointURL(baseURL, "/price"))
        let (data, response) = try await transport.data(for: request)
        try Self.requireOK(response, data)
        return try JSONDecoder().decode(PriceResult.self, from: data)
    }

    /// `GET /free-coins/available` — the live remaining count in the shared faucet pool.
    public func freeCoinsAvailable() async throws -> Int {
        let request = URLRequest(url: Self.endpointURL(baseURL, "/free-coins/available"))
        let (data, response) = try await transport.data(for: request)
        try Self.requireOK(response, data)
        return try JSONDecoder().decode(FreeCoinsAvailableBody.self, from: data).available
    }

    // MARK: - Live-signed wallet-management actions

    /// `POST /wallet/api/claim` — no body; identity is the signer. Distinguishes `granted` (200)
    /// from `cooldown`/`pool_exhausted` (429) into one `ClaimResult` rather than throwing for the
    /// not-yet-eligible case, since that's an expected, common outcome, not an error condition.
    public func claimFreeCoins(identity: WalletIdentity) async throws -> ClaimResult {
        let request = try Self.signedRequest(method: "POST", path: "/wallet/api/claim", body: Data(), baseURL: baseURL, identity: identity)
        let (data, response) = try await transport.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0

        switch status {
        case 200:
            let body = try JSONDecoder().decode(ClaimGrantedBody.self, from: data)
            return ClaimResult(granted: true, amount: body.amount, reason: nil, nextEligibleAt: Self.parseDate(body.nextEligibleAt))
        case 429:
            let body = try JSONDecoder().decode(ClaimDeniedBody.self, from: data)
            return ClaimResult(granted: false, amount: nil, reason: body.reason, nextEligibleAt: body.nextEligibleAt.flatMap(Self.parseDate))
        default:
            throw try Self.error(for: status, data: data)
        }
    }

    /// `POST /wallet/api/transfer` — live-signed; `from` is always the verified signer, never
    /// trusted from the body, per CONTRACT.md. This is the entire buy/sell mechanism CONTRACT.md
    /// describes: "another way" to acquire aicoin besides the faucet/IAP.
    public func transfer(to address: String, amount: Double, identity: WalletIdentity) async throws -> TransferResult {
        let body = try JSONEncoder().encode(TransferRequestBody(toUserId: address, amount: amount))
        let request = try Self.signedRequest(method: "POST", path: "/wallet/api/transfer", body: body, baseURL: baseURL, identity: identity)
        let (data, response) = try await transport.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw try Self.error(for: status, data: data) }
        let decoded = try? JSONDecoder().decode(TransferSuccessBody.self, from: data)
        return TransferResult(balance: decoded?.balance)
    }

    /// `POST /wallet/api/revoke-tokens` — no body; invalidates every previously issued API token
    /// for this address. Callers should also call `AICoinTokenCache.invalidate()` on their local
    /// cache so a stale token isn't reused for the next AI-provider call.
    public func revokeTokens(identity: WalletIdentity) async throws {
        let request = try Self.signedRequest(method: "POST", path: "/wallet/api/revoke-tokens", body: Data(), baseURL: baseURL, identity: identity)
        let (data, response) = try await transport.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw try Self.error(for: status, data: data) }
    }

    // MARK: - IAP redemption

    /// `POST /wallet/api/redeem-iap` — unsigned (crediting a wallet can't harm anyone; the
    /// security burden is on Apple's JWS, verified server-side). Idempotent server-side on
    /// `transactionId`, so it's safe to call again if a previous attempt's response was lost.
    /// - Parameter offerID: the pin from `checkOffer()`, when the purchase was started against a
    ///   checked offer. Passing it is what makes the server credit the amount the user was shown
    ///   rather than whatever the offer says by the time redemption lands; omitting it (an older
    ///   client, or a redelivery whose pin has since expired) falls back to the live offer.
    public func redeemIAP(to address: String, signedTransaction: String, offerID: String? = nil) async throws -> RedeemResult {
        let body = try JSONEncoder().encode(
            RedeemIAPRequestBody(toUserId: address, signedTransaction: signedTransaction, offerId: offerID))
        var request = URLRequest(url: Self.endpointURL(baseURL, "/wallet/api/redeem-iap"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        let (data, response) = try await transport.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw try Self.error(for: status, data: data) }
        return try JSONDecoder().decode(RedeemResult.self, from: data)
    }

    // MARK: - Plumbing

    /// Joins `path` (which must start with `/`) onto `baseURL` (which is assumed to carry no path
    /// of its own, e.g. `https://proxy.aicoin.oeaio.com`) via `URLComponents` rather than
    /// `appendingPathComponent`, so the resulting `url.path` is exactly `path` — this matters
    /// because `WalletSigner`'s canonical message is signed over that exact path string.
    static func endpointURL(_ baseURL: URL, _ path: String) -> URL {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) ?? URLComponents()
        let existing = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = existing + (path.hasPrefix("/") ? path : "/\(path)")
        return components.url ?? baseURL
    }

    static func signedRequest(method: String, path: String, body: Data, baseURL: URL, identity: WalletIdentity) throws -> URLRequest {
        var request = URLRequest(url: endpointURL(baseURL, path))
        request.httpMethod = method
        if !body.isEmpty {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        try WalletSigner.applyLiveSignature(to: &request, identity: identity)
        return request
    }

    static func requireOK(_ response: URLResponse, _ data: Data) throws {
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw try error(for: status, data: data) }
    }

    static func error(for status: Int, data: Data) throws -> AICoinError {
        if status == 402 {
            return .insufficientBalance(balance: AICoinRouter.parseBalance(from: data))
        }
        let message = (try? JSONDecoder().decode(ServerErrorBody.self, from: data))?.error
        return .server(status: status, message: message)
    }

    /// CONTRACT.md's `next_eligible_at` fields are RFC3339 strings; tolerate both with and
    /// without fractional seconds since the Java server's exact formatter isn't pinned down here.
    static func parseDate(_ string: String) -> Date? {
        let withFractional = ISO8601DateFormatter()
        withFractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = withFractional.date(from: string) { return date }
        return ISO8601DateFormatter().date(from: string)
    }
}
