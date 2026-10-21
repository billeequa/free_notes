import Foundation
import SwiftUI
import ZIPFoundation

struct StoreIndex: Codable { var version = 1; var records: [LocalRecord] = [] }
struct StoreTransaction: Codable {
    let records: [LocalRecord]
    let writes: [String: String]
    let removes: [String]
}
struct StoreSnapshot { var records: [LocalRecord]; var error: String? }

@MainActor final class NotesStore: ObservableObject {
    @Published var records: [LocalRecord] = []
    @Published var loaded = false
    @Published var error: String?
    @Published var notice: String?
    @Published var editingID: String?
    @Published var showDoubleXPrompt = false
    @Published var rootURL: URL
    private var scopedURL: URL?
    private(set) var generation = 0
    var didChange: (() -> Void)?
    private let defaults: UserDefaults
    static var defaultFolder: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("JNotes", isDirectory: true) }
    init(defaults: UserDefaults = .standard, root: URL? = nil) {
        self.defaults = defaults; self.rootURL = root ?? Self.defaultFolder
        if root == nil, let bookmark = defaults.data(forKey: "notesBookmark") {
            var stale = false
            do {
                let url = try URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
                guard url.startAccessingSecurityScopedResource() else { throw JNotesError("Choose your notes folder again to restore access.") }
                scopedURL = url; rootURL = url
                if stale { defaults.set(try url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil), forKey: "notesBookmark") }
            } catch { self.error = "Notes folder is unavailable. Choose it again in Settings. \(error.localizedDescription)" }
        }
    }
    var tasks: [Payload] { records.filter { $0.payload.kind == .task && !$0.deleted }.map(\.payload).sorted { (Dates.parse($0.created_at) ?? .distantPast) < (Dates.parse($1.created_at) ?? .distantPast) } }
    func documents(_ kind: RecordKind, trash: Bool = false, search: String = "", sort: String = "modified") -> [LocalRecord] {
        records.filter { $0.payload.kind == kind && $0.deleted == trash && (search.isEmpty || $0.payload.title.localizedCaseInsensitiveContains(search) || $0.payload.body.localizedCaseInsensitiveContains(search)) }
            .sorted { a, b in
                let l = Dates.parse(sort == "created" ? a.payload.created_at : a.payload.modified_at) ?? .distantPast
                let r = Dates.parse(sort == "created" ? b.payload.created_at : b.payload.modified_at) ?? .distantPast
                return l == r ? a.id < b.id : l > r
            }
    }
    func load() async {
        guard error == nil else { loaded = true; return }
        let root = rootURL
        let snapshot = await Task.detached(priority: .userInitiated) { Self.scan(root) }.value
        records = snapshot.records; error = snapshot.error; loaded = true
        if error == nil { do { try persistIndex() } catch { self.error = error.localizedDescription } }
    }
    nonisolated static func scan(_ root: URL) -> StoreSnapshot {
        do {
            let fm = FileManager.default
            try fm.createDirectory(at: root, withIntermediateDirectories: true)
            try recoverTransaction(root)
            let indexURL = root.appendingPathComponent(".jnotes-ios-index.json")
            let index: StoreIndex
            if fm.fileExists(atPath: indexURL.path) {
                // Never discard an unreadable index: it carries stable cloud IDs.
                index = try JSONDecoder().decode(StoreIndex.self, from: Data(contentsOf: indexURL))
                guard index.version == 1 else { throw JNotesError("Unsupported notes index. Files preserved.") }
            } else { index = StoreIndex() }
            var result: [LocalRecord] = []
            let lookup = Dictionary(index.records.compactMap { r in r.filename.map { ((r.deleted ? "Trash/" : "") + $0, r) } }, uniquingKeysWith: { a, _ in a })
            for trash in [false, true] {
                let folder = trash ? root.appendingPathComponent("Trash") : root
                guard fm.fileExists(atPath: folder.path) else { continue }
                for url in try fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey], options: [.skipsHiddenFiles]) where url.pathExtension.lowercased() == "txt" && url.lastPathComponent != FileFormats.todoName {
                    guard try url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else { continue }
                    let previous = lookup[(trash ? "Trash/" : "") + url.lastPathComponent]
                    let modified = try url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate ?? Date()
                    let p = FileFormats.parseNote(try String(contentsOf: url, encoding: .utf8), filename: url.lastPathComponent, modified: modified, id: previous?.id ?? UUID().uuidString.lowercased())
                    result.append(LocalRecord(payload: p, deleted: trash, filename: url.lastPathComponent))
                }
            }
            let taskURL = root.appendingPathComponent(FileFormats.todoName)
            if fm.fileExists(atPath: taskURL.path) {
                result += try FileFormats.parseTasks(String(contentsOf: taskURL, encoding: .utf8)).map { LocalRecord(payload: $0) }
            }
            let present = Set(result.map(\.id))
            // Missing indexed files become tombstones, preserving sync identity.
            result += index.records.filter { !present.contains($0.id) }.map { r in var deleted = r; deleted.deleted = true; return deleted }
            guard Set(result.map(\.id)).count == result.count else { throw JNotesError("Duplicate local identities. Sync paused.") }
            return StoreSnapshot(records: result, error: nil)
        } catch { return StoreSnapshot(records: [], error: "Could not load notes: \(error.localizedDescription). Original files are preserved.") }
    }
    private func persistIndex(_ value: [LocalRecord]? = nil) throws {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(StoreIndex(records: value ?? records)).write(to: rootURL.appendingPathComponent(".jnotes-ios-index.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func selectFolder(_ url: URL) async throws {
        guard url.startAccessingSecurityScopedResource() else { throw JNotesError("Folder access was not granted") }
        let bookmark: Data
        do { bookmark = try url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil) } catch { url.stopAccessingSecurityScopedResource(); throw error }
        scopedURL?.stopAccessingSecurityScopedResource(); scopedURL = url; rootURL = url
        defaults.set(bookmark, forKey: "notesBookmark"); error = nil; loaded = false; await load()
    }
    nonisolated static func recoverTransaction(_ root: URL) throws {
        let fm = FileManager.default, journal = root.appendingPathComponent(".jnotes-ios-journal.json")
        guard fm.fileExists(atPath: journal.path) else { return }
        let tx = try JSONDecoder().decode(StoreTransaction.self, from: Data(contentsOf: journal))
        func path(_ relative: String) throws -> URL {
            let value = root.appendingPathComponent(relative).standardizedFileURL
            guard !relative.hasPrefix("/"), !relative.components(separatedBy: "/").contains(".."), value.path.hasPrefix(root.standardizedFileURL.path + "/") else { throw JNotesError("Invalid local save journal") }
            return value
        }
        for (name, text) in tx.writes {
            let url = try path(name)
            try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try text.write(to: url, atomically: true, encoding: .utf8)
        }
        // The intended destination is durable before an old name is removed.
        for name in tx.removes {
            let url = try path(name)
            if tx.writes[name] == nil && fm.fileExists(atPath: url.path) { try fm.removeItem(at: url) }
        }
        try JSONEncoder().encode(StoreIndex(records: tx.records)).write(to: root.appendingPathComponent(".jnotes-ios-index.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        try fm.removeItem(at: journal)
    }
    private func commit(_ tx: StoreTransaction) throws {
        let journal = rootURL.appendingPathComponent(".jnotes-ios-journal.json")
        try JSONEncoder().encode(tx).write(to: journal, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        do { try Self.recoverTransaction(rootURL) }
        catch {
            // Retain the transaction for recovery; prevent another write replacing it.
            self.error = "A local save needs recovery. Restart JNotes to retry. Original files are preserved."
            throw error
        }
        records = tx.records; generation += 1; didChange?()
    }
    func save(_ original: Payload, deleted: Bool = false, touch: Bool = true) throws {
        guard loaded, error == nil else { throw JNotesError("Resolve the notes folder error before saving.") }
        var p = try original.validated(); if touch { p.modified_at = Dates.iso(Date()) }
        let old = records.first { $0.id == p.id }
        var record = LocalRecord(payload: p, deleted: deleted, filename: old?.filename)
        var next = records.filter { $0.id != p.id }, writes: [String: String] = [:], removes: [String] = []
        if p.kind != .task {
            var name = FileFormats.filename(p)
            if next.contains(where: { $0.filename == name && $0.deleted == deleted }) { name = String(name.dropLast(4)) + "-" + String(p.id.prefix(8)) + ".txt" }
            record.filename = name
            let destination = (deleted ? "Trash/" : "") + name
            writes[destination] = FileFormats.serializeNote(p)
            if let oldName = old?.filename {
                let source = (old!.deleted ? "Trash/" : "") + oldName
                if source != destination { removes.append(source) }
            }
            next.append(record)
        } else {
            next.append(record)
            let liveTasks = next.filter { $0.payload.kind == .task && !$0.deleted }.map(\.payload).sorted { $0.order < $1.order }
            writes[FileFormats.todoName] = FileFormats.serializeTasks(liveTasks)
        }
        try commit(StoreTransaction(records: next, writes: writes, removes: removes))
        if p.kind == .task && p.done && old?.payload.done == false { considerDoubleXPrompt() }
    }
    func updateTask(_ id: String, transform: (inout Payload) -> Void) {
        guard var p = records.first(where: { $0.id == id })?.payload else { return }
        transform(&p); perform { try save(p) }
    }
    func perform(_ action: () throws -> Void) { do { try action() } catch { self.notice = error.localizedDescription } }
    func permanentDelete(_ id: String) throws {
        guard let r = records.first(where: { $0.id == id }), r.deleted else { return }
        var next = records; let i = next.firstIndex(where: { $0.id == id })!
        next[i].filename = nil; next[i].payload.body = ""; next[i].payload.title = ""; next[i].payload.description = ""
        let removes = r.filename.map { ["Trash/" + $0] } ?? []
        try commit(StoreTransaction(records: next, writes: [:], removes: removes))
    }
    func importFiles(_ urls: [URL]) throws {
        for url in urls {
            let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
            if url.pathExtension.lowercased() == "zip" {
                let temporary = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true)
                defer { try? FileManager.default.removeItem(at: temporary) }
                try SafeZIP.extract(url, to: temporary)
                let enumerator = FileManager.default.enumerator(at: temporary, includingPropertiesForKeys: [.isRegularFileKey])!
                let files = enumerator.compactMap { $0 as? URL }.filter { $0.pathExtension.lowercased() == "txt" }
                try importFiles(files)
            } else if url.lastPathComponent == FileFormats.todoName {
                for task in try FileFormats.parseTasks(String(contentsOf: url, encoding: .utf8)) {
                    if let existing = records.first(where: { $0.id == task.id }), !existing.payload.sameContent(as: task) { throw JNotesError("A task with this ID already exists. Existing task preserved.") }
                    try save(task, touch: false)
                }
            } else {
                let p = FileFormats.parseNote(try String(contentsOf: url, encoding: .utf8), filename: url.lastPathComponent)
                try save(p, touch: false)
            }
        }
        notice = "Imported. Files saved locally."
    }
    func exportZIP() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("JNotes-\(Dates.formatter("yyyyMMdd-HHmmss").string(from: Date())).zip")
        let archive = try Archive(url: url, accessMode: .create)
        for record in records where record.payload.kind != .task && !record.deleted {
            guard let name = record.filename else { continue }
            try archive.addEntry(with: name, relativeTo: rootURL)
        }
        let taskURL = rootURL.appendingPathComponent(FileFormats.todoName)
        if FileManager.default.fileExists(atPath: taskURL.path) { try archive.addEntry(with: FileFormats.todoName, relativeTo: rootURL) }
        return url
    }
    private func considerDoubleXPrompt() {
        let day = Dates.day(Date())
        guard defaults.bool(forKey: "doubleXEnabled"), defaults.string(forKey: "doubleXPromptDay") != day,
              tasks.filter({ $0.completed_at.flatMap(Dates.parse).map(Dates.day) == day }).count > 6,
              !records.contains(where: { !$0.deleted && $0.payload.double_x_date == day }) else { return }
        defaults.set(day, forKey: "doubleXPromptDay"); showDoubleXPrompt = true
    }
    func doubleXToday() throws -> Payload {
        let day = Dates.day(Date())
        let base = records.first { !$0.deleted && $0.payload.kind == .journal && $0.payload.journal_date == day }?.payload ?? Payload.new(.journal)
        let p = Journal.markDoubleX(base, tasks: tasks); try save(p); return p
    }
}
