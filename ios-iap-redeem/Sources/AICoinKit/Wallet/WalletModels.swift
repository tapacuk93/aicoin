import Foundation

/// `GET /wallet/api/balance/{address}` → `{"user_id":"...","balance":N}`.
struct BalanceResponseBody: Decodable {
    let userId: String
    let balance: Double

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case balance
    }
}

/// Unified result of `POST /wallet/api/claim`, covering both its `200` (granted) and `429`
/// (cooldown / pool exhausted) shapes — CONTRACT.md's free-coin faucet section.
public struct ClaimResult: Sendable, Equatable {
    public let granted: Bool
    public let amount: Double?
    public let reason: String?
    public let nextEligibleAt: Date?
}

struct ClaimGrantedBody: Decodable {
    let granted: Bool
    let amount: Double
    let nextEligibleAt: String

    enum CodingKeys: String, CodingKey {
        case granted, amount
        case nextEligibleAt = "next_eligible_at"
    }
}

struct ClaimDeniedBody: Decodable {
    let granted: Bool
    let reason: String
    let nextEligibleAt: String?

    enum CodingKeys: String, CodingKey {
        case granted, reason
        case nextEligibleAt = "next_eligible_at"
    }
}

struct TransferRequestBody: Codable {
    let toUserId: String
    let amount: Double

    enum CodingKeys: String, CodingKey {
        case toUserId = "to_user_id"
        case amount
    }
}

/// CONTRACT.md documents `/wallet/api/transfer`'s error shape (`400 {"error":"insufficient
/// balance"}`) but not an explicit success body — this package's `balance` field is populated
/// only if the server happens to return one; treat it as advisory display data, not a guarantee,
/// until the deployed server's actual success response is confirmed.
public struct TransferResult: Sendable, Equatable {
    public let balance: Double?
}

struct TransferSuccessBody: Decodable {
    let balance: Double?
}

struct RedeemIAPRequestBody: Codable {
    let toUserId: String
    let signedTransaction: String

    enum CodingKeys: String, CodingKey {
        case toUserId = "to_user_id"
        case signedTransaction = "signed_transaction"
    }
}

/// `POST /wallet/api/redeem-iap` → `200 {"credited":N,"balance":N}`.
public struct RedeemResult: Decodable, Sendable, Equatable {
    public let credited: Double
    public let balance: Double
}

/// `GET /price` → market-rate signal, informational only (never changes the fixed 1-coin-per-call
/// spend rate).
public struct PriceResult: Decodable, Sendable, Equatable {
    public let priceUsd: Double
    public let totalSpendUsd: Double
    public let weightedTotal: Double
    public let halfLifeDays: Double

    enum CodingKeys: String, CodingKey {
        case priceUsd = "price_usd"
        case totalSpendUsd = "total_spend_usd"
        case weightedTotal = "weighted_total"
        case halfLifeDays = "half_life_days"
    }
}

struct FreeCoinsAvailableBody: Decodable {
    let available: Int
}

struct ServerErrorBody: Decodable {
    let error: String
}
