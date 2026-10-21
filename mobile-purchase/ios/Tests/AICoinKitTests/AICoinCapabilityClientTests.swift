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

private func sentBody(_ request: URLRequest?) -> [String: Any] {
    guard let data = request?.httpBody, let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return [:] }
    return object
}

final class AICoinCapabilityClientTests: XCTestCase {
    private let base = URL(string: "https://proxy.example")!

    private func client(_ transport: StubTransport, bus: AICoinEventBus = AICoinEventBus(),
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
}
