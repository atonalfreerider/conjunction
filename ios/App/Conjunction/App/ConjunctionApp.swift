import SwiftUI
import UserNotifications
import ConjunctionCore

@main
struct ConjunctionApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var app = AppModel.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
        }
        .onChange(of: scenePhase) { _, phase in
            app.nearby.setForeground(phase == .active)
            if phase == .active {
                // A pause may have ended, Bluetooth permission may have changed in Settings.
                app.applyNearby()
            }
        }
    }
}

/// Launch, notification taps and Bluetooth state restoration.
///
/// When iOS relaunches the app in the background for a Bluetooth event (state restoration), the
/// launch options carry the restore identifiers; creating `AppModel` re-creates the node, and
/// `applyNearby` re-creates both CoreBluetooth managers with the same identifiers when Nearby is on,
/// which is what restoration requires.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        MainActor.assumeIsolated {
            let app = AppModel.shared
            if launchOptions?[.bluetoothCentrals] != nil || launchOptions?[.bluetoothPeripherals] != nil {
                app.nearby.setForeground(false)
            }
            app.applyNearby()
        }
        return true
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        MainActor.assumeIsolated { AppModel.shared.nearby.setForeground(false) }
    }

    /// While the app is open, matches show full-screen instead; messages for another chat still banner.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let event = NearbyEvent.fromUserInfo(info) else { return }
        await MainActor.run { AppModel.shared.pendingEvent = event }
    }
}
