import Foundation

/// Talks to aicoin-proxy's public and /admin endpoints. Every write goes
/// through here, and every failure comes back as the proxy's own words.
@MainActor
final class AdminClient: ObservableObject {
    static let defaultURL = "https://proxy.aicoin.oeaio.com"

    @Published var baseURL: String {
        didSet { UserDefaults.standard.set(baseURL, forKey: "baseURL") }
    }
    @Published private(set) var hasToken: Bool

    init() {
        baseURL = UserDefaults.standard.string(forKey: "baseURL") ?? Self.defaultURL
        hasToken = Keychain.read() != nil
    }

    func setToken(_ token: String) {
        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { Keychain.delete() } else { Keychain.save(t) }
        hasToken = !t.isEmpty
    }

    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    // MARK: transport

    private func request(_ path: String, method: String = "GET", body: Any? = nil,
                         admin: Bool = false) async throws -> Data {
        guard let url = URL(string: baseURL.trimmingCharacters(in: .whitespaces) + path) else {
            throw Failure(message: "The proxy address is not a URL")
        }
        var req = URLRequest(url: url, timeoutInterval: 30)
        req.httpMethod = method
        if admin {
            guard let token = Keychain.read() else {
                throw Failure(message: "No admin token yet - add it in Settings")
            }
            req.setValue(token, forHTTPHeaderField: "X-Admin-Token")
        }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let said = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw Failure(message: "\(status): \(said ?? String(decoding: data.prefix(200), as: UTF8.self))")
        }
        return data
    }

    private func get<T: Decodable>(_ path: String, admin: Bool = false) async throws -> T {
        try JSONDecoder().decode(T.self, from: await request(path, admin: admin))
    }

    // MARK: reads

    func budget() async throws -> Budget { try await get("/admin/budget", admin: true) }
    func price() async throws -> Price { try await get("/price") }
    func health() async throws -> Health { try await get("/health") }
    func packages() async throws -> [Package] { (try await get("/iap/packages") as Packages).packages }
    func offer() async throws -> Offer? { (try await get("/iap/offer") as OfferEnvelope).offer }
    /// One page of wallets, richest first, filtered by address substring.
    func wallets(offset: Int, limit: Int, query: String) async throws -> Wallets {
        var c = URLComponents()
        c.queryItems = [URLQueryItem(name: "offset", value: String(offset)),
                        URLQueryItem(name: "limit", value: String(limit))]
        if !query.isEmpty { c.queryItems?.append(URLQueryItem(name: "q", value: query)) }
        return try await get("/admin/wallets?" + (c.percentEncodedQuery ?? ""), admin: true)
    }
    func transactions(_ address: String) async throws -> [Transaction] {
        let a = address.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? address
        return (try await get("/admin/wallets/\(a)/transactions", admin: true) as Transactions).transactions
    }
    func activity() async throws -> [ActivityEntry] {
        (try await get("/admin/activity", admin: true) as Activity).activity
    }

    // MARK: writes

    /// The spend ceiling. 0 removes it.
    func setBudget(usd: Double) async throws {
        _ = try await request("/admin/budget", method: "POST", body: ["usd": usd], admin: true)
    }

    /// The single offer every app sells; coins 0 closes sales.
    func setOffer(coins: Int, usdPrice: Double?) async throws {
        var body: [String: Any] = ["coins": coins]
        if let usdPrice { body["usd_price"] = usdPrice }
        _ = try await request("/admin/iap/offer", method: "POST", body: body, admin: true)
    }

    /// Overwrites the whole catalog - every app reads it.
    func setPackages(_ packages: [Package]) async throws {
        let body = packages.map { p -> [String: Any] in
            var d: [String: Any] = ["product_id": p.product_id, "coins": p.coins]
            if let h = p.usd_price_hint { d["usd_price_hint"] = h }
            return d
        }
        _ = try await request("/admin/iap/packages", method: "POST", body: body, admin: true)
    }

    func credit(address: String, amount: Double, reason: String) async throws {
        _ = try await request("/admin/credit", method: "POST",
                              body: ["address": address, "amount": amount, "reason": reason], admin: true)
    }

    // MARK: per-app caps

    func apps() async throws -> [AppCap] { (try await get("/admin/apps", admin: true) as AppCaps).apps }

    /// Sets an app's daily cap (nil removes it) and, when given, its wallets.
    func putApp(_ name: String, usdPerDay: Double?, wallets: [String]?) async throws {
        var body: [String: Any] = ["usd_per_day": usdPerDay.map { $0 as Any } ?? NSNull()]
        if let wallets { body["wallets"] = wallets }
        _ = try await request("/admin/apps/\(name)", method: "PUT", body: body, admin: true)
    }

    func deleteApp(_ name: String) async throws {
        _ = try await request("/admin/apps/\(name)", method: "DELETE", admin: true)
    }

    // MARK: alerts

    func alerts() async throws -> AlertsInfo { try await get("/admin/alerts", admin: true) }

    func registerDevice(token: String, environment: String, disabled: [String]) async throws {
        _ = try await request("/admin/devices", method: "POST",
                              body: ["token": token, "environment": environment, "disabled": disabled], admin: true)
    }

    func testPush() async throws -> [String] {
        let data = try await request("/admin/alerts/test", method: "POST", body: [String: Any](), admin: true)
        return (try? JSONDecoder().decode(TestPush.self, from: data))?.results ?? []
    }

    func internalWallets(add: [String] = [], remove: [String] = []) async throws {
        var body: [String: Any] = [:]
        if !add.isEmpty { body["add"] = add }
        if !remove.isEmpty { body["remove"] = remove }
        _ = try await request("/admin/internal-wallets", method: "POST", body: body, admin: true)
    }
}

