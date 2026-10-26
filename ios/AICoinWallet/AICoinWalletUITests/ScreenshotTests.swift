import XCTest

/// Captures the App Store screenshot set. Not part of the normal test pass —
/// run it explicitly:
///
///   xcodebuild test -project AICoinWallet.xcodeproj -scheme AICoinWallet \
///     -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' \
///     -only-testing:AICoinWalletUITests/ScreenshotTests \
///     -resultBundlePath /tmp/shots.xcresult
///
/// then pull the PNGs out with
/// `xcrun xcresulttool export attachments --path /tmp/shots.xcresult --output-path <dir>`.
///
/// Like `testClaimingFreeCoinsAgainstTheLiveProxy…`, this drives the real
/// production proxy so the balance and price on screen are genuine.
final class ScreenshotTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    func testCaptureAppStoreScreenshots() throws {
        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET_WALLET"]
        app.launch()

        // 1 — the entry screen, before a wallet exists.
        let create = app.buttons["Create New Wallet"]
        XCTAssertTrue(create.waitForExistence(timeout: 10))
        capture(app, "01-connect")

        create.tap()
        let saved = app.switches["savedToggle"]
        XCTAssertTrue(saved.waitForExistence(timeout: 10))
        let continueButton = app.buttons["continueButton"]
        for _ in 0..<3 where !continueButton.isEnabled {
            saved.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
            _ = continueButton.wait(for: \.isEnabled, toEqual: true, timeout: 2)
        }
        continueButton.tap()

        let balance = app.staticTexts["balanceValue"]
        XCTAssertTrue(balance.waitForExistence(timeout: 15))
        expectation(for: NSPredicate(format: "label == '0'"), evaluatedWith: balance)
        waitForExpectations(timeout: 20)

        capture(app, "02-balance")

        app.buttons["receiveButton"].tap()
        XCTAssertTrue(app.staticTexts["receiveAddress"].waitForExistence(timeout: 5))
        capture(app, "03-receive")
    }
}
