import Foundation

/// Thin seam over `URLSession.data(for:)`, generalized from the identically-shaped protocol
/// already duplicated in All Languages Learner's `AICoinGateway.swift` and All AI Learn's
/// `HTTPTransport.swift`. Lets `AICoinRouter` decorate whatever transport an app already uses
/// (a shared client seam of its own, or `URLSession.shared` directly — `URLSession` conforms
/// below, so no wrapper type is needed),
/// and lets tests substitute a mock with zero real network access.
public protocol HTTPTransport: Sendable {
    func data(for request: URLRequest) async throws -> (Data, URLResponse)
}

extension URLSession: HTTPTransport {}

/// A transport that can hand a response over line by line as it arrives, rather than whole when it
/// ends — what a streamed `POST /consortium` (`stream: true`, NDJSON) needs to show a panel's work
/// while it happens. A separate protocol, not a new requirement on `HTTPTransport`, so every
/// transport an app already wrote keeps compiling; one that does not conform still works, it just
/// delivers the events together when the call ends.
public protocol HTTPLineStreamingTransport: HTTPTransport {
    /// The response, and its body as lines (without their terminators) delivered as each arrives.
    func lines(for request: URLRequest) async throws -> (AsyncThrowingStream<String, Error>, URLResponse)
}

extension URLSession: HTTPLineStreamingTransport {
    public func lines(for request: URLRequest) async throws -> (AsyncThrowingStream<String, Error>, URLResponse) {
        let (bytes, response) = try await bytes(for: request)
        let stream = AsyncThrowingStream<String, Error> { continuation in
            let task = Task {
                do {
                    for try await line in bytes.lines {
                        continuation.yield(line)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
        return (stream, response)
    }
}

/// A whole body as the lines a streaming transport would have delivered — for a transport that can
/// only hand over whole bodies.
func wholeBodyLines(_ data: Data) -> AsyncThrowingStream<String, Error> {
    let text = String(decoding: data, as: UTF8.self)
    return AsyncThrowingStream { continuation in
        for line in text.split(whereSeparator: \.isNewline) { continuation.yield(String(line)) }
        continuation.finish()
    }
}
