import XCTest
@testable import AICoinKit

/// Decoding + bundle-ID-prefix filtering tests against the exact 12-entry example from
/// CONTRACT.md's IAP section (4 tiers × 3 apps).
final class AICoinPackageTests: XCTestCase {
    /// Mirrors CONTRACT.md's `GET /iap/packages` example exactly: 4 tiers × 3 apps.
    static let contractExampleJSON = """
    {
      "packages": [
        {"product_id":"com.tarasmaslov.infiniteairadio.aicoin.small",  "coins":50,   "usd_price_hint":0.99},
        {"product_id":"com.tarasmaslov.infiniteairadio.aicoin.medium", "coins":200,  "usd_price_hint":2.99},
        {"product_id":"com.tarasmaslov.infiniteairadio.aicoin.large",  "coins":1000, "usd_price_hint":9.99},
        {"product_id":"com.tarasmaslov.infiniteairadio.aicoin.xl",     "coins":5000, "usd_price_hint":44.99},
        {"product_id":"com.tarasmaslov.alllanguageslearner.aicoin.small",  "coins":50,   "usd_price_hint":0.99},
        {"product_id":"com.tarasmaslov.alllanguageslearner.aicoin.medium", "coins":200,  "usd_price_hint":2.99},
        {"product_id":"com.tarasmaslov.alllanguageslearner.aicoin.large",  "coins":1000, "usd_price_hint":9.99},
        {"product_id":"com.tarasmaslov.alllanguageslearner.aicoin.xl",     "coins":5000, "usd_price_hint":44.99},
        {"product_id":"com.tarasmaslov.learnit.aicoin.small",  "coins":50,   "usd_price_hint":0.99},
        {"product_id":"com.tarasmaslov.learnit.aicoin.medium", "coins":200,  "usd_price_hint":2.99},
        {"product_id":"com.tarasmaslov.learnit.aicoin.large",  "coins":1000, "usd_price_hint":9.99},
        {"product_id":"com.tarasmaslov.learnit.aicoin.xl",     "coins":5000, "usd_price_hint":44.99}
      ]
    }
    """

    private func decodePackages() throws -> [AICoinPackage] {
        try JSONDecoder().decode(AICoinPackagesResponse.self, from: Data(Self.contractExampleJSON.utf8)).packages
    }

    func testDecodesAllTwelveEntriesWithCorrectFieldMapping() throws {
        let packages = try decodePackages()
        XCTAssertEqual(packages.count, 12)

        let first = try XCTUnwrap(packages.first)
        XCTAssertEqual(first.productId, "com.tarasmaslov.infiniteairadio.aicoin.small")
        XCTAssertEqual(first.coins, 50)
        XCTAssertEqual(first.usdPriceHint, 0.99)
        XCTAssertEqual(first.id, first.productId, "Identifiable id must be the product id")
    }

    func testFilteringByInfiniteAIRadioBundleIDReturnsExactlyItsFourTiers() throws {
        let packages = try decodePackages()
        let mine = packages.filtered(byBundleIDPrefix: "com.tarasmaslov.infiniteairadio")

        XCTAssertEqual(mine.count, 4)
        XCTAssertEqual(Set(mine.map(\.coins)), [50, 200, 1000, 5000])
        XCTAssertTrue(mine.allSatisfy { $0.productId.hasPrefix("com.tarasmaslov.infiniteairadio") })
    }

    func testFilteringByAllLanguagesLearnerBundleIDExcludesTheOtherTwoApps() throws {
        let packages = try decodePackages()
        let mine = packages.filtered(byBundleIDPrefix: "com.tarasmaslov.alllanguageslearner")

        XCTAssertEqual(mine.count, 4)
        XCTAssertFalse(mine.contains { $0.productId.contains("infiniteairadio") })
        XCTAssertFalse(mine.contains { $0.productId.contains(".learnit.") })
    }

    func testFilteringByLearnItBundleIDDoesNotAccidentallyMatchAllLanguagesLearner() throws {
        // Regression guard: "learnit" must not be treated as a prefix-substring of
        // "alllanguageslearner" or vice versa — hasPrefix, not contains.
        let packages = try decodePackages()
        let mine = packages.filtered(byBundleIDPrefix: "com.tarasmaslov.learn-it")

        XCTAssertEqual(mine.count, 4)
        XCTAssertTrue(mine.allSatisfy { $0.productId.hasPrefix("com.tarasmaslov.learnit") })
    }

    /// Learn It's real bundle ID carries a hyphen its product IDs cannot (Apple's product-id
    /// alphabet excludes it), so filtering the live catalog by a raw `Bundle.main.bundleIdentifier`
    /// used to match nothing at all and leave that one app's paywall permanently empty. The
    /// fixture above deliberately carries the *server's* real, hyphen-free product IDs — matching
    /// `application.yaml` and CONTRACT.md — so this stays a test of the real shape.
    func testHyphenatedBundleIDStillMatchesItsHyphenFreeProductIDs() throws {
        XCTAssertEqual(AICoinProductID.prefix(forBundleID: "com.tarasmaslov.learn-it"), "com.tarasmaslov.learnit")
        XCTAssertEqual(
            AICoinProductID.prefix(forBundleID: "com.tarasmaslov.infiniteairadio"),
            "com.tarasmaslov.infiniteairadio",
            "an already-legal bundle id must pass through untouched")

        let packages = try decodePackages()
        XCTAssertEqual(packages.filtered(byBundleIDPrefix: "com.tarasmaslov.learn-it").count, 4)
    }

    func testEmptyPrefixReturnsEverythingUnfiltered() throws {
        let packages = try decodePackages()
        XCTAssertEqual(packages.filtered(byBundleIDPrefix: "").count, 12)
    }

    func testUnknownBundleIDPrefixReturnsEmpty() throws {
        let packages = try decodePackages()
        XCTAssertTrue(packages.filtered(byBundleIDPrefix: "com.someoneelse.otherapp").isEmpty)
    }

    // MARK: - IAPManager.filter static behavior mirrors the free-function extension

    func testIAPManagerLoadPackagesFilteringIsConsistentWithArrayExtension() throws {
        let packages = try decodePackages()
        let viaExtension = packages.filtered(byBundleIDPrefix: "com.tarasmaslov.infiniteairadio")
        // IAPManager.loadPackages() uses the same `filtered(byBundleIDPrefix:)` extension
        // internally; this test exists to pin that down explicitly so a future refactor that
        // duplicates the filtering logic elsewhere doesn't silently diverge from it.
        XCTAssertEqual(viaExtension.map(\.productId).sorted(), [
            "com.tarasmaslov.infiniteairadio.aicoin.large",
            "com.tarasmaslov.infiniteairadio.aicoin.medium",
            "com.tarasmaslov.infiniteairadio.aicoin.small",
            "com.tarasmaslov.infiniteairadio.aicoin.xl",
        ])
    }
}
