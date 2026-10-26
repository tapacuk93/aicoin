import XCTest

final class AICoinWalletUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Create → back up → the wallet, with its address and the live balance shown.
    private func createWallet(_ app: XCUIApplication) {
        app.launchArguments = ["UITEST_RESET_WALLET"]
        app.launch()

        let create = app.buttons["Create New Wallet"]
        XCTAssertTrue(create.waitForExistence(timeout: 5))
        create.tap()

        let saved = app.switches["savedToggle"]
        XCTAssertTrue(saved.waitForExistence(timeout: 5))
        let continueButton = app.buttons["continueButton"]
        // The switch at the row's trailing edge: tapping a toggle row's label does not flip it,
        // and a tap during the screen's appearance can be dropped, so it is retried.
        for _ in 0..<3 where !continueButton.isEnabled {
            saved.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
            _ = continueButton.wait(for: \.isEnabled, toEqual: true, timeout: 2)
        }
        XCTAssertTrue(continueButton.isEnabled)
        continueButton.tap()
    }

    func testCreatingAWalletReachesTheWalletWithItsAddressAndBalance() throws {
        let app = XCUIApplication()
        createWallet(app)

        let addressLabel = app.staticTexts["addressLabel"]
        XCTAssertTrue(addressLabel.waitForExistence(timeout: 5))
        XCTAssertEqual(addressLabel.label.count, 64)

        // Fetched from the live proxy: a brand-new wallet holds nothing.
        let balance = app.staticTexts["balanceValue"]
        expectation(for: NSPredicate(format: "label == '0'"), evaluatedWith: balance)
        waitForExpectations(timeout: 15)
    }

    func testReceiveShowsTheSameAddressAsTheWallet() throws {
        let app = XCUIApplication()
        createWallet(app)

        let address = app.staticTexts["addressLabel"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        let expected = address.label
        app.buttons["receiveButton"].tap()
        let shown = app.staticTexts["receiveAddress"]
        XCTAssertTrue(shown.waitForExistence(timeout: 5))
        XCTAssertEqual(shown.label, expected)
        XCTAssertTrue(app.images["addressQRCode"].exists)
    }

    /// Against the live proxy, so it only runs while the shared pool has claims left; with the pool
    /// empty the faucet is not shown at all.
    func testClaimingFreeCoinsAgainstTheLiveProxyUpdatesTheBalance() throws {
        let app = XCUIApplication()
        createWallet(app)

        let claim = app.buttons["claimBtn"]
        guard claim.waitForExistence(timeout: 10) else {
            throw XCTSkip("the free-coin pool is empty, so the faucet is hidden")
        }
        claim.tap()
        let ok = app.alerts.buttons["OK"]
        XCTAssertTrue(ok.waitForExistence(timeout: 15))
        ok.tap()
        let balance = app.staticTexts["balanceValue"]
        expectation(for: NSPredicate(format: "label == '100'"), evaluatedWith: balance)
        waitForExpectations(timeout: 15)
    }
}
