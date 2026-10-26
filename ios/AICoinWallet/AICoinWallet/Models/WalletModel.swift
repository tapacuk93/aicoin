import Foundation

/// What the wallet screen shows about the open wallet, fetched from the proxy: balance, price, the
/// faucet pool and the wallet's own standing. One refresh asks for all four at once.
@MainActor
final class WalletModel: ObservableObject {
    let keys: WalletKeys

    @Published private(set) var balance: Double?
    @Published private(set) var price: ProxyAPI.PriceResponse?
    @Published private(set) var freeClaimsLeft: Int?
    @Published private(set) var standing: ProxyAPI.Reputation?
    /// Set when the balance — the one number the screen is for — could not be fetched, so a
    /// dash on screen says "unknown" rather than passing for an empty wallet.
    @Published private(set) var balanceFailed = false

    init(keys: WalletKeys) {
        self.keys = keys
    }

    var usdValue: Double? {
        guard let balance, let price else { return nil }
        return balance * price.price_usd
    }

    func refresh() async {
        async let balance = try? ProxyAPI.fetchBalance(address: keys.address)
        async let price = try? ProxyAPI.fetchPrice()
        async let pool = try? ProxyAPI.fetchFreeCoinsAvailable()
        async let standing = try? ProxyAPI.fetchReputation(address: keys.address)
        let fetchedBalance = await balance
        if let fetchedBalance { self.balance = fetchedBalance }
        balanceFailed = fetchedBalance == nil
        if let price = await price { self.price = price }
        if let pool = await pool { freeClaimsLeft = pool }
        if let standing = await standing { self.standing = standing }
    }

    func refreshPool() async {
        if let pool = try? await ProxyAPI.fetchFreeCoinsAvailable() { freeClaimsLeft = pool }
    }
}

enum Coins {
    static let symbol = "AIC"

    static func format(_ value: Double) -> String {
        value.formatted(.number.precision(.fractionLength(0...6)))
    }

    static func usd(_ value: Double) -> String {
        let digits = value != 0 && abs(value) < 0.01 ? 4...6 : 2...2
        return value.formatted(.currency(code: "USD").precision(.fractionLength(digits)))
    }

    /// A coin's price: to four significant digits, since it is a fraction of a cent-scale number
    /// where rounding to cents would show $0.01 for anything from half a cent to a cent and a half.
    static func price(_ value: Double) -> String {
        value.formatted(.currency(code: "USD").precision(.significantDigits(2...4)))
    }

    /// `abcd12…ef90` — enough of an address to recognise it, short enough to fit a line.
    static func shortAddress(_ address: String) -> String {
        guard address.count > 12 else { return address }
        return "\(address.prefix(6))…\(address.suffix(4))"
    }

    static func isAddress(_ text: String) -> Bool {
        text.count == 64 && text.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }
}
