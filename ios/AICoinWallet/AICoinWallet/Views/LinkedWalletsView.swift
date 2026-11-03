import SwiftUI

/// What an app's "Connect AICoin" code says: `aicoin:link?address=<64 hex>&app=<name>` — see
/// CONTRACT.md, "Linked wallets". Only the app's own public address is in it, so a code seen over
/// somebody's shoulder gives nothing away; what links it is this wallet's signature.
struct LinkCode: Equatable {
    let address: String
    let app: String

    init?(_ scanned: String) {
        let text = scanned.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: text),
              components.scheme?.lowercased() == "aicoin",
              components.path.lowercased() == "link" || components.host?.lowercased() == "link"
        else { return nil }
        let items = components.queryItems ?? []
        guard let address = items.first(where: { $0.name == "address" })?.value?.lowercased(),
              Coins.isAddress(address)
        else { return nil }
        let app = items.first(where: { $0.name == "app" })?.value?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.address = address
        self.app = String((app.isEmpty ? "An app" : app).prefix(60))
    }

    /// `1a2b3c…7d8e9f` — what the app shows next to its code, to compare before linking.
    static func short(_ address: String) -> String {
        "\(address.prefix(6))…\(address.suffix(6))"
    }
}

/// Apps whose own wallets spend this wallet's coins, how much each has spent, and the limit on it.
struct LinkedWalletsView: View {
    let keys: WalletKeys

    @State private var links: [ProxyAPI.Link]?
    @State private var loadFailed = false
    @State private var isScanning = false
    @State private var pending: LinkCode?
    @State private var editing: ProxyAPI.Link?
    @State private var removing: Set<String> = []
    @State private var scanProblem: String?
    @State private var error: String?

