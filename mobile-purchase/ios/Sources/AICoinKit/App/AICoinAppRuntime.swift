import Foundation

/// An app's one, process-wide AICoin wiring: the wallet identity it pays with, the API token
/// derived from it, and the `HTTPTransport` every AI-provider call goes through.
///
/// This is the glue every app built on this package used to write for itself (`AICoinRuntime` in
/// All Languages Learner, AI News and AI Music Gen; `AICoinWallet`/`CurrentWallet` in
/// InfiniteAIRadio), each copy with its own subset of the fixes. One copy now carries all of them:
/// the Keychain fallback, the cross-device reconcile, the lock-guarded identity swap, and the
/// UI-test hooks that keep a test run off the real wallet.
///
/// Deliberately not `@MainActor`: provider clients are plain `Sendable` structs whose `transport`
/// default has to be resolvable from any isolation context (audio and image prefetch run off the
/// main actor). Everything held here is `Sendable` or lock-guarded. UI-facing, observable state
/// lives in `AICoinAppStore` instead.
public final class AICoinAppRuntime: @unchecked Sendable {
    public struct Configuration: Sendable {
        /// The Keychain service the wallet seed is stored under — normally `<bundle id>.aicoin`, a
        /// per-app item, so each app's wallet and balance are its own. An app that shipped on
        /// `WalletKeychainStore`'s default service keeps passing that, or its users would wake up
        /// to a new, empty wallet.
        public var keychainService: String
        public var keychainAccount: String = "wallet-seed"
        /// Whether the wallet is shared with the user's other devices through iCloud Keychain — see
        /// `WalletKeychainStore`.
        public var synchronizable: Bool = true
        /// Namespaces this app's `UserDefaults` bookkeeping: the faucet flag is
        /// `<prefix>_aicoin_faucet_claim_attempted_v1` unless `faucetDefaultsKey` says otherwise.
        public var defaultsKeyPrefix: String
        /// The exact key an app's earlier, hand-written faucet used. Wins over the prefix, so
        /// adopting this package doesn't make an existing install look new and claim again.
        public var faucetDefaultsKey: String?
        public var baseURL: URL = AICoinConfig.defaultBaseURL
        /// The app's bundle ID, from which its IAP product ids are derived. Defaults to the running
        /// app's own.
        public var bundleID: String
        /// Debug convenience: keep a test wallet usable by re-claiming the faucet whenever the
        /// balance runs dry, so on-device testing isn't interrupted by a purchase sheet.
        ///
        /// Honoured only when this package itself is compiled with `DEBUG` (which SwiftPM and Xcode
        /// do for a Debug build of the app), and apps should still only set it under their own
        /// `#if DEBUG`: shipping an automatic top-up would be a free-coin farm, and the faucet
        /// spends real money. It grants nothing waiting wouldn't — the server's one-hour cooldown
        /// and shared pool still apply.
        public var debugAutoReloadWhenEmpty: Bool

        public init(
            keychainService: String,
            keychainAccount: String = "wallet-seed",
            synchronizable: Bool = true,
            defaultsKeyPrefix: String,
            faucetDefaultsKey: String? = nil,
            baseURL: URL = AICoinConfig.defaultBaseURL,
            bundleID: String? = nil,
            debugAutoReloadWhenEmpty: Bool = false
        ) {
            self.keychainService = keychainService
            self.keychainAccount = keychainAccount
            self.synchronizable = synchronizable
            self.defaultsKeyPrefix = defaultsKeyPrefix
            self.faucetDefaultsKey = faucetDefaultsKey
            self.baseURL = baseURL
            self.bundleID = bundleID ?? Bundle.main.bundleIdentifier ?? ""
            self.debugAutoReloadWhenEmpty = debugAutoReloadWhenEmpty
        }

        /// The `UserDefaults` key `AICoinFaucet` records its one claim under.
        public var faucetKey: String {
            faucetDefaultsKey ?? "\(defaultsKeyPrefix)_aicoin_faucet_claim_attempted_v1"
        }

        /// What this app's product ids start with — the bundle ID with anything Apple forbids in a
        /// product id (a hyphen, say) taken out.
        public var productIDPrefix: String {
            AICoinProductID.prefix(forBundleID: bundleID)
        }

