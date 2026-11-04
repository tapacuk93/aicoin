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

/// A transport that can hand a response body over in pieces as they arrive — what streamed speech
/// needs (ElevenLabs `/stream` and `/stream/with-timestamps` through the proxy, or the proxy's own
/// `POST /audio` with `stream: true`), so playback can start on the first audio instead of the
/// last. Like `HTTPLineStreamingTransport`, a separate protocol, so every transport an app already
/// wrote keeps compiling; one that does not conform still works through `streamingData(for:)`,
/// which then delivers the whole body as one piece when the call ends.
///
/// Piece boundaries are wherever the network put them: neither HTTP chunks, nor lines, nor audio
/// frames or samples. A reader that needs any of those reassembles them.
public protocol HTTPDataStreamingTransport: HTTPTransport {
    /// The response head, once it has arrived, and the body as pieces of bytes delivered as each
    /// arrives. The stream ends when the body does, or throws if the connection breaks first
    /// (e.g. `URLError(.networkConnectionLost)` for a chunked body cut short). Cancelling the task
    /// that iterates it — or dropping the stream — cancels the request.
    func dataStream(for request: URLRequest) async throws -> (AsyncThrowingStream<Data, Error>, URLResponse)
}

extension HTTPTransport {
    /// `dataStream(for:)` when this transport can stream; otherwise `data(for:)`, delivered as one
    /// piece. The one call a streaming client needs, whatever transport it was handed.
    public func streamingData(for request: URLRequest) async throws -> (AsyncThrowingStream<Data, Error>, URLResponse) {
        if let streaming = self as? any HTTPDataStreamingTransport {
            return try await streaming.dataStream(for: request)
        }
        let (data, response) = try await data(for: request)
        return (wholeBodyChunks(data), response)
    }
}

/// A whole body as the one piece a streaming transport would have delivered in several.
func wholeBodyChunks(_ data: Data) -> AsyncThrowingStream<Data, Error> {
    AsyncThrowingStream { continuation in
        if !data.isEmpty { continuation.yield(data) }
        continuation.finish()
    }
}

/// The body of `stream`, read to the end — at most `limit` bytes are kept (an error body, which is
/// all this is used for, is small; a stream that turns out to be audio is not worth holding).
func collect(_ stream: AsyncThrowingStream<Data, Error>, limit: Int = 64 * 1024) async throws -> Data {
    var collected = Data()
    for try await piece in stream where collected.count < limit {
        collected.append(piece.prefix(limit - collected.count))
    }
    return collected
}

extension URLSession: HTTPDataStreamingTransport {
    /// Each `didReceive data` callback of a data task, as it happens. `URLSession.bytes(for:)`
    /// would serve too, but it hands over one byte per iteration, leaving the batching — and the
    /// question of when a batch is "enough" — to every caller; the delegate already has the
    /// natural batch, the bytes that just came off the network.
    public func dataStream(for request: URLRequest) async throws -> (AsyncThrowingStream<Data, Error>, URLResponse) {
        let relay = StreamingDataRelay()
        let task = dataTask(with: request)
        task.delegate = relay
        return try await relay.start(task)
    }
}

/// Bridges one data task's delegate callbacks to a response continuation and a body stream.
final class StreamingDataRelay: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var head: CheckedContinuation<URLResponse, Error>?
    private var body: AsyncThrowingStream<Data, Error>.Continuation?

    func start(_ task: URLSessionDataTask) async throws -> (AsyncThrowingStream<Data, Error>, URLResponse) {
        let (stream, body) = AsyncThrowingStream<Data, Error>.makeStream()
        body.onTermination = { termination in
            if case .cancelled = termination { task.cancel() }
        }
        lock.withLock { self.body = body }
        let response = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URLResponse, Error>) in
                lock.withLock { head = continuation }
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
        return (stream, response)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let continuation = lock.withLock { () -> CheckedContinuation<URLResponse, Error>? in
            defer { head = nil }
            return head
        }
        continuation?.resume(returning: response)
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        _ = lock.withLock { body }?.yield(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let (continuation, body) = lock.withLock { () -> (CheckedContinuation<URLResponse, Error>?, AsyncThrowingStream<Data, Error>.Continuation?) in
            defer { head = nil; self.body = nil }
            return (head, self.body)
        }
        // Failed before any response: the call itself throws, as `data(for:)` would.
        continuation?.resume(throwing: error ?? URLError(.badServerResponse))
        if let error {
            body?.finish(throwing: error)
        } else {
            body?.finish()
        }
    }
}
