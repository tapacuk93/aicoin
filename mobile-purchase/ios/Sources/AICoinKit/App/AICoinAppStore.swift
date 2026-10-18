import Foundation
import Combine
import SwiftUI

/// The observable, UI-facing half of an app's AICoin integration: the balance badge's data
/// source, the StoreKit purchase flow, and the one flag that raises `BuyAICoinSheet`.
///
/// Subscribes to `AICoinEventBus` so *any* call site hitting a `402` — text, narration, a picture
/// — raises the buy sheet without each of them having to catch and re-plumb the error itself. Far
/// more reliable than threading a `catch AICoinError.insufficientBalance` through every `Task`,
/// several of which in any app deliberately swallow their errors (image search, translation, voice
/// design all fail soft).
@MainActor
public final class AICoinAppStore: ObservableObject {
    public let runtime: AICoinAppRuntime

    /// Drives `aicoinBuySheet(_:)`. Set from anywhere by an insufficient-balance event, or by the
    /// app when the user taps its coin badge.
    @Published public var showBuySheet = false

    public let balanceStore: WalletBalanceStore
    public let iapManager: IAPManager

    private let defaults: UserDefaults
    private var cancellable: AnyCancellable?
    /// `start()` is usually called from a `.task`, which SwiftUI may re-run if the view's identity
    /// changes. The UserDefaults flag already stops a *later launch* from re-claiming; this stops a
    /// second call within this launch from racing the first one before that flag is written.
    private var didStart = false

    public init(runtime: AICoinAppRuntime, eventBus: AICoinEventBus = .shared, defaults: UserDefaults = .standard) {
        self.runtime = runtime
        self.defaults = defaults
        let identity = runtime.identity
        let walletClient = runtime.walletClient

        var balanceStore: WalletBalanceStore?
        #if DEBUG
        // See `Configuration.debugAutoReloadWhenEmpty`. Never under the UI-test hooks: they keep
        // the faucet off entirely. The reload is tied to the wallet this launch started with, so
        // after a reconcile switch it would top up the abandoned one — harmless for a debug
        // convenience, and the server's cooldown bounds it.
        if runtime.configuration.debugAutoReloadWhenEmpty, !runtime.hooks.isActive {
            balanceStore = WalletBalanceStore(
                debugAutoReloadWhenEmpty: identity,
                walletClient: walletClient,
                eventBus: eventBus
            )
        }
        #endif
        self.balanceStore = balanceStore
            ?? WalletBalanceStore(address: identity.address, walletClient: walletClient, eventBus: eventBus)

        // Product ids are `<prefix>.aicoin.{small,medium,large,xl}`, where the prefix is the bundle
        // ID with anything Apple forbids taken out; `IAPManager` narrows the shared cross-app
        // `/iap/packages` list to those.
        self.iapManager = IAPManager(
            walletClient: walletClient,
            bundleIDPrefix: runtime.configuration.bundleID,
            eventBus: eventBus,
            pinDefaults: defaults
        )

        cancellable = eventBus.events
            .receive(on: DispatchQueue.main)
            .sink { [weak self] event in
                guard case .insufficientBalance = event else { return }
                self?.showBuySheet = true
            }
    }

    /// The wallet in use right now — it can change when `reconcileWallet()` switches.
    public var identity: WalletIdentity { runtime.identity }
    public var walletClient: WalletClient { runtime.walletClient }

    /// Called once at launch: picks up any purchase that was paid for but never credited (app
    /// killed mid-flow, a redeem call that failed) and retries crediting it, warms the buy sheet,
    /// settles the wallet with the user's other devices, and silently tops up a brand-new wallet
    /// from the free faucet. Repeat calls do nothing.
    ///
    /// Nothing on screen waits on any of it: the first view renders whether the claim succeeds, is
    /// refused, or never completes.
    public func start() {
        guard !didStart else { return }
        didStart = true

        iapManager.startObservingUnfinishedTransactions(address: identity.address)
        // What the buy sheet shows, fetched before anybody asks to see it. Fetched when the sheet
        // opened, it used to be ten seconds of spinner on a shipped Mac build before a price
        // appeared; by the time a user runs out of coins this has long since finished.
        Task { [iapManager] in await iapManager.preload() }

        let runtime = self.runtime
        let defaults = self.defaults
        Task { [weak self] in
            // Reconcile *before* claiming, not alongside. A claim that lands first funds this
            // device's own wallet, and reconcile then sees two funded wallets and keeps both —
            // leaving the user's real, shared balance out of sight on this device for good.
            await self?.reconcileWallet()
            let result = await AICoinFaucet.claimIfNeeded(
                walletClient: runtime.walletClient,
                identity: runtime.identity,
                defaults: defaults,
                key: runtime.configuration.faucetKey,
                // Any hook, not only the faucet one: under the hooks the wallet is ephemeral and
                // the network is a stub, so there is nothing a claim could do but write the flag.
                disabled: runtime.hooks.isActive
            )
            // A grant is worth a balance refresh so the badge shows the new coins.
            guard result?.granted == true else { return }
            await self?.balanceStore.refresh()
        }
    }

    /// Settles on one wallet with the user's other devices — call at launch (`start()` does) and
    /// on coming back to the foreground, since the shared wallet can reach this device through
    /// iCloud Keychain at any time. On a switch, moves purchase crediting and the balance badge
    /// over to the new wallet too.
    public func reconcileWallet() async {
        guard await runtime.reconcile() else { return }
        let address = runtime.identity.address
        iapManager.startObservingUnfinishedTransactions(address: address)
        await balanceStore.switchAddress(to: address)
    }
}

/// Presents `BuyAICoinSheet` whenever `store.showBuySheet` is set — the sheet every app used to
/// wire by hand, with the store's current wallet rather than one captured at launch.
private struct AICoinBuySheetModifier: ViewModifier {
    @ObservedObject var store: AICoinAppStore

    func body(content: Content) -> some View {
        content.sheet(isPresented: $store.showBuySheet) {
            BuyAICoinSheet(
                iapManager: store.iapManager,
                walletStore: store.balanceStore,
                identity: store.identity,
                walletClient: store.walletClient
            )
        }
    }
}

extension View {
    /// Presents `BuyAICoinSheet` when `store.showBuySheet` is true — raised automatically by any
    /// `402`, or by the app (say, from its `CoinBalanceBadge`).
    public func aicoinBuySheet(_ store: AICoinAppStore) -> some View {
        modifier(AICoinBuySheetModifier(store: store))
    }
}
