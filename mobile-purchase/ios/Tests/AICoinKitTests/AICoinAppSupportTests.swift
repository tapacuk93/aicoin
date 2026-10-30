import XCTest
@testable import AICoinKit

/// Answers every request with a handler's response and records what it was asked — enough to see
/// which host a call went to and which headers it carried.
private final class RecordingTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [URLRequest] = []
    private let handler: @Sendable (URLRequest) throws -> (Data, URLResponse)

    init(_ handler: @escaping @Sendable (URLRequest) throws -> (Data, URLResponse)) {
        self.handler = handler
    }

    var requests: [URLRequest] { lock.withLock { _requests } }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        lock.withLock { _requests.append(request) }
        return try handler(request)
    }
}

private func reply(_ body: String, status: Int = 200, for request: URLRequest) -> (Data, URLResponse) {
    AICoinUITestTransport.json(body, status: status, url: request.url!)
}

private func status(_ response: URLResponse) -> Int {
    (response as? HTTPURLResponse)?.statusCode ?? -1
}

private let anthropicURL = URL(string: "https://api.anthropic.com/v1/messages")!

final class AICoinAppSupportTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "AICoinAppSupportTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func configuration(bundleID: String = "com.oeaio.infiniteaibook") -> AICoinAppRuntime.Configuration {
        .init(keychainService: "\(bundleID).aicoin", defaultsKeyPrefix: "infiniteaibook", bundleID: bundleID)
    }

    /// A runtime that never touches the Keychain: a fixed wallet, and a reconcile that answers
    /// with whatever the test says.
    private func runtime(
        underlying: any HTTPTransport,
        hooks: AICoinUITestHooks = .inactive,
        eventBus: AICoinEventBus = AICoinEventBus(),
        identity: WalletIdentity = .generate(),
        reconcileTo shared: WalletIdentity? = nil
    ) -> AICoinAppRuntime {
        AICoinAppRuntime(
            configuration: configuration(),
            underlying: underlying,
            hooks: hooks,
            eventBus: eventBus,
            directTransport: nil,
            uiTestFakeAI: nil,
            identity: identity,
            reconciler: { _, _ in shared }
        )
    }

    // MARK: - Configuration

    func testFaucetKeyFollowsThePrefixUnlessALegacyKeyIsGiven() {
        XCTAssertEqual(configuration().faucetKey, "infiniteaibook_aicoin_faucet_claim_attempted_v1")
        var legacy = configuration()
        legacy.faucetDefaultsKey = "settings.aicoinFaucetClaimAttempted"
        XCTAssertEqual(legacy.faucetKey, "settings.aicoinFaucetClaimAttempted")
    }

    func testProductIDPrefixDropsWhatAppleForbids() {
        XCTAssertEqual(configuration(bundleID: "com.tarasmaslov.learn-it").productIDPrefix, "com.tarasmaslov.learnit")
        XCTAssertEqual(configuration().productIDPrefix, "com.oeaio.infiniteaibook")
    }

    // MARK: - AICoinFaucet

    func testFaucetClaimsOnceAndThenNeverAgain() async {
        let transport = RecordingTransport { reply(#"{"granted":true,"amount":5,"next_eligible_at":"2026-10-18T12:00:00Z"}"#, for: $0) }
        let client = WalletClient(transport: transport)
        let identity = WalletIdentity.generate()

        let first = await AICoinFaucet.claimIfNeeded(walletClient: client, identity: identity, defaults: defaults, key: "k")
        let second = await AICoinFaucet.claimIfNeeded(walletClient: client, identity: identity, defaults: defaults, key: "k")

        XCTAssertEqual(first?.granted, true)
        XCTAssertNil(second)
        XCTAssertEqual(transport.requests.count, 1)
        XCTAssertTrue(defaults.bool(forKey: "k"))
    }

    func testADenialIsAVerdictTooAndIsNotAskedAgain() async {
        let transport = RecordingTransport { reply(#"{"granted":false,"reason":"cooldown"}"#, status: 429, for: $0) }
        let client = WalletClient(transport: transport)

        let first = await AICoinFaucet.claimIfNeeded(walletClient: client, identity: .generate(), defaults: defaults, key: "k")
        _ = await AICoinFaucet.claimIfNeeded(walletClient: client, identity: .generate(), defaults: defaults, key: "k")

        XCTAssertEqual(first?.granted, false)
        XCTAssertEqual(transport.requests.count, 1)
    }

    func testAnEmptyPoolIsAskedAgainNextLaunch() async {
        let transport = RecordingTransport { reply(#"{"granted":false,"reason":"pool_exhausted"}"#, status: 429, for: $0) }
        let client = WalletClient(transport: transport)

        _ = await AICoinFaucet.claimIfNeeded(walletClient: client, identity: .generate(), defaults: defaults, key: "k")
        _ = await AICoinFaucet.claimIfNeeded(walletClient: client, identity: .generate(), defaults: defaults, key: "k")

        XCTAssertEqual(transport.requests.count, 2)
        XCTAssertFalse(defaults.bool(forKey: "k"))
    }

    func testAClaimThatNeverReachedTheServerIsRetriedNextTime() async {
        let transport = RecordingTransport { _ in throw URLError(.notConnectedToInternet) }
        let client = WalletClient(transport: transport)

        let first = await AICoinFaucet.claimIfNeeded(walletClient: client, identity: .generate(), defaults: defaults, key: "k")
        _ = await AICoinFaucet.claimIfNeeded(walletClient: client, identity: .generate(), defaults: defaults, key: "k")

        XCTAssertNil(first)
        XCTAssertFalse(defaults.bool(forKey: "k"))
        XCTAssertEqual(transport.requests.count, 2)
    }

    func testADisabledFaucetNeitherCallsNorTouchesTheFlag() async {
        let transport = RecordingTransport { reply(#"{"granted":true,"amount":5,"next_eligible_at":"x"}"#, for: $0) }

        let result = await AICoinFaucet.claimIfNeeded(
            walletClient: WalletClient(transport: transport), identity: .generate(),
            defaults: defaults, key: "k", disabled: true
        )

        XCTAssertNil(result)
        XCTAssertTrue(transport.requests.isEmpty)
        XCTAssertNil(defaults.object(forKey: "k"))
    }

    // MARK: - AICoinAppRuntime

    func testProviderCallsAreRehostedWithTheCurrentWalletsToken() async throws {
        let transport = RecordingTransport { reply("{}", for: $0) }
        let rt = runtime(underlying: transport)

        _ = try await rt.transport.data(for: URLRequest(url: anthropicURL))

        let sent = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(sent.url?.host, AICoinConfig.defaultBaseURL.host)
        XCTAssertEqual(sent.value(forHTTPHeaderField: "X-AI"), "anthropic")
        let token = try XCTUnwrap(sent.value(forHTTPHeaderField: "X-Api-Key"))
        XCTAssertEqual(try WalletSigner.decodeTokenPayload(token).addr, rt.identity.address)
    }

    func testTheTokenIsCachedUntilTheWalletChanges() async throws {
        let transport = RecordingTransport { reply("{}", for: $0) }
        let first = WalletIdentity.generate()
        let rt = runtime(underlying: transport, identity: first)

        let token = rt.currentToken()
        XCTAssertNotNil(token)
        XCTAssertEqual(rt.currentToken(), token, "a second read should come from the cache, not a re-sign")
        let cache = rt.tokenCache

        let second = WalletIdentity.generate()
        rt.use(second)

        XCTAssertEqual(rt.identity, second)
        XCTAssertFalse(rt.tokenCache === cache, "identity and token cache swap together")
        let swapped = try XCTUnwrap(rt.currentToken())
        XCTAssertNotEqual(swapped, token)
        XCTAssertEqual(try WalletSigner.decodeTokenPayload(swapped).addr, second.address)

        // And the transport built at launch presents the new wallet's token on the next call.
        _ = try await rt.transport.data(for: URLRequest(url: anthropicURL))
        XCTAssertEqual(transport.requests.last?.value(forHTTPHeaderField: "X-Api-Key"), swapped)
    }

    func testReconcileSwitchesToTheSharedWallet() async {
        let shared = WalletIdentity.generate()
        let rt = runtime(underlying: RecordingTransport { reply("{}", for: $0) }, reconcileTo: shared)

        let switched = await rt.reconcile()

        XCTAssertTrue(switched)
        XCTAssertEqual(rt.identity, shared)
    }

    func testReconcileKeepingTheWalletReportsNoSwitch() async {
        let mine = WalletIdentity.generate()
        let rt = runtime(underlying: RecordingTransport { reply("{}", for: $0) }, identity: mine, reconcileTo: nil)

        let switched = await rt.reconcile()

        XCTAssertFalse(switched)
        XCTAssertEqual(rt.identity, mine)
    }

    func testReconcileNeverRunsUnderTheUITestHooks() async {
        let mine = WalletIdentity.generate()
        let rt = runtime(
            underlying: RecordingTransport { reply("{}", for: $0) },
            hooks: AICoinUITestHooks(environment: ["UITEST_AICOIN_DISABLE_FAUCET": "1"]),
            identity: mine,
            reconcileTo: .generate()
        )

        let switched = await rt.reconcile()

        XCTAssertFalse(switched)
        XCTAssertEqual(rt.identity, mine)
    }

    func testUnderTheHooksNothingReachesTheRealNetwork() async throws {
        let real = RecordingTransport { reply("{}", for: $0) }
        let rt = runtime(underlying: real, hooks: AICoinUITestHooks(environment: ["UITEST_AICOIN_BALANCE": "1234"]))

        let balance = try await rt.walletClient.balance(address: rt.identity.address)
        do {
            _ = try await rt.transport.data(for: URLRequest(url: anthropicURL))
            XCTFail("a provider call under the hooks should read as offline")
        } catch {}

        XCTAssertEqual(balance, 1234)
        XCTAssertTrue(real.requests.isEmpty)
    }

    func testADirectTransportReplacesTheRouterButNotUnderTheHooks() async throws {
        let direct = RecordingTransport { reply("{}", for: $0) }
        let make = { (hooks: AICoinUITestHooks) in
            AICoinAppRuntime(
                configuration: self.configuration(), underlying: RecordingTransport { reply("{}", for: $0) },
                hooks: hooks, eventBus: AICoinEventBus(), directTransport: direct, identity: .generate()
            )
        }

        _ = try await make(.inactive).transport.data(for: URLRequest(url: anthropicURL))
        XCTAssertEqual(direct.requests.count, 1)

        _ = try? await make(AICoinUITestHooks(environment: ["UITEST_AICOIN_DISABLE_FAUCET": "1"]))
            .transport.data(for: URLRequest(url: anthropicURL))
        XCTAssertEqual(direct.requests.count, 1)
    }

    // MARK: - AICoinUITestHooks

    func testHooksParseTheEnvironment() {
        let hooks = AICoinUITestHooks(environment: [
            "UITEST_AICOIN_BALANCE": "12.5",
            "UITEST_AICOIN_DISABLE_FAUCET": "1",
            "UITEST_AICOIN_FORCE_INSUFFICIENT": "1",
            "UITEST_AICOIN_FAKE_AI": "1",
        ])
        XCTAssertEqual(hooks.balance, 12.5)
        XCTAssertTrue(hooks.faucetDisabled)
        XCTAssertTrue(hooks.forceInsufficient)
        XCTAssertTrue(hooks.fakeAI)
        XCTAssertTrue(hooks.isActive)
    }

    func testHooksAreInertWithoutTheirVariables() {
        let hooks = AICoinUITestHooks(environment: ["UITEST_AICOIN_FAKE_AI": "0", "UITEST_AICOIN_BALANCE": "lots", "PATH": "/bin"])
        XCTAssertNil(hooks.balance)
        XCTAssertFalse(hooks.fakeAI)
        XCTAssertFalse(hooks.isActive)
        XCTAssertFalse(AICoinUITestHooks.inactive.isActive)
    }

    func testDisablingTheFaucetAloneTakesTheAppOffline() {
        XCTAssertTrue(AICoinUITestHooks(environment: ["UITEST_AICOIN_DISABLE_FAUCET": "1"]).isActive)
    }

    // MARK: - AICoinUITestTransport

    private let proxy = AICoinConfig.defaultBaseURL

    func testStubAnswersBalanceAndPackages() async throws {
        let stub = AICoinUITestTransport(hooks: AICoinUITestHooks(environment: ["UITEST_AICOIN_BALANCE": "77"]),
                                         productIDPrefix: "com.oeaio.infiniteaibook")
        let client = WalletClient(transport: stub)

        let balance = try await client.balance(address: "ab")
        let packages = try await client.iapPackages()

        XCTAssertEqual(balance, 77)
        XCTAssertEqual(packages.map(\.productId), ["small", "medium", "large", "xl"].map { "com.oeaio.infiniteaibook.aicoin.\($0)" })
        XCTAssertEqual(packages.map(\.coins), [50, 200, 1000, 5000])
    }

    func testStubNeverMintsAndNeverMovesCoins() async throws {
        let stub = AICoinUITestTransport(hooks: AICoinUITestHooks(environment: ["UITEST_AICOIN_DISABLE_FAUCET": "1"]),
                                         productIDPrefix: "p")
        let claim = try await WalletClient(transport: stub).claimFreeCoins(identity: .generate())
        XCTAssertFalse(claim.granted)

        let (_, response) = try await stub.data(for: URLRequest(url: proxy.appendingPathComponent("wallet/api/transfer")))
        XCTAssertEqual(status(response), 400)
    }

    func testForcedInsufficientBalanceSurfacesThroughTheRouter() async {
        let bus = AICoinEventBus()
        var events: [AICoinEvent] = []
        let subscription = bus.events.sink { events.append($0) }
        defer { subscription.cancel() }
        let stub = AICoinUITestTransport(
            hooks: AICoinUITestHooks(environment: ["UITEST_AICOIN_FORCE_INSUFFICIENT": "1", "UITEST_AICOIN_FAKE_AI": "1"]),
            productIDPrefix: "p",
            fakeAI: { reply("should not be used", for: $0) }
        )
        let router = AICoinRouter(underlying: stub, eventBus: bus, tokenProvider: { "token" })

        do {
            _ = try await router.data(for: URLRequest(url: anthropicURL))
            XCTFail("expected insufficientBalance")
        } catch {
            XCTAssertEqual(error as? AICoinError, .insufficientBalance(balance: 0))
        }
        XCTAssertEqual(events, [.insufficientBalance(balance: 0)])
    }

    func testFakeAIAnswersPaidCallsOnlyWhenItsHookIsSet() async throws {
        let canned: @Sendable (URLRequest) -> (Data, URLResponse)? = { reply(#"{"content":[]}"#, for: $0) }
        var paid = URLRequest(url: proxy.appendingPathComponent("v1/messages"))
        paid.setValue("anthropic", forHTTPHeaderField: "X-AI")
        var capability = URLRequest(url: proxy.appendingPathComponent("text"))
        capability.setValue("token", forHTTPHeaderField: "X-Api-Key")

        let on = AICoinUITestTransport(hooks: AICoinUITestHooks(environment: ["UITEST_AICOIN_FAKE_AI": "1"]),
                                       productIDPrefix: "p", fakeAI: canned)
        let (data, response) = try await on.data(for: paid)
        XCTAssertEqual(status(response), 200)
        XCTAssertEqual(String(decoding: data, as: UTF8.self), #"{"content":[]}"#)
        let (_, capabilityResponse) = try await on.data(for: capability)
        XCTAssertEqual(status(capabilityResponse), 200)

        let off = AICoinUITestTransport(hooks: AICoinUITestHooks(environment: ["UITEST_AICOIN_DISABLE_FAUCET": "1"]),
                                        productIDPrefix: "p", fakeAI: canned)
        do {
            _ = try await off.data(for: paid)
            XCTFail("without the fake-AI hook a paid call is offline")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .notConnectedToInternet)
        }
    }

    func testStubTreatsAnythingElseAsOffline() async {
        let stub = AICoinUITestTransport(hooks: AICoinUITestHooks(environment: ["UITEST_AICOIN_FAKE_AI": "1"]),
                                         productIDPrefix: "p", fakeAI: { reply("{}", for: $0) })
        for url in [URL(string: "https://en.wikipedia.org/wiki/Swift")!, proxy.appendingPathComponent("wallet/api/redeem-iap")] {
            do {
                _ = try await stub.data(for: URLRequest(url: url))
                XCTFail("\(url) should read as offline")
            } catch {
                XCTAssertEqual((error as? URLError)?.code, .notConnectedToInternet)
            }
        }
    }

    // MARK: - DirectProviderKeyTransport

    func testDirectTransportIsAbsentWithoutKeys() {
        XCTAssertNil(DirectProviderKeyTransport.fromEnvironment([:]))
        XCTAssertNil(DirectProviderKeyTransport.fromEnvironment(["SOAK_ANTHROPIC_API_KEY": ""]))
    }

    func testDirectTransportInjectsEachProvidersOwnHeader() async throws {
        let wire = RecordingTransport { reply("{}", for: $0) }
        let direct = try XCTUnwrap(DirectProviderKeyTransport.fromEnvironment([
            "SOAK_ANTHROPIC_API_KEY": "ant",
            "SOAK_ELEVENLABS_API_KEY": "eleven",
            "SOAK_GEMINI_API_KEY": "gem",
            "SOAK_STABILITY_API_KEY": "stab",
            "SOAK_OPENAI_API_KEY": "oai",
        ])).sending(through: wire)

        for host in ["api.anthropic.com", "api.elevenlabs.io", "generativelanguage.googleapis.com",
                     "api.stability.ai", "api.openai.com", "en.wikipedia.org"] {
            _ = try await direct.data(for: URLRequest(url: URL(string: "https://\(host)/x")!))
        }

        let sent = wire.requests
        XCTAssertEqual(sent[0].value(forHTTPHeaderField: "x-api-key"), "ant")
        XCTAssertEqual(sent[1].value(forHTTPHeaderField: "xi-api-key"), "eleven")
        XCTAssertEqual(sent[2].value(forHTTPHeaderField: "x-goog-api-key"), "gem")
        XCTAssertEqual(sent[3].value(forHTTPHeaderField: "Authorization"), "Bearer stab")
        XCTAssertEqual(sent[4].value(forHTTPHeaderField: "Authorization"), "Bearer oai")
        XCTAssertNil(sent[5].allHTTPHeaderFields?["Authorization"])
        XCTAssertFalse(sent.contains { $0.url?.host == AICoinConfig.defaultBaseURL.host })
    }

    func testDirectTransportRefusesAProviderItHasNoKeyFor() async {
        let wire = RecordingTransport { reply("{}", for: $0) }
        let direct = DirectProviderKeyTransport(anthropic: "ant", underlying: wire)
        do {
            _ = try await direct.data(for: URLRequest(url: URL(string: "https://api.elevenlabs.io/v1/tts")!))
            XCTFail("expected a refusal")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .userAuthenticationRequired)
        }
        XCTAssertTrue(wire.requests.isEmpty)
    }

    // MARK: - AICoinAppStore

    @MainActor
    func testAnInsufficientBalanceEventRaisesTheBuySheet() async throws {
        let bus = AICoinEventBus()
        let rt = runtime(underlying: RecordingTransport { reply(#"{"user_id":"x","balance":0}"#, for: $0) }, eventBus: bus)
        let store = AICoinAppStore(runtime: rt, eventBus: bus, defaults: defaults)
        XCTAssertFalse(store.showBuySheet)

        bus.events.send(.paidCallSucceeded)
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertFalse(store.showBuySheet, "only a 402 raises the sheet")

        bus.events.send(.insufficientBalance(balance: 0))
        for _ in 0..<50 where !store.showBuySheet {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertTrue(store.showBuySheet)
    }

    @MainActor
    func testReconcilingMovesTheBalanceStoreToTheNewWallet() async {
        let shared = WalletIdentity.generate()
        let rt = runtime(underlying: RecordingTransport { reply(#"{"user_id":"x","balance":3}"#, for: $0) }, reconcileTo: shared)
        let store = AICoinAppStore(runtime: rt, eventBus: AICoinEventBus(), defaults: defaults)

        await store.reconcileWallet()

        XCTAssertEqual(store.identity, shared)
        XCTAssertEqual(store.balanceStore.address, shared.address)
    }
}
