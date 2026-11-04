import XCTest
import Combine
import Network
@testable import AICoinKit

/// Streams a canned reply in the pieces a test chooses, through a stream the test can feed while
/// the call is in flight; records what it was sent.
private final class PieceStubTransport: HTTPDataStreamingTransport, @unchecked Sendable {
    private let lock = NSLock()
    private let status: Int
    private let headers: [String: String]
    private var _requests: [URLRequest] = []
    let stream: AsyncThrowingStream<Data, Error>
    let feed: AsyncThrowingStream<Data, Error>.Continuation

    init(status: Int = 200, headers: [String: String] = [:]) {
        self.status = status
        self.headers = headers
        (stream, feed) = AsyncThrowingStream<Data, Error>.makeStream()
    }

    var requests: [URLRequest] { lock.withLock { _requests } }

    func dataStream(for request: URLRequest) async throws -> (AsyncThrowingStream<Data, Error>, URLResponse) {
        lock.withLock { _requests.append(request) }
        return (stream, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!)
    }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        XCTFail("a streamed call should read pieces, not a whole body")
        return (Data(), URLResponse())
    }
}

/// Can only hand over whole bodies.
private final class WholeStubTransport: HTTPTransport, @unchecked Sendable {
    let body: Data
    let status: Int
    let headers: [String: String]
    init(_ body: Data, status: Int = 200, headers: [String: String] = [:]) {
        self.body = body
        self.status = status
        self.headers = headers
    }
    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        (body, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: headers)!)
    }
}

private final class Events: @unchecked Sendable {
    private let lock = NSLock()
    private var _events: [AICoinEvent] = []
    var events: [AICoinEvent] { lock.withLock { _events } }
    func append(_ event: AICoinEvent) { lock.withLock { _events.append(event) } }
}

private let streamURL = URL(string: "https://api.elevenlabs.io/v1/text-to-speech/voice123/stream/with-timestamps?output_format=mp3_44100_128")!
private let pcmHeaders = [
    "Content-Type": "audio/pcm;rate=24000;channels=1;encoding=s16le",
    "X-Audio-Sample-Rate": "24000", "X-Audio-Channels": "1",
    "X-Aicoin-Provider": "elevenlabs", "X-Aicoin-Model": "eleven_multilingual_v2", "X-Aicoin-Pieces": "1",
]

final class AICoinStreamingTests: XCTestCase {

    // MARK: - AICoinRouter

