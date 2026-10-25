import SwiftUI

/// Each app's daily dollar cap, what it has spent today against it, and when that resets.
struct AppsView: View {
    @EnvironmentObject private var client: AdminClient
    @State private var apps: [AppCap] = []
    @State private var error: String?
    @State private var editing: AppCap?
    @State private var adding = false

    var body: some View {
        NavigationStack {
            List {
                if apps.isEmpty && error == nil {
                    Text("No apps yet. An app is a name for one or more wallets, with a daily spend cap.")
                        .foregroundStyle(.secondary)
                }
                ForEach(apps) { app in
                    Button { editing = app } label: { AppRow(app: app) }
                        .buttonStyle(.plain)
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
        do { apps = try await client.apps() } catch { self.error = error.localizedDescription }
    }
}

private struct AppRow: View {
    let app: AppCap

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(app.app).font(.headline)
                Spacer()
                if app.capped {
                    Label("Capped", systemImage: "hand.raised.fill").font(.caption).foregroundStyle(.red)
                }
            }
            HStack {
                Text("\(app.spent_today_usd.usd) today")
                Spacer()
                Text(app.usd_per_day.map { "of \($0.usd)/day" } ?? "no cap").foregroundStyle(.secondary)
            }
            .font(.subheadline)
            if let cap = app.usd_per_day, cap > 0 {
                ProgressView(value: min(app.spent_today_usd, cap), total: cap)
                    .tint(app.capped ? .red : (app.spent_today_usd / cap > 0.8 ? .orange : .green))
            }
            if let reset = app.resetDate {
                Text("Resets \(reset.formatted(date: .omitted, time: .shortened)) (\(reset, style: .relative))")
                    .font(.caption).foregroundStyle(.secondary)
            }
            ForEach(app.wallets, id: \.self) { w in
                Text(w.shortAddress).font(.caption.monospaced()).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

/// Adds an app, or changes one's cap and wallets.
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
    @State private var error: String?
    @State private var busy = false
    @State private var confirmDelete = false

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
                    Toggle("Daily cap", isOn: $capped)
                    if capped {
                        HStack {
                            Text("$")
                            TextField("20", text: $cap).keyboardType(.decimalPad)
                            Text("per day").foregroundStyle(.secondary)
                        }
                    }
                } footer: {
                    Text("Spend resets at midnight UTC. At the cap, the app's requests get 429 and are not charged.")
                }
                Section("Wallets") {
                    ForEach(wallets, id: \.self) { w in
                        Text(w).font(.caption.monospaced())
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
                if existing != nil {
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
            .onAppear {
                if let e = existing {
                    name = e.app
                    capped = e.usd_per_day != nil
                    cap = e.usd_per_day.map { String(format: "%g", $0) } ?? ""
                    wallets = e.wallets
                }
            }
        }
    }

    private var appName: String { name.trimmingCharacters(in: .whitespaces).lowercased() }
    private var valid: Bool {
        !appName.isEmpty && (!capped || (Double(cap).map { $0 > 0 } ?? false))
    }

    private func save() async {
        busy = true; defer { busy = false }
        error = nil
        do {
            try await client.putApp(appName, usdPerDay: capped ? Double(cap) : nil, wallets: wallets)
            await onSaved()
            dismiss()
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
