import SwiftUI
import UniformTypeIdentifiers

struct EditorRequest: Identifiable { let payload: Payload; let editing: Bool; var id: String { payload.id } }
enum HomePage: String, CaseIterable { case ebooks = "Ebooks", notes = "Notes", journal = "Journal", todos = "To Do" }

struct HomeView: View {
    @EnvironmentObject var store: NotesStore
    @EnvironmentObject var sync: SyncCoordinator
    @AppStorage("theme") private var theme = "dark_1"
    @AppStorage("fontScale") private var scale = 1.0
    @AppStorage("showReader") private var showReader = true
    @AppStorage("folderChosen") private var folderChosen = false
    @State private var page: HomePage = .notes
    @State private var jump = 0
    @State private var settings = false
    @State private var folderPicker = false
    @State private var editor: EditorRequest?
    private var palette: Palette { Palette(theme) }
    private var pages: [HomePage] { HomePage.allCases.filter { showReader || $0 != .ebooks } }
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("JNotes").font(.title2.weight(.medium)); Spacer()
                Button { settings = true } label: { Image(systemName: "gearshape").frame(width: 44, height: 44) }.accessibilityLabel("Settings")
            }.padding(.horizontal, 16).background(palette.chrome)
            HStack(spacing: 0) {
                ForEach(pages, id: \.self) { target in
                    Button { if page == target { jump += 1 }; page = target } label: {
                        VStack(spacing: 10) {
                            Text(target.rawValue).font(.system(size: 14 * scale, weight: .medium)).lineLimit(1).minimumScaleFactor(0.7)
                            Rectangle().fill(page == target ? palette.primary : Color.clear).frame(height: 3)
                        }.padding(.top, 12).frame(maxWidth: .infinity)
                    }.accessibilityAddTraits(page == target ? .isSelected : [])
                }
            }.background(palette.surface).gesture(DragGesture(minimumDistance: 24).onEnded { value in
                guard let i = pages.firstIndex(of: page) else { return }; let n = i + (value.translation.width < 0 ? 1 : -1)
                if pages.indices.contains(n) { page = pages[n] }
            })
            if !store.loaded { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
            else if let error = store.error {
                VStack(spacing: 20) { Text(error); Button("Pick notes folder") { folderPicker = true } }.padding(24).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if !folderChosen {
                VStack(spacing: 20) {
                    Image(systemName: "folder").font(.system(size: 48)); Text("Your notes, in your files").font(.title2)
                    Text("Choose a notes folder. Existing JNotes text files and tasks will be loaded automatically.")
                    Button("Pick notes folder") { folderPicker = true }.buttonStyle(.borderedProminent)
                    Button("Use JNotes folder on this iPhone") { folderChosen = true }
                }.multilineTextAlignment(.center).padding(28).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                switch page {
                case .notes: DocumentList(kind: .note, jump: jump, open: open)
                case .journal: DocumentList(kind: .journal, jump: jump, open: open)
                case .todos: TodoView(jump: jump, onNotes: { page = .notes })
                case .ebooks: BookLibraryView(jump: jump)
                }
            }
        }
        .background(palette.background.ignoresSafeArea()).foregroundStyle(palette.text).tint(palette.primary)
        .preferredColorScheme(palette.dark ? .dark : .light)
        .fullScreenCover(isPresented: $settings) { SettingsView() }
        .fullScreenCover(item: $editor, onDismiss: { store.editingID = nil; sync.schedule() }) { NoteEditor(request: $0) }
        .fileImporter(isPresented: $folderPicker, allowedContentTypes: [.folder]) { result in
            Task { do { try await store.selectFolder(result.get()); folderChosen = store.error == nil } catch { store.notice = error.localizedDescription } }
        }
        .alert("JNotes", isPresented: Binding(get: { store.notice != nil }, set: { if !$0 { store.notice = nil } })) { Button("OK") { store.notice = nil } } message: { Text(store.notice ?? "") }
        .alert("Double X Day?", isPresented: $store.showDoubleXPrompt) {
            Button("Yes") { store.perform { open(try store.doubleXToday(), true) } }; Button("No", role: .cancel) { }
        } message: { Text("You've completed more than six things today. Mark today's journal as a Double X Day?") }
        .onChange(of: showReader) { value in if !value && page == .ebooks { page = .notes } }
    }
    private func open(_ payload: Payload, _ editing: Bool) { editor = EditorRequest(payload: payload, editing: editing) }
}