// MARK: shapes, as the proxy writes them (see CONTRACT.md)

struct Budget: Decodable {
    let budget_usd: Double?
    let spend_usd: Double
    let internal_spend_usd: Double?
    let remaining_usd: Double?
    let exhausted: Bool
}

struct Price: Decodable {
    let price_usd: Double
    let total_spend_usd: Double
    let half_life_days: Double?
}

struct Health: Decodable { let providers: [Provider] }
struct Provider: Decodable, Identifiable {
    let name: String
    let enabled: Bool
    let healthy: Bool
    let rateLimited: Bool?
    let overBudget: Bool?
    let state: String?
    let detail: String?
    let latencyMs: Int?
    var id: String { name }

    /// The proxy saw a paid call refused because the provider account cannot pay.
    var isOutOfCredit: Bool { state == "out_of_credit" }
    /// Green only when recent calls were fine and neither the probe nor real calls say otherwise.
    var isWorking: Bool { healthy && state != "down" && !isOutOfCredit }
}

struct Packages: Decodable { let packages: [Package] }
struct Package: Codable, Identifiable, Hashable {
    var product_id: String
    var coins: Int
    var usd_price_hint: Double?
    var id: String { product_id }
    /// The app a product belongs to: everything before ".aicoin.".
    var app: String {
        product_id.components(separatedBy: ".aicoin.").first?.components(separatedBy: ".").last ?? product_id
    }
    var tier: String { product_id.components(separatedBy: ".").last ?? "" }
}

struct OfferEnvelope: Decodable { let offer: Offer? }
struct Offer: Decodable {
    let coins: Int
    let tier: String?
    let usd_price: Double?
    let set_at: Double?
}

struct Wallets: Decodable {
    /// Absent from a proxy that predates paging, which sends every wallet at once.
    let total: Int?
    let wallets: [WalletSummary]
}
struct WalletSummary: Decodable, Identifiable, Hashable {
    let address: String
    let balance: Double
    let transaction_count: Int
    var id: String { address }
}

struct Transactions: Decodable { let transactions: [Transaction] }
struct Transaction: Decodable, Hashable {
    let type: String
    let amount: Double?
    let at: Double?
    let balance_after: Double?
    let provider: String?
    let reason: String?
    var date: Date? { at.map { Date(timeIntervalSince1970: $0 / 1000) } }
}

struct Activity: Decodable { let activity: [ActivityEntry] }
struct ActivityEntry: Decodable, Hashable {
    let address: String
    let tx: Transaction
}

struct AppCaps: Decodable { let apps: [AppCap] }
struct AppCap: Decodable, Identifiable, Hashable {
    let app: String
    let usd_per_day: Double?
    let spent_today_usd: Double
    let remaining_usd: Double?
    let capped: Bool
    let reset_at: String
    let wallets: [String]
    var id: String { app }
    var resetDate: Date? { ISO8601DateFormatter().date(from: reset_at) }
}

struct AlertsInfo: Decodable {
    let apns_configured: Bool
    let apns_key_id: String?
    let kinds: [AlertKind]
    let recent: [AlertEntry]
}
struct AlertKind: Decodable, Identifiable, Hashable {
    let id: String
    let label: String
    let debounce_minutes: Int
}
struct AlertEntry: Decodable, Hashable {
    let kind: String
    let title: String
    let body: String
    let at: String
    let devices: Int?
    let pushed: Int?
    var date: Date? { ISO8601DateFormatter.withFraction.date(from: at) ?? ISO8601DateFormatter().date(from: at) }
}
struct TestPush: Decodable { let results: [String] }

extension ISO8601DateFormatter {
    static let withFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}

extension String {
    /// A wallet address short enough to read: the first and last six.
    var shortAddress: String { count > 14 ? "\(prefix(6))…\(suffix(6))" : self }
}
