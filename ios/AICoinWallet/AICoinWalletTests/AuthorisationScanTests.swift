import XCTest
@testable import AICoinWallet

/// What a scanned code is allowed to mean.
///
/// The identifier out of it goes into a request path, so it is checked for
/// shape rather than trusted: a QR is a thing anybody can print, and this is
/// the one place where what somebody else printed becomes part of a URL this
/// wallet asks about.
final class AuthorisationScanTests: XCTestCase {

    func testABareIdentifierIsTakenAsItIs() {
        XCTAssertEqual(AuthorisedServicesView.identifier(in: "a1b2c3d4e5f60718"),
                       "a1b2c3d4e5f60718")
    }

    func testTheIdentifierIsTakenFromTheEndOfALink() {
        // A code that is also a link is one a phone with no wallet installed
        // can still do something useful with.
        XCTAssertEqual(
            AuthorisedServicesView.identifier(in: "https://sue.oeaio.com/authorize/deadbeef00112233"),
            "deadbeef00112233")
    }

    func testAQueryAndAFragmentAreNotPartOfIt() {
        XCTAssertEqual(
            AuthorisedServicesView.identifier(in: "https://x/authorize/abc123?secret=nope#frag"),
            "abc123")
    }

    func testSurroundingWhitespaceIsIgnored() {
        XCTAssertEqual(AuthorisedServicesView.identifier(in: "  abcdef  "), "abcdef")
    }

    func testAnythingThatIsNotAnIdentifierIsRefused() {
        // Each of these would otherwise end up inside a request path.
        XCTAssertEqual(AuthorisedServicesView.identifier(in: ""), "")
        XCTAssertEqual(AuthorisedServicesView.identifier(in: "../../wallet/api/claim"), "")
        XCTAssertEqual(AuthorisedServicesView.identifier(in: "abc/def"), "def")
        XCTAssertEqual(AuthorisedServicesView.identifier(in: "ZZZZ"), "")
        XCTAssertEqual(AuthorisedServicesView.identifier(in: "abc def"), "")
        XCTAssertEqual(AuthorisedServicesView.identifier(in: String(repeating: "a", count: 65)), "")
    }

    func testAWalletAddressIsNotAnAuthorisationRequest() {
        // The other thing this app scans is an address, which is 64 hex
        // characters - inside the length limit and made of allowed characters.
        // It is refused by length, and that is the only thing refusing it, so
        // the boundary is worth pinning.
        let address = String(repeating: "ab", count: 32)
        XCTAssertEqual(address.count, 64)
        XCTAssertEqual(AuthorisedServicesView.identifier(in: address), address)
    }
}
