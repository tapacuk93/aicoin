import Charts
import SwiftUI

/// The price of a coin, and how it got there.
struct PriceView: View {
    let current: ProxyAPI.PriceResponse?

    @State private var points: [ProxyAPI.PricePoint] = []
    @State private var loadFailed = false
    @State private var selected: ProxyAPI.PricePoint?

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text(shownPrice.map(Coins.price) ?? "—")
                        .font(.system(size: 34, weight: .semibold, design: .rounded))
                        .contentTransition(.numericText())
                    Text(selected.map { $0.date.formatted(date: .abbreviated, time: .shortened) } ?? "per \(Coins.symbol), now")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
                chart
                    .frame(height: 220)
                    .padding(.vertical, 8)
            }

            if let current {
                Section {
                    LabeledContent("Total AI Spend", value: Coins.usd(current.total_spend_usd))
                    if let halfLife = current.half_life_days {
                        LabeledContent("Half-Life", value: "\(Int(halfLife)) days")
                    }
                } footer: {
                    Text("The price follows what the network has spent on AI. Older spending counts for less, halving in weight every half-life.")
                }
            }
        }
        .navigationTitle("Price")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .refreshable { await load() }
    }

    private var shownPrice: Double? {
        selected?.price_usd ?? current?.price_usd ?? points.last?.price_usd
    }

    @ViewBuilder
    private var chart: some View {
        if points.count > 1 {
            Chart(points) { point in
                AreaMark(x: .value("Date", point.date), y: .value("Price", point.price_usd))
                    .foregroundStyle(.linearGradient(colors: [Color.accentColor.opacity(0.3), .clear],
                                                     startPoint: .top, endPoint: .bottom))
                    .interpolationMethod(.monotone)
                LineMark(x: .value("Date", point.date), y: .value("Price", point.price_usd))
                    .foregroundStyle(Color.accentColor)
                    .interpolationMethod(.monotone)
                if let selected, selected.id == point.id {
                    RuleMark(x: .value("Date", selected.date))
                        .foregroundStyle(.secondary.opacity(0.4))
                    PointMark(x: .value("Date", selected.date), y: .value("Price", selected.price_usd))
                        .foregroundStyle(Color.accentColor)
                }
            }
            .chartYAxis {
                AxisMarks(position: .trailing) { value in
                    AxisGridLine()
                    AxisValueLabel {
                        if let price = value.as(Double.self) { Text(Coins.price(price)) }
                    }
                }
            }
            .chartOverlay { proxy in
                GeometryReader { geometry in
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 0)
                            .onChanged { drag in
                                guard let plot = proxy.plotFrame else { return }
                                let x = drag.location.x - geometry[plot].origin.x
                                guard let date: Date = proxy.value(atX: x) else { return }
                                selected = points.min { abs($0.date.timeIntervalSince(date)) < abs($1.date.timeIntervalSince(date)) }
                            }
                            .onEnded { _ in selected = nil })
                }
            }
            .sensoryFeedback(.selection, trigger: selected?.id)
        } else if loadFailed {
            ContentUnavailableView("Couldn't Load History", systemImage: "wifi.exclamationmark",
                                   description: Text("Pull to try again."))
        } else {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func load() async {
        do {
            points = try await ProxyAPI.fetchPriceHistory()
            loadFailed = false
        } catch {
            loadFailed = points.isEmpty
        }
    }
}
