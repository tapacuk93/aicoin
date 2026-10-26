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

    public struct Start: Sendable, Equatable {
        /// `"panel"` or `"lead"`.
        public let mode: String
        public let panel: [String]
        public let editor: String?
        public let subject: String?
        /// `"standard"` or `"best"`.
        public let quality: String?
        /// Provider -> model id, for every panelist.
        public let models: [String: String]

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
            return .start(Start(
                mode: string("mode") ?? "panel", panel: strings("panel"), editor: string("editor"),
                subject: string("subject"), quality: string("quality"),
                models: (object["models"] as? [String: Any])?.compactMapValues { $0 as? String } ?? [:]
            ))
        case "turn":
            guard let provider = string("provider"), let status = string("status").flatMap(Turn.Status.init)
            else { return nil }
            return .turn(Turn(
                stage: string("stage") ?? "", round: int("round"), provider: provider, model: string("model"),
                status: status, seconds: (object["seconds"] as? NSNumber)?.doubleValue,
                clean: object["clean"] as? Bool, comments: string("comments"), error: string("error")
            ))
        case "read":
            guard let provider = string("provider") else { return nil }
            return .read(Read(
                stage: string("stage") ?? "", round: int("round"), provider: provider,
                refs: strings("refs"), titles: strings("titles"), added: strings("added")
            ))
        default:
            return nil
        }
    }
}
