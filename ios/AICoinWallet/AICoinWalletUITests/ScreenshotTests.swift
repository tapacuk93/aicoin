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
        let generate = app.buttons["Generate new wallet"]
        XCTAssertTrue(generate.waitForExistence(timeout: 10))
        capture(app, "01-connect")

        generate.tap()
        // The backup screen shows the private key in the clear — never shipped
        // as a screenshot, so step straight past it.
        let continueButton = app.buttons["I've saved it — continue"]
        let appeared = continueButton.waitForExistence(timeout: 10)
        if !appeared {
            capture(app, "99-diagnostic-after-generate")
            let dump = XCTAttachment(string: app.debugDescription)
            dump.name = "99-hierarchy"
            dump.lifetime = .keepAlways
            add(dump)
        }
        XCTAssertTrue(appeared)
        continueButton.tap()

        // Claim once so the balance card shows a real number rather than 0.
        let claim = app.buttons["claimBtn"]
        XCTAssertTrue(claim.waitForExistence(timeout: 15))
        let balance = app.staticTexts["balanceValue"]
        XCTAssertTrue(balance.waitForExistence(timeout: 10))
        claim.tap()
        expectation(for: NSPredicate(format: "label == '10'"), evaluatedWith: balance)
        waitForExpectations(timeout: 20)

        // 2 — balance, live price, receiving address.
        capture(app, "02-balance")

        // 3 — send coins + API tokens. The whole wallet is only ~1.5 screens,
        // so one swipe already pins the scroll to the bottom; a second swipe
        // captures a byte-identical frame.
        app.swipeUp()
        capture(app, "03-send-and-tokens")
    }
}
