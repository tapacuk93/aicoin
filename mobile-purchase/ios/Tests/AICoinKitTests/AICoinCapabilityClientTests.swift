import XCTest
import Combine
@testable import AICoinKit

/// Answers every request with one canned reply and records what it was sent.
private final class StubTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private let body: Data
    private let status: Int
    private var _requests: [URLRequest] = []

    init(_ body: String, status: Int = 200) {
        self.body = Data(body.utf8)
        self.status = status
    }

    var requests: [URLRequest] { lock.withLock { _requests } }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        lock.withLock { _requests.append(request) }
        return (body, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}

/// Hands its reply over line by line, through a stream the test feeds, so a test can see events
/// arrive before the call has finished.
private final class LineStubTransport: HTTPLineStreamingTransport, @unchecked Sendable {
    private let lock = NSLock()
    private let status: Int
    private var _requests: [URLRequest] = []
    let stream: AsyncThrowingStream<String, Error>
    let feed: AsyncThrowingStream<String, Error>.Continuation

    init(status: Int = 200) {
        self.status = status
        (stream, feed) = AsyncThrowingStream<String, Error>.makeStream()
    }

    var requests: [URLRequest] { lock.withLock { _requests } }

    func lines(for request: URLRequest) async throws -> (AsyncThrowingStream<String, Error>, URLResponse) {
        lock.withLock { _requests.append(request) }
        return (stream, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        XCTFail("a streamed call should read lines, not a whole body")
        return (Data(), URLResponse())
    }
}

/// Collects events from a `@Sendable` callback.
private final class EventLog: @unchecked Sendable {
    private let lock = NSLock()
    private var _events: [ConsortiumEvent] = []
    var events: [ConsortiumEvent] { lock.withLock { _events } }
    func append(_ event: ConsortiumEvent) { lock.withLock { _events.append(event) } }
}

private func sentBody(_ request: URLRequest?) -> [String: Any] {
    guard let data = request?.httpBody, let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return [:] }
    return object
}

final class AICoinCapabilityClientTests: XCTestCase {
    private let base = URL(string: "https://proxy.example")!

    private func client(_ transport: any HTTPTransport, bus: AICoinEventBus = AICoinEventBus(),
                        token: String? = "tok") -> AICoinCapabilityClient {
        AICoinCapabilityClient(transport: transport, baseURL: base, eventBus: bus, token: { token })
    }

