import Foundation

/// The larger work a `POST /consortium` request belongs to — a book's contents, in reading order —
/// sent as `library`. See CONTRACT.md's "Consortium" section.
///
/// The proxy puts only an *index* of it in front of the panel (ref, title, length or "not written
/// yet", the `current` part marked); a panelist that needs an earlier part's text asks for it by
/// ref and the proxy adds it to the record. So sending the whole book costs upload, not tokens: the
/// panel pays only for what it reads.
public struct ConsortiumLibrary: Sendable, Equatable {
    public struct Entry: Sendable, Equatable {
        /// Dotted position in the tree: `"1"`, `"1.1"`, `"1.1.2"`.
        public let ref: String
        public let title: String
        /// The part's text (HTML is fine), or nil for a part that is planned but not written yet.
        public let text: String?

        public init(ref: String, title: String, text: String? = nil) {
            self.ref = ref
            self.title = title
            self.text = text
        }
    }

    /// The ref of the part this request is writing, if it is one of the entries.
    public let current: String?
    public let entries: [Entry]

    public init(current: String?, entries: [Entry]) {
        self.current = current
        self.entries = entries
    }

    var json: [String: Any] {
        var body: [String: Any] = [
            "entries": entries.map { entry -> [String: Any] in
                var item: [String: Any] = ["ref": entry.ref, "title": entry.title]
                if let text = entry.text { item["text"] = text }
                return item
            },
        ]
        if let current { body["current"] = current }
        return body
    }
}

/// What to push when a durable consortium call (one with a `callID`) ends while the app is not
/// watching it — so the app can let iOS suspend it instead of holding a background task that iOS
/// cuts short. Sent only to this wallet's devices registered for `topic` (see
/// `AICoinCapabilityClient.registerPushDevice`), and only if no client has the call's stream open
/// when it ends. The push carries `data` beside `aps`, plus `call_id`, so a tap can re-attach.
public struct ConsortiumNotify: Sendable, Equatable {
    /// The app's bundle id — the APNs topic.
    public var topic: String
    public var title: String
    public var body: String?
    /// Opaque keys for the app, at most 1 KB as JSON.
    public var data: [String: String]
    /// What to say if the call fails; without them a failure pushes nothing.
    public var failureTitle: String?
    public var failureBody: String?

    public init(topic: String, title: String, body: String? = nil, data: [String: String] = [:],
                failureTitle: String? = nil, failureBody: String? = nil) {
        self.topic = topic
        self.title = title
        self.body = body
        self.data = data
        self.failureTitle = failureTitle
        self.failureBody = failureBody
    }

    var json: [String: Any] {
        var out: [String: Any] = ["topic": topic, "title": title]
        if let body { out["body"] = body }
        if !data.isEmpty { out["data"] = data }
        if let failureTitle { out["failure_title"] = failureTitle }
        if let failureBody { out["failure_body"] = failureBody }
        return out
    }
}

/// Which models a consortium seats. `.best` is each provider's strongest model, at several times
/// the price per turn.
public enum ConsortiumQuality: String, Sendable {
    case standard
    case best
}

/// What a streamed consortium reports while it runs — see `AICoinCapabilityClient.consortium`'s
/// `onEvent`. Keep-alive pings are not delivered; the final result is the call's return value.
public enum ConsortiumEvent: Sendable, Equatable {
    /// The panel is seated and the first turn is about to be paid for.
    case start(Start)
    /// A panelist's turn started, finished or failed.
    case turn(Turn)
    /// A panelist asked to read parts of the library; they are now in the panel's record.
    case read(Read)
    /// The proxy running the call went away and another picked it up from its last checkpoint:
    /// what had finished stands, and the turn that was in flight starts again.
    case resumed(Resumed)
    /// The editor could not do its merge or revision — out of credit, an error, a reply that was
    /// not one whole answer — and another panelist took the turn, and edits from now on.
    case editorChanged(EditorChanged)

    /// The event's place in the call, counting from 1 — the same on every attach, so a client
    /// coming back with a `callID` can skip the ones it has already shown. 0 from a proxy that
    /// does not number events.
    public var seq: Int {
        switch self {
        case .start(let start): return start.seq
        case .turn(let turn): return turn.seq
        case .read(let read): return read.seq
        case .resumed(let resumed): return resumed.seq
        case .editorChanged(let changed): return changed.seq
        }
    }

    public struct EditorChanged: Sendable, Equatable {
        public let seq: Int
        public let from: String
        public let to: String
        /// Why the editor gave the turn up, in the proxy's words.
        public let reason: String
        /// `"draft"` (a lead that could not write), `"merge"` or `"revise"`.
        public let stage: String
        public let round: Int

        public init(seq: Int = 0, from: String, to: String, reason: String, stage: String, round: Int) {
            self.seq = seq
            self.from = from
            self.to = to
            self.reason = reason
            self.stage = stage
            self.round = round
        }
    }

    /// A provider kept off the panel, and why — today only an account that cannot pay.
    public struct Skipped: Sendable, Equatable {
        public let provider: String
        public let reason: String

        public init(provider: String, reason: String) {
            self.provider = provider
            self.reason = reason
        }
    }

    public struct Resumed: Sendable, Equatable {
        public let seq: Int
        /// The stage the call was in: `"draft"`, `"merge"`, `"review"` or `"revise"`.
        public let from: String
        public let round: Int