        public var keychainStore: WalletKeychainStore {
            WalletKeychainStore(service: keychainService, account: keychainAccount, synchronizable: synchronizable)
        }
    }

    /// Settles this device's wallet against the one shared from the user's other devices. A seam
    /// only so tests can stand in for the Keychain; in an app it is always
    /// `WalletKeychainStore.reconcile`.
    typealias Reconciler = @Sendable (WalletIdentity, (String) async throws -> Double) async -> WalletIdentity?

    public let configuration: Configuration
    /// The UI-test hooks this runtime was built under. When any is set the wallet is ephemeral and
    /// nothing leaves the process — see `AICoinUITestHooks`.
    public let hooks: AICoinUITestHooks
    /// The transport `AICoinRouter` sends on after rewriting a provider call — the app's own, or
    /// the offline UI-test stub. Exposed so a call to the proxy's own endpoints (which mustn't go
    /// through the router) can use the same one.
    public let underlying: any HTTPTransport
    /// **The** app transport. Every AI-provider request is issued through this instead of
    /// `URLSession.shared`: `AICoinRouter` re-hosts calls to known provider hosts at the proxy with
    /// this wallet's token (a `402` surfaces as `AICoinError.insufficientBalance` and is published
    /// on the event bus) and lets every other host pass straight through. There is no
    /// personal-API-key fallback.
    public let transport: any HTTPTransport
    /// Balance / claim / transfer / IAP-redemption calls against the aicoin server itself — on the
    /// plain underlying transport, not `transport`: these are wallet-management endpoints, not AI
    /// providers, so they must not be re-routed or billed.
    public let walletClient: WalletClient

    private let current: CurrentWallet
    private let reconciler: Reconciler

    /// - Parameters:
    ///   - underlying: What actually goes on the network. Replaced by `AICoinUITestTransport`
    ///     whenever `hooks.isActive`.
    ///   - hooks: Read from the process environment by default.
    ///   - eventBus: Where the router publishes `.paidCallSucceeded` / `.insufficientBalance`.
    ///   - directTransport: A soak run's `DirectProviderKeyTransport`. When given, it *is* the app
    ///     transport and the wallet is bypassed for provider calls. Apps must only pass it under
    ///     `#if DEBUG`. Ignored under the UI-test hooks, which must never reach a real provider.
    ///   - uiTestFakeAI: The app's canned replies under `UITEST_AICOIN_FAKE_AI` — see
    ///     `AICoinUITestTransport`.
    ///   - identity: A fixed wallet, for previews and tests; the Keychain is not touched at all.
    public convenience init(
        configuration: Configuration,
        underlying: any HTTPTransport = URLSession.shared,
        hooks: AICoinUITestHooks = .init(environment: ProcessInfo.processInfo.environment),
        eventBus: AICoinEventBus = .shared,
        directTransport: (any HTTPTransport)? = nil,
        uiTestFakeAI: (@Sendable (URLRequest) -> (Data, URLResponse)?)? = nil,
        identity: WalletIdentity? = nil
    ) {
        let store = configuration.keychainStore
        self.init(
            configuration: configuration,
            underlying: underlying,
            hooks: hooks,
            eventBus: eventBus,
            directTransport: directTransport,
            uiTestFakeAI: uiTestFakeAI,
            identity: identity,
            reconciler: { await store.reconcile(current: $0, balance: $1) }
        )
    }