    func testTextPostsToTheProxysOwnEndpointWithTheTokenAndEveryField() async throws {
        let transport = StubTransport(#"{"answer":"Hola","escalated":false,"capability":"text","subject":"translation","provider":"openai","model":"gpt-5","considered":["openai"],"calls":1,"coins_charged":2,"errors":[]}"#)

        let answer = try await client(transport).text(
            prompt: "Say hi", context: "ctx", subject: "translation", provider: "openai",
            escalate: false, maxTokens: 300, timeout: 33
        )

        XCTAssertEqual(answer.answer, "Hola")
        XCTAssertEqual(answer.provider, "openai")
        XCTAssertEqual(answer.model, "gpt-5")
        XCTAssertEqual(answer.coinsCharged, 2)
        XCTAssertEqual(answer.subject, "translation")
        let request = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(request.url?.absoluteString, "https://proxy.example/text")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Api-Key"), "tok")
        XCTAssertEqual(request.timeoutInterval, 33)
        let body = sentBody(request)
        XCTAssertEqual(body["prompt"] as? String, "Say hi")
        XCTAssertEqual(body["context"] as? String, "ctx")
        XCTAssertEqual(body["subject"] as? String, "translation")
        XCTAssertEqual(body["provider"] as? String, "openai")
        XCTAssertEqual(body["escalate"] as? Bool, false)
        XCTAssertEqual(body["max_tokens"] as? Int, 300)
    }

    func testFastestIsSentOnlyWhenAsked() async throws {
        let transport = StubTransport(#"{"answer":"x"}"#)
        _ = try await client(transport).text(prompt: "p", escalate: false, fastest: true)
        let body = sentBody(transport.requests.first)
        XCTAssertEqual(body["fastest"] as? Bool, true)
        XCTAssertEqual(body["escalate"] as? Bool, false)
    }

    func testSlashesGoUnescapedBecauseTheProxyReadsBodiesAsYAML() async throws {
        let transport = StubTransport(#"{"answer":"x"}"#)
        _ = try await client(transport).text(prompt: "practical/theoretical, 1/2")
        let raw = String(decoding: try XCTUnwrap(transport.requests.first?.httpBody), as: UTF8.self)
        XCTAssertTrue(raw.contains("practical/theoretical, 1/2"))
        XCTAssertFalse(raw.contains(#"\/"#))
    }

    func testUnsetFieldsAreLeftOutRatherThanSentAsNull() async throws {
        let transport = StubTransport(#"{"answer":"x"}"#)
        _ = try await client(transport).text(prompt: "p")
        XCTAssertEqual(Set(sentBody(transport.requests.first).keys), ["prompt"])
        XCTAssertEqual(transport.requests.first?.timeoutInterval, 120)
    }

    func testConsortiumReportsItsEditorAsTheProviderAndWaitsMinutes() async throws {
        let transport = StubTransport(#"{"answer":"merged","settled":true,"stopped_reason":"clean","rounds":2,"panel":["anthropic","openai"],"editor":"anthropic","mode":"lead","calls":8,"coins_charged":8}"#)

        let answer = try await client(transport).consortium(prompt: "Q", context: "C", maxRounds: 2)

        XCTAssertEqual(answer.answer, "merged")
        XCTAssertEqual(answer.provider, "anthropic")
        XCTAssertEqual(answer.settled, true)
        XCTAssertEqual(answer.stoppedReason, "clean")
        XCTAssertEqual(answer.mode, "lead")
        let request = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(request.url?.path, "/consortium")
        XCTAssertEqual(request.timeoutInterval, 600)
        let body = sentBody(request)
        XCTAssertEqual(body["mode"] as? String, "auto")
        XCTAssertEqual(body["max_rounds"] as? Int, 2)
        XCTAssertEqual(body["context"] as? String, "C")
    }

    func testAudioDecodesTheProxysMediaAndPinsTheProvider() async throws {
        let mp3 = Data([0xFF, 0xFB, 0x90, 0x00])
        let transport = StubTransport(#"{"media_base64":"\#(mp3.base64EncodedString())","media_type":"audio/mpeg","provider":"googletts"}"#)

        let media = try await client(transport).audio(prompt: "Salama", provider: "googletts", voice: "v", language: "mg-MG")

        XCTAssertEqual(media.data, mp3)
        XCTAssertEqual(media.mediaType, "audio/mpeg")
        XCTAssertEqual(media.provider, "googletts")
        let body = sentBody(transport.requests.first)
        XCTAssertEqual(transport.requests.first?.url?.path, "/audio")
        XCTAssertEqual(body["provider"] as? String, "googletts")
        XCTAssertEqual(body["voice"] as? String, "v")
        XCTAssertEqual(body["language"] as? String, "mg-MG")
    }

    func testImageSendsSizeAndDecodesBytes() async throws {
        let png = Data([0x89, 0x50, 0x4E, 0x47])
        let transport = StubTransport(#"{"media_base64":"\#(png.base64EncodedString())","media_type":"image/png"}"#)
        let media = try await client(transport).image(prompt: "an abacus", size: "1024x1024")
        XCTAssertEqual(media.data, png)
        XCTAssertEqual(sentBody(transport.requests.first)["size"] as? String, "1024x1024")
        XCTAssertNil(media.provider)
    }

    func testAReplyWithoutMediaIsAFailureNotSilence() {
        XCTAssertThrowsError(try AICoinCapabilityClient.media(from: Data(#"{"answer":"hi"}"#.utf8))) {
            XCTAssertEqual($0 as? AICoinError, .malformedResponse)
        }
        XCTAssertThrowsError(try AICoinCapabilityClient.media(from: Data(#"{"media_base64":""}"#.utf8)))
    }

    func testAnEmptyWalletIsTypedAndRaisesTheBuySheet() async {
        let bus = AICoinEventBus()
        var events: [AICoinEvent] = []
        let subscription = bus.events.sink { events.append($0) }
        defer { subscription.cancel() }
        let transport = StubTransport(#"{"error":"insufficient aicoin balance","balance":0.5}"#, status: 402)

        do {
            _ = try await client(transport, bus: bus).text(prompt: "p")
            XCTFail("an empty wallet is not an answer")
        } catch {
            XCTAssertEqual(error as? AICoinError, .insufficientBalance(balance: 0.5))
            XCTAssertTrue(AICoinError.isTerminal(error))
        }
        XCTAssertEqual(events, [.insufficientBalance(balance: 0.5)], "this path skips AICoinRouter, so it has to say so itself")
    }

    func testASuccessfulCallMarksTheBalanceStale() async throws {
        let bus = AICoinEventBus()
        var events: [AICoinEvent] = []
        let subscription = bus.events.sink { events.append($0) }
        defer { subscription.cancel() }
        _ = try await client(StubTransport(#"{"answer":"a"}"#), bus: bus).text(prompt: "p")
        XCTAssertEqual(events, [.paidCallSucceeded])
    }

    func testNoTokenSendsNothing() async {
        let transport = StubTransport(#"{"answer":"a"}"#)
        do {
            _ = try await client(transport, token: nil).text(prompt: "p")
            XCTFail("expected missingToken")
        } catch {
            XCTAssertEqual(error as? AICoinError, .missingToken)
        }
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testOtherFailuresKeepTheirStatusAndTheServersWords() async {
        do {
            _ = try await client(StubTransport(#"{"error":"no provider answered"}"#, status: 502)).text(prompt: "p")
            XCTFail("expected error")
        } catch {
            XCTAssertEqual(error as? AICoinError, .server(status: 502, message: "no provider answered"))
            XCTAssertFalse(AICoinError.isTerminal(error), "a 502 may clear on its own")
        }
        do {
            _ = try await client(StubTransport("gateway down", status: 503)).text(prompt: "p")
            XCTFail("expected error")
        } catch {
            XCTAssertEqual(error as? AICoinError, .server(status: 503, message: "gateway down"))
        }
        do {
            _ = try await client(StubTransport(#"{"nope":1}"#)).text(prompt: "p")
            XCTFail("expected error")
        } catch {
            XCTAssertEqual(error as? AICoinError, .malformedResponse)
        }
    }

    func testIsTerminalCoversOnlyWalletErrors() {
        XCTAssertTrue(AICoinError.isTerminal(AICoinError.missingToken))
        XCTAssertTrue(AICoinError.isTerminal(AICoinError.insufficientBalance(balance: nil)))
        XCTAssertFalse(AICoinError.isTerminal(AICoinError.malformedResponse))
        XCTAssertFalse(AICoinError.isTerminal(URLError(.timedOut)))
    }

    // MARK: - Streamed consortium

    private let startLine = #"{"event":"start","mode":"panel","poll":false,"panel":["anthropic","kimi"],"editor":"anthropic","subject":"writing","quality":"best","models":{"anthropic":"claude-opus-5-5","kimi":"kimi-k3.1-pro"},"max_output_tokens":20000,"max_rounds":2}"#
    private let resultLine = #"{"event":"result","answer":"<p>Chapter</p>","subject":"writing","settled":true,"stopped_reason":"clean","rounds":1,"panel":["anthropic","kimi"],"editor":"anthropic","mode":"panel","quality":"best","calls":5,"coins_charged":40,"reads":[]}"#

    func testWithoutACallbackNothingNewIsSent() async throws {
        let transport = StubTransport(#"{"answer":"a"}"#)
        _ = try await client(transport).consortium(prompt: "Q")
        XCTAssertEqual(Set(sentBody(transport.requests.first).keys), ["prompt", "mode"],
                       "an old caller's request is byte-for-byte what it was")
    }

    func testAStreamedConsortiumSendsTheNewFieldsAndDeliversEventsAsTheyArrive() async throws {
        let transport = LineStubTransport()
        let log = EventLog()
        let library = ConsortiumLibrary(current: "1.2", entries: [
            .init(ref: "1", title: "Origins", text: "<p>Once</p>"),
            .init(ref: "1.1", title: "Rivers", text: "<p>Water</p>"),
            .init(ref: "1.2", title: "Roads"),
        ])
        let call = Task {
            try await client(transport).consortium(
                prompt: "Write 1.2", mode: "panel", library: library, quality: .best,
                maxOutputTokens: 20000, onEvent: { log.append($0) })
        }

        transport.feed.yield(startLine)
        transport.feed.yield(#"{"event":"turn","stage":"draft","round":0,"provider":"kimi","model":"kimi-k3.1-pro","status":"started"}"#)
        // Delivered while the call is still running — the point of streaming.
        for _ in 0..<200 where log.events.count < 2 { try await Task.sleep(nanoseconds: 5_000_000) }
        XCTAssertEqual(log.events.count, 2)

        transport.feed.yield(#"{"event":"ping"}"#)
        transport.feed.yield(#"{"event":"read","stage":"draft","round":0,"provider":"kimi","refs":["1.1","9"],"titles":["Rivers",""],"added":["1.1"],"notes":["9 is not in the contents."]}"#)
        transport.feed.yield(#"{"event":"turn","stage":"review","round":1,"provider":"anthropic","model":"claude-opus-5-5","status":"finished","seconds":41.2,"clean":false,"comments":"The bridge date is wrong."}"#)
        transport.feed.yield(#"{"event":"turn","stage":"review","round":1,"provider":"kimi","model":"kimi-k3.1-pro","status":"failed","error":"upstream timed out"}"#)
        transport.feed.yield(#"{"event":"something_new","x":1}"#)
        transport.feed.yield(resultLine)
        transport.feed.finish()

        let answer = try await call.value
        XCTAssertEqual(answer.answer, "<p>Chapter</p>")
        XCTAssertEqual(answer.quality, "best")
        XCTAssertEqual(answer.provider, "anthropic")
        XCTAssertEqual(answer.coinsCharged, 40)

        let events = log.events
        XCTAssertEqual(events.count, 5, "pings and unknown events are not delivered: \(events)")
        guard case .start(let start) = events[0] else { return XCTFail("first is start") }
        XCTAssertEqual(start.panel, ["anthropic", "kimi"])
        XCTAssertEqual(start.quality, "best")
        XCTAssertEqual(start.models["anthropic"], "claude-opus-5-5")
        guard case .read(let read) = events[2] else { return XCTFail("third is the read") }
        XCTAssertEqual(read.refs, ["1.1", "9"])
        XCTAssertEqual(read.titles, ["Rivers", ""])
        XCTAssertEqual(read.added, ["1.1"])
        guard case .turn(let review) = events[3] else { return XCTFail("fourth is a review") }
        XCTAssertEqual(review.status, .finished)
        XCTAssertEqual(review.clean, false)
        XCTAssertEqual(review.seconds, 41.2)
        XCTAssertEqual(review.comments, "The bridge date is wrong.")
        guard case .turn(let failed) = events[4] else { return XCTFail("fifth is a failure") }
        XCTAssertEqual(failed.status, .failed)
        XCTAssertEqual(failed.error, "upstream timed out")

        let body = sentBody(transport.requests.first)
        XCTAssertEqual(body["stream"] as? Bool, true)
        XCTAssertEqual(body["quality"] as? String, "best")
        XCTAssertEqual(body["max_output_tokens"] as? Int, 20000)
        XCTAssertEqual(body["mode"] as? String, "panel")
        let sentLibrary = try XCTUnwrap(body["library"] as? [String: Any])
        XCTAssertEqual(sentLibrary["current"] as? String, "1.2")
        let entries = try XCTUnwrap(sentLibrary["entries"] as? [[String: Any]])
        XCTAssertEqual(entries.map { $0["ref"] as? String }, ["1", "1.1", "1.2"])
        XCTAssertEqual(entries[0]["text"] as? String, "<p>Once</p>")
        XCTAssertNil(entries[2]["text"], "an unwritten part carries no text, not an empty one")
    }

    func testAnErrorLineIsTheErrorThePlainCallWouldHaveThrown() async {
        let transport = LineStubTransport()
        transport.feed.yield(startLine)
        transport.feed.yield(#"{"event":"error","status":502,"error":"no panelist answered","calls":2,"coins_charged":2,"errors":[]}"#)
        transport.feed.finish()
        do {
            _ = try await client(transport).consortium(prompt: "Q", onEvent: { _ in })
            XCTFail("expected error")
        } catch {
            XCTAssertEqual(error as? AICoinError, .server(status: 502, message: "no panelist answered"))
        }
    }

    func testARefusalBeforeTheStreamIsAnOrdinaryStatus() async {
        let bus = AICoinEventBus()
        var events: [AICoinEvent] = []
        let subscription = bus.events.sink { events.append($0) }
        defer { subscription.cancel() }
        let transport = LineStubTransport(status: 402)
        transport.feed.yield(#"{"error":"insufficient aicoin balance","balance":0}"#)
        transport.feed.finish()
        do {
            _ = try await client(transport, bus: bus).consortium(prompt: "Q", onEvent: { _ in })
            XCTFail("expected 402")
        } catch {
            XCTAssertEqual(error as? AICoinError, .insufficientBalance(balance: 0))
        }
        XCTAssertEqual(events, [.insufficientBalance(balance: 0)])
    }

    func testAStreamThatEndsWithoutAResultIsMalformed() async {
        let transport = LineStubTransport()
        transport.feed.yield(startLine)
        transport.feed.finish()
        do {
            _ = try await client(transport).consortium(prompt: "Q", onEvent: { _ in })
            XCTFail("expected error")
        } catch {
            XCTAssertEqual(error as? AICoinError, .malformedResponse)
        }
    }

    func testATransportThatCannotStreamStillDeliversEveryEvent() async throws {
        let log = EventLog()
        let transport = StubTransport([startLine, #"{"event":"ping"}"#, resultLine].joined(separator: "\n") + "\n")
        let answer = try await client(transport).consortium(prompt: "Q", onEvent: { log.append($0) })
        XCTAssertEqual(answer.answer, "<p>Chapter</p>")
        XCTAssertEqual(log.events.count, 1)
        XCTAssertEqual(sentBody(transport.requests.first)["stream"] as? Bool, true)
    }

    func testTheRouterPassesAStreamToTheProxysOwnEndpointsThrough() async throws {
        // Apps give the router to everything; if it swallowed the stream, every event would
        // arrive at the end.
        let inner = LineStubTransport()
        let router = AICoinRouter(underlying: inner, baseURL: base, eventBus: AICoinEventBus(), tokenProvider: { "tok" })
        let log = EventLog()
        let call = Task { try await client(router).consortium(prompt: "Q", onEvent: { log.append($0) }) }
        inner.feed.yield(startLine)
        for _ in 0..<200 where log.events.isEmpty { try await Task.sleep(nanoseconds: 5_000_000) }
        XCTAssertEqual(log.events.count, 1, "delivered before the call ended")
        inner.feed.yield(resultLine)
        inner.feed.finish()
        _ = try await call.value
    }

    // MARK: - Durable calls

    func testACallIDIsSentAndResumedCallsAreReportedWithTheirNumbers() async throws {
        let transport = LineStubTransport()
        let log = EventLog()
        let call = Task {
            try await client(transport).consortium(prompt: "Q", callID: "chapter-7", onEvent: { log.append($0) })
        }
        transport.feed.yield(#"{"event":"start","seq":1,"mode":"panel","panel":["kimi"],"call_id":"chapter-7","resumed":true}"#)
        transport.feed.yield(#"{"event":"turn","seq":2,"stage":"draft","round":0,"provider":"kimi","model":"kimi-k3.1-pro","status":"started","fell_back_from":"kimi-k3.1-pro-highspeed"}"#)
        transport.feed.yield(#"{"event":"resumed","seq":3,"from":"review","round":2}"#)
        transport.feed.yield(#"{"event":"result","seq":4,"answer":"done","call_id":"chapter-7"}"#)
        transport.feed.finish()
        _ = try await call.value

        XCTAssertEqual(sentBody(transport.requests.first)["call_id"] as? String, "chapter-7")
        let events = log.events
        XCTAssertEqual(events.map(\.seq), [1, 2, 3], "a client that comes back skips what it has shown by seq")
        guard case .start(let start) = events[0] else { return XCTFail("start first") }
        XCTAssertTrue(start.resumed)
        XCTAssertEqual(start.callID, "chapter-7")
        guard case .turn(let turn) = events[1] else { return XCTFail("then a turn") }
        XCTAssertEqual(turn.fellBackFrom, "kimi-k3.1-pro-highspeed")
        XCTAssertEqual(turn.model, "kimi-k3.1-pro", "model is the one that answered")
        XCTAssertEqual(events[2], .resumed(.init(seq: 3, from: "review", round: 2)))
    }

    func testAStreamThatGoesSilentIsAStalledConnectionNotAnEndlessWait() async {
        // After a proxy restart the phone saw a stream go quiet without closing, and sat on stale
        // progress for good. The proxy pings every 15 s, so silence past the idle timeout means
        // the connection is gone.
        let transport = LineStubTransport()
        let log = EventLog()
        transport.feed.yield(startLine)
        let started = Date()
        do {
            _ = try await client(transport).consortium(prompt: "Q", callID: "c1", idleTimeout: 0.3,
                                                       onEvent: { log.append($0) })
            XCTFail("a silent stream must not wait forever")
        } catch {
            XCTAssertTrue(AICoinCapabilityClient.isStreamStalled(error), "\(error)")
            XCTAssertEqual((error as? URLError)?.code, .networkConnectionLost,
                           "a transport error retry policies already treat as transient")
            XCTAssertFalse(AICoinError.isTerminal(error))
        }
        XCTAssertLessThan(Date().timeIntervalSince(started), 5)
        XCTAssertEqual(log.events.count, 1, "what arrived before the silence was delivered")
    }

    func testPingsKeepAStreamAlive() async throws {
        let transport = LineStubTransport()
        let call = Task { try await client(transport).consortium(prompt: "Q", idleTimeout: 0.4, onEvent: { _ in }) }
        transport.feed.yield(startLine)
        for _ in 0..<6 {
            try await Task.sleep(nanoseconds: 150_000_000)
            transport.feed.yield(#"{"event":"ping"}"#)
        }
        transport.feed.yield(resultLine)
        transport.feed.finish()
        let answer = try await call.value
        XCTAssertEqual(answer.answer, "<p>Chapter</p>", "0.9 s of pings is not 0.4 s of silence")
    }

    func testNotStalledIsNotMistakenForStalled() {
        XCTAssertFalse(AICoinCapabilityClient.isStreamStalled(URLError(.networkConnectionLost)))
        XCTAssertFalse(AICoinCapabilityClient.isStreamStalled(AICoinError.malformedResponse))
    }

    func testAnEditorHandOverAndASkippedProviderAreReported() async throws {
        let transport = LineStubTransport()
        let log = EventLog()
        let call = Task { try await client(transport).consortium(prompt: "Q", onEvent: { log.append($0) }) }
        transport.feed.yield(#"{"event":"start","seq":1,"mode":"panel","panel":["kimi","google"],"editor":"kimi","skipped":[{"provider":"anthropic","reason":"out of credit since 2026-10-27T08:00:00Z (HTTP 400): Your credit balance is too low"}]}"#)
        transport.feed.yield(#"{"event":"editor_changed","seq":2,"from":"kimi","to":"google","reason":"upstream timed out","stage":"merge","round":0}"#)
        transport.feed.yield(#"{"event":"result","seq":3,"answer":"a","editor":"google"}"#)
        transport.feed.finish()
        let answer = try await call.value

        XCTAssertEqual(answer.provider, "google", "the result names the editor that finished the answer")
        guard case .start(let start) = log.events.first else { return XCTFail("start first") }
        XCTAssertEqual(start.skipped.map(\.provider), ["anthropic"])
        XCTAssertTrue(start.skipped[0].reason.contains("credit balance"))
        XCTAssertEqual(log.events.last, .editorChanged(.init(seq: 2, from: "kimi", to: "google",
                                                             reason: "upstream timed out", stage: "merge", round: 0)))
        XCTAssertEqual(log.events.last?.seq, 2)
    }
}
