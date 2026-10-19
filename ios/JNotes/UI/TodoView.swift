import SwiftUI

struct TaskEditRequest: Identifiable { var payload: Payload; var id: String { payload.id } }
struct ArchiveFolder: Identifiable { let id: String, title: String; let tasks: [Payload] }
struct TodoView: View {
    let jump: Int
    let onNotes: () -> Void
    @EnvironmentObject var store: NotesStore
    @AppStorage("theme") private var theme = "dark_1"
    @State private var filter = "Normal"
    @State private var expanded = Set<String>()
    @State private var pendingID: String?
    @State private var pendingLeft = true
    @State private var edit: TaskEditRequest?
    @State private var delete: Payload?
    @State private var newestID: String?
    private var palette: Palette { Palette(theme) }
    private var completed: [Payload] { store.tasks.filter(\.done).sorted { (Dates.parse($0.completed_at!) ?? .distantPast) < (Dates.parse($1.completed_at!) ?? .distantPast) } }
    private var active: [Payload] { store.tasks.filter { !$0.done } }
    private var filtered: [Payload] {
        switch filter { case "All": return completed + active; case "Open": return active; case "Completed": return completed; case "Flagged": return store.tasks.filter(\.isFlagged); default: return active }
    }
    private var folders: [ArchiveFolder] {
        let today = Dates.day(Date()), year = String(today.prefix(4)), month = String(today.prefix(7))
        let groups = Dictionary(grouping: completed) { p -> String in
            let day = p.completed_at.flatMap(Dates.parse).map(Dates.day) ?? today
            return day.hasPrefix(year) ? "month:" + String(day.prefix(7)) : "year:" + String(day.prefix(4))
        }
        let keys = groups.keys.filter { $0.hasPrefix("year:") }.sorted() + groups.keys.filter { $0.hasPrefix("month:") }.sorted()
        return keys.map { key in
            let value = String(key.dropFirst(key.hasPrefix("year:") ? 5 : 6))
            let title = key.hasPrefix("year:") ? value : (Dates.formatter("yyyy-MM-dd").date(from: value + "-01").map { Dates.formatter("MMMM").string(from: $0) } ?? value) + (value == month ? " (this month)" : "")
            return ArchiveFolder(id: key, title: "✅ " + title, tasks: groups[key]!)
        }
    }
    var body: some View {
        GeometryReader { geometry in
            ScrollViewReader { proxy in
                ZStack(alignment: .bottomTrailing) {
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            HStack {
                                Text("View").font(.subheadline)
                                Menu {
                                    ForEach(["Normal","All","Open","Completed","Flagged"], id: \.self) { label in Button(label) { filter = label; pendingID = nil } }
                                    Divider(); Button("Jump to open items") { filter = "Normal"; withAnimation { proxy.scrollTo("active", anchor: .top) } }
                                } label: { Text(filter).padding(.horizontal, 12).padding(.vertical, 8).background(palette.surface, in: RoundedRectangle(cornerRadius: 12)) }
                                Spacer()
                            }.padding(.bottom, 8).id("top")
                            if filter == "Normal" {
                                ForEach(folders) { folder in
                                    VStack(spacing: 4) {
                                        Button {
                                            if expanded.contains(folder.id) { expanded.remove(folder.id) } else { expanded.insert(folder.id) }
                                        } label: {
                                            HStack { Text(folder.title); Spacer(); Text(String(folder.tasks.count)); Image(systemName: expanded.contains(folder.id) ? "chevron.down" : "chevron.right") }.padding(16).foregroundStyle(palette.secondary)
                                        }
                                        if expanded.contains(folder.id) { ForEach(folder.tasks) { p in taskCard(p).padding(.horizontal, 8) } }
                                    }.padding(.bottom, expanded.contains(folder.id) ? 12 : 0).background(palette.primary.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
                                }
                                ForEach(Array(completed.suffix(10))) { p in taskCard(p).id("recent:" + p.id) }
                                Color.clear.frame(height: 8).id("active")
                                VStack(spacing: 8) {
                                    if active.isEmpty { Text(store.tasks.isEmpty ? "Tap + to add your first to-do." : "No open tasks. Scroll up for completed tasks.").foregroundStyle(palette.secondary).padding(24) }
                                    ForEach(active) { p in taskCard(p).id(p.id) }
                                    Spacer(minLength: 0)
                                }.frame(minHeight: max(0, geometry.size.height - 32), alignment: .top)
                            } else {
                                ForEach(filtered) { p in taskCard(p).id(p.id) }
                                if filtered.isEmpty { Text("No \(filter.lowercased()) tasks.").padding(24) }
                            }
                            Color.clear.frame(height: 90)
                        }.padding(.horizontal, 16).padding(.top, 16)
                    }
                    Button { edit = TaskEditRequest(payload: .new(.task)) } label: {
                        Image(systemName: "plus").font(.title2).frame(width: 56, height: 56).background(palette.primary, in: RoundedRectangle(cornerRadius: 16)).foregroundStyle(palette.dark ? Color.black : Color.white)
                    }.accessibilityLabel("Add to-do").padding(20)
                }
                .onAppear { DispatchQueue.main.async { proxy.scrollTo("active", anchor: .top) } }
                .onChange(of: jump) { _ in pendingID = nil; filter = "Normal"; DispatchQueue.main.async { withAnimation { proxy.scrollTo("active", anchor: .top) } } }
                .onChange(of: newestID) { id in if let id { filter = "Normal"; DispatchQueue.main.async { withAnimation { proxy.scrollTo(id, anchor: .center) } } } }
            }
        }
        .sheet(item: $edit) { request in TaskEditView(original: request.payload) { p in try store.save(p); newestID = p.id } }
        .confirmationDialog("Delete to-do?", isPresented: Binding(get: { delete != nil }, set: { if !$0 { delete = nil } }), titleVisibility: .visible) {
            Button("Delete", role: .destructive) { if let p = delete { store.perform { try store.save(p, deleted: true) } }; delete = nil }
        } message: { Text("This permanently removes the item and its timestamps from the list.") }
    }
    private func taskCard(_ p: Payload) -> some View {
        TaskCard(payload: p, palette: palette, pending: pendingID == p.id, left: pendingLeft,
                 reveal: { left in withAnimation(.easeOut(duration: 0.16)) { pendingLeft = left; pendingID = pendingID == p.id ? nil : p.id } },
                 cancel: { withAnimation { pendingID = nil } },
                 confirm: { pendingID = nil; store.updateTask(p.id) { $0.completed_at = Dates.iso(Date()) } },
                 reopen: { store.updateTask(p.id) { $0.completed_at = nil } },
                 edit: { edit = TaskEditRequest(payload: p) },
                 toggleFlag: { flag in store.updateTask(p.id) { task in if task.flags.contains(flag.rawValue) { task.flags.removeAll { $0 == flag.rawValue } } else { task.flags.append(flag.rawValue) } } },
                 removeLegacy: { store.updateTask(p.id) { $0.flagged = false } },
                 delete: { delete = p }, onNotes: onNotes)
    }
}
struct TaskCard: View {
    let payload: Payload, palette: Palette
    let pending: Bool, left: Bool
    let reveal: (Bool) -> Void
    let cancel: () -> Void, confirm: () -> Void, reopen: () -> Void, edit: () -> Void
    let toggleFlag: (TaskFlag) -> Void
    let removeLegacy: () -> Void, delete: () -> Void, onNotes: () -> Void
    @AppStorage("fontScale") private var scale = 1.0
    @State private var drag: CGFloat = 0
    private var offset: CGFloat { pending ? (left ? 64 : -64) : min(0, max(-64, drag)) }
    var body: some View {
        ZStack(alignment: left ? .leading : .trailing) {
            if pending || drag < -5 {
                Button(action: confirm) { Image(systemName: "checkmark").font(.title2.bold()).frame(width: 56, height: 56).background(Color(hex: 0x208447), in: RoundedRectangle(cornerRadius: 12)).foregroundStyle(.white) }.disabled(!pending).accessibilityLabel("Confirm completion: \(payload.title)")
            }
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 10) {
                    Button { if payload.done { reopen() } else if pending { cancel() } else { reveal(true) } } label: {
                        Image(systemName: payload.done || pending ? "checkmark.square.fill" : "square").font(.system(size: 24)).foregroundStyle(palette.primary.opacity(payload.done || pending ? 0.65 : 1)).frame(width: 36, height: 40)
                    }.accessibilityLabel(payload.done ? "Reopen: \(payload.title)" : pending ? "Cancel completion: \(payload.title)" : "Show completion confirmation: \(payload.title)")
                    Text(([payload.symbols, payload.title].filter { !$0.isEmpty }).joined(separator: " ")).font(.system(size: 18 * scale, weight: .medium)).frame(maxWidth: .infinity, alignment: .leading)
                    Menu { actions } label: { Image(systemName: "ellipsis").rotationEffect(.degrees(90)).frame(width: 36, height: 40) }
                }
                if !payload.description.isEmpty { Text(payload.description).font(.system(size: 16 * scale)).padding(.horizontal, 8) }
                Text(payload.completed_at.map { "Completed: " + Dates.display($0) } ?? "Added: " + Dates.display(payload.created_at)).font(.system(size: 11 * scale)).foregroundStyle(palette.secondary).padding(.horizontal, 8)
            }
            .padding(.horizontal, 8).padding(.vertical, 6).frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.taskColor(payload.flags, done: payload.done), in: RoundedRectangle(cornerRadius: 12))
            .foregroundStyle(payload.done ? palette.secondary : (payload.flags.isEmpty ? palette.text : Color(hex: palette.dark ? 0xF4F4F5 : 0x202124)))
            .contentShape(Rectangle()).offset(x: offset)
            .onTapGesture { if pending { cancel() } else { edit() } }.contextMenu { actions }
            .simultaneousGesture(DragGesture(minimumDistance: 20).onChanged { value in if !payload.done && abs(value.translation.width) > abs(value.translation.height) { drag = value.translation.width } }.onEnded { value in
                guard abs(value.translation.width) > abs(value.translation.height) else { drag = 0; return }
                if pending { cancel() } else if value.translation.width > 64 { onNotes() } else if value.translation.width < -32 && !payload.done { reveal(false) }; drag = 0
            })
        }.clipShape(RoundedRectangle(cornerRadius: 12))
    }
    @ViewBuilder private var actions: some View {
        Button("Edit", action: edit)
        ForEach(TaskFlag.allCases) { flag in Button("\(payload.flags.contains(flag.rawValue) ? "✓ " : "")Mark \(flag.label) \(flag.emoji)") { toggleFlag(flag) } }
        if payload.flagged { Button("Remove legacy flag", action: removeLegacy) }
        Button(payload.done ? "Mark incomplete" : "Show completion check") { if payload.done { reopen() } else { reveal(false) } }
        Button("Delete", role: .destructive, action: delete)
    }
}
struct TaskEditView: View {
    let original: Payload
    let save: (Payload) throws -> Void
    @Environment(\.dismiss) private var dismiss
    @AppStorage("theme") private var theme = "dark_1"
    @State private var title = ""
    @State private var description = ""
    @State private var flags = Set<String>()
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                TextField("To-do", text: $title, axis: .vertical)
                TextField("Description", text: $description, axis: .vertical).lineLimit(4...12)
                ForEach(TaskFlag.allCases) { flag in Toggle("Mark \(flag.label) \(flag.emoji)", isOn: Binding(get: { flags.contains(flag.rawValue) }, set: { if $0 { flags.insert(flag.rawValue) } else { flags.remove(flag.rawValue) } })) }
                if let error { Text(error).foregroundStyle(.red) }
            }.navigationTitle(original.title.isEmpty ? "Add to-do" : "Edit to-do").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("Save") {
                        var p = original; p.title = title; p.description = description; p.flags = TaskFlag.allCases.map(\.rawValue).filter { flags.contains($0) }; p.flagged = original.flagged && p.flags.isEmpty
                        do { try save(p); dismiss() } catch { self.error = error.localizedDescription }
                    }.disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
                }
        }.preferredColorScheme(Palette(theme).dark ? .dark : .light).tint(Palette(theme).primary)
            .onAppear { title = original.title; description = original.description; flags = Set(original.flags) }
    }
}