        public init(seq: Int = 0, from: String, round: Int) {
            self.seq = seq
            self.from = from
            self.round = round
        }
    }

    public struct Start: Sendable, Equatable {
        /// `"panel"` or `"lead"`.
        public let mode: String
        public let panel: [String]
        public let editor: String?
        public let subject: String?
        /// `"standard"` or `"best"`.
        public let quality: String?
        /// Provider -> model id, for every panelist: the first each will try.
        public let models: [String: String]
        public var seq: Int = 0
        /// The call's id, when it was given one.
        public var callID: String?
        /// True when this call was picked up from a checkpoint after the proxy running it went away.
        public var resumed: Bool = false
        /// Providers that would have been on the panel but were left off (out of credit).
        public var skipped: [Skipped] = []

        public init(mode: String, panel: [String], editor: String? = nil, subject: String? = nil,
                    quality: String? = nil, models: [String: String] = [:]) {
            self.mode = mode
            self.panel = panel
            self.editor = editor
            self.subject = subject
            self.quality = quality
            self.models = models
        }
    }

    public struct Turn: Sendable, Equatable {
        public enum Status: String, Sendable {
            case started, finished, failed
        }

        /// `"draft"`, `"merge"`, `"review"` or `"revise"`.
        public let stage: String
        /// 0 for drafting and merging; the review round otherwise.
        public let round: Int
        public let provider: String
        public let model: String?
        public let status: Status
        /// How long the turn took, on `finished` and on an editor's unusable reply.
        public let seconds: Double?
        /// Reviews only: whether the reviewer had nothing to say.
        public let clean: Bool?
        /// Reviews with comments: the first few hundred characters of them.
        public let comments: String?
        /// `failed` only: why.
        public let error: String?
        public var seq: Int = 0
        /// When this turn's first model failed and another of the provider's models took it over:
        /// the one it gave up on. `model` is the one that answered.
        public var fellBackFrom: String?

        /// Public so an app can describe or replay events in its own tests.
        public init(stage: String, round: Int, provider: String, model: String? = nil, status: Status,
                    seconds: Double? = nil, clean: Bool? = nil, comments: String? = nil, error: String? = nil) {
            self.stage = stage
            self.round = round
            self.provider = provider
            self.model = model
            self.status = status
            self.seconds = seconds
            self.clean = clean
            self.comments = comments
            self.error = error
        }
    }

    public struct Read: Sendable, Equatable {
        public let stage: String
        public let round: Int
        public let provider: String
        /// The refs asked for, and their titles ("" for a ref not in the library).
        public let refs: [String]
        public let titles: [String]
        /// The refs actually added to the record — not those already there, unwritten or unknown.
        public let added: [String]
        public var seq: Int = 0

        public init(stage: String, round: Int, provider: String, refs: [String], titles: [String], added: [String]) {
            self.stage = stage
            self.round = round
            self.provider = provider
            self.refs = refs
            self.titles = titles
            self.added = added
        }
    }

    /// One NDJSON line as an event, or nil for a line that is not one of these (a ping, the
    /// result, or an event a newer proxy added).
    static func parse(_ object: [String: Any]) -> ConsortiumEvent? {
        func string(_ key: String) -> String? { object[key] as? String }
        func strings(_ key: String) -> [String] { (object[key] as? [Any])?.compactMap { $0 as? String } ?? [] }
        func int(_ key: String) -> Int { (object[key] as? NSNumber)?.intValue ?? 0 }
        switch string("event") {
        case "start":
            var start = Start(
                mode: string("mode") ?? "panel", panel: strings("panel"), editor: string("editor"),
                subject: string("subject"), quality: string("quality"),
                models: (object["models"] as? [String: Any])?.compactMapValues { $0 as? String } ?? [:]
            )
            start.seq = int("seq")
            start.callID = string("call_id")
            start.resumed = object["resumed"] as? Bool ?? false
            start.skipped = (object["skipped"] as? [[String: Any]] ?? []).compactMap { item in
                guard let provider = item["provider"] as? String else { return nil }
                return Skipped(provider: provider, reason: item["reason"] as? String ?? "")
            }
            return .start(start)
        case "editor_changed":
            guard let from = string("from"), let to = string("to") else { return nil }
            return .editorChanged(EditorChanged(seq: int("seq"), from: from, to: to, reason: string("reason") ?? "",
                                                stage: string("stage") ?? "", round: int("round")))
        case "resumed":
            return .resumed(Resumed(seq: int("seq"), from: string("from") ?? "", round: int("round")))
        case "turn":
            guard let provider = string("provider"), let status = string("status").flatMap(Turn.Status.init)
            else { return nil }
            var turn = Turn(
                stage: string("stage") ?? "", round: int("round"), provider: provider, model: string("model"),
                status: status, seconds: (object["seconds"] as? NSNumber)?.doubleValue,
                clean: object["clean"] as? Bool, comments: string("comments"), error: string("error")
            )
            turn.seq = int("seq")
            turn.fellBackFrom = string("fell_back_from")
            return .turn(turn)
        case "read":
            guard let provider = string("provider") else { return nil }
            var read = Read(
                stage: string("stage") ?? "", round: int("round"), provider: provider,
                refs: strings("refs"), titles: strings("titles"), added: strings("added")
            )
            read.seq = int("seq")
            return .read(read)
        default:
            return nil
        }
    }
}
