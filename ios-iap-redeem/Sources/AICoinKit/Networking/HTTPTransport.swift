import Foundation

/// Thin seam over `URLSession.data(for:)`, generalized from the identically-shaped protocol
/// already duplicated in All Languages Learner's `AICoinGateway.swift` and Learn It's
/// `HTTPTransport.swift`. Lets `AICoinRouter` decorate whatever transport an app already uses
/// (its own `URLSessionHTTPTransport`, a shared `AIHTTPClient`, or `URLSession.shared` directly),
/// and lets tests substitute a mock with zero real network access.
public protocol HTTPTransport: Sendable {
    func data(for request: URLRequest) async throws -> (Data, URLResponse)
}

extension URLSession: HTTPTransport {}
