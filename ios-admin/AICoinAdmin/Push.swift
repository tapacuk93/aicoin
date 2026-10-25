import SwiftUI
import UIKit
import UserNotifications

/// Receives the APNs device token and shows alerts while the app is open.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        Task { @MainActor in PushManager.shared.received(token: hex) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Task { @MainActor in PushManager.shared.status = "Could not register: \(error.localizedDescription)" }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }
}

/// Asks for permission, registers this device with the proxy, and keeps which alert kinds are off.
@MainActor
final class PushManager: ObservableObject {
    static let shared = PushManager()

    /// The APNs host the proxy must use for this build: a development-signed build gets tokens
    /// from the sandbox, TestFlight and App Store builds from production.
    static let environment: String = {
        #if DEBUG
        return "development"
        #else
        return "production"
        #endif
    }()

    @Published private(set) var token: String? = UserDefaults.standard.string(forKey: "apnsToken")
    @Published var status: String?
    @Published private(set) var disabled: Set<String> =
        Set(UserDefaults.standard.stringArray(forKey: "disabledAlertKinds") ?? [])

    private weak var client: AdminClient?

    /// Called once at launch: re-registers so the proxy always has the current token.
    func attach(_ client: AdminClient) {
        self.client = client
        Task { await requestAndRegister() }
    }

    func requestAndRegister() async {
        let granted = (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        if !granted {
            status = "Notifications are off for AICoin Admin in iOS Settings"
            return
        }
        UIApplication.shared.registerForRemoteNotifications()
    }

    func received(token: String) {
        self.token = token
        UserDefaults.standard.set(token, forKey: "apnsToken")
        Task { await sync() }
    }

    func setEnabled(_ kind: String, _ on: Bool) {
        if on { disabled.remove(kind) } else { disabled.insert(kind) }
        UserDefaults.standard.set(Array(disabled).sorted(), forKey: "disabledAlertKinds")
        Task { await sync() }
    }

    /// Tells the proxy this device's token, environment and opt-outs.
    func sync() async {
        guard let token, let client else { return }
        guard client.hasToken else {
            status = "Add the admin token to receive alerts"
            return
        }
        do {
            try await client.registerDevice(token: token, environment: Self.environment, disabled: Array(disabled).sorted())
            status = "Registered for alerts (\(Self.environment))"
        } catch {
            status = "Could not register with the proxy: \(error.localizedDescription)"
        }
    }
}

/// Which alerts this device gets, a test push, and what the proxy sent lately.
struct NotificationsView: View {
    @EnvironmentObject private var client: AdminClient
    @EnvironmentObject private var push: PushManager
    @State private var info: AlertsInfo?
    @State private var error: String?
    @State private var testResult: String?

    var body: some View {
        List {
            Section {
                if let status = push.status { Text(status).font(.footnote) }
                if let info, !info.apns_configured {
                    Text("The proxy has no APNs key, so alerts are recorded but not pushed.")
                        .font(.footnote).foregroundStyle(.orange)
                }
                Button("Send a test notification") { Task { await test() } }
                    .disabled(push.token == nil)
                if let testResult { Text(testResult).font(.caption).foregroundStyle(.secondary) }
            } header: { Text("This device") }
            Section {
                ForEach(info?.kinds ?? []) { kind in
                    Toggle(isOn: Binding(get: { !push.disabled.contains(kind.id) },
                                         set: { push.setEnabled(kind.id, $0) })) {
                        VStack(alignment: .leading) {
                            Text(kind.label)
                            if kind.debounce_minutes > 0 {
                                Text("at most once per \(duration(kind.debounce_minutes))")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } header: { Text("Alerts") }
            Section("Recent") {
                ForEach(Array((info?.recent ?? []).enumerated()), id: \.offset) { _, a in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(a.title).font(.subheadline)
                        Text(a.body).font(.caption).foregroundStyle(.secondary)
                        if let d = a.date {
                            Text("\(d.formatted(date: .abbreviated, time: .shortened)) · pushed to \(a.pushed ?? 0) of \(a.devices ?? 0)")
                                .font(.caption2).foregroundStyle(.tertiary)
                        }
                    }
                }
            }
            ErrorBanner(message: error)
        }
        .navigationTitle("Notifications")
        .refreshable { await load() }
        .task { await push.sync(); await load() }
    }

    private func duration(_ minutes: Int) -> String {
        minutes >= 60 ? "\(minutes / 60) h" : "\(minutes) min"
    }

    private func load() async {
        error = nil
        do { info = try await client.alerts() } catch { self.error = error.localizedDescription }
    }

    private func test() async {
        do {
            await push.sync()
            let results = try await client.testPush()
            testResult = results.isEmpty ? "No devices registered" : results.joined(separator: "\n")
        } catch { testResult = error.localizedDescription }
    }
}
