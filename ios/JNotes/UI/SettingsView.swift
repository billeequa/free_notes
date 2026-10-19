import SwiftUI
import UniformTypeIdentifiers

struct ShareFile: Identifiable { let url: URL; var id: String { url.path } }
struct SettingsView: View {
    @EnvironmentObject var store: NotesStore
    @EnvironmentObject var sync: SyncCoordinator
    @Environment(\.dismiss) private var dismiss
    @AppStorage("theme") private var theme = "dark_1"
    @AppStorage("fontScale") private var scale = 1.0
    @AppStorage("doubleXEnabled") private var doubleX = false
    @AppStorage("showReader") private var showReader = true
    @AppStorage("folderChosen") private var folderChosen = false
    @State private var folderPicker = false
    @State private var imports = false
    @State private var trash = false
    @State private var cloud = false
    @State private var share: ShareFile?
    private var palette: Palette { Palette(theme) }
    var body: some View {
        NavigationStack {
            Form {
                Section("Notes location") {
                    Text(store.rootURL.lastPathComponent).font(.headline)
                    Button("Change") { if sync.connected { store.notice = "Disconnect Web Sync before changing the notes folder." } else { folderPicker = true } }
                    Text("Notes and tasks save automatically to your selected folder.").font(.footnote)
                    Button("Import text files or ZIP") { imports = true }
                    Button("Export notes and tasks as ZIP") { store.perform { share = ShareFile(url: try store.exportZIP()) } }
                }
                Section("Appearance") {
                    Picker("Theme", selection: $theme) { ForEach(Palette.themes, id: \.0) { pair in Text(pair.1).tag(pair.0) } }
                    HStack { Text("Font size"); Spacer(); Text("\(Int(scale * 100))%") }
                    Slider(value: $scale, in: 0.8...1.6, step: 0.05).accessibilityLabel("Font size")
                }
                Section("Journal") { Toggle("Show Double X Day", isOn: $doubleX) }
                Section("E Reader") { Toggle("Show E Reader", isOn: $showReader) }
                Section("Web Sync") {
                    Button("Web Sync") { cloud = true }
                    Text(sync.status).font(.footnote)
                    Link("JNotes website · \(sync.website)", destination: URL(string: sync.website)!)
                }
                Section("Trash") { Button("Open trash") { trash = true } }
                Section("About") {
                    Text("JNotes for iOS 1.0.0")
                    Link("Source code · GitHub", destination: URL(string: "https://github.com/billeequa/free_notes")!)
                    Text("Your files stay readable and editable on this phone without an account or server connection.").font(.footnote)
                }
            }
            .scrollContentBackground(.hidden).background(palette.background).navigationTitle("Settings").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .navigationBarLeading) { Button { dismiss() } label: { Image(systemName: "arrow.left") }.accessibilityLabel("Back") } }
            .navigationDestination(isPresented: $trash) { TrashView() }
            .navigationDestination(isPresented: $cloud) { WebSyncView() }
        }
        .tint(palette.primary).foregroundStyle(palette.text).preferredColorScheme(palette.dark ? .dark : .light)
        .fileImporter(isPresented: $folderPicker, allowedContentTypes: [.folder]) { result in
            Task { do { try await store.selectFolder(result.get()); folderChosen = store.error == nil } catch { store.notice = error.localizedDescription } }
        }
        .fileImporter(isPresented: $imports, allowedContentTypes: [.plainText, .zip], allowsMultipleSelection: true) { result in store.perform { try store.importFiles(result.get()) } }
        .sheet(item: $share) { ActivitySheet(items: [$0.url]) }
        .alert("JNotes", isPresented: Binding(get: { store.notice != nil }, set: { if !$0 { store.notice = nil } })) { Button("OK") { store.notice = nil } } message: { Text(store.notice ?? "") }
    }
}
struct ActivitySheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) { }
}
struct TrashView: View {
    @EnvironmentObject var store: NotesStore
    @State private var deleting: LocalRecord?
    var body: some View {
        List {
            let deleted = store.records.filter { $0.deleted && ($0.filename != nil || $0.payload.title.hasSuffix("(conflict backup)")) }
            if deleted.isEmpty { Text("Trash is empty") }
            ForEach(deleted) { record in
                VStack(alignment: .leading, spacing: 8) {
                    Text(record.payload.displayTitle).font(.headline)
                    HStack {
                        Button("Restore") { store.perform { try store.save(record.payload, deleted: false) } }
                        Spacer()
                        Button("Delete forever", role: .destructive) { deleting = record }
                    }.buttonStyle(.borderless)
                }.padding(.vertical, 4)
            }
        }.navigationTitle("Trash")
            .confirmationDialog("Delete forever?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                Button("Delete forever", role: .destructive) { if let deleting { store.perform { try store.permanentDelete(deleting.id) } }; deleting = nil }
            } message: { Text("This permanently removes the local copy. Encrypted cloud revision history is retained when sync is enabled.") }
    }
}
struct WebSyncView: View {
    @EnvironmentObject var store: NotesStore
    @EnvironmentObject var sync: SyncCoordinator
    @State private var email = ""
    @State private var password = ""
    @State private var error: String?
    @State private var preview = false
    @State private var disconnect = false
    @AppStorage("theme") private var theme = "dark_1"
    var body: some View {
        Form {
            Section("Status") {
                Text(sync.status)
                if sync.busy { ProgressView() }
                if sync.connected {
                    Toggle("Enable synchronization", isOn: $sync.enabled).onChange(of: sync.enabled) { if $0 { Task { await sync.sync() } } }
                    Button("Sync now") { Task { await sync.sync() } }.disabled(!sync.enabled || sync.busy)
                    let last = UserDefaults.standard.double(forKey: "lastSync")
                    if last > 0 { Text("Last sync: " + Dates.display(Dates.iso(Date(timeIntervalSince1970: last)))).font(.footnote) }
                }
            }
            if !sync.connected || !sync.enabled {
                Section("Connect") {
                    TextField("Email", text: $email).textContentType(.username).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("JNotes password", text: $password).textContentType(.password)
                    Button("Sign in") {
                        Task {
                            let value = password; password = ""; error = nil
                            do { try await sync.connect(email: email, password: value); preview = true } catch { self.error = error.localizedDescription }
                        }
                    }.disabled(sync.busy || email.isEmpty || password.isEmpty)
                    if sync.connected { Button("Review and enable sync") { preview = true } }
                    if let error { Text(error).foregroundStyle(.red) }
                }
            }
            Section("Website") {
                Link("JNotes website · \(sync.website)", destination: URL(string: sync.website)!)
                Link("Set up my password / recover my account", destination: URL(string: sync.website)!)
                Text("Use the same email and password as the website. Account setup and recovery happen there. Your phone files remain available if the server is down.").font(.footnote)
            }
            if !sync.conflicts.isEmpty {
                Section("Conflicts") {
                    ForEach(sync.conflicts) { c in
                        DisclosureGroup(c.local.displayTitle) {
                            Text("On phone\(c.localDeleted ? " (deleted)" : "")").font(.headline)
                            Text(c.local.kind == .task ? c.local.description : c.local.body).lineLimit(8).textSelection(.enabled)
                            Text("On web\(c.remote.deleted ? " (deleted)" : "")").font(.headline)
                            Text(c.remotePayload.kind == .task ? c.remotePayload.description : c.remotePayload.body).lineLimit(8).textSelection(.enabled)
                            Button("Keep phone version") { resolve(c.id, "local") }
                            Button("Use web version") { resolve(c.id, "remote") }
                            Button("Keep both versions") { resolve(c.id, "both") }
                        }
                    }
                }
            }
            if sync.connected { Section { Button("Disconnect", role: .destructive) { disconnect = true }.disabled(sync.busy) } }
        }
        .scrollContentBackground(.hidden).background(Palette(theme).background).navigationTitle("Web Sync").navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $preview) { previewView }
        .confirmationDialog("Disconnect Web Sync?", isPresented: $disconnect, titleVisibility: .visible) {
            Button("Disconnect", role: .destructive) { do { try sync.disconnect() } catch { self.error = error.localizedDescription } }
        } message: { Text("All notes remain on this phone. Pending operations and conflicts are retained for reconnection.") }
    }
    private func resolve(_ id: String, _ choice: String) { do { try sync.resolve(id, choice: choice) } catch { self.error = error.localizedDescription } }
    private var previewView: some View {
        NavigationStack {
            List {
                Section("First sync preview") {
                    ForEach([RecordKind.task, .note, .journal], id: \.rawValue) { kind in
                        let count = store.records.filter { !$0.deleted && $0.payload.kind == kind }.count
                        Text("\(count) \(kind == .task ? "tasks" : kind == .note ? "notes" : "journal entries")")
                    }
                    Text("Sync all local items and download your cloud notebook. Different edits to the same item will be preserved for review.")
                }
                Section {
                    Button("Enable sync for all items") { preview = false; Task { await sync.start() } }
                    Button("Cancel", role: .cancel) { preview = false }
                }
            }.navigationTitle("Review sync").navigationBarTitleDisplayMode(.inline)
        }.presentationDetents([.medium, .large])
    }
}