    var body: some View {
        List {
            if let links, !links.isEmpty {
                Section {
                    ForEach(links) { link in
                        Button { editing = link } label: { row(link) }
                            .foregroundStyle(.primary)
                            .swipeActions {
                                Button("Remove", role: .destructive) { remove(link) }
                            }
                            .contextMenu {
                                Button("Change Limit", systemImage: "slider.horizontal.3") { editing = link }
                                Button("Remove", systemImage: "link.badge.minus", role: .destructive) { remove(link) }
                            }
                    }
                } footer: {
                    Text("Tap to change the limit, swipe left to remove. A removed app goes back to its own coins at once.")
                }
            } else if links != nil {
                ContentUnavailableView {
                    Label("No Linked Apps", systemImage: "link")
                } description: {
                    Text("Choose Connect AICoin in an app such as AI UI, then scan the code it shows. The app keeps its own key and spends this wallet's coins, up to a limit you set.")
                } actions: {
                    Button("Scan Code") { isScanning = true }
                        .buttonStyle(.borderedProminent)
                }
            } else if loadFailed {
                ContentUnavailableView("Couldn't Load Linked Apps", systemImage: "wifi.exclamationmark",
                                       description: Text("Pull to try again."))
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("Linked Apps")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Scan Code", systemImage: "qrcode.viewfinder") { isScanning = true }
            }
        }
        .task { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $isScanning) {
            ScannerSheet(title: "Scan App Code") { scanned in
                isScanning = false
                if let code = LinkCode(scanned) {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { pending = code }
                } else {
                    scanProblem = "That isn't an app's Connect AICoin code."
                }
            }
        }
        .sheet(item: Binding(get: { pending.map(PendingCode.init) }, set: { pending = $0?.code })) { item in
            LinkRequestView(keys: keys, code: item.code) { Task { await load() } }
        }
        .sheet(item: $editing) { link in
            LinkRequestView(keys: keys, code: nil, existing: link) { Task { await load() } }
        }
        .alert("Unrecognised Code", isPresented: Binding(get: { scanProblem != nil }, set: { if !$0 { scanProblem = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(scanProblem ?? "")
        }
        .alert("Couldn't Remove", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(error ?? "")
        }
    }

    private struct PendingCode: Identifiable {
        let code: LinkCode
        var id: String { code.address }
    }

    private func row(_ link: ProxyAPI.Link) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(link.app.isEmpty ? "Unnamed app" : link.app)
                Text(LinkCode.short(link.address))
                    .font(.footnote.monospaced())
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if removing.contains(link.address) {
                ProgressView()
            } else {
                VStack(alignment: .trailing, spacing: 2) {
                    Text("\(Coins.format(link.spent)) spent")
                        .font(.subheadline)
                    Text(link.limit.map { "of \(Coins.format($0))" } ?? "No limit")
                        .font(.footnote)
                        .foregroundStyle(Self.reachedLimit(link) ? .red : .secondary)
                }
            }
        }
    }

    static func reachedLimit(_ link: ProxyAPI.Link) -> Bool {
        link.limit.map { link.spent >= $0 } ?? false
    }

    private func load() async {
        do {
            links = try await ProxyAPI.fetchLinks(keys: keys)
            loadFailed = false
        } catch {
            loadFailed = links == nil
        }
    }

    private func remove(_ link: ProxyAPI.Link) {
        removing.insert(link.address)
        Task {
            defer { removing.remove(link.address) }
            do {
                try await ProxyAPI.removeLink(keys: keys, address: link.address)
                withAnimation { links?.removeAll { $0.address == link.address } }
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// Linking a scanned app, or changing an existing link's limit. Before anything is signed it shows
/// the app's name and the ends of its address: a QR is a thing anybody can print, and agreeing to
/// one on the strength of having scanned it is agreeing to whatever was printed.
struct LinkRequestView: View {
    let keys: WalletKeys
    let code: LinkCode?
    var existing: ProxyAPI.Link?
    var onSaved: () -> Void = {}

    @Environment(\.dismiss) private var dismiss
    @State private var limited = false
    @State private var limitText = ""
    @State private var isSaving = false
    @State private var saveError: String?
    @State private var saved = false

    private var address: String { existing?.address ?? code?.address ?? "" }
    private var app: String { existing?.app ?? code?.app ?? "" }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 8) {
                        Image(systemName: "link.circle.fill")
                            .font(.system(size: 44))
                            .foregroundStyle(.tint)
                        Text(app).font(.title2.bold()).multilineTextAlignment(.center)
                        Text(existing == nil ? "wants to spend this wallet's coins" : "spends this wallet's coins")
                            .foregroundStyle(.secondary)
                        Text(LinkCode.short(address))
                            .font(.callout.monospaced())
                            .padding(.top, 4)
                            .accessibilityLabel("Address ending \(address.suffix(6))")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                } footer: {
                    if existing == nil {
                        Text("Check that the app shows the same address. It keeps its own key and never sees yours.")
                    } else if let existing {
                        Text("Spent so far: \(Coins.format(existing.spent)) coins.")
                    }
                }
                Section {
                    Toggle("Limit Spending", isOn: $limited.animation())
                    if limited {
                        HStack {
                            TextField("Coins", text: $limitText)
                                .keyboardType(.decimalPad)
                                .accessibilityIdentifier("linkLimitField")
                            Text("coins").foregroundStyle(.secondary)
                        }
                    }
                } footer: {
                    Text(limited
                         ? "The most it may spend in total. Lower it to what it has spent to stop it."
                         : "It may spend all of this wallet's coins. You can set a limit or remove it any time under Linked Apps.")
                }
                if let saveError {
                    Section {
                        Label(saveError, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red)
                    }
                }
                Section {
                    Button(action: save) {
                        HStack {
                            Spacer()
                            if isSaving { ProgressView() } else { Text(existing == nil ? "Link" : "Save").bold() }
                            Spacer()
                        }
                    }
                    .disabled(isSaving || (limited && parsedLimit == nil))
                }
            }
            .navigationTitle(existing == nil ? "Link App" : "Linked App")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(existing == nil ? "Don't Link" : "Cancel") { dismiss() }
                }
            }
        }
        .onAppear {
            if let limit = existing?.limit {
                limited = true
                limitText = Coins.format(limit)
            }
        }
        .sensoryFeedback(.success, trigger: saved)
    }

    private var parsedLimit: Double? {
        let text = limitText.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespaces)
        guard let value = Double(text), value >= 0, value.isFinite else { return nil }
        return value
    }

    private func save() {
        isSaving = true
        saveError = nil
        Task {
            defer { isSaving = false }
            do {
                try await ProxyAPI.saveLink(keys: keys, address: address, app: app,
                                            limit: limited ? parsedLimit : nil)
                saved = true
                onSaved()
                dismiss()
            } catch {
                saveError = Self.explain(error.localizedDescription)
            }
        }
    }

    /// The proxy's refusals in words (CONTRACT.md, `POST /wallet/api/links`).
    static func explain(_ message: String) -> String {
        switch message {
        case "already_linked": return "That app is already linked to another wallet. Remove it there first, or disconnect it in the app."
        case "is_owner": return "Wallets that have linked apps of their own, or are linked themselves, can't be linked."
        default: return message
        }
    }
}