    init(
        configuration: Configuration,
        underlying: any HTTPTransport,
        hooks: AICoinUITestHooks,
        eventBus: AICoinEventBus,
        directTransport: (any HTTPTransport)?,
        uiTestFakeAI: (@Sendable (URLRequest) -> (Data, URLResponse)?)?,
        identity: WalletIdentity?,
        reconciler: @escaping Reconciler
    ) {
        self.configuration = configuration
        self.hooks = hooks
        self.reconciler = reconciler

        let initial: WalletIdentity
        if let identity {
            initial = identity
        } else if hooks.isActive {
            // Under the UI-test hooks, deliberately an ephemeral, never-persisted identity: a test
            // must not read, write, or transact against the real Keychain wallet on the machine
            // running it.
            initial = .generate()
        } else {
            // A Keychain failure (a locked device during a background launch, a signing setup
            // without keychain access) must not take the whole app down. An ephemeral wallet still
            // lets the app launch and explain itself — zero balance, every paid call 402s into the
            // buy sheet — and the next launch retries persisting a real one.
            initial = (try? configuration.keychainStore.loadOrCreateIdentity()) ?? .generate()
        }
        let current = CurrentWallet(initial)
        self.current = current

        // `nil` in every normal launch; the offline stub whenever any `UITEST_AICOIN_*` hook is
        // set. Both `walletClient` and the router route through it, which is what makes "a UI test
        // never touches the live faucet or the live proxy" structural rather than a convention.
        let wire: any HTTPTransport = hooks.isActive
            ? AICoinUITestTransport(hooks: hooks, productIDPrefix: configuration.productIDPrefix, fakeAI: uiTestFakeAI)
            : underlying
        self.underlying = wire
        self.walletClient = WalletClient(baseURL: configuration.baseURL, transport: wire)
        if let directTransport, !hooks.isActive {
            self.transport = directTransport
        } else {
            // Reads the token through `current` on every call rather than capturing one cache, so
            // a wallet switch takes effect on the very next request.
            self.transport = AICoinRouter(
                underlying: wire,
                baseURL: configuration.baseURL,
                eventBus: eventBus,
                tokenProvider: { current.tokenCache.currentToken() }
            )
        }
    }

    /// The Ed25519 keypair identifying this wallet. Created transparently on first launch and
    /// persisted in the Keychain — never typed in by the user. Can change while the app runs, when
    /// reconciling with the user's other devices settles on another wallet; read it fresh rather
    /// than keeping a copy.
    public var identity: WalletIdentity { current.identity }

    /// Caches the locally-signed API token presented as `X-Api-Key` (re-issued shortly before
    /// expiry). Swapped together with `identity`.
    public var tokenCache: AICoinTokenCache { current.tokenCache }

    /// The current wallet's API token, in the `@Sendable () -> String?` shape `AICoinRouter` and
    /// proxy capability clients take.
    public func currentToken() -> String? {
        current.tokenCache.currentToken()
    }

    /// Switches every later call — signing, tokens, balance, purchases — to `identity`.
    public func use(_ identity: WalletIdentity) {
        current.set(identity)
    }

    /// Settles on one wallet with the user's other devices: a device that made its own empty
    /// wallet moves to the funded one shared through iCloud Keychain (see
    /// `WalletKeychainStore.reconcile`), so the same coins show everywhere. Returns whether it
    /// switched; `AICoinAppStore.reconcileWallet()` is what moves the balance and purchase
    /// observers along with it.
    ///
    /// Never runs under the UI-test hooks: a test's wallet is ephemeral and must never meet a real
    /// one.
    @discardableResult
    public func reconcile() async -> Bool {
        guard !hooks.isActive else { return false }
        let client = walletClient
        let mine = identity
        guard let switched = await reconciler(mine, { try await client.balance(address: $0) }),
              switched.address != mine.address else { return false }
        use(switched)
        return true
    }
}

/// The wallet in use and the token cache signed with it, swapped together under a lock: requests
/// sign from whatever thread they run on, and a wallet switch lands from the main actor. A token
/// signed by one wallet presented alongside another's address would be refused, so the two must
/// never be observed half-swapped.
final class CurrentWallet: @unchecked Sendable {
    private let lock = NSLock()
    private var _identity: WalletIdentity
    private var _tokenCache: AICoinTokenCache

    init(_ identity: WalletIdentity) {
        _identity = identity
        _tokenCache = AICoinTokenCache(identity: identity)
    }

    var identity: WalletIdentity { lock.lock(); defer { lock.unlock() }; return _identity }
    var tokenCache: AICoinTokenCache { lock.lock(); defer { lock.unlock() }; return _tokenCache }

    func set(_ identity: WalletIdentity) {
        let cache = AICoinTokenCache(identity: identity)
        lock.lock(); _identity = identity; _tokenCache = cache; lock.unlock()
    }
}
