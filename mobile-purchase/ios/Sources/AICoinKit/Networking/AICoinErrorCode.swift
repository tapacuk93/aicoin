import Foundation

/// A short, stable code shown after every error message an AICoin app gives its readers
/// ("… (AIC-201)"), so a support email can say exactly what went wrong without the reader ever
/// seeing the provider's own words — those are about the relay's account, keys or request
/// format, and read as the app being broken. The detail stays in the error for the log.
///
/// Numbers never change meaning once shipped; a new failure gets a new number. Hundreds group
/// them by where the failure happened:
/// - 1xx: the reader's wallet — the only ones the reader can fix themselves
/// - 2xx: writing text
/// - 3xx: drawing pictures
/// - 4xx: the AICoin service itself
/// - 5xx: the improved (ElevenLabs) voice
/// - 9xx: the app's own work on the device
public struct AICoinErrorCode: RawRepresentable, Hashable, Sendable, CustomStringConvertible {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }

    public var description: String { "AIC-\(rawValue)" }

    /// `message` followed by this code, as a reader sees it.
    public func worded(_ message: String) -> String { "\(message) (\(self))" }

    // MARK: Wallet
    public static let insufficientBalance = Self(rawValue: 101)
    public static let walletNotSetUp = Self(rawValue: 102)

    // MARK: Text
    /// A refusal the provider will repeat: its account, its keys, a request it won't take.
    public static let textUnavailable = Self(rawValue: 201)
    public static let textRateLimited = Self(rawValue: 202)
    public static let textBusy = Self(rawValue: 203)
    /// A gateway (502/504) gave up waiting for the provider.
    public static let textGatewayTimeout = Self(rawValue: 204)
    /// Any other 5xx.
    public static let textBrieflyUnavailable = Self(rawValue: 205)
    /// The app's own deadline passed with no answer.
    public static let textTimedOut = Self(rawValue: 206)
    public static let textGarbled = Self(rawValue: 207)
    /// Declined or cut off before writing anything.
    public static let textEmpty = Self(rawValue: 208)
    /// The answer kept coming back in the wrong language.
    public static let textWrongLanguage = Self(rawValue: 209)

    // MARK: Pictures
    public static let imageUnavailable = Self(rawValue: 301)
    public static let imageUnusable = Self(rawValue: 302)
    public static let imageNotConfigured = Self(rawValue: 303)

    // MARK: AICoin service
    public static let serviceUnavailable = Self(rawValue: 401)
    public static let serviceRejected = Self(rawValue: 402)
    public static let serviceMalformed = Self(rawValue: 403)

    // MARK: Improved voice
    public static let voiceUnavailable = Self(rawValue: 501)
    public static let voiceListUnavailable = Self(rawValue: 502)
    public static let voiceDesignUnavailable = Self(rawValue: 503)

    // MARK: On the device
    public static let exportFailed = Self(rawValue: 901)
}

/// An error that carries an `AICoinErrorCode`, for a caller (a log line, a support link) that
/// wants the code without parsing the message.
public protocol AICoinCodedError: LocalizedError {
    var code: AICoinErrorCode { get }
}
