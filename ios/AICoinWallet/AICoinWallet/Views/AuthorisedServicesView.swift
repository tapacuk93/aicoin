import SwiftUI

/// Letting a service spend from this wallet by scanning its code, and taking
/// that permission back afterwards.
///
/// The alternative it replaces is pasting a token into whatever asked for one.
/// That works, and it is the wrong shape for a phone: the token is long, it is
/// secret, and pasting it is the moment it ends up in a screenshot. Worse, a
/// pasted token could only ever be withdrawn by revoking *every* token the
/// wallet had issued, because a token is self-verifying — the address inside
/// it is the key that checks it — so nothing about the token itself can be
/// taken away.
///
/// What can be taken away is this wallet's permission for whoever holds it.
/// That is what a grant is, and it is why approving here mints a token that
/// names one: the row below with a REVOKE button beside it is the same grant,
/// and pressing it stops every token that service holds at once.
///
/// Nothing is approved without being read first. The code carries an
/// identifier and nothing else, so what the service is and what it says it
/// wants are fetched from the proxy and shown — a QR is a thing anybody can
/// print, and agreeing to one on the strength of having scanned it is agreeing
/// to whatever was printed.
struct AuthorisedServicesView: View {
    let keys: WalletKeys

    private static let expiryOptions: [(label: String, days: Int)] = [
        ("7 days", 7), ("30 days", 30), ("90 days", 90)
    ]

    @State private var isScanning = false
    @State private var pendingId = ""
    @State private var pendingService = ""
    @State private var pendingNote = ""
    @State private var expiryDays = 30
    @State private var message = ""
    @State private var isError = false
    @State private var isWorking = false
    @State private var grants: [ProxyAPI.Grant] = []
    @State private var revoking: String?

    var body: some View {
        WalletCard(title: "Authorised services") {
            Text("Scan the code a service shows you and it can make AI-proxy calls billed to this wallet, without ever holding your key. Revoke it here and it stops immediately.")
                .font(.caption)
                .foregroundColor(WalletTheme.muted)

            if pendingId.isEmpty {
                PrimaryButton(title: "Scan a code") { isScanning = true }
            } else {
                // What is being agreed to, before agreeing to it.
                VStack(alignment: .leading, spacing: 6) {
                    Text(pendingService.isEmpty ? "an unnamed service" : pendingService)
                        .font(.headline)
                        .foregroundColor(WalletTheme.accent)
                    if !pendingNote.isEmpty {
                        Text(pendingNote).font(.caption).foregroundColor(WalletTheme.muted)
                    }
                    Text("wants to make AI-proxy calls billed to this wallet")
                        .font(.caption)
                        .foregroundColor(WalletTheme.muted)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(WalletTheme.background)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(WalletTheme.border))

                Text("Allow for").font(.caption).foregroundColor(WalletTheme.muted)
                Picker("Allow for", selection: $expiryDays) {
                    ForEach(Self.expiryOptions, id: \.days) { option in
                        Text(option.label).tag(option.days)
                    }
                }
                .pickerStyle(.segmented)

                PrimaryButton(title: "Allow", disabled: isWorking) { approve() }
                SecondaryButton(title: "Not this one") { clearPending() }
            }

            StatusMessage(text: message, isError: isError)

            if !grants.isEmpty {
                Text("Allowed now").font(.caption).foregroundColor(WalletTheme.muted)
                ForEach(grants) { grant in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(grant.service).foregroundColor(WalletTheme.accent)
                            Text(Self.since(grant.granted))
                                .font(.caption2)
                                .foregroundColor(WalletTheme.muted)
                        }
                        Spacer()
                        DangerButton(title: "Revoke", disabled: revoking == grant.id) {
                            revoke(grant)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }

            SecondaryButton(title: "Refresh") { loadGrants() }
        }
        .sheet(isPresented: $isScanning) {
            QRScannerView(
                onScan: { scanned in
                    isScanning = false
                    look(at: scanned)
                },
                onCancel: { isScanning = false })
        }
        .task { loadGrants() }
    }

    /// A scanned code names a request; the request says who is asking.
    ///
    /// The code may be the identifier on its own or a URL with it on the end,
    /// because a code that is also a link is one a phone with no wallet
    /// installed can still do something useful with.
    private func look(at scanned: String) {
        let id = Self.identifier(in: scanned)
        guard !id.isEmpty else {
            message = "That code is not an authorisation request."
            isError = true
            return
        }
        message = "Reading..."
        isError = false
        Task {
            do {
                let request = try await ProxyAPI.fetchAuthorisation(id: id)
                guard request.status == "pending" else {
                    message = "That request has already been answered."
                    isError = true
                    return
                }
                pendingId = id
                pendingService = request.service ?? ""
                pendingNote = request.note ?? ""
                message = ""
            } catch {
                message = error.localizedDescription
                isError = true
            }
        }
    }

    private func approve() {
        isWorking = true
        message = "Allowing..."
        isError = false
        let id = pendingId
        Task {
            defer { isWorking = false }
            do {
                try await ProxyAPI.approveAuthorisation(keys: keys, id: id,
                                                        expiresInSeconds: expiryDays * 86400)
                clearPending()
                message = "Allowed. It can be revoked here at any time."
                isError = false
                loadGrants()
            } catch {
                message = error.localizedDescription
                isError = true
            }
        }
    }

    private func revoke(_ grant: ProxyAPI.Grant) {
        revoking = grant.id
        message = "Revoking \(grant.service)..."
        isError = false
        Task {
            defer { revoking = nil }
            do {
                try await ProxyAPI.revokeGrant(keys: keys, id: grant.id)
                message = "\(grant.service) can no longer spend from this wallet."
                isError = false
                loadGrants()
            } catch {
                message = error.localizedDescription
                isError = true
            }
        }
    }

    private func loadGrants() {
        Task {
            // A failure here is not worth a message: it is a list that could not
            // be refreshed, not a thing the person asked for and did not get.
            grants = (try? await ProxyAPI.fetchGrants(keys: keys)) ?? grants
        }
    }

    private func clearPending() {
        pendingId = ""
        pendingService = ""
        pendingNote = ""
    }

    /// The identifier out of whatever was scanned: the last path component of
    /// a URL, or the whole string when it is bare. Checked for shape rather
    /// than trusted, since it goes into a request path.
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
        guard epochSeconds > 0 else { return "allowed" }
        let date = Date(timeIntervalSince1970: TimeInterval(epochSeconds))
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return "since \(formatter.string(from: date))"
    }
}
