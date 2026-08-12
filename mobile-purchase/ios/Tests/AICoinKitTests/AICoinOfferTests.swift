import XCTest
@testable import AICoinKit

/// Decoding and per-app product selection for the current-offer model (CONTRACT.md, "The current
/// offer"), plus the pin bookkeeping that keeps a redelivered transaction crediting what the user
/// was shown.
final class AICoinOfferTests: XCTestCase {

    /// Mirrors `GET /iap/offer` exactly, as `CoinOffer.toJson` renders it server-side.
    static let offerJSON = """
    {
      "offer": {
        "coins": 350,
        "tier": "large",
        "usd_price": 9.99,
        "product_ids": [
          "com.tarasmaslov.infiniteairadio.aicoin.large",
          "com.tarasmaslov.alllanguageslearner.aicoin.large",
          "com.tarasmaslov.learnit.aicoin.large"
        ],
        "set_at": 1700000000000
      }
    }
    """

    private func decodeOffer() throws -> AICoinOffer {
        try XCTUnwrap(JSONDecoder().decode(AICoinOfferResponse.self, from: Data(Self.offerJSON.utf8)).offer)
    }

    // MARK: - Decoding

    func testDecodesTheContractShape() throws {
        let offer = try decodeOffer()

        XCTAssertEqual(offer.coins, 350)
        XCTAssertEqual(offer.tier, "large")
        XCTAssertEqual(offer.usdPrice, 9.99)
        XCTAssertEqual(offer.productIds.count, 3)
        XCTAssertEqual(offer.setAt, Date(timeIntervalSince1970: 1_700_000_000))
    }

    func testNullOfferMeansSalesAreClosedNotAFailure() throws {
        let decoded = try JSONDecoder().decode(
            AICoinOfferResponse.self, from: Data(#"{"offer":null}"#.utf8))

        XCTAssertNil(decoded.offer)
    }

    func testCheckResponseCarriesThePin() throws {
        let json = """
        {"offer":{"coins":350,"tier":"large","usd_price":9.99,"product_ids":["a.aicoin.large"]},
         "offer_id":"o_7f3","expires_in":900}
        """
        let decoded = try JSONDecoder().decode(AICoinOfferCheckResponse.self, from: Data(json.utf8))

        XCTAssertEqual(decoded.offerID, "o_7f3")
        XCTAssertEqual(decoded.expiresIn, 900)
        XCTAssertEqual(decoded.offer?.coins, 350)
    }

    // MARK: - Per-app product selection

    func testEachAppPicksItsOwnProductOutOfTheOffer() throws {
        let offer = try decodeOffer()

        XCTAssertEqual(
            offer.productID(forBundleID: "com.tarasmaslov.infiniteairadio"),
            "com.tarasmaslov.infiniteairadio.aicoin.large")
        XCTAssertEqual(
            offer.productID(forBundleID: "com.tarasmaslov.alllanguageslearner"),
            "com.tarasmaslov.alllanguageslearner.aicoin.large")
    }

    /// The same hyphen trap as the package catalog: Learn It's bundle ID can't be matched raw.
    func testLearnItsHyphenatedBundleIDResolvesToItsHyphenFreeProduct() throws {
        let offer = try decodeOffer()

        XCTAssertEqual(
            offer.productID(forBundleID: "com.tarasmaslov.learn-it"),
            "com.tarasmaslov.learnit.aicoin.large")
    }

    func testAnAppWithNoProductInTheOfferGetsNothingRatherThanAnotherAppsProduct() throws {
        let offer = try decodeOffer()

        XCTAssertNil(offer.productID(forBundleID: "com.someoneelse.otherapp"))
    }

    func testAnUnknownBundleIDFallsBackToTheFirstProduct() throws {
        // Defensive default matching `filtered(byBundleIDPrefix:)`: an app that couldn't determine
        // its own bundle id shows something rather than a broken empty paywall.
        let offer = try decodeOffer()

        XCTAssertEqual(offer.productID(forBundleID: ""), "com.tarasmaslov.infiniteairadio.aicoin.large")
    }

    // MARK: - Redeem body

    func testRedeemBodyOmitsOfferIDEntirelyWhenThereIsNoPin() throws {
        let body = RedeemIAPRequestBody(toUserId: "addr", signedTransaction: "jws", offerId: nil)

        let json = try XCTUnwrap(String(data: JSONEncoder().encode(body), encoding: .utf8))

        XCTAssertFalse(json.contains("offer_id"), "a pre-offer client's body must stay byte-identical")
    }

    func testRedeemBodyCarriesThePinWhenThereIsOne() throws {
        let body = RedeemIAPRequestBody(toUserId: "addr", signedTransaction: "jws", offerId: "o_7f3")

        let json = try XCTUnwrap(String(data: JSONEncoder().encode(body), encoding: .utf8))

        XCTAssertTrue(json.contains("\"offer_id\":\"o_7f3\""), json)
    }

    // MARK: - Pin store

    private func makeStore() throws -> (OfferPinStore, UserDefaults) {
        let suiteName = "aicoin.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        addTeardownBlock { defaults.removePersistentDomain(forName: suiteName) }
        return (OfferPinStore(defaults: defaults), defaults)
    }

    func testPinSurvivesForARedeliveredTransaction() throws {
        let (store, _) = try makeStore()

        store.record(offerID: "o_7f3", forTransactionID: "tx1")

        XCTAssertEqual(store.offerID(forTransactionID: "tx1"), "o_7f3")
        XCTAssertNil(store.offerID(forTransactionID: "tx2"))
    }

    func testPinIsDroppedOnceItsTransactionIsCredited() throws {
        let (store, _) = try makeStore()
        store.record(offerID: "o_7f3", forTransactionID: "tx1")

        store.clear(transactionID: "tx1")

        XCTAssertNil(store.offerID(forTransactionID: "tx1"))
    }

    func testExpiredPinsAreForgotten() throws {
        let (store, defaults) = try makeStore()
        // Two hours old — past the store's own one-hour horizon, itself well past the server's
        // 15-minute pin TTL, so the entry is worthless and must not be returned.
        defaults.set(["tx1": ["o_old", String(Date().timeIntervalSince1970 - 7200)]], forKey: "aicoin.offerPins")

        XCTAssertNil(store.offerID(forTransactionID: "tx1"))
    }

    func testStoreStaysBounded() throws {
        let (store, _) = try makeStore()

        for i in 0..<50 {
            store.record(offerID: "o_\(i)", forTransactionID: "tx\(i)")
        }

        // Newest survive, oldest are evicted; the most recent write is always readable.
        XCTAssertEqual(store.offerID(forTransactionID: "tx49"), "o_49")
        XCTAssertNil(store.offerID(forTransactionID: "tx0"))
    }
}
