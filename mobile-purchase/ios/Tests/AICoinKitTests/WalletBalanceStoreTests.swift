import XCTest
import Combine
@testable import AICoinKit

/// Fails its first `failures` calls with a network error, then serves `body`
/// with a 200 — the shape of the blip this store is meant to survive (no
/// signal at launch, a DNS hiccup, a request cut short by backgrounding).
private final class FlakyTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var remainingFailures: Int
    private let body: String
    private var _callCount = 0

    init(failures: Int, thenServing body: String) {
        self.remainingFailures = failures
        self.body = body
    }

    var callCount: Int { lock.withLock { _callCount } }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        try lock.withLock {
            _callCount += 1
            if remainingFailures > 0 {
                remainingFailures -= 1
                throw URLError(.notConnectedToInternet)
            }
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
        return (Data(body.utf8), response)
    }
}

@MainActor
final class WalletBalanceStoreTests: XCTestCase {
    private let address = String(repeating: "aa", count: 32)
    private let baseURL = URL(string: "https://proxy.aicoin.oeaio.com")!

    private var originalDelays: [TimeInterval] = []

    override func setUp() {
        super.setUp()
        originalDelays = WalletBalanceStore.retryDelays
        WalletBalanceStore.retryDelays = [0.02, 0.02, 0.02]
    }

    override func tearDown() {
        WalletBalanceStore.retryDelays = originalDelays
        super.tearDown()
    }

    private func store(_ transport: HTTPTransport) -> WalletBalanceStore {
        WalletBalanceStore(
            address: address,
            walletClient: WalletClient(baseURL: baseURL, transport: transport),
            // A fresh bus, not `.shared`: otherwise an event published by any
            // other test refreshes this store behind the assertions below.
            eventBus: AICoinEventBus()
        )
    }

    /// The regression this whole retry cycle exists for: one failed read used
    /// to leave `balance` nil — the badge's "—" state — for the rest of the
    /// session, because `CoinBalanceBadge` reads once from `.task` and nothing
    /// else re-read a balance until an event bus message happened to arrive.
    func testAFailedReadRetriesItselfUntilItSucceeds() async throws {
        let transport = FlakyTransport(failures: 1, thenServing: #"{"user_id":"\#(address)","balance":7}"#)
        let store = store(transport)

        await store.refresh()
        XCTAssertNil(store.balance, "the first read failed, so there should be no balance yet")
        XCTAssertNotNil(store.lastError)

        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(store.balance, 7, "the scheduled retry should have filled the balance in")
        XCTAssertNil(store.lastError, "a successful retry has to clear the error it recovered from")
    }

    /// The schedule is finite by design — it survives a blip, it does not sit
    /// there hammering a proxy that is genuinely down.
    func testRetriesStopAfterTheScheduleIsSpent() async throws {
        let transport = FlakyTransport(failures: .max, thenServing: "{}")
        let store = store(transport)

        await store.refresh()
        try await Task.sleep(nanoseconds: 300_000_000)
        let settled = transport.callCount
        XCTAssertEqual(settled, 1 + WalletBalanceStore.retryDelays.count,
                       "expected the initial read plus exactly one attempt per scheduled delay")

        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(transport.callCount, settled, "the store kept retrying after its schedule ran out")
        XCTAssertNil(store.balance)
    }

    /// A retry cycle that has already run to exhaustion must not block a later
    /// one — otherwise the first bad launch would disarm the recovery path for
    /// the rest of the session, which is the very thing being fixed.
    func testAnExhaustedCycleStillAllowsALaterRetry() async throws {
        let transport = FlakyTransport(failures: 4, thenServing: #"{"user_id":"\#(address)","balance":3}"#)
        let store = store(transport)

        await store.refresh()
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertNil(store.balance, "all four attempts of this cycle were served failures")

        // Stands in for a foregrounding or an event-bus refresh arriving later.
        await store.refresh()
        XCTAssertEqual(store.balance, 3)
    }

    /// A refresh that succeeds outright should leave nothing scheduled behind
    /// it — the happy path must not quietly spawn a retry cycle.
    func testASuccessfulReadSchedulesNothing() async throws {
        let transport = FlakyTransport(failures: 0, thenServing: #"{"user_id":"\#(address)","balance":42}"#)
        let store = store(transport)

        await store.refresh()
        XCTAssertEqual(store.balance, 42)

        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(transport.callCount, 1, "a successful read should not have been followed by a retry")
    }
}
