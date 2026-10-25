import SwiftUI

/// What every app sells: the live offer, and the catalog behind it.
struct SalesView: View {
    @EnvironmentObject private var client: AdminClient
    @State private var offer: Offer?
    @State private var packages: [Package] = []
    @State private var exhausted = false
    @State private var error: String?
    @State private var offerCoins = ""
    @State private var offerPrice = ""
    @State private var confirmOffer = false
    @State private var confirmClose = false
    @State private var editing: Package?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let o = offer {
                        LabeledContent("Coins", value: "\(o.coins)")
                        if let p = o.usd_price { LabeledContent("Price", value: p.usd) }
                        if let t = o.tier { LabeledContent("Tier", value: t) }
                        Button("Close sales", role: .destructive) { confirmClose = true }
                    } else {
                        Text("No offer - each app sells its catalog").foregroundStyle(.secondary)
                    }
                    TextField("Coins to offer", text: $offerCoins).keyboardType(.numberPad)
                    TextField("At price (optional, e.g. 9.99)", text: $offerPrice).keyboardType(.decimalPad)
                    Button("Set offer") { confirmOffer = true }
                        .disabled(Int(offerCoins).map { $0 <= 0 } ?? true)
                } header: { Text("Current offer") }

                Section {
                    if exhausted {
                        Label("The budget is exhausted, so the catalog is served empty. Raise it in Overview before editing - saving now would erase the real catalog.",
                              systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                    }
                    ForEach(groupedApps, id: \.self) { app in
                        DisclosureGroup(app) {
                            ForEach(packages.filter { $0.app == app }) { p in
                                Button { editing = p } label: {
                                    HStack {
                                        Text(p.tier)
                                        Spacer()
                                        Text("\(p.coins) coins").foregroundStyle(.secondary)
                                        Text(p.usd_price_hint?.usd ?? "-").monospacedDigit()
                                    }
                                }
                                .disabled(!canEdit)
                            }
                        }
                    }
                } header: { Text("Catalog") } footer: {
                    Text("Price hints are display only - what Apple charges is set in App Store Connect.")
                }
                ErrorBanner(message: error)
            }
            .navigationTitle("Sales")
            .refreshable { await load() }
            .task { await load() }
            .confirmationDialog("Offer \(offerCoins) coins in every app?", isPresented: $confirmOffer, titleVisibility: .visible) {
                Button("Set offer") { Task { await setOffer() } }
            }
            .confirmationDialog("Close sales in every app?", isPresented: $confirmClose, titleVisibility: .visible) {
                Button("Close sales", role: .destructive) { Task { await close() } }
            }
            .sheet(item: $editing) { p in
                PackageEditor(package: p) { updated in Task { await save(updated) } }
            }
            .disabled(busy)
        }
    }

    private var groupedApps: [String] { Array(Set(packages.map(\.app))).sorted() }
    /// Never edit from an empty or budget-blanked catalog: the save overwrites everything.
    private var canEdit: Bool { !exhausted && !packages.isEmpty }

    private func load() async {
        error = nil
        do {
            async let o = client.offer()
            async let p = client.packages()
            async let b = client.budget()
            (offer, packages, exhausted) = try await (o, p, b.exhausted)
        } catch { self.error = error.localizedDescription }
    }

    private func setOffer() async {
        guard let coins = Int(offerCoins) else { return }
        busy = true; defer { busy = false }
        do {
            try await client.setOffer(coins: coins, usdPrice: Double(offerPrice))
            offerCoins = ""; offerPrice = ""
            await load()
        } catch { self.error = error.localizedDescription }
    }

    private func close() async {
        busy = true; defer { busy = false }
        do { try await client.setOffer(coins: 0, usdPrice: nil); await load() }
        catch { self.error = error.localizedDescription }
    }

    private func save(_ updated: Package) async {
        guard canEdit else { return }
        busy = true; defer { busy = false }
        do {
            // Fresh, then one entry changed: never write back a stale or empty list.
            var current = try await client.packages()
            guard !current.isEmpty, let i = current.firstIndex(where: { $0.product_id == updated.product_id }) else {
                error = "The catalog changed or came back empty - not saved"; return
            }
            current[i] = updated
            try await client.setPackages(current)
            await load()
        } catch { self.error = error.localizedDescription }
    }
}

struct PackageEditor: View {
    @Environment(\.dismiss) private var dismiss
    let package: Package
    let onSave: (Package) -> Void
    @State private var coins = ""
    @State private var hint = ""

    var body: some View {
        NavigationStack {
            Form {
                Section(package.product_id) {
                    TextField("Coins", text: $coins).keyboardType(.numberPad)
                    TextField("Price hint", text: $hint).keyboardType(.decimalPad)
                }
            }
            .navigationTitle("\(package.app) \(package.tier)")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        var p = package
                        p.coins = Int(coins) ?? package.coins
                        p.usd_price_hint = Double(hint) ?? package.usd_price_hint
                        onSave(p); dismiss()
                    }
                    .disabled((Int(coins) ?? 0) <= 0)
                }
            }
            .onAppear {
                coins = "\(package.coins)"
                hint = package.usd_price_hint.map { String($0) } ?? ""
            }
        }
    }
}
