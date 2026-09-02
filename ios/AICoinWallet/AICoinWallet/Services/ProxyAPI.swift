import Foundation

/// Talks to the aicoin-proxy server's wallet/price endpoints. Mirrors
/// wallet.html's `fetchJson`/`signedFetch` and CONTRACT.md's exact request/
/// response shapes — see aicoin-proxy/src/main/java/com/aicoin/proxy/
/// ProxyFrontendHandler.java for the server-side source of truth.
enum ProxyAPI {
    static let baseURL = URL(string: "https://proxy.aicoin.oeaio.com")!

    struct APIError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    // ---- unauthenticated reads ----

    struct PriceResponse: Decodable {
        let price_usd: Double
        let total_spend_usd: Double
    }

    static func fetchPrice() async throws -> PriceResponse {
        let (data, response) = try await URLSession.shared.data(from: baseURL.appendingPathComponent("price"))
        try requireOK(response, data)
        return try JSONDecoder().decode(PriceResponse.self, from: data)
    }

    struct AvailableResponse: Decodable {
        let available: Int
    }

    static func fetchFreeCoinsAvailable() async throws -> Int {
        let (data, response) = try await URLSession.shared.data(from: baseURL.appendingPathComponent("free-coins/available"))
        try requireOK(response, data)
        return try JSONDecoder().decode(AvailableResponse.self, from: data).available
    }

    struct BalanceResponse: Decodable {
        let user_id: String
        let balance: Double
    }

    static func fetchBalance(address: String) async throws -> Double {
        let url = baseURL.appendingPathComponent("wallet/api/balance/\(address)")
        let (data, response) = try await URLSession.shared.data(from: url)
        try requireOK(response, data)
        return try JSONDecoder().decode(BalanceResponse.self, from: data).balance
    }

    /// A wallet's standing: what it owes, what it has been caught at, and a rating out of five —
    /// with the reasons, which are the part worth showing.
    struct Reputation: Decodable {
        let address: String
        let balance: Double
        let owed: Double
        let double_spends: Int
        let rating: Int
        let purchases: Int
        let calls: Int
        let reasons: [String]
    }

    static func fetchReputation(address: String) async throws -> Reputation {
        let url = baseURL.appendingPathComponent("wallet/api/reputation/\(address)")
        let (data, response) = try await URLSession.shared.data(from: url)
        try requireOK(response, data)
        return try JSONDecoder().decode(Reputation.self, from: data)
    }

    // ---- live-signed wallet-management actions ----

    enum ClaimOutcome {
        case granted(amount: Double)
        case cooldown(nextEligibleAt: String)
        case poolExhausted
    }

    static func claim(keys: WalletKeys) async throws -> ClaimOutcome {
        let path = "/wallet/api/claim"
        let headers = try WalletSigner.signLiveRequest(keys: keys, method: "POST", path: path, body: Data())
        let (data, response) = try await send(method: "POST", path: path, body: Data(), extraHeaders: headers.httpHeaders)
        let httpResponse = response as? HTTPURLResponse
        let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]

        if httpResponse?.statusCode == 200, let json, json["granted"] as? Bool == true {
            return .granted(amount: (json["amount"] as? Double) ?? 0)
        }
        if httpResponse?.statusCode == 429, let json, (json["reason"] as? String) == "pool_exhausted" {
            return .poolExhausted
        }
        if httpResponse?.statusCode == 429, let json, let nextEligibleAt = json["next_eligible_at"] as? String {
            return .cooldown(nextEligibleAt: nextEligibleAt)
        }
        throw APIError(message: (json?["error"] as? String) ?? "Claim failed.")
    }

    static func transfer(keys: WalletKeys, to: String, amount: Double) async throws {
        let path = "/wallet/api/transfer"
        let bodyString = "{\"to_user_id\":\"\(to)\",\"amount\":\(amount)}"
        let body = Data(bodyString.utf8)
        let headers = try WalletSigner.signLiveRequest(keys: keys, method: "POST", path: path, body: body)
        let (data, response) = try await send(method: "POST", path: path, body: body, extraHeaders: headers.httpHeaders)
        try requireOK(response, data)
    }

    static func revokeTokens(keys: WalletKeys) async throws {
        let path = "/wallet/api/revoke-tokens"
        let headers = try WalletSigner.signLiveRequest(keys: keys, method: "POST", path: path, body: Data())
        let (data, response) = try await send(method: "POST", path: path, body: Data(), extraHeaders: headers.httpHeaders)
        try requireOK(response, data)
    }

    // ---- plumbing ----

    private static func send(method: String, path: String, body: Data, extraHeaders: [String: String]) async throws -> (Data, URLResponse) {
        guard let url = URL(string: baseURL.absoluteString + path) else {
            throw APIError(message: "invalid request path")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in extraHeaders {
            request.setValue(value, forHTTPHeaderField: name)
        }
        if !body.isEmpty {
            request.httpBody = body
        }
        return try await URLSession.shared.data(for: request)
    }

    private static func requireOK(_ response: URLResponse, _ data: Data) throws {
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            let message = (json?["error"] as? String) ?? "Request failed."
            throw APIError(message: message)
        }
    }
}
