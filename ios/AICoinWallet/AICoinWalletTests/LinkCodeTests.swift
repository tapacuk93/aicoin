import Testing
@testable import AICoinWallet
import Foundation

struct LinkCodeTests {
    private let address = String(repeating: "ab", count: 32)

    @Test func readsAddressAndAppName() {
        let code = LinkCode("aicoin:link?address=\(address)&app=AI%20UI")
        #expect(code == LinkCode(address: address, app: "AI UI"))
    }

    @Test func toleratesWhitespaceAndUppercase() {
        let code = LinkCode("  AICOIN:link?address=\(address.uppercased())&app=AI%20UI\n")
        #expect(code?.address == address)
    }

    @Test func namesAnUnnamedApp() {
        #expect(LinkCode("aicoin:link?address=\(address)")?.app == "An app")
    }

    @Test func refusesWhatIsNotALinkCode() {
        #expect(LinkCode(address) == nil)                                   // a bare address is a payment
        #expect(LinkCode("aicoin:link?address=abc&app=x") == nil)           // malformed address
        #expect(LinkCode("https://example.com/link?address=\(address)") == nil)
        #expect(LinkCode("aicoin:pay?address=\(address)") == nil)
        #expect(LinkCode("https://sue.oeaio.com/authorize/deadbeef00112233") == nil)
    }

    @Test func shortAddressShowsBothEnds() {
        let a = "123456" + String(repeating: "0", count: 52) + "abcdef"
        #expect(LinkCode.short(a) == "123456…abcdef")
    }
}

private extension LinkCode {
    init(address: String, app: String) {
        self.init("aicoin:link?address=\(address)&app=\(app.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!)")!
    }
}
