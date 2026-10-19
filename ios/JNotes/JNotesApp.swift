import SwiftUI
import BackgroundTasks

@MainActor final class AppDelegate: NSObject, UIApplicationDelegate {
    static var backgroundWork: (() async -> Void)?
    static let taskID = "com.billeequa.jnotes.ios.refresh"
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.taskID, using: nil) { task in
            let work = Task { @MainActor in await Self.backgroundWork?(); task.setTaskCompleted(success: !Task.isCancelled); Self.schedule() }
            task.expirationHandler = { work.cancel() }
        }
        return true
    }
    static func schedule() {
        guard UserDefaults.standard.bool(forKey: "syncEnabled") else { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskID); return }
        let request = BGAppRefreshTaskRequest(identifier: taskID); request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
@main struct JNotesApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var store: NotesStore
    @StateObject private var sync: SyncCoordinator
    @Environment(\.scenePhase) private var phase
    init() {
        let notes = NotesStore()
        // Construct a single shared store for the app and sync; no networking on startup.
        _store = StateObject(wrappedValue: notes)
        let coordinator = SyncCoordinator(store: notes)
        _sync = StateObject(wrappedValue: coordinator)
        AppDelegate.backgroundWork = { if !notes.loaded { await notes.load() }; await coordinator.sync() }
    }
    var body: some Scene {
        WindowGroup {
            HomeView().environmentObject(store).environmentObject(sync)
                .task { await store.load(); sync.schedule() }
                .onChange(of: phase) { value in
                    if value == .active { sync.schedule() }
                    if value == .background { AppDelegate.schedule() }
                }
        }
    }
}
