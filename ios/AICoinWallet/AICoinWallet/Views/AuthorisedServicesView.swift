import SwiftUI

/// Letting a service spend from this wallet by scanning its code, and taking that permission back.
///
/// The alternative it replaces is pasting a token into whatever asked for one. That works, and it
/// is the wrong shape for a phone: the token is long, it is secret, and pasting it is the moment it
/// ends up in a screenshot. Worse, a pasted token could only ever be withdrawn by revoking *every*
/// token the wallet had issued, because a token is self-verifying — nothing about the token itself
/// can be taken away.
///
/// What can be taken away is this wallet's permission for whoever holds it. That is what a grant
/// is, and it is why approving mints a token that names one: revoking the grant stops every token
/// that service holds at once.
struct AuthorisedServicesView: View {
    let keys: WalletKeys

    @State private var grants: [ProxyAPI.Grant]?
    @State private var loadFailed = false
    @State private var isScanning = false
    @State private var pendingId: String?
    @State private var revoking: Set<String> = []
    @State private var scanProblem: String?
    @State private var error: String?

    var body: some View {
        List {
            if let grants, !grants.isEmpty {
                Section {
                    ForEach(grants) { grant in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(grant.service.isEmpty ? "Unnamed service" : grant.service)
                                Text(Self.since(grant.granted))
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if revoking.contains(grant.id) { ProgressView() }
                        }
                        .swipeActions {
                            Button("Revoke", role: .destructive) { revoke(grant) }
                        }
                        .contextMenu {
                            Button("Revoke Access", systemImage: "xmark.shield", role: .destructive) { revoke(grant) }
                        }
                    }
                } footer: {
                    Text("Swipe left to revoke. A revoked service stops working immediately.")
                }
            } else if grants != nil {
                ContentUnavailableView {
                    Label("No Authorised Services", systemImage: "checkmark.shield")
                } description: {
                    Text("Scan the code a service shows you to let it make AI calls billed to this wallet, without ever holding your key.")
                } actions: {
                    Button("Scan Code") { isScanning = true }
                        .buttonStyle(.borderedProminent)
                }
            } else if loadFailed {
                ContentUnavailableView("Couldn't Load Services", systemImage: "wifi.exclamationmark",
                                       description: Text("Pull to try again."))
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Authorised Services")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Scan Code", systemImage: "qrcode.viewfinder") { isScanning = true }
            }
        }
        .task { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $isScanning) {
            ScannerSheet(title: "Scan Service Code") { scanned in
                isScanning = false
                let id = Self.identifier(in: scanned)
                if id.isEmpty {
                    scanProblem = "That code isn't a service asking for access."
                } else {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { pendingId = id }
                }
            }
        }
        .sheet(item: Binding(get: { pendingId.map(PendingID.init) }, set: { pendingId = $0?.id })) { pending in
            AuthorisationRequestView(keys: keys, id: pending.id) {
                Task { await load() }
            }
        }
        .alert("Unrecognised Code", isPresented: Binding(get: { scanProblem != nil }, set: { if !$0 { scanProblem = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(scanProblem ?? "")
        }
        .alert("Couldn't Revoke", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(error ?? "")
        }
    }

    private struct PendingID: Identifiable { let id: String }

    private func load() async {
        do {
            grants = try await ProxyAPI.fetchGrants(keys: keys)
            loadFailed = false
        } catch {
            loadFailed = grants == nil
        }
    }

    private func revoke(_ grant: ProxyAPI.Grant) {
        revoking.insert(grant.id)
        Task {
            defer { revoking.remove(grant.id) }
            do {
                try await ProxyAPI.revokeGrant(keys: keys, id: grant.id)
                withAnimation { grants?.removeAll { $0.id == grant.id } }
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    /// The identifier out of whatever was scanned: the last path component of a URL, or the whole
    /// string when it is bare. Checked for shape rather than trusted, since it goes into a request
    /// path.
    static func identifier(in scanned: String) -> String {
        var text = scanned.trimmingCharacters(in: .whitespacesAndNewlines)
        if let hash = text.firstIndex(of: "#") { text = String(text[..<hash]) }
        if let query = text.firstIndex(of: "?") { text = String(text[..<query]) }
        if let slash = text.lastIndex(of: "/") { text = String(text[text.index(after: slash)...]) }
        let allowed = CharacterSet(charactersIn: "0123456789abcdef-")
        guard !text.isEmpty, text.count <= 64,
              text.unicodeScalars.allSatisfy({ allowed.contains($0) }) else {
            return ""
        }
        return text
    }

    private static func since(_ epochSeconds: Int64) -> String {
        guard epochSeconds > 0 else { return "Allowed" }
        let date = Date(timeIntervalSince1970: TimeInterval(epochSeconds))
        return "Allowed since \(date.formatted(date: .abbreviated, time: .omitted))"
    }
}

/// A service asking for access, read from the proxy before anything is agreed to: the code carries
/// only an identifier, and a QR is a thing anybody can print — agreeing to one on the strength of
/// having scanned it is agreeing to whatever was printed.
struct AuthorisationRequestView: View {
    let keys: WalletKeys
    let id: String
    var onAllowed: () -> Void = {}

    private static let expiryOptions: [(label: String, days: Int)] = [
        ("7 Days", 7), ("30 Days", 30), ("90 Days", 90)
    ]

    @Environment(\.dismiss) private var dismiss
    @State private var request: ProxyAPI.Authorisation?
    @State private var loadError: String?
    @State private var expiryDays = 30
    @State private var isAllowing = false
    @State private var allowError: String?
    @State private var allowed = false

    var body: some View {
        NavigationStack {
            Group {
                if let request {
                    if request.status == "pending" {
                        form(for: request)
                    } else {
                        ContentUnavailableView("Already Answered", systemImage: "checkmark.circle",
                                               description: Text("This request has already been allowed or has expired. Ask the service for a new code."))
                    }
                } else if let loadError {
                    ContentUnavailableView("Request Not Found", systemImage: "questionmark.circle",
                                           description: Text(loadError))
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Allow Access")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(request?.status == "pending" ? "Don't Allow" : "Close") { dismiss() }
                }
            }
        }
        .task { await load() }
        .sensoryFeedback(.success, trigger: allowed)
    }

    private func form(for request: ProxyAPI.Authorisation) -> some View {
        Form {
            Section {
                VStack(spacing: 8) {
                    Image(systemName: "app.badge.checkmark")
                        .font(.system(size: 44))
                        .foregroundStyle(.tint)
                    Text(request.service?.isEmpty == false ? request.service! : "An unnamed service")
                        .font(.title2.bold())
                        .multilineTextAlignment(.center)
                    Text("wants to make AI calls billed to this wallet")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    if let note = request.note, !note.isEmpty {
                        Text(note)
                            .font(.callout)
                            .multilineTextAlignment(.center)
                            .padding(.top, 4)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }
            Section {
                Picker("Allow For", selection: $expiryDays) {
                    ForEach(Self.expiryOptions, id: \.days) { option in
                        Text(option.label).tag(option.days)
                    }
                }
            } footer: {
                Text("It never sees your private key. You can revoke it any time under Authorised Services.")
            }
            if let allowError {
                Section {
                    Label(allowError, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red)
                }
            }
            Section {
                Button {
                    allow()
                } label: {
                    HStack {
                        Spacer()
                        if isAllowing { ProgressView() } else { Text("Allow").bold() }
                        Spacer()
                    }
                }
                .disabled(isAllowing)
            }
        }
    }

    private func load() async {
        do {
            request = try await ProxyAPI.fetchAuthorisation(id: id)
        } catch {
            loadError = "The code may have expired — requests last ten minutes. Ask the service for a new one."
        }
    }

    private func allow() {
        isAllowing = true
        allowError = nil
        Task {
            defer { isAllowing = false }
            do {
                try await ProxyAPI.approveAuthorisation(keys: keys, id: id, expiresInSeconds: expiryDays * 86400)
                allowed = true
                onAllowed()
                dismiss()
            } catch {
                allowError = error.localizedDescription
            }
        }
    }
}