    func testARoutedStreamIsRewrittenOntoTheProxyAndDeliveredPieceByPiece() async throws {
        let transport = PieceStubTransport(headers: ["Content-Type": "application/json"])
        let bus = AICoinEventBus()
        let events = Events()
        let cancellable = bus.events.sink { events.append($0) }
        defer { cancellable.cancel() }
        let router = AICoinRouter(underlying: transport, eventBus: bus, tokenProvider: { "tok" })

        var request = URLRequest(url: streamURL)
        request.httpMethod = "POST"
        request.httpBody = Data(#"{"text":"hi","model_id":"eleven_v3"}"#.utf8)
        let (pieces, response) = try await router.dataStream(for: request)

        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        let sent = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(sent.url?.host, AICoinConfig.defaultBaseURL.host)
        XCTAssertEqual(sent.url?.path, "/v1/text-to-speech/voice123/stream/with-timestamps")
        XCTAssertEqual(sent.url?.query, "output_format=mp3_44100_128")
        XCTAssertEqual(sent.value(forHTTPHeaderField: "X-AI"), "elevenlabs")
        XCTAssertEqual(sent.value(forHTTPHeaderField: "X-Api-Key"), "tok")
        XCTAssertEqual(events.events, [.paidCallSucceeded])

        var iterator = pieces.makeAsyncIterator()
        transport.feed.yield(Data("{\"audio_base64\":\"AAAA\"}\n\n".utf8))
        let first = try await iterator.next()
        XCTAssertEqual(first, Data("{\"audio_base64\":\"AAAA\"}\n\n".utf8), "the first piece arrives before the stream ends")
        transport.feed.yield(Data("{\"audio_base64\":\"BBBB\"}\n\n".utf8))
        transport.feed.finish()
        let second = try await iterator.next()
        XCTAssertEqual(second, Data("{\"audio_base64\":\"BBBB\"}\n\n".utf8))
        let end = try await iterator.next()
        XCTAssertNil(end)
    }

    func testARouted402IsTheTypedErrorAndIsPublished() async throws {
        let transport = PieceStubTransport(status: 402)
        transport.feed.yield(Data(#"{"error":"insufficient aicoin balance","#.utf8))
        transport.feed.yield(Data(#""balance":0.5}"#.utf8))
        transport.feed.finish()
        let bus = AICoinEventBus()
        let events = Events()
        let cancellable = bus.events.sink { events.append($0) }
        defer { cancellable.cancel() }
        let router = AICoinRouter(underlying: transport, eventBus: bus, tokenProvider: { "tok" })

        do {
            _ = try await router.dataStream(for: URLRequest(url: streamURL))
            XCTFail("expected insufficientBalance")
        } catch let error as AICoinError {
            XCTAssertEqual(error, .insufficientBalance(balance: 0.5))
        }
        XCTAssertEqual(events.events, [.insufficientBalance(balance: 0.5)])
    }

    func testARoutedStreamWithNoTokenSendsNothing() async throws {
        let transport = PieceStubTransport()
        let router = AICoinRouter(underlying: transport, eventBus: AICoinEventBus(), tokenProvider: { nil })
        do {
            _ = try await router.dataStream(for: URLRequest(url: streamURL))
            XCTFail("expected missingToken")
        } catch let error as AICoinError {
            XCTAssertEqual(error, .missingToken)
        }
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testAnUnderlyingThatCannotStreamStillDeliversTheBodyAsOnePiece() async throws {
        let router = AICoinRouter(underlying: WholeStubTransport(Data("whole".utf8)), eventBus: AICoinEventBus(),
                                  tokenProvider: { "tok" })
        let (pieces, _) = try await router.dataStream(for: URLRequest(url: streamURL))
        var all: [Data] = []
        for try await piece in pieces { all.append(piece) }
        XCTAssertEqual(all, [Data("whole".utf8)])
    }

    func testAnyTransportCanBeAskedForStreamingData() async throws {
        let transport: any HTTPTransport = WholeStubTransport(Data("abc".utf8))
        let (pieces, _) = try await transport.streamingData(for: URLRequest(url: streamURL))
        var all = Data()
        for try await piece in pieces { all.append(piece) }
        XCTAssertEqual(all, Data("abc".utf8))
    }

    // MARK: - AICoinCapabilityClient.audioStream

    func testAudioStreamAsksForAStreamAndHandsOverWholeSamples() async throws {
        let transport = PieceStubTransport(headers: pcmHeaders)
        let bus = AICoinEventBus()
        let events = Events()
        let cancellable = bus.events.sink { events.append($0) }
        defer { cancellable.cancel() }
        let client = AICoinCapabilityClient(transport: transport, eventBus: bus, token: { "tok" })

        let speech = try await client.audioStream(prompt: "Shalom", provider: "elevenlabs", language: "he")

        XCTAssertEqual(speech.format, .pcm16(sampleRate: 24_000, channels: 1))
        XCTAssertEqual(speech.provider, "elevenlabs")
        XCTAssertEqual(speech.model, "eleven_multilingual_v2")
        XCTAssertEqual(speech.pieces, 1)
        XCTAssertEqual(events.events, [.paidCallSucceeded])
        let sent = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(sent.url?.path, "/audio")
        XCTAssertEqual(sent.value(forHTTPHeaderField: "X-Api-Key"), "tok")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: sent.httpBody ?? Data()) as? [String: Any])
        XCTAssertEqual(body["stream"] as? Bool, true)
        XCTAssertEqual(body["prompt"] as? String, "Shalom")
        XCTAssertEqual(body["provider"] as? String, "elevenlabs")
        XCTAssertEqual(body["language"] as? String, "he")

        var iterator = speech.audio.makeAsyncIterator()
        transport.feed.yield(Data([1, 2, 3]))  // a sample and a half
        let first = try await iterator.next()
        XCTAssertEqual(first, Data([1, 2]), "only whole samples, and before the stream ends")
        transport.feed.yield(Data([4]))
        transport.feed.yield(Data([5, 6, 7]))
        transport.feed.finish()
        var rest = Data()
        while let piece = try await iterator.next() {
            XCTAssertEqual(piece.count % 2, 0)
            XCTAssertEqual(piece.startIndex, 0, "pieces are plain Data, not slices")
            rest.append(piece)
        }
        XCTAssertEqual(rest, Data([3, 4, 5, 6]), "the trailing half sample is dropped")
    }

    func testAudioStreamCutShortThrowsAfterWhatArrived() async throws {
        let transport = PieceStubTransport(headers: pcmHeaders)
        let client = AICoinCapabilityClient(transport: transport, eventBus: AICoinEventBus(), token: { "tok" })
        let speech = try await client.audioStream(prompt: "x")
        transport.feed.yield(Data([1, 2, 3, 4]))
        transport.feed.finish(throwing: URLError(.networkConnectionLost))
        var got = Data()
        do {
            for try await piece in speech.audio { got.append(piece) }
            XCTFail("a stream cut short must throw")
        } catch let error as URLError {
            XCTAssertEqual(error.code, .networkConnectionLost)
        }
        XCTAssertEqual(got, Data([1, 2, 3, 4]))
    }

    func testAudioStream402IsTheTypedErrorAndIsPublished() async throws {
        let transport = PieceStubTransport(status: 402, headers: ["Content-Type": "application/json"])
        transport.feed.yield(Data(#"{"error":"insufficient aicoin balance","balance":0}"#.utf8))
        transport.feed.finish()
        let bus = AICoinEventBus()
        let events = Events()
        let cancellable = bus.events.sink { events.append($0) }
        defer { cancellable.cancel() }
        let client = AICoinCapabilityClient(transport: transport, eventBus: bus, token: { "tok" })
        do {
            _ = try await client.audioStream(prompt: "x")
            XCTFail("expected insufficientBalance")
        } catch let error as AICoinError {
            XCTAssertEqual(error, .insufficientBalance(balance: 0))
        }
        XCTAssertEqual(events.events, [.insufficientBalance(balance: 0)])
    }

    func testAudioStreamServerErrorCarriesTheProxysMessage() async throws {
        let transport = PieceStubTransport(status: 502, headers: ["Content-Type": "application/json"])
        transport.feed.yield(Data(#"{"error":"no provider answered"}"#.utf8))
        transport.feed.finish()
        let client = AICoinCapabilityClient(transport: transport, eventBus: AICoinEventBus(), token: { "tok" })
        do {
            _ = try await client.audioStream(prompt: "x")
            XCTFail("expected a server error")
        } catch let error as AICoinError {
            XCTAssertEqual(error, .server(status: 502, message: "no provider answered"))
        }
    }

    func testAudioStreamFromAProxyThatCannotStreamIsTheBufferedMp3() async throws {
        let mp3 = Data([0xFF, 0xFB, 0x10, 0x00])
        let json = #"{"media_base64":"\#(mp3.base64EncodedString())","media_type":"audio/mpeg","provider":"openai"}"#
        let client = AICoinCapabilityClient(
            transport: WholeStubTransport(Data(json.utf8), headers: ["Content-Type": "application/json"]),
            eventBus: AICoinEventBus(), token: { "tok" })
        let speech = try await client.audioStream(prompt: "x")
        XCTAssertEqual(speech.format, .encoded(mediaType: "audio/mpeg"))
        XCTAssertEqual(speech.provider, "openai")
        var all = Data()
        for try await piece in speech.audio { all.append(piece) }
        XCTAssertEqual(all, mp3)
    }

    func testAudioStreamWithoutATokenSendsNothing() async throws {
        let transport = PieceStubTransport(headers: pcmHeaders)
        let client = AICoinCapabilityClient(transport: transport, eventBus: AICoinEventBus(), token: { nil })
        do {
            _ = try await client.audioStream(prompt: "x")
            XCTFail("expected missingToken")
        } catch let error as AICoinError {
            XCTAssertEqual(error, .missingToken)
        }
        XCTAssertTrue(transport.requests.isEmpty)
    }

    // MARK: - UI-test stub

    func testTheUITestStubStreamsItsCannedReplyInPieces() async throws {
        let audio = Data((0..<10_000).map { UInt8($0 % 256) })
        let stub = AICoinUITestTransport(
            hooks: AICoinUITestHooks(environment: ["UITEST_AICOIN_FAKE_AI": "1"]),
            productIDPrefix: "com.example",
            fakeAI: { request in
                (audio, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: pcmHeaders)!)
            })
        let router = AICoinRouter(underlying: stub, eventBus: AICoinEventBus(), tokenProvider: { "tok" })
        let (pieces, response) = try await router.dataStream(for: URLRequest(url: streamURL))
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        var sizes: [Int] = []
        var all = Data()
        for try await piece in pieces {
            sizes.append(piece.count)
            all.append(piece)
        }
        XCTAssertEqual(sizes, [4096, 4096, 1808])
        XCTAssertEqual(all, audio)
    }

    func testTheUITestStubRefusesAStreamWith402WhenForcedInsufficient() async throws {
        let stub = AICoinUITestTransport(
            hooks: AICoinUITestHooks(environment: ["UITEST_AICOIN_FORCE_INSUFFICIENT": "1"]),
            productIDPrefix: "com.example")
        let client = AICoinCapabilityClient(transport: stub, eventBus: AICoinEventBus(), token: { "tok" })
        do {
            _ = try await client.audioStream(prompt: "x")
            XCTFail("expected insufficientBalance")
        } catch let error as AICoinError {
            XCTAssertEqual(error, .insufficientBalance(balance: 0))
        }
    }

    // MARK: - URLSession, over a real socket

    /// The one place the delegate plumbing can be checked: a local server that sends one chunk,
    /// waits, then sends the rest. The first piece has to arrive while the server is still waiting.
    func testURLSessionDeliversTheFirstPieceBeforeTheBodyEnds() async throws {
        let server = try ChunkedServer()
        defer { server.stop() }
        let port = try await server.start()

        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/stream")!)
        request.timeoutInterval = 10
        let (pieces, response) = try await URLSession.shared.dataStream(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)

        var iterator = pieces.makeAsyncIterator()
        let first = try await iterator.next()
        XCTAssertEqual(first, Data("first-piece;".utf8))
        XCTAssertFalse(server.sentRest, "read before the server sent the rest")
        server.release()
        var rest = Data()
        while let piece = try await iterator.next() { rest.append(piece) }
        XCTAssertEqual(String(decoding: rest, as: UTF8.self), "second-piece;")
    }

    func testURLSessionReportsAChunkedBodyCutShort() async throws {
        let server = try ChunkedServer(cutShort: true)
        defer { server.stop() }
        let port = try await server.start()

        let (pieces, _) = try await URLSession.shared.dataStream(for: URLRequest(url: URL(string: "http://127.0.0.1:\(port)/")!))
        var got = Data()
        do {
            for try await piece in pieces { got.append(piece) }
            XCTFail("a chunked body without its terminator must not end cleanly")
        } catch {
            XCTAssertTrue(error is URLError, "\(error)")
        }
        XCTAssertEqual(String(decoding: got, as: UTF8.self), "first-piece;")
    }
}

/// A one-connection HTTP/1.1 server on loopback that answers with a chunked body in two parts,
/// holding the second until `release()` — or, `cutShort`, closing after the first without the
/// terminating chunk.
private final class ChunkedServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "ChunkedServer")
    private let cutShort: Bool
    private let lock = NSLock()
    private var connection: NWConnection?
    private var _sentRest = false
    private let gate = DispatchSemaphore(value: 0)

    var sentRest: Bool { lock.withLock { _sentRest } }

    init(cutShort: Bool = false) throws {
        self.cutShort = cutShort
        listener = try NWListener(using: .tcp, on: .any)
    }

    func start() async throws -> UInt16 {
        listener.newConnectionHandler = { [weak self] connection in self?.serve(connection) }
        return try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [listener] state in
                switch state {
                case .ready:
                    listener.stateUpdateHandler = nil
                    continuation.resume(returning: listener.port!.rawValue)
                case .failed(let error):
                    listener.stateUpdateHandler = nil
                    continuation.resume(throwing: error)
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
    }

    func release() { gate.signal() }

    func stop() {
        gate.signal()
        listener.cancel()
        lock.withLock { connection }?.cancel()
    }

    private func serve(_ connection: NWConnection) {
        lock.withLock { self.connection = connection }
        connection.start(queue: queue)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [self] _, _, _, _ in
            let head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
            let first = "first-piece;"
            send(connection, head + String(first.utf8.count, radix: 16) + "\r\n" + first + "\r\n") {
                if self.cutShort {
                    // What the proxy does to a stream that broke after it started: reset, not a
                    // clean close — URLSession reads a clean close after a chunk as the body's end.
                    connection.forceCancel()
                    return
                }
                DispatchQueue.global().async {
                    self.gate.wait()
                    self.lock.withLock { self._sentRest = true }
                    let second = "second-piece;"
                    self.send(connection, String(second.utf8.count, radix: 16) + "\r\n" + second + "\r\n0\r\n\r\n") {
                        connection.cancel()
                    }
                }
            }
        }
    }

    private func send(_ connection: NWConnection, _ text: String, then: @escaping () -> Void) {
        connection.send(content: Data(text.utf8), completion: .contentProcessed { _ in then() })
    }
}
