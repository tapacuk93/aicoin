import XCTest
@testable import AICoinWallet

/// Every entry shape the ledger's Lua scripts write (see `AicoinLedger`), as `cjson.encode` writes
/// it, decodes — one unreadable entry would fail the whole history, not just its own row.
final class TransactionDecodingTests: XCTestCase {
    func testEveryLedgerEntryShapeDecodes() throws {
        let json = """
        {"transactions":[
          {"type":"claim","amount":100,"balance_after":100,"at":1793006319933},
          {"type":"transfer_out","amount":2.5,"counterparty":"\(String(repeating: "a", count: 64))","balance_after":97.5,"at":1793006319934},
          {"type":"transfer_in","amount":1,"counterparty":"\(String(repeating: "b", count: 64))","balance_after":98.5,"at":1793006319935},
          {"type":"debit","amount":0.25,"provider":"anthropic","balance_after":98.25,"at":1793006319936},
          {"type":"debit_settlement","amount":0.5,"owed":0,"provider":"openai","balance_after":97.75,"at":1793006319937},
          {"type":"refund","amount":0.25,"provider":"google","balance_after":98,"at":1793006319938},
          {"type":"iap","amount":500,"product_id":"com.tarasmaslov.alllanguageslearner.aicoin.small","balance_after":598,"at":1793006319939},
          {"type":"admin_credit","amount":10,"reason":"support","balance_after":608,"at":1.793006319940e+12}
        ]}
        """
        struct Response: Decodable { let transactions: [ProxyAPI.Transaction] }
        let decoded = try JSONDecoder().decode(Response.self, from: Data(json.utf8)).transactions
        XCTAssertEqual(decoded.map(\.type),
                       ["claim", "transfer_out", "transfer_in", "debit", "debit_settlement", "refund", "iap", "admin_credit"])
        XCTAssertEqual(decoded[1].counterparty?.count, 64)
        XCTAssertEqual(decoded[3].provider, "anthropic")
        XCTAssertEqual(decoded[7].date.timeIntervalSince1970, 1_793_006_319.94, accuracy: 0.001)
        XCTAssertEqual(Set(decoded.map(\.id)).count, decoded.count, "ids must be unique for List")
    }
}
