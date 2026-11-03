import Foundation
import StoreKit

/// The App Store storefront this app is running under, sent to the proxy on every request as
/// `X-AICoin-Storefront` (see CONTRACT.md's "Storefront restrictions").
///
/// Apple does not allow AI services without a Chinese permit (OpenAI's, every one of them) in apps
/// on the China mainland store. The proxy picks providers itself for automatic routing, panels,
/// escalation, failover, images and speech, so it does the excluding; this header is how it knows
/// to. `allows(proxyProvider:)` is for the app's own UI: never offer a provider the proxy would
/// refuse (`451 provider_unavailable_in_region`).
public enum AICoinStorefront {
    public static let headerName = "X-AICoin-Storefront"
    public static let chinaMainland = "CHN"
    /// Proxy provider names never used on the China mainland storefront.
    public static let restrictedInChina: Set<String> = ["openai"]

    /// `UITEST_FORCE_CHINA_STOREFRONT=1` makes the storefront `CHN` from the first read on.
    static let forceChinaKey = "UITEST_FORCE_CHINA_STOREFRONT"

    private final class State: @unchecked Sendable {
        let lock = NSLock()
        /// What StoreKit last said, once it has said anything.
        var storeKit: String?
        /// Set by `override(_:)` (or the UI-test env var); wins over StoreKit while set.
        var overridden: String?
        var environmentRead = false
        var started = false
    }

    private static let state = State()

    /// ISO alpha-3 code: StoreKit's Storefront once known; until then "CHN" if Locale.current.region
    /// is CN, else nil. Thread-safe (lock), readable from any task.
    public static var countryCode: String? {
        state.lock.withLock {
            readEnvironmentLocked()
            return resolve(overridden: state.overridden, storeKit: state.storeKit,
                           region: Locale.current.region?.identifier)
        }
    }

    public static var isChinaMainland: Bool {
        countryCode == chinaMainland
    }

    /// Reads Storefront.current and keeps following Storefront.updates. Idempotent;
    /// AICoinAppRuntime calls it at startup, apps may call it too.
    public static func start() {
        let first: Bool = state.lock.withLock {
            defer { state.started = true }
            return !state.started
        }
        guard first else { return }
        Task.detached(priority: .utility) {
            if let current = await Storefront.current {
                record(current.countryCode)
            }
            for await update in Storefront.updates {
                record(update.countryCode)
            }
        }
    }

    /// For tests / UI tests; nil clears. Env UITEST_FORCE_CHINA_STOREFRONT=1 forces CHN at first read.
    public static func override(_ code: String?) {
        state.lock.withLock {
            readEnvironmentLocked()
            state.overridden = normalized(code)
        }
    }

    /// Whether a proxy provider name ("openai", "anthropic", …) may be used on the current storefront.
    public static func allows(proxyProvider: String) -> Bool {
        allows(proxyProvider: proxyProvider, storefront: countryCode)
    }

    /// Sets the header when countryCode is known.
    public static func apply(to request: inout URLRequest) {
        apply(countryCode, to: &request)
    }

    // MARK: - Internals (pure, for tests)

    /// `request` with the header applied — for call sites that hold a `let`.
    static func applied(to request: URLRequest) -> URLRequest {
        var request = request
        apply(to: &request)
        return request
    }

    static func apply(_ code: String?, to request: inout URLRequest) {
        guard let code else { return }
        request.setValue(code, forHTTPHeaderField: headerName)
    }

    /// What StoreKit has said, if it has (nil before `start()` hears back, and under `swift test`
    /// unless something started it).
    static var storeKitCode: String? {
        state.lock.withLock { state.storeKit }
    }

    static func allows(proxyProvider: String, storefront: String?) -> Bool {
        guard normalized(storefront) == chinaMainland else { return true }
        return !restrictedInChina.contains(proxyProvider.trimmingCharacters(in: .whitespaces).lowercased())
    }

    /// An override wins; then StoreKit; before StoreKit answers, a CN region is taken as China.
    static func resolve(overridden: String?, storeKit: String?, region: String?) -> String? {
        if let overridden { return overridden }
        if let storeKit { return storeKit }
        return region?.uppercased() == "CN" ? chinaMainland : nil
    }

    /// Upper-cased three letters, or nil.
    static func normalized(_ code: String?) -> String? {
        guard let code = code?.trimmingCharacters(in: .whitespaces).uppercased(),
              code.count == 3, code.allSatisfy({ $0.isASCII && $0.isLetter }) else { return nil }
        return code
    }

    private static func record(_ code: String) {
        state.lock.withLock { state.storeKit = normalized(code) }
    }

    /// Called under the lock: applies the UI-test env var once, on the first read.
    private static func readEnvironmentLocked() {
        guard !state.environmentRead else { return }
        state.environmentRead = true
        if ProcessInfo.processInfo.environment[forceChinaKey] == "1" {
            state.overridden = chinaMainland
        }
    }
}
