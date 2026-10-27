import SwiftUI

/// Every app the proxy knows: its own daily dollar cap (or the common budget from Overview), today's
/// one-time top-up, what it has spent today, and when that resets.
struct AppsView: View {
    @EnvironmentObject private var client: AdminClient
    @State private var apps: [AppCap] = []
    @State private var budget: Budget?
    @State private var error: String?
    @State private var editing: AppCap?
    @State private var adding = false

    var body: some View {
        NavigationStack {
            List {
                if let b = budget {
                    Section {
                        LabeledContent("Common budget",
                                       value: b.remaining_usd.map { "\($0.usd) left" } ?? "no ceiling")
                    } footer: {
                        Text("An app without a cap of its own uses only the common budget from Overview.")
                    }
                }
                Section {
                    if apps.isEmpty && error == nil {
                        Text("No apps yet. An app is a name for one or more wallets, with a daily spend cap.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(apps) { app in
                        Button { editing = app } label: { AppRow(app: app) }
                            .buttonStyle(.plain)
                    }
                }
                ErrorBanner(message: error)
            }
            .navigationTitle("Apps")
            .toolbar {
                Button { adding = true } label: { Image(systemName: "plus") }
            }
            .refreshable { await load() }
            .task { await load() }
            .sheet(item: $editing) { app in
                AppEditor(existing: app) { await load() }
            }
            .sheet(isPresented: $adding) {
                AppEditor(existing: nil) { await load() }
            }
        }
    }

    private func load() async {
        error = nil
        do {
            async let a = client.apps()
            async let b = client.budget()
            (apps, budget) = try await (a, b)
        } catch { self.error = error.localizedDescription }
    }
}

private struct AppRow: View {
    let app: AppCap

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(app.app).font(.headline).lineLimit(1).truncationMode(.middle)
                Spacer()
                if app.capped {
                    Label("Capped", systemImage: "hand.raised.fill").font(.caption).foregroundStyle(.red)
                }
            }
            HStack {
                Text("\(app.spent_today_usd.usd) today")
                Spacer()
                if let cap = app.usd_per_day {
                    Text("of \(cap.usd)/day" + (app.topUp > 0 ? " + \(app.topUp.usd)" : ""))
                        .foregroundStyle(.secondary)
                } else {
                    Text("common budget").foregroundStyle(.secondary)
                }
            }
            .font(.subheadline)
            if let limit = app.limitToday, limit > 0 {
                ProgressView(value: min(app.spent_today_usd, limit), total: limit)
                    .tint(app.capped ? .red : (app.spent_today_usd / limit > 0.8 ? .orange : .green))
            }
            if app.topUp > 0 {
                Text("Top-up: \((app.top_up_remaining_usd ?? 0).usd) of \(app.topUp.usd) left today")
                    .font(.caption).foregroundStyle(.blue)
            }
            if app.usd_per_day != nil, let reset = app.resetDate {
                Text("Resets \(reset.formatted(date: .omitted, time: .shortened)) (\(reset, style: .relative))")
                    .font(.caption).foregroundStyle(.secondary)
            }
            ForEach(app.wallets, id: \.self) { w in
                WalletLine(wallet: w, coins: app.coins(w))
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

private struct WalletLine: View {
    let wallet: String
    let coins: Double?

    var body: some View {
        HStack {
            Text(wallet.shortAddress).font(.caption.monospaced()).foregroundStyle(.secondary)
            Spacer()
            if let coins {
                Text(coins <= 0 ? "empty (\(Int(coins)) coins)" : "\(Int(coins)) coins")
                    .font(.caption)
                    .foregroundStyle(coins <= 0 ? .red : .secondary)
            }
        }
    }
}

/// Adds an app, or changes one's cap, wallets and today's top-up.
private struct AppEditor: View {
    @EnvironmentObject private var client: AdminClient
    @Environment(\.dismiss) private var dismiss
    let existing: AppCap?
    let onSaved: () async -> Void

    @State private var name = ""
    @State private var cap = ""
    @State private var capped = true
    @State private var wallets: [String] = []
    @State private var newWallet = ""
    @State private var topUp = ""
    @State private var current: AppCap?
    @State private var error: String?
    @State private var busy = false
    @State private var confirmDelete = false
    @State private var confirmTopUp = false

    var body: some View {
        NavigationStack {
            Form {
                if existing == nil {
                    Section {
                        TextField("earth-empire", text: $name)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                    } header: { Text("Name") } footer: {
                        Text("Lowercase letters, digits, - . _. Apps can also name themselves with the X-AICoin-App header.")
                    }
                }
                Section {
                    Toggle("Own daily cap", isOn: $capped)
                    if capped {
                        HStack {
                            Text("$")
                            TextField("20", text: $cap).keyboardType(.decimalPad)
                            Text("per day").foregroundStyle(.secondary)
                        }
                    }
                } footer: {
                    Text(capped
                         ? "Spend resets at midnight UTC. At the cap, the app's requests get 429 and are not charged."
                         : "Uses the common budget from Overview: nothing else limits this app.")
                }
                if let app = current, app.isConfigured, app.usd_per_day != nil {
                    topUpSection(app)
                }
                Section("Wallets") {
                    ForEach(wallets, id: \.self) { w in
                        VStack(alignment: .leading) {
                            Text(w).font(.caption.monospaced())
                            if let coins = current?.coins(w) {
                                Text(coins <= 0 ? "Empty: \(Int(coins)) coins - calls get 402 until it is credited"
                                                : "\(Int(coins)) coins")
                                    .font(.caption).foregroundStyle(coins <= 0 ? .red : .secondary)
                            }
                        }
                    }
                    .onDelete { wallets.remove(atOffsets: $0) }
                    HStack {
                        TextField("Wallet address (64 hex)", text: $newWallet)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().font(.caption.monospaced())
                        Button("Add") {
                            wallets.append(newWallet.trimmingCharacters(in: .whitespaces).lowercased())
                            newWallet = ""
                        }
                        .disabled(newWallet.trimmingCharacters(in: .whitespaces).count != 64)
                    }
                }
                if existing?.isConfigured == true {
                    Section {
                        Button("Delete app", role: .destructive) { confirmDelete = true }
                    }
                }
                ErrorBanner(message: error)
            }
            .navigationTitle(existing?.app ?? "New app")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }.disabled(!valid || busy)
                }
            }
            .confirmationDialog("Delete \(existing?.app ?? "")? Its wallets stop being capped.",
                                isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) { Task { await delete() } }
            }
            .confirmationDialog("Add \((topUpAmount ?? 0).usd) for today only to \(appName)? Its daily cap stays as it is.",
                                isPresented: $confirmTopUp, titleVisibility: .visible) {
                Button("Add top-up") { Task { await addTopUp() } }
            }
            .onAppear {
                if let e = existing {
                    current = e
                    name = e.app
                    capped = e.usd_per_day != nil
                    cap = e.usd_per_day.map { String(format: "%g", $0) } ?? ""
                    wallets = e.wallets
                }
            }
        }
    }

    @ViewBuilder private func topUpSection(_ app: AppCap) -> some View {
        Section {
            if app.topUp > 0 {
                LabeledContent("Today", value: "\((app.top_up_remaining_usd ?? 0).usd) of \(app.topUp.usd) left")
            }
            HStack {
                Text("$")
                TextField("5", text: $topUp).keyboardType(.decimalPad)
                Button("Add") { confirmTopUp = true }
                    .disabled(busy || !((topUpAmount ?? 0) > 0))
            }
            if app.topUp > 0 {
                Button("Clear today's top-up", role: .destructive) { Task { await clearTopUp() } }
                    .disabled(busy)
            }
        } header: { Text("One-time top-up") } footer: {
            Text("Extra dollars for today (UTC) on top of the daily cap, spent after the cap. The cap is not changed; what is left is gone at midnight UTC.")
        }
    }

    private var topUpAmount: Double? { Double(topUp.replacingOccurrences(of: ",", with: ".")) }
    private var appName: String { name.trimmingCharacters(in: .whitespaces).lowercased() }
    private var valid: Bool {
        !appName.isEmpty && (!capped || (Double(cap).map { $0 > 0 } ?? false))
    }

    private func save() async {
        busy = true; defer { busy = false }
        error = nil
        // A catalog app left on the common budget with no wallets: nothing to write.
        if let e = existing, !e.isConfigured, !capped, wallets.isEmpty {
            dismiss()
            return
        }
        do {
            try await client.putApp(appName, usdPerDay: capped ? Double(cap) : nil, wallets: wallets)
            await onSaved()
            dismiss()
        } catch { self.error = error.localizedDescription }
    }

    private func refresh() async {
        current = try? await client.apps().first { $0.app == appName }
        await onSaved()
    }

    private func addTopUp() async {
        guard let usd = topUpAmount, usd > 0 else { return }
        busy = true; defer { busy = false }
        error = nil
        do {
            try await client.topUpApp(appName, usd: usd)
            topUp = ""
            await refresh()
        } catch { self.error = error.localizedDescription }
    }

    private func clearTopUp() async {
        busy = true; defer { busy = false }
        error = nil
        do {
            try await client.clearTopUp(appName)
            await refresh()
        } catch { self.error = error.localizedDescription }
    }

    private func delete() async {
        busy = true; defer { busy = false }
        do {
            try await client.deleteApp(appName)
            await onSaved()
            dismiss()
        } catch { self.error = error.localizedDescription }
    }
}
