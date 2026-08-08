import Foundation

/// Thin seam over `URLSession.data(for:)`, generalized from the identically-shaped protocol
/// already duplicated in All Languages Learner's `AICoinGateway.swift` and Learn It's
/// `HTTPTransport.swift`. Lets `AICoinRouter` decorate whatever transport an app already uses
/// (a shared client seam of its own, or `URLSession.shared` directly — `URLSession` conforms
/// below, so no wrapper type is needed),
/// and lets tests substitute a mock with zero real network access.
public protocol HTTPTransport: Sendable {
    func data(for request: URLRequest) async throws -> (Data, URLResponse)
}

extension URLSession: HTTPTransport {}
