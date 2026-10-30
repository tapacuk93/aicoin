import Foundation

/// Which app made a paid call, and what for — the two labels the proxy's transaction log
/// (`GET /admin/transactions`) shows beside each call's cost.
///
/// Both are optional headers on the wire and purely descriptive: a call without them is accepted,
/// priced and charged exactly as one with them, and the proxy records it as app `unknown` (or the
/// app its wallet is assigned to) and purpose `unspecified`. Builds that predate this send neither.
///
/// - `X-AICoin-App` is the running app's bundle identifier, added to every proxied call by
///   `AICoinRouter` and `AICoinCapabilityClient` without the app doing anything.
/// - `X-AICoin-Purpose` is a short word or two naming what the call is for (`"narrate"`,
///   `"translate"`, `"chat"`, `"image"` …). The app says it, one of three ways:
///   1. around any code that ends in paid calls, however deep (shared kits included):
///      `try await AICoinCallTags.withPurpose("narrate") { … }`;
///   2. on a `URLRequest` it builds itself: `request.setValue("narrate", forHTTPHeaderField:
///      AICoinCallTags.purposeHeader)`;
///   3. on `AICoinCapabilityClient`'s `text`/`consortium`/`image`/`audio`: `purpose: "lesson"`.
///   A header already on the request wins over an argument, which wins over `withPurpose`.
public enum AICoinCallTags {
    public static let appHeader = "X-AICoin-App"
    public static let purposeHeader = "X-AICoin-Purpose"

    /// The longest purpose sent; the proxy truncates to the same length.
    public static let maxPurposeLength = 64

    /// The purpose of every proxied call made inside `withPurpose`, including from child tasks
    /// and `Task {}` blocks started there (not `Task.detached`).
    @TaskLocal public static var purpose: String?

    /// The app named on every call: the main bundle's identifier.
    public static let appID: String? = Bundle.main.bundleIdentifier

    /// Runs `operation` with every proxied call inside it labelled `purpose`.
    public static func withPurpose<T>(
        _ purpose: String?, _ operation: () async throws -> T
    ) async rethrows -> T {
        try await $purpose.withValue(purpose, operation: operation)
    }

    /// Trimmed to one line of at most `maxPurposeLength` printable characters; nil when nothing is
    /// left.
    public static func sanitized(_ purpose: String?) -> String? {
        guard let purpose else { return nil }
        let printable = purpose.unicodeScalars.filter { $0.value >= 0x20 && $0.value < 0x7F }
        let trimmed = String(String.UnicodeScalarView(printable)).trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(maxPurposeLength))
    }

    /// Adds the app and purpose headers `request` does not already carry. Nothing is added for a
    /// purpose that is nil or empty: the proxy then records "unspecified".
    static func tag(_ request: inout URLRequest, purpose explicit: String? = nil, appID: String? = appID) {
        if request.value(forHTTPHeaderField: appHeader) == nil, let appID, !appID.isEmpty {
            request.setValue(appID, forHTTPHeaderField: appHeader)
        }
        if request.value(forHTTPHeaderField: purposeHeader) == nil,
           let purpose = sanitized(explicit) ?? sanitized(Self.purpose) {
            request.setValue(purpose, forHTTPHeaderField: purposeHeader)
        }
    }
}
