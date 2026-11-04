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

    /// A tap, or under Mac Catalyst a click, which is what reaches a Mac window reliably.
    private func press(_ element: XCUIElement) {
        #if targetEnvironment(macCatalyst)
        element.click()
        #else
        element.tap()
        #endif
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    func testCaptureAppStoreScreenshots() throws {
        let app = XCUIApplication()
        app.launchArguments = ["UITEST_RESET_WALLET", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        // 1 — the entry screen, before a wallet exists.
        let create = app.buttons["Create New Wallet"]
        XCTAssertTrue(create.waitForExistence(timeout: 10))
        capture(app, "01-connect")

        app.activate()
        press(create)
        // A switch on iOS; under Mac Catalyst it may surface as another control type.
        let saved = app.descendants(matching: .any)["savedToggle"].firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 10))
        let continueButton = app.buttons["continueButton"]
        for _ in 0..<3 where !continueButton.isEnabled {
            let spot = saved.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5))
            #if targetEnvironment(macCatalyst)
            spot.click()
            #else
            spot.tap()
            #endif
            _ = continueButton.wait(for: \.isEnabled, toEqual: true, timeout: 2)
        }
        press(continueButton)

        let balance = app.staticTexts["balanceValue"]
        XCTAssertTrue(balance.waitForExistence(timeout: 15))
        expectation(for: NSPredicate(format: "label == '0'"), evaluatedWith: balance)
        waitForExpectations(timeout: 20)

        capture(app, "02-balance")

        press(app.buttons["receiveButton"])
        if app.staticTexts["receiveAddress"].waitForExistence(timeout: 10) {
            capture(app, "03-receive")
        }

        // 4 — apps whose own wallets spend this wallet's coins.
        if app.buttons["Done"].exists { press(app.buttons["Done"]) }
        let linked = app.buttons["Linked Apps"]
        for _ in 0..<4 where !linked.isHittable { app.swipeUp() }
        press(linked)
        XCTAssertTrue(app.staticTexts["No Linked Apps"].waitForExistence(timeout: 15))
        capture(app, "04-linked-apps")
    }
}
