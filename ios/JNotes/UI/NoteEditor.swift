import SwiftUI
import UIKit

struct NoteEditor: View {
    let request: EditorRequest
    @EnvironmentObject var store: NotesStore
    @EnvironmentObject var sync: SyncCoordinator
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("theme") private var theme = "dark_1"
    @AppStorage("fontScale") private var scale = 1.0
    @State private var draft: Payload
    @State private var saved: Payload
    @State private var editing: Bool
    @State private var rename = false
    @State private var info = false
    @State private var trash = false
    @State private var history = false
    @State private var title = ""
    @State private var saveError: String?
    @State private var link: URL?
    @State private var saveTask: Task<Void, Never>?
    private var palette: Palette { Palette(theme) }
    init(request: EditorRequest) {
        self.request = request; _draft = State(initialValue: request.payload); _saved = State(initialValue: request.payload); _editing = State(initialValue: request.editing)
    }
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Button { if save() { dismiss() } } label: { Image(systemName: "arrow.left").frame(width: 44, height: 44) }.accessibilityLabel("Back")
                VStack(spacing: 2) {
                    Button { title = draft.title; rename = true } label: { Text(draft.displayTitle).font(.system(size: 18 * scale, weight: .medium)).lineLimit(1) }
                    if draft != saved || saveError != nil { Button(saveError == nil ? "Unsaved changes" : "Not saved — Retry") { _ = save() }.font(.caption).foregroundStyle(saveError == nil ? palette.secondary : .red) }
                }.frame(maxWidth: .infinity)
                if editing { Button { editing = false; _ = save() } label: { Image(systemName: "keyboard.chevron.compact.down").frame(width: 38, height: 44) }.accessibilityLabel("Read note and hide keyboard") }
                Menu {
                    Button("Note information") { info = true }
                    if sync.connected { Button("Revision history") { history = true } }
                    Button("Move to trash", role: .destructive) { trash = true }
                } label: { Image(systemName: "ellipsis").rotationEffect(.degrees(90)).frame(width: 44, height: 44) }.accessibilityLabel("Note actions")
            }.background(palette.chrome)
            NativeNoteText(text: $draft.body, editing: $editing, palette: palette, fontSize: 17 * scale, openLink: { link = $0 })
                .padding(.horizontal, 18).padding(.top, 12)
        }
        .background(palette.background.ignoresSafeArea()).foregroundStyle(palette.text).tint(palette.primary)
        .preferredColorScheme(palette.dark ? .dark : .light)
        .onAppear { store.editingID = draft.id }
        .onChange(of: draft) { _ in
            saveTask?.cancel(); saveTask = Task { do { try await Task.sleep(nanoseconds: 1_500_000_000); try Task.checkCancellation(); _ = save() } catch { } }
        }
        .onChange(of: scenePhase) { phase in if phase != .active { _ = save() } }
        .onDisappear { saveTask?.cancel(); _ = save(); store.editingID = nil; sync.schedule() }
        .alert("Rename note", isPresented: $rename) {
            TextField("Title", text: $title); Button("Rename") { draft.title = title.replacingOccurrences(of: "\n", with: " "); _ = save() }; Button("Cancel", role: .cancel) { }
        }
        .alert("Note information", isPresented: $info) { Button("Close", role: .cancel) { } } message: { Text("Created \(Dates.display(draft.created_at))\nLast saved \(Dates.display(saved.modified_at))\(saveError.map { "\n" + $0 } ?? "")") }
        .confirmationDialog("Move note to trash?", isPresented: $trash, titleVisibility: .visible) {
            Button("Move to trash", role: .destructive) { do { try store.save(draft, deleted: true); saved = draft; dismiss() } catch { saveError = error.localizedDescription } }
        } message: { Text("You can restore it later from Trash.") }
        .alert("Open link?", isPresented: Binding(get: { link != nil }, set: { if !$0 { link = nil } })) {
            Button("Open") { if let link { UIApplication.shared.open(link) }; link = nil }; Button("Cancel", role: .cancel) { link = nil }
        } message: { Text(link?.absoluteString ?? "") }
        .sheet(isPresented: $history) { RevisionHistoryView(id: draft.id) { p in draft = p; _ = save() } }
        .interactiveDismissDisabled()
    }
    @discardableResult private func save() -> Bool {
        guard draft != saved else { return true }
        do { try store.save(draft); draft.modified_at = store.records.first { $0.id == draft.id }!.payload.modified_at; saved = draft; saveError = nil; return true }
        catch { saveError = error.localizedDescription; return false }
    }
}

