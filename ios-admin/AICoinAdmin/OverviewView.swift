import SwiftUI

/// Is anything on sale, how much room is left, and is every provider up.
struct OverviewView: View {
    @EnvironmentObject private var client: AdminClient
    @State private var budget: Budget?
    @State private var price: Price?
    @State private var providers: [Provider] = []
    @State private var error: String?
    @State private var raiseBy: Double?
    @State private var customRaise = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            List {
                Section("Spend budget") {
                    if let b = budget {
                        if b.exhausted {
                            Label("Exhausted - nothing is on sale in any app", systemImage: "exclamationmark.octagon.fill")
                                .foregroundStyle(.red)
                        }
                        LabeledContent("Ceiling", value: b.budget_usd.map(\.usd) ?? "none")
                        LabeledContent("Spent", value: b.spend_usd.usd)
                        LabeledContent("Remaining", value: b.remaining_usd.map(\.usd) ?? "unlimited")
                        if let limit = b.budget_usd, limit > 0 {
                            ProgressView(value: min(b.spend_usd, limit), total: limit)
                                .tint(b.exhausted ? .red : (b.spend_usd / limit > 0.85 ? .orange : .green))
                        }
                    } else {
                        ProgressView()
                    }
                }
                Section {
                    HStack {
                        ForEach([50.0, 100.0, 200.0], id: \.self) { step in
                            Button("+\(Int(step))") { raiseBy = step }
                                .buttonStyle(.bordered)
                        }
                    }
                    HStack {
                        TextField("Other amount", text: $customRaise)
                            .keyboardType(.decimalPad)
                        Button("Raise") { raiseBy = Double(customRaise) }
                            .disabled(Double(customRaise) == nil)
                    }
                } header: {
                    Text("Raise the ceiling")
                } footer: {
                    Text("Raising it reopens the catalog when it was exhausted. It never stops AI calls for coins already sold.")
                }
                Section("Price") {
                    if let p = price {
                        LabeledContent("One aicoin", value: String(format: "$%.5f", p.price_usd))
                        LabeledContent("Spend behind it", value: p.total_spend_usd.usd)
                    }
                }
                Section("Providers") {
                    ForEach(providers) { p in
                        HStack {
                            Image(systemName: !p.enabled ? "minus.circle.fill" : p.isWorking ? "checkmark.circle.fill" : "xmark.octagon.fill")
                                .foregroundStyle(!p.enabled ? .gray : p.isWorking ? .green : .red)
                            VStack(alignment: .leading) {
                                Text(p.name)
                                if p.isOutOfCredit {
                                    // The probe asks a free question an empty account still answers, so
                                    // this comes from real calls the provider refused for credit — and
                                    // routing skips the provider until one succeeds again.
                                    Text("Out of credit")
                                        .font(.caption.bold()).foregroundStyle(.red)
                                    if let detail = p.detail {
                                        Text(detail).font(.caption).foregroundStyle(.red)
                                    }
                                } else {
                                    Text([p.state, p.detail].compactMap { $0 }.joined(separator: " - "))
                                        .font(.caption).foregroundStyle(p.state == "down" ? .red : .secondary)
                                }
                                // healthy is inferred from recent forwarded calls, so a provider can answer
                                // its probe and still be red; say which calls failed
                                if p.overBudget == true && !p.isOutOfCredit {
                                    Text("recent calls got 402/403 (out of credit or refused)")
                                        .font(.caption).foregroundStyle(.red)
                                }
                                if p.rateLimited == true {
                                    Text("recent calls got 429 (rate limited)")
                                        .font(.caption).foregroundStyle(.red)
                                }
                            }
                            Spacer()
                            if let ms = p.latencyMs { Text("\(ms) ms").font(.caption).foregroundStyle(.secondary) }
                        }
                        .opacity(p.enabled ? 1 : 0.4)
                    }
                }
                Section {
                    NavigationLink("Activity across all wallets") { ActivityView() }
                }
                ErrorBanner(message: error)
            }
            .navigationTitle("AICoin")
            .refreshable { await load() }
            .task { await load() }
            .confirmationDialog(confirmTitle, isPresented: Binding(get: { raiseBy != nil }, set: { if !$0 { raiseBy = nil } }),
                                titleVisibility: .visible) {
                Button("Raise to \(newCeiling.usd)") { Task { await raise() } }
            }
            .disabled(busy)
        }
    }

    private var newCeiling: Double { (budget?.budget_usd ?? 0) + (raiseBy ?? 0) }
    private var confirmTitle: String {
        "Raise the spend ceiling from \((budget?.budget_usd ?? 0).usd) by \((raiseBy ?? 0).usd)?"
    }

    private func raise() async {
        guard let by = raiseBy, by > 0 else { return }
        busy = true; defer { busy = false; raiseBy = nil }
        // Read it fresh, so two phones raising at once each add their own amount.
        do {
            let now = try await client.budget()
            try await client.setBudget(usd: (now.budget_usd ?? 0) + by)
            customRaise = ""
            await load()
        } catch { self.error = error.localizedDescription }
    }

    private func load() async {
        error = nil
        do {
            async let b = client.budget()
            async let p = client.price()
            async let h = client.health()
            (budget, price, providers) = try await (b, p, h.providers)
        } catch { self.error = error.localizedDescription }
    }
}
