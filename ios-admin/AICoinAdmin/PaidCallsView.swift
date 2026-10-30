import SwiftUI

/// Every paid AI call the proxy recorded, newest first: which app, what for, whether it worked and
/// what it cost in dollars. Filters by app, purpose and failures; totals are for the whole filtered
/// set, not just what is loaded.
struct PaidCallsView: View {
    @EnvironmentObject private var client: AdminClient

    @State private var calls: [PaidCall] = []
    @State private var totals: PaidCallTotals?
    @State private var apps: [String] = []
    @State private var purposes: [String] = []
    @State private var retentionDays: Int?
    @State private var nextBefore: String?
    @State private var reachedEnd = false
    @State private var loading = false
    @State private var error: String?
    /// The proxy answered 404: it predates the endpoint. Nothing is retried until a manual refresh.
    @State private var unavailable = false

    @State private var app: String?
    @State private var purpose: String?
    @State private var failedOnly = false

    private var filterKey: String { "\(app ?? "")|\(purpose ?? "")|\(failedOnly)" }

    var body: some View {
        NavigationStack {
            List {
                if unavailable {
                    Section {
                        ContentUnavailableView(
                            "Not available on this server yet",
                            systemImage: "clock.badge.questionmark",
                            description: Text("This proxy does not record transactions. Deploy a newer aicoin-proxy, then pull to refresh."))
                    }
                } else {
                    Section { filters }
                    if let totals { Section { TotalsHeader(totals: totals, retentionDays: retentionDays) } }
                    Section {
                        if calls.isEmpty && !loading && error == nil {
                            Text("No paid calls match.").foregroundStyle(.secondary)
                        }
                        ForEach(calls) { call in
                            PaidCallRow(call: call)
                                .onAppear { if call.id == calls.last?.id { Task { await loadMore() } } }
                        }
                        if loading {
                            HStack { Spacer(); ProgressView(); Spacer() }
                        } else if !reachedEnd && !calls.isEmpty && error == nil {
                            // A narrow filter may come back with a short page that is not the end.
                            Button("Load more") { Task { await loadMore() } }
                        }
                    }
                    ErrorBanner(message: error)
                }
            }
            .navigationTitle("Transactions")
            .refreshable { await reload() }
            .task(id: filterKey) { await reload() }
        }
    }

    private var filters: some View {
        Group {
            Picker("App", selection: $app) {
                Text("All apps").tag(String?.none)
                ForEach(apps, id: \.self) { Text($0).tag(String?.some($0)) }
            }
            Picker("Purpose", selection: $purpose) {
                Text("All purposes").tag(String?.none)
                ForEach(purposes, id: \.self) { Text($0).tag(String?.some($0)) }
            }
            Toggle("Failed only", isOn: $failedOnly)
        }
    }

    private func reload() async {
        calls = []
        nextBefore = nil
        reachedEnd = false
        unavailable = false
        error = nil
        await fetch(before: nil)
    }

    private func loadMore() async {
        guard !loading, !reachedEnd, !unavailable, error == nil, let cursor = nextBefore else { return }
        await fetch(before: cursor)
    }

    private func fetch(before: String?) async {
        loading = true
        defer { loading = false }
        let key = filterKey
        do {
            let page = try await client.paidCalls(before: before, app: app, purpose: purpose, failedOnly: failedOnly)
            guard key == filterKey else { return }  // the filters changed while this was in flight
            let known = Set(calls.map(\.id))
            calls.append(contentsOf: page.transactions.filter { !known.contains($0.id) })
            totals = page.totals
            if let a = page.apps { apps = Array(Set(a).union(app.map { [$0] } ?? [])).sorted() }
            if let p = page.purposes { purposes = Array(Set(p).union(purpose.map { [$0] } ?? [])).sorted() }
            retentionDays = page.retention_days
            nextBefore = page.next_before
            reachedEnd = page.next_before == nil
        } catch let failure as AdminClient.Failure where failure.status == 404 {
            unavailable = true
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }
}

private struct TotalsHeader: View {
    let totals: PaidCallTotals
    let retentionDays: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(totals.usd.usd).font(.title2.bold())
                Spacer()
                Text("\(totals.count) calls").foregroundStyle(.secondary)
            }
            HStack {
                if totals.failures > 0 {
                    Label("\(totals.failures) failed", systemImage: "xmark.octagon.fill").foregroundStyle(.red)
                } else {
                    Label("none failed", systemImage: "checkmark.circle").foregroundStyle(.green)
                }
                Spacer()
                if let cost = totals.cost_usd { Text("provider cost \(cost.usd)").foregroundStyle(.secondary) }
            }
            .font(.caption)
            if let retentionDays {
                Text("Last \(retentionDays) days").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }
}

private struct PaidCallRow: View {
    let call: PaidCall

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(call.app).font(.subheadline.bold()).lineLimit(1).truncationMode(.middle)
                Spacer()
                Text(call.usd.preciseUSD).font(.subheadline.monospacedDigit())
            }
            HStack(spacing: 6) {
                Text(call.purpose)
                    .font(.caption)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Color.secondary.opacity(0.15), in: Capsule())
                StatusBadge(ok: call.ok, status: call.status)
                Spacer()
                if let date = call.date {
                    Text(date.formatted(date: .abbreviated, time: .shortened))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Text([call.provider, call.model].compactMap { $0 }.joined(separator: " · "))
                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            if let error = call.error, !call.ok {
                Text(error).font(.caption2).foregroundStyle(.red).lineLimit(2)
            }
        }
        .padding(.vertical, 2)
    }
}

private struct StatusBadge: View {
    let ok: Bool
    let status: Int

    var body: some View {
        Text(ok ? "OK" : "Failed \(status)")
            .font(.caption2.bold())
            .foregroundStyle(.white)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(ok ? Color.green : Color.red, in: Capsule())
    }
}

private extension Double {
    /// A single call is usually a fraction of a cent: show enough digits to tell calls apart.
    var preciseUSD: String { self == 0 ? "$0" : (self < 1 ? String(format: "$%.4f", self) : usd) }
}