/// One native text surface for notes and journal; switching to reading removes the cursor.
struct NativeNoteText: UIViewRepresentable {
    @Binding var text: String
    @Binding var editing: Bool
    let palette: Palette
    let fontSize: Double
    let openLink: (URL) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView(); view.backgroundColor = .clear; view.isScrollEnabled = true
        view.textContainerInset = UIEdgeInsets(top: 0, left: 0, bottom: CGFloat(fontSize * 6), right: 0)
        view.textContainer.lineFragmentPadding = 0; view.alwaysBounceVertical = true
        view.keyboardDismissMode = .interactive; view.delegate = context.coordinator
        view.adjustsFontForContentSizeCategory = true; view.accessibilityLabel = "Note text"
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tap(_:)))
        tap.cancelsTouchesInView = false; tap.delegate = context.coordinator; view.addGestureRecognizer(tap)
        let toolbar = UIToolbar(); toolbar.sizeToFit()
        toolbar.items = [UIBarButtonItem(systemItem: .flexibleSpace), UIBarButtonItem(title: "Read", style: .done, target: context.coordinator, action: #selector(Coordinator.read))]
        view.inputAccessoryView = toolbar; context.coordinator.view = view
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        context.coordinator.parent = self
        if view.text != text { let selected = view.selectedRange; view.text = text; view.selectedRange = NSRange(location: min(selected.location, view.text.utf16.count), length: 0) }
        view.font = .systemFont(ofSize: fontSize); view.textColor = UIColor(palette.text); view.tintColor = UIColor(palette.primary)
        view.textContainerInset.bottom = CGFloat(fontSize * 6)
        if view.isEditable != editing { view.isEditable = editing; view.dataDetectorTypes = editing ? [] : [.link] }
        if editing && !view.isFirstResponder { DispatchQueue.main.async { if context.coordinator.parent.editing { view.becomeFirstResponder() } } }
        if !editing && view.isFirstResponder { view.resignFirstResponder() }
    }
    final class Coordinator: NSObject, UITextViewDelegate, UIGestureRecognizerDelegate {
        var parent: NativeNoteText
        weak var view: UITextView?
        init(_ parent: NativeNoteText) { self.parent = parent }
        func textViewDidChange(_ textView: UITextView) { parent.text = textView.text }
        func textViewDidEndEditing(_ textView: UITextView) { if parent.editing { parent.editing = false } }
        func textView(_ textView: UITextView, shouldInteractWith URL: URL, in characterRange: NSRange, interaction: UITextItemInteraction) -> Bool { parent.openLink(URL); return false }
        @objc func read() { parent.editing = false; view?.resignFirstResponder() }
        @objc func tap(_ gesture: UITapGestureRecognizer) {
            guard !parent.editing, let view else { return }
            let point = gesture.location(in: view)
            guard let position = view.closestPosition(to: point) else { return }
            let index = view.offset(from: view.beginningOfDocument, to: position)
            if index < view.attributedText.length, let link = view.attributedText.attribute(.link, at: index, effectiveRange: nil) as? URL { parent.openLink(link); return }
            view.selectedRange = NSRange(location: index, length: 0); parent.editing = true
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool { true }
    }
}

struct RevisionHistoryView: View {
    let id: String
    let restore: (Payload) -> Void
    @EnvironmentObject var sync: SyncCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var revisions: [(HistoryRevision, Payload)] = []
    @State private var error: String?
    @State private var selected: Int?
    var body: some View {
        NavigationStack {
            List {
                if let error { Text(error) }
                ForEach(revisions.indices, id: \.self) { index in
                    let item = revisions[index]
                    DisclosureGroup("Revision \(item.0.revision) · \(Dates.display(item.0.changed_at ?? item.1.modified_at))\(item.0.deleted ? " · Deleted" : "")") {
                        Text(item.1.title).font(.headline); Text(item.1.kind == .task ? item.1.description : item.1.body).textSelection(.enabled)
                        Button("Restore this revision") { selected = index }
                    }
                }
            }.navigationTitle("Revision history").toolbar { Button("Close") { dismiss() } }
                .task { do { revisions = try await sync.history(id) } catch { self.error = error.localizedDescription } }
                .confirmationDialog("Restore revision?", isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } }), titleVisibility: .visible) {
                    Button("Restore as current version") { if let selected { restore(revisions[selected].1) }; dismiss() }
                }
        }
    }
}
