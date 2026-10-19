import XCTest
import ZIPFoundation
@testable import JNotes

final class JNotesTests: XCTestCase {
    func vector() throws -> [String: Any] { try JSONSerialization.jsonObject(with: Data(contentsOf: Bundle(for: Self.self).url(forResource: "vectors", withExtension: "json")!)) as! [String: Any] }
    func testAndroidAndBrowserCryptoVector() throws {
        let v = try vector()
        let b = try JSONDecoder().decode(Bootstrap.self, from: JSONSerialization.data(withJSONObject: v["bootstrap"]!))
        let credentials = try SyncCrypto.derive(v["password"] as! String, bootstrap: b)
        XCTAssertEqual(credentials.auth, v["auth"] as? String)
        XCTAssertEqual(credentials.wrap.base64EncodedString(), v["wrap"] as? String)
        let key = try SyncCrypto.unb64(v["key"] as! String)
        let aad = try JSONSerialization.data(withJSONObject: v["aad"]!, options: [.withoutEscapingSlashes])
        let e = try JSONDecoder().decode(Envelope.self, from: JSONSerialization.data(withJSONObject: v["envelope"]!))
        let plaintext = try SyncCrypto.open(key, envelope: e, aad: aad)
        let decoded = try JSONSerialization.jsonObject(with: plaintext) as! [String: String]
        XCTAssertEqual(decoded, v["plaintext"] as? [String: String])
        let seal = try SyncCrypto.seal(key, keyID: e.key_id, plaintext: plaintext, aad: aad, nonce: SyncCrypto.unb64(v["nonce"] as! String))
        XCTAssertEqual(seal, e)
        var wrong = aad; wrong.append(0)
        XCTAssertThrowsError(try SyncCrypto.open(key, envelope: e, aad: wrong))
        XCTAssertEqual(try SyncCrypto.recoveryCode(key), v["recovery_code"] as? String)
        XCTAssertEqual(try SyncCrypto.recoveryKey(v["recovery_code"] as! String).base64EncodedString(), v["recovery_key"] as? String)
        XCTAssertThrowsError(try SyncCrypto.recoveryKey((v["recovery_code"] as! String).dropLast() + "0"))
    }
    func testTaskRoundTripLegacyAndMalformedInput() throws {
        var p = Payload.new(.task, title: "line one\nline two 🚨")
        p.description = "A\\B\r\nπ é"; p.flags = ["urgent","long_term"]; p.completed_at = "2026-10-19T12:34:56.123Z"
        let encoded = FileFormats.serializeTasks([p]), parsed = try FileFormats.parseTasks(encoded)
        XCTAssertEqual(parsed.count, 1); XCTAssertEqual(parsed[0].title, p.title); XCTAssertEqual(parsed[0].description, p.description); XCTAssertEqual(parsed[0].flags, p.flags)
        let legacy = encoded.replacingOccurrences(of: "Jnotes To-do: 2", with: "Jnotes To-do: 1").components(separatedBy: "\n").filter { !$0.hasPrefix("flags:") }.joined(separator: "\n")
        XCTAssertEqual(try FileFormats.parseTasks(legacy)[0].flags, [])
        XCTAssertThrowsError(try FileFormats.parseTasks(encoded + "\nid: incomplete\n"))
        XCTAssertThrowsError(try FileFormats.parseTasks(encoded.replacingOccurrences(of: "urgent", with: "unknown")))
        XCTAssertThrowsError(try FileFormats.unescape("bad\\z"))
        XCTAssertThrowsError(try FileFormats.parseTasks(FileFormats.serializeTasks([p,p])))
    }
    func testNotesPreserveWhitespaceAndEditableDoubleX() {
        var p = Payload.new(.journal, title: "2026-10-19")
        p.journal_date = "2026-10-19"; p.body = "\n  prose\n\n"; p.created_at = "2026-10-19T10:20:30Z"; p.modified_at = p.created_at
        let q = FileFormats.parseNote(FileFormats.serializeNote(p), filename: "note.txt", id: p.id)
        XCTAssertEqual(q.body, p.body); XCTAssertEqual(q.kind, .journal); XCTAssertEqual(q.created_at, p.created_at)
        var task = Payload.new(.task, title: "Measure beam power"); task.completed_at = Dates.iso(Dates.formatter("yyyy-MM-dd HH:mm:ss").date(from: "2026-10-19 12:00:00")!)
        let dx = Journal.markDoubleX(p, tasks: [task])
        XCTAssertTrue(dx.body.contains("✓ Measure beam power")); XCTAssertTrue(dx.body.hasSuffix(p.body)); XCTAssertEqual(dx.template_version, 1)
        var edited = dx; edited.body = "I changed the headings myself."
        XCTAssertEqual(Journal.markDoubleX(edited, tasks: []).body, edited.body)
        XCTAssertEqual(Journal.nextTitle("2026-10-19", existing: ["2026-10-19", "2026-10-19 II Double X Day"]), "2026-10-19 III")
    }
    func testPlainTextAndInvalidHeaderRemainReadable() {
        let raw = "Title: appears in prose\nThis is not metadata\n\nOriginal text\n"
        XCTAssertEqual(FileFormats.parseNote(raw, filename: "x.txt").body, raw)
        XCTAssertEqual(FileFormats.parseNote("Just text\n\n", filename: "x.txt").body, "Just text\n\n")
    }
    func testFieldwiseTaskMergeAndConflict() {
        let base = Payload.new(.task, title: "Original")
        var local = base, remote = base
        local.completed_at = "2026-10-19T18:00:00Z"; remote.description = "Added on web"
        let merged = Payload.mergeTask(base: base, local: local, remote: remote)
        XCTAssertEqual(merged?.description, "Added on web"); XCTAssertEqual(merged?.completed_at, local.completed_at)
        local.title = "Phone"; remote.title = "Web"
        XCTAssertNil(Payload.mergeTask(base: base, local: local, remote: remote))
        var note = base; note.kind = .note
        XCTAssertNil(Payload.mergeTask(base: note, local: note, remote: note))
    }
    func testTimestampNormalizationPreservesAndroidNanoseconds() throws {
        let offset = "2026-10-19T14:12:30.123456789-04:00"
        XCTAssertEqual(Dates.normalizedInstant(offset), "2026-10-19T18:12:30.123456789Z")
        XCTAssertEqual(Dates.epochMillis(offset), Dates.epochMillis("2026-10-19T18:12:30.123Z"))
        var p = Payload.new(.task, title: "Task")
        p.created_at = offset; p.completed_at = "2026-10-19T19:00:00.999999999Z"
        let q = try FileFormats.parseTasks(FileFormats.serializeTasks([p]))[0]
        XCTAssertEqual(q.created_at, "2026-10-19T18:12:30.123456789Z")
        XCTAssertEqual(q.completed_at, p.completed_at)
        XCTAssertTrue(try p.validated().sameContent(as: q))
        let note = Payload.new(.note, title: "Stable")
        let loaded = FileFormats.parseNote(FileFormats.serializeNote(note), filename: "note.txt", id: note.id)
        XCTAssertTrue(note.sameContent(as: loaded))
    }
    func testCanonicalPayloadIncludesExplicitNulls() throws {
        let p = Payload.new(.note)
        let data = try p.canonical(), json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertTrue(json["completed_at"] is NSNull); XCTAssertTrue(json["journal_date"] is NSNull)
        var bad = p; bad.order = Int64.min; XCTAssertThrowsError(try bad.validated())
    }
    @MainActor func testOfflineSaveReloadTrashRestoreAndTaskDeletion() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = NotesStore(root: root); await store.load(); XCTAssertNil(store.error)
        var p = Payload.new(.note, title: "A note"); p.body = "Readable without an account"
        try store.save(p)
        let task = Payload.new(.task, title: "Task"); try store.save(task)
        let reloaded = NotesStore(root: root); await reloaded.load()
        XCTAssertEqual(reloaded.records.count, 2); XCTAssertEqual(reloaded.records.first { $0.id == p.id }?.payload.body, p.body)
        try reloaded.save(p, deleted: true)
        XCTAssertEqual(reloaded.documents(.note, trash: true).count, 1)
        try reloaded.save(p, deleted: false)
        XCTAssertEqual(reloaded.documents(.note).count, 1)
        try reloaded.save(task, deleted: true); XCTAssertEqual(reloaded.tasks.count, 0)
        let again = NotesStore(root: root); await again.load()
        XCTAssertTrue(again.records.first { $0.id == task.id }!.deleted)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent(again.documents(.note)[0].filename!), encoding: .utf8), FileFormats.serializeNote(again.documents(.note)[0].payload))
    }
    @MainActor func testCorruptTaskFileCannotBeOverwritten() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let url = root.appendingPathComponent(FileFormats.todoName), raw = "Jnotes To-do: 2\n\nid: damaged\n"
        try raw.write(to: url, atomically: true, encoding: .utf8)
        let store = NotesStore(root: root); await store.load()
        XCTAssertNotNil(store.error); XCTAssertThrowsError(try store.save(.new(.task, title: "New task")))
        XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), raw)
    }
    @MainActor func testExportImportPreservesPortableFiles() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let other = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root); try? FileManager.default.removeItem(at: other) }
        let store = NotesStore(root: root); await store.load()
        var note = Payload.new(.note, title: "Export me"); note.body = "λ\nWriting"
        try store.save(note); try store.save(.new(.task, title: "Export task"))
        let zip = try store.exportZIP(); defer { try? FileManager.default.removeItem(at: zip) }
        let imported = NotesStore(root: other); await imported.load(); try imported.importFiles([zip])
        XCTAssertEqual(imported.tasks.count, 1); XCTAssertEqual(imported.documents(.note).first?.payload.body, note.body)
    }
    @MainActor func testInterruptedRenameRecoversBeforeScanning() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = NotesStore(root: root); await store.load()
        var p = Payload.new(.note, title: "Before"); p.body = "Preserve my writing"; try store.save(p)
        let old = store.documents(.note)[0].filename!
        p.title = "After"; let name = FileFormats.filename(p)
        let records = [LocalRecord(payload: p, filename: name)]
        let tx = StoreTransaction(records: records, writes: [name: FileFormats.serializeNote(p)], removes: [old])
        // Simulate termination after the durable journal but before the destination write.
        try JSONEncoder().encode(tx).write(to: root.appendingPathComponent(".jnotes-ios-journal.json"))
        let recovered = NotesStore(root: root); await recovered.load()
        XCTAssertNil(recovered.error); XCTAssertEqual(recovered.documents(.note).count, 1)
        XCTAssertEqual(recovered.documents(.note)[0].id, p.id); XCTAssertEqual(recovered.documents(.note)[0].payload.title, "After")
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(old).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".jnotes-ios-journal.json").path))
    }
    func testUnsafeLocalJournalCannotEscapeNotesFolder() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let tx = StoreTransaction(records: [], writes: ["../escape.txt": "bad"], removes: [])
        try JSONEncoder().encode(tx).write(to: root.appendingPathComponent(".jnotes-ios-journal.json"))
        XCTAssertThrowsError(try NotesStore.recoverTransaction(root))
    }
    func testServiceConfigRejectsAdminKeysAndNonHTTPS() throws {
        let service = try ServiceDirectory.bundled()
        XCTAssertNoThrow(try service.validated())
        let bad = ServiceDirectory(schema_version: 1, revision: 1, protocol_version: 1, deployment_id: service.deployment_id, web_url: service.web_url, supabase_url: "http://example.org", publishable_key: "sb_secret_never_ship")
        XCTAssertThrowsError(try bad.validated())
    }
    func testEpubContainerSpineMetadataAndCache() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("source"), cache = root.appendingPathComponent("cache")
        let files = [
            "mimetype": "application/epub+zip",
            "META-INF/container.xml": "<container><rootfiles><rootfile full-path=\"EPUB/content.opf\"/></rootfiles></container>",
            "EPUB/content.opf": "<package xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><metadata><dc:title>Test λ Book</dc:title><dc:creator>Author</dc:creator></metadata><manifest><item id=\"one\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"two\" href=\"ch2.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"one\"/><itemref idref=\"two\"/></spine></package>",
            "EPUB/ch1.xhtml": "<html><head><title>One</title></head><body>First chapter</body></html>",
            "EPUB/ch2.xhtml": "<html><head><title>Two</title></head><body>Second chapter</body></html>"
        ]
        for (path, text) in files {
            let url = source.appendingPathComponent(path)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try text.write(to: url, atomically: true, encoding: .utf8)
        }
        let file = root.appendingPathComponent("sample.epub"), archive = try Archive(url: file, accessMode: .create)
        for path in files.keys.sorted() { try archive.addEntry(with: path, relativeTo: source) }
        let book = try EPUB.open(file, cache: cache)
        XCTAssertEqual(book.title, "Test λ Book"); XCTAssertEqual(book.author, "Author"); XCTAssertEqual(book.chapters.count, 2)
        XCTAssertEqual(book.chapters[0].lastPathComponent, "ch1.xhtml")
        XCTAssertEqual(try EPUB.open(file, cache: cache).chapters.count, 2)
    }
    func testEpubRejectsTraversal() {
        let root = URL(fileURLWithPath: "/tmp/book")
        XCTAssertThrowsError(try EPUB.inside("../../private.txt", relativeTo: root.appendingPathComponent("OPS/content.opf"), root: root))
        XCTAssertThrowsError(try EPUB.inside("https://example.org", relativeTo: root, root: root))
    }
}