struct DocumentList: View {
    let kind: RecordKind, jump: Int
    let open: (Payload, Bool) -> Void
    @EnvironmentObject var store: NotesStore
    @AppStorage("theme") private var theme = "dark_1"
    @AppStorage("fontScale") private var scale = 1.0
    @AppStorage("sortMode") private var sort = "modified"
    @AppStorage("doubleXEnabled") private var doubleX = false
    @State private var query = ""
    @State private var rename: Payload?
    @State private var renameText = ""
    @State private var trash: Payload?
    private var palette: Palette { Palette(theme) }
    var body: some View {
        ScrollViewReader { proxy in
            ZStack(alignment: .bottomTrailing) {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        HStack {
                            TextField("Search \(kind == .journal ? "journal" : "notes")", text: $query).textFieldStyle(.roundedBorder)
                            Menu { Button("Modified") { sort = "modified" }; Button("Created") { sort = "created" } } label: { Image(systemName: "arrow.up.arrow.down").frame(width: 44, height: 44) }.accessibilityLabel("Sort")
                        }.id("top")
                        let notes = store.documents(kind, search: query, sort: sort)
                        if notes.isEmpty { Text(query.isEmpty ? "Tap + to create your first \(kind == .journal ? "journal entry" : "note")." : "No matching notes.").foregroundStyle(palette.secondary).padding(24) }
                        ForEach(notes) { record in
                            Button { open(record.payload, false) } label: {
                                HStack(alignment: .top) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(record.payload.displayTitle).font(.system(size: 18 * scale, weight: .medium)).multilineTextAlignment(.leading)
                                        Text(Dates.display(sort == "created" ? record.payload.created_at : record.payload.modified_at)).font(.system(size: 13 * scale)).foregroundStyle(palette.secondary)
                                    }
                                    Spacer(minLength: 0)
                                    Menu { actions(record.payload) } label: { Image(systemName: "ellipsis").rotationEffect(.degrees(90)).frame(width: 36, height: 36) }.accessibilityLabel("Note actions")
                                }.card(palette.surface)
                            }.buttonStyle(.plain).contextMenu { actions(record.payload) }
                        }
                        Color.clear.frame(height: 90)
                    }.padding(16)
                }.onChange(of: jump) { _ in withAnimation { proxy.scrollTo("top", anchor: .top) } }
                Button(action: create) { Image(systemName: "plus").font(.title2).frame(width: 56, height: 56).background(palette.primary, in: RoundedRectangle(cornerRadius: 16)).foregroundStyle(palette.dark ? Color.black : Color.white) }.accessibilityLabel(kind == .journal ? "New journal entry" : "New note").padding(20)
            }
        }
        .alert("Rename note", isPresented: Binding(get: { rename != nil }, set: { if !$0 { rename = nil } })) {
            TextField("Title", text: $renameText)
            Button("Rename") { if var p = rename { p.title = renameText.replacingOccurrences(of: "\n", with: " "); store.perform { try store.save(p) } }; rename = nil }
            Button("Cancel", role: .cancel) { rename = nil }
        }
        .confirmationDialog("Move note to trash?", isPresented: Binding(get: { trash != nil }, set: { if !$0 { trash = nil } }), titleVisibility: .visible) {
            Button("Move to trash", role: .destructive) { if let p = trash { store.perform { try store.save(p, deleted: true) } }; trash = nil }
        } message: { Text("You can restore it later from Trash.") }
    }
    @ViewBuilder private func actions(_ p: Payload) -> some View {
        Button("Open") { open(p, false) }
        Button("Rename") { rename = p; renameText = p.title }
        if kind == .journal && doubleX && p.subtype != "double_x_day" { Button("Mark Double X Day") { store.perform { let marked = Journal.markDoubleX(p, tasks: store.tasks); try store.save(marked); open(marked, true) } } }
        Button("Move to trash", role: .destructive) { trash = p }
    }
    private func create() {
        var p = Payload.new(kind)
        if kind == .journal { p.title = Journal.nextTitle(p.journal_date!, existing: store.documents(.journal).map { $0.payload.title }) }
        store.perform { try store.save(p); open(p, true) }
    }
}
