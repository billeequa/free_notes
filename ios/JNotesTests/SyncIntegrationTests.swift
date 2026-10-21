import XCTest
@testable import JNotes

private final class MemorySecret { var data: Data? }
private final class MockCloudProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> Data)?
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "unit-test.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let data = try Self.handler!(request)
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() { }
}
private final class CloudFixture {
    let owner = "22222222-2222-4222-8222-222222222222", keyID = "33333333-3333-4333-8333-333333333333", epoch = "55555555-5555-4555-8555-555555555555"
    let service: ServiceDirectory, bootstrap: Bootstrap, key: Data, vault: Vault, session: AuthSession
    var changes: [RemoteRecord] = [], operations: [String: CommitReply] = [:], commitRequests: [[String: Any]] = []
    var loseNextCommitReply = false
    private let lock = NSLock()
    init() throws {
        let url = Bundle(for: SyncIntegrationTests.self).url(forResource: "vectors", withExtension: "json")!
        let v = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        bootstrap = try JSONDecoder().decode(Bootstrap.self, from: JSONSerialization.data(withJSONObject: v["bootstrap"]!))
        service = ServiceDirectory(schema_version: 1, revision: 1, protocol_version: 1, deployment_id: bootstrap.deployment_id, web_url: "https://unit-test.invalid", supabase_url: "https://unit-test.invalid", publishable_key: "sb_publishable_synthetic_test")
        key = try SyncCrypto.unb64(v["key"] as! String)
        let wrap = try SyncCrypto.unb64(v["wrap"] as! String)
        let envelope = try SyncCrypto.seal(wrap, keyID: keyID, plaintext: key, aad: SyncCrypto.aad(bootstrap.deployment_id, owner, keyID, "password", 0, false, vault: true))
        vault = Vault(key_id: keyID, password_envelope: envelope, staged_envelope: nil, transition_id: nil)
        session = AuthSession(access_token: "synthetic-access", refresh_token: "synthetic-refresh", user: AuthUser(id: owner, email: "test@example.invalid"))
    }
    func append(_ p: Payload, deleted: Bool = false) throws {
        let previous = changes.last { $0.id == p.id }?.revision ?? 0
        let e = try SyncCrypto.seal(key, keyID: keyID, plaintext: p.canonical(), aad: SyncCrypto.aad(bootstrap.deployment_id, owner, keyID, p.id, previous + 1, deleted))
        changes.append(RemoteRecord(id: p.id, revision: previous + 1, seq: Int64(changes.count + 1), envelope: e, deleted: deleted, op_id: nil))
    }
    private func body(_ request: URLRequest) throws -> [String: Any] {
        if let data = request.httpBody { return try JSONSerialization.jsonObject(with: data) as! [String: Any] }
        if let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }; var data = Data(), buffer = [UInt8](repeating: 0, count: 8192)
            while stream.hasBytesAvailable { let n = stream.read(&buffer, maxLength: buffer.count); if n <= 0 { break }; data.append(buffer, count: n) }
            return try JSONSerialization.jsonObject(with: data) as! [String: Any]
        }
        return [:]
    }
    func request(_ r: URLRequest) throws -> Data {
        lock.lock(); defer { lock.unlock() }
        let path = r.url!.path
        if path.hasSuffix("public_bootstrap") {
            XCTAssertNil(r.value(forHTTPHeaderField: "Authorization")); return try JSONEncoder().encode([bootstrap])
        }
        if path.hasSuffix("token") { return try JSONEncoder().encode(session) }
        XCTAssertEqual(r.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-access")
        if path.hasSuffix("get_vault") { return try JSONEncoder().encode(VaultState(vault: vault, epoch: epoch, high_water: Int64(changes.count))) }
        let b = try body(r)
        if path.hasSuffix("get_changes") {
            let after = (b["p_after"] as! NSNumber).int64Value
            let page = changes.filter { $0.seq > after }
            return try JSONEncoder().encode(ChangePage(changes: page, epoch: epoch, high_water: Int64(changes.count), next_cursor: page.last?.seq ?? after))
        }
        if path.hasSuffix("ack_cursor") { return Data() } // PostgREST void responses may be empty.
        if path.hasSuffix("commit_operation") {
            commitRequests.append(b)
            let op = b["p_op_id"] as! String
            if let previous = operations[op] { return try JSONEncoder().encode(previous) }
            let id = b["p_id"] as! String, expected = (b["p_expected_revision"] as! NSNumber).int64Value
            let current = changes.last { $0.id == id }
            if (current?.revision ?? 0) != expected { return try JSONEncoder().encode(CommitReply(status: "CONFLICT", revision: nil, seq: nil, current: current)) }
            let envelope = try JSONDecoder().decode(Envelope.self, from: JSONSerialization.data(withJSONObject: b["p_envelope"]!))
            let deleted = b["p_deleted"] as! Bool
            let plain = try SyncCrypto.open(key, envelope: envelope, aad: SyncCrypto.aad(bootstrap.deployment_id, owner, keyID, id, expected + 1, deleted))
            _ = try JSONDecoder().decode(Payload.self, from: plain).validated(expectedID: id)
            let remote = RemoteRecord(id: id, revision: expected + 1, seq: Int64(changes.count + 1), envelope: envelope, deleted: deleted, op_id: op)
            changes.append(remote)
            let reply = CommitReply(status: "OK", revision: remote.revision, seq: remote.seq, current: nil); operations[op] = reply
            if loseNextCommitReply { loseNextCommitReply = false; throw URLError(.timedOut) }
            return try JSONEncoder().encode(reply)
        }
        throw JNotesError("Unexpected test endpoint: " + path)
    }
}
final class SyncIntegrationTests: XCTestCase {
    @MainActor private func coordinator(store: NotesStore, fixture: CloudFixture, stateURL: URL, defaults: UserDefaults, secret: MemorySecret) -> SyncCoordinator {
        let storage = SyncSecretStorage(read: { secret.data }, save: { secret.data = $0 }, clear: { secret.data = nil })
        return SyncCoordinator(store: store, stateURL: stateURL, defaults: defaults, secrets: storage, discover: { _ in fixture.service }, makeAPI: { service, session in
            let configuration = URLSessionConfiguration.ephemeral; configuration.protocolClasses = [MockCloudProtocol.self]
            return CloudAPI(service, session: session, configuration: configuration)
        })
    }
    @MainActor private func setup(_ fixture: CloudFixture, store: NotesStore, stateURL: URL, defaults: UserDefaults, secret: MemorySecret) throws {
        var disk = SyncDisk(); disk.owner = fixture.owner; disk.deployment = fixture.bootstrap.deployment_id; disk.epoch = fixture.epoch; disk.keyID = fixture.keyID; disk.root = store.rootURL.standardizedFileURL.path; disk.service = fixture.service
        try JSONEncoder().encode(disk).write(to: stateURL)
        secret.data = try JSONEncoder().encode(SyncSecret(key: fixture.key, session: fixture.session, origin: fixture.service.supabase_url, owner: fixture.owner, keyID: fixture.keyID))
        defaults.set(true, forKey: "syncEnabled"); defaults.set(true, forKey: "syncHasSession")
        MockCloudProtocol.handler = fixture.request
    }
    @MainActor func testPullLostUploadReplyAndRestartRetrySameOperation() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { try? FileManager.default.removeItem(at: root); defaults.removePersistentDomain(forName: suite); MockCloudProtocol.handler = nil }
        let fixture = try CloudFixture(), secret = MemorySecret(), stateURL = root.appendingPathComponent("sync.json")
        var p = Payload.new(.note, title: "From the web"); p.body = "Original web content"; try fixture.append(p)
        let store = NotesStore(root: root.appendingPathComponent("notes")); await store.load()
        try setup(fixture, store: store, stateURL: stateURL, defaults: defaults, secret: secret)
        let sync = coordinator(store: store, fixture: fixture, stateURL: stateURL, defaults: defaults, secret: secret)
        await sync.sync(); XCTAssertEqual(sync.status, "Synced"); XCTAssertEqual(store.documents(.note).first?.payload.body, p.body)
        p.body = "Edited offline on phone"; try store.save(p); fixture.loseNextCommitReply = true
        await sync.sync(); sync.enabled = false
        let interrupted = try JSONDecoder().decode(SyncDisk.self, from: Data(contentsOf: stateURL))
        XCTAssertEqual(interrupted.pending.count, 1); XCTAssertEqual(fixture.changes.count, 2)
        let reloadedStore = NotesStore(root: store.rootURL); await reloadedStore.load()
        let restarted = coordinator(store: reloadedStore, fixture: fixture, stateURL: stateURL, defaults: defaults, secret: secret)
        restarted.enabled = true; await restarted.sync(); restarted.enabled = false
        XCTAssertEqual(restarted.status, "Synced"); XCTAssertEqual(fixture.changes.count, 2)
        XCTAssertEqual(fixture.commitRequests.count, 2)
        XCTAssertEqual(fixture.commitRequests[0]["p_op_id"] as? String, fixture.commitRequests[1]["p_op_id"] as? String)
        XCTAssertEqual(fixture.commitRequests[0]["p_envelope"] as? NSDictionary, fixture.commitRequests[1]["p_envelope"] as? NSDictionary)
        let finished = try JSONDecoder().decode(SyncDisk.self, from: Data(contentsOf: stateURL))
        XCTAssertTrue(finished.pending.isEmpty); XCTAssertEqual(finished.cursor, 2)
        XCTAssertEqual(reloadedStore.documents(.note).first?.payload.body, "Edited offline on phone")
    }
    @MainActor func testConcurrentNoteConflictPreservesBothAndUsesWebExplicitly() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let suite = UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { try? FileManager.default.removeItem(at: root); defaults.removePersistentDomain(forName: suite); MockCloudProtocol.handler = nil }
        let fixture = try CloudFixture(), secret = MemorySecret(), stateURL = root.appendingPathComponent("sync.json")
        var original = Payload.new(.note, title: "Shared note"); original.body = "Original"; try fixture.append(original)
        let store = NotesStore(root: root.appendingPathComponent("notes")); await store.load()
        try setup(fixture, store: store, stateURL: stateURL, defaults: defaults, secret: secret)
        let sync = coordinator(store: store, fixture: fixture, stateURL: stateURL, defaults: defaults, secret: secret)
        await sync.sync()
        var local = original; local.body = "Phone draft"; try store.save(local)
        var remote = original; remote.body = "Web draft"; try fixture.append(remote)
        await sync.sync(); sync.enabled = false
        XCTAssertEqual(sync.conflicts.count, 1); XCTAssertEqual(store.documents(.note).first?.payload.body, "Phone draft")
        try sync.resolve(original.id, choice: "remote")
        XCTAssertTrue(sync.conflicts.isEmpty); XCTAssertEqual(store.documents(.note).first?.payload.body, "Web draft")
        XCTAssertEqual(store.documents(.note, trash: true).first?.payload.body, "Phone draft")
        let disk = try JSONDecoder().decode(SyncDisk.self, from: Data(contentsOf: stateURL))
        XCTAssertEqual(disk.cursor, 2); XCTAssertTrue(disk.conflicts.isEmpty)
    }
}
