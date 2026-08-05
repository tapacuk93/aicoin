import XCTest

final class AICoinWalletUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testGenerateWalletReachesTheWalletScreenWithAnAddressShown() throws {
        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET_WALLET"]
        app.launch()

        let generateButton = app.buttons["Generate new wallet"]
        XCTAssertTrue(generateButton.waitForExistence(timeout: 5))
        generateButton.tap()

        let continueButton = app.buttons["I've saved it — continue"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5))
        continueButton.tap()

        let addressLabel = app.staticTexts["addressLabel"]
        XCTAssertTrue(addressLabel.waitForExistence(timeout: 5))
        XCTAssertEqual(addressLabel.label.count, 64)

        XCTAssertTrue(app.buttons["claimBtn"].waitForExistence(timeout: 5))
    }

    /// Exercises a real live-signed claim against the production proxy
    /// (proxy.aicoin.oeaio.com) — confirms the app's Ed25519 signing and
    /// networking layer actually interoperate with the real server, not
    /// just unit-tested logic in isolation.
    func testClaimingFreeCoinsAgainstTheLiveProxyUpdatesBalanceAndPool() throws {
        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET_WALLET"]
        app.launch()

        app.buttons["Generate new wallet"].tap()
        let continueButton = app.buttons["I've saved it — continue"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5))
        continueButton.tap()

        let poolLabel = app.staticTexts["freeCoinsRemainingValue"]
        XCTAssertTrue(poolLabel.waitForExistence(timeout: 10))
        XCTAssertTrue(poolLabel.label.contains("free coins left in the pool"))
        XCTAssertFalse(poolLabel.label.contains("\u{2026}"), "pool count never loaded from the live server")

        let claimButton = app.buttons["claimBtn"]
        XCTAssertTrue(claimButton.waitForExistence(timeout: 5))
        claimButton.tap()

        let balanceValue = app.staticTexts["balanceValue"]
        let balanceUpdated = NSPredicate(format: "label == '10'")
        expectation(for: balanceUpdated, evaluatedWith: balanceValue, handler: nil)
        waitForExpectations(timeout: 10)
    }
}
