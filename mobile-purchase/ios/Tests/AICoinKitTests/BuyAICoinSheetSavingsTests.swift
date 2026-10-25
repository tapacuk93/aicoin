import XCTest
@testable import AICoinKit

/// The per-coin "Save N%" badges on the buy sheet's package rows.
final class BuyAICoinSheetSavingsTests: XCTestCase {
    // The live catalog's shape: 50 / 200 / 1000 / 5000 coins.
    private let packages = [
        AICoinPackage(productId: "s", coins: 50, usdPriceHint: 0.99),
        AICoinPackage(productId: "m", coins: 200, usdPriceHint: 1.99),
        AICoinPackage(productId: "l", coins: 1000, usdPriceHint: 11.99),
        AICoinPackage(productId: "xl", coins: 5000, usdPriceHint: 29.99),
    ]

    private func hintUnit(_ p: AICoinPackage, _ storeKit: Bool) -> Double? {
        storeKit ? nil : p.usdPriceHint / Double(p.coins)
    }

    func testSavingsAreAgainstTheSmallestPackagePerCoin() {
        let savings = BuyAICoinSheet.savings(for: packages, unitPrice: hintUnit)
        XCTAssertNil(savings["s"], "the baseline has nothing to save against")
        XCTAssertEqual(savings["m"], 50)
        XCTAssertEqual(savings["l"], 39)
        XCTAssertEqual(savings["xl"], 70)
    }

    func testTinySavingsAreLeftOut() {
        let flat = [
            AICoinPackage(productId: "a", coins: 100, usdPriceHint: 1.00),
            AICoinPackage(productId: "b", coins: 200, usdPriceHint: 1.98),
        ]
        XCTAssertTrue(BuyAICoinSheet.savings(for: flat, unitPrice: hintUnit).isEmpty)
    }

    func testStoreKitPricesWinOnlyWhenEveryRowHasOne() {
        // One row unresolved in StoreKit: every row falls back to the hint, never a mix.
        let partial: (AICoinPackage, Bool) -> Double? = { p, storeKit in
            if storeKit { return p.productId == "s" ? nil : 0.001 }
            return p.usdPriceHint / Double(p.coins)
        }
        XCTAssertEqual(BuyAICoinSheet.savings(for: packages, unitPrice: partial)["m"], 50)
    }
}
