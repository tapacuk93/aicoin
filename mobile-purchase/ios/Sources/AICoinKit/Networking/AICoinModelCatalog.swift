import Foundation

/// Which model stands behind each provider right now, as the proxy publishes it in `GET /skills`
/// under `"models"`.
///
/// An app that keeps what a model wrote — Infinite AI Book keeps every section it generated —
/// needs this to notice when that writing has been overtaken. The provider name cannot tell it:
/// "anthropic" is the same string across every model Anthropic ships, and the proxy's model ids are
/// config, so they change with a restart and no app release. Stamping each piece of writing with a
/// `textSignature` when it is written, and comparing against the current one later, is what lets a
/// reader offer "rewrite with the newer model" only when there is one.
///
/// The proxy lists only providers its routing would actually reach (keyed, with a model, not down),
/// with the exact model a call would send — so a provider missing here is one a call cannot use now,
/// not one whose model is unknown.
public struct AICoinModelCatalog: Decodable, Sendable, Equatable {
    /// `POST /text`: provider → the model a single-provider call sends.
    public var text: [String: String]
    /// `POST /image`: provider → model.
    public var image: [String: String]
    /// `POST /audio`: provider → model.
    public var audio: [String: String]
    /// Who sits on a `POST /consortium` panel, as `"provider:model"` — kept as the server lists them
    /// rather than split, because the pair is what identifies the panel and nothing here needs them
    /// apart.
    public var consortiumPanel: [String]
    /// The panelist that merges and revises, by provider name; nil when there is no panel.
    public var consortiumEditor: String?

    public init(text: [String: String] = [:], image: [String: String] = [:], audio: [String: String] = [:],
                consortiumPanel: [String] = [], consortiumEditor: String? = nil) {
        self.text = text
        self.image = image
        self.audio = audio
        self.consortiumPanel = consortiumPanel
        self.consortiumEditor = consortiumEditor
    }

    private enum CodingKeys: String, CodingKey {
        case text, image, audio, consortium
    }

    private enum ConsortiumKeys: String, CodingKey {
        case panel, editor
    }

    /// Decodes the `"models"` object itself. Every part is optional: a proxy that adds a capability
    /// or drops the consortium should leave the rest readable, not turn the whole catalog into an
    /// error that an app would treat as "nothing known".
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        text = try c.decodeIfPresent([String: String].self, forKey: .text) ?? [:]
        image = try c.decodeIfPresent([String: String].self, forKey: .image) ?? [:]
        audio = try c.decodeIfPresent([String: String].self, forKey: .audio) ?? [:]
        if c.contains(.consortium), try !c.decodeNil(forKey: .consortium) {
            let consortium = try c.nestedContainer(keyedBy: ConsortiumKeys.self, forKey: .consortium)
            consortiumPanel = try consortium.decodeIfPresent([String].self, forKey: .panel) ?? []
            consortiumEditor = try consortium.decodeIfPresent(String.self, forKey: .editor)
        } else {
            consortiumPanel = []
            consortiumEditor = nil
        }
    }

    /// A stable string naming what writes text for `provider` now, for storing next to the text and
    /// comparing later: `"anthropic/claude-sonnet-5"`.
    ///
    /// - nil or `"consortium"`: `"consortium/"` + the panel, sorted and joined by `"+"`. Sorted
    ///   because the panel's order is the server's ranking, which can change with no model changing,
    ///   and a signature that moved on a reorder would offer rewrites for nothing.
    /// - `"automatic"`: nil. Automatic routing picks a provider per request by subject, so there is
    ///   no one model behind it; the signature worth storing is the one built from the response's
    ///   own `provider` and `model` (see `signature(provider:model:)`).
    /// - Any other provider: nil when this catalog does not list it — unknown, which a caller must
    ///   not read as "changed".
    public func textSignature(forProvider provider: String?) -> String? {
        let name = provider?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        switch name {
        case "", "consortium":
            guard !consortiumPanel.isEmpty else { return nil }
            return "consortium/" + consortiumPanel.sorted().joined(separator: "+")
        case "automatic":
            return nil
        default:
            guard let model = text[name] else { return nil }
            return Self.signature(provider: name, model: model)
        }
    }

    /// `"provider/model"`. One spelling, so a signature stamped from a response and one read from
    /// the catalog compare equal when they name the same model.
    public static func signature(provider: String, model: String) -> String {
        "\(provider)/\(model)"
    }

    /// `GET /skills`, decoded down to `"models"`.
    ///
    /// A proxy older than the field answers without it, and that is an empty catalog rather than an
    /// error: the app learns nothing about models, which is the truth, and still works. Public and
    /// unauthenticated on the proxy, so no token is sent.
    ///
    /// Errors: any non-2xx → `AICoinError.server(status:message:)`; a body that is not JSON →
    /// `AICoinError.malformedResponse`. Transport errors pass through untouched.
    public static func fetch(baseURL: URL = AICoinConfig.defaultBaseURL,
                             transport: any HTTPTransport = URLSession.shared) async throws -> AICoinModelCatalog {
        var request = URLRequest(url: baseURL.appendingPathComponent("skills"))
        request.httpMethod = "GET"
        let (data, response) = try await transport.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw AICoinError.server(status: status, message: AICoinCapabilityClient.errorMessage(from: data))
        }
        struct Envelope: Decodable { let models: AICoinModelCatalog? }
        guard let envelope = try? JSONDecoder().decode(Envelope.self, from: data) else {
            throw AICoinError.malformedResponse
        }
        return envelope.models ?? AICoinModelCatalog()
    }
}
