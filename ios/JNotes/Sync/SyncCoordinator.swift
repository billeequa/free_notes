import Foundation
import SwiftUI

struct CloudBase: Codable { var payload: Payload; var deleted: Bool; var revision: Int64 }
struct PendingOperation: Codable {
    let op: String, id: String
    let payload: Payload, envelope: Envelope
    let deleted: Bool, base: Int64
}
struct SyncConflict: Codable, Identifiable {
    let id: String
    var local: Payload, localDeleted: Bool
    var remote: RemoteRecord, remotePayload: Payload
}
struct SyncDisk: Codable {
    var version = 1
    var owner = "", deployment = "", epoch = "", keyID = "", root = ""
    var cursor: Int64 = 0, floor: Int64 = 0
    var device = UUID().uuidString.lowercased()
    var bases: [String: CloudBase] = [:]
    var pending: [String: PendingOperation] = [:]
    var conflicts: [String: SyncConflict] = [:]
    var service: ServiceDirectory?
}
struct SyncSecret: Codable { let key: Data; var session: AuthSession; let origin: String, owner: String, keyID: String }
struct RPCVoid: Decodable { init(from decoder: Decoder) throws {} }
struct HistoryRevision: Decodable, Identifiable {
    let id: String; let revision: Int64, seq: Int64; let envelope: Envelope; let deleted: Bool
    let changed_at: String?
}

@MainActor final class SyncCoordinator: ObservableObject {
    @Published var status = "Sync is off"
    @Published var busy = false
    @Published var connected = false
    @Published var conflicts: [SyncConflict] = []
    @Published var enabled: Bool { didSet { UserDefaults.standard.set(enabled, forKey: "syncEnabled") } }
    @Published var website = "https://j4notes.pages.dev"
    private var disk = SyncDisk()
    private var stateError: String?
    private weak var store: NotesStore?
    private var scheduled: Task<Void, Never>?
    private let diskURL: URL
    init(store: NotesStore) {
        self.store = store; enabled = UserDefaults.standard.bool(forKey: "syncEnabled")
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        diskURL = support.appendingPathComponent("jnotes-sync-v1.json")
        do {
            try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: diskURL.path) {
                disk = try JSONDecoder().decode(SyncDisk.self, from: Data(contentsOf: diskURL))
                guard disk.version == 1 else { throw JNotesError("Unsupported sync metadata") }
            }
            connected = !disk.owner.isEmpty && UserDefaults.standard.bool(forKey: "syncHasSession")
            if let service = disk.service { website = service.web_url }
            conflicts = Array(disk.conflicts.values).sorted { $0.id < $1.id }
            status = enabled && connected ? "Saved on phone — pending sync" : "Sync is off"
        } catch { stateError = "Sync metadata needs repair. Local notes remain available."; status = stateError! }
        store.didChange = { [weak self] in self?.schedule() }
    }
    private func persist() throws {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(disk).write(to: diskURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        conflicts = Array(disk.conflicts.values).sorted { $0.id < $1.id }
    }
    func schedule() {
        guard enabled, connected else { return }
        scheduled?.cancel()
        scheduled = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 1_000_000_000); try Task.checkCancellation(); await self?.sync() } catch { }
        }
    }
    func connect(email: String, password: String) async throws {
        guard !busy else { throw JNotesError("Sync is already running") }
        if let stateError { throw JNotesError(stateError) }
        guard let store, store.loaded, store.error == nil else { throw JNotesError("Choose a working notes folder first") }
        busy = true; defer { busy = false }
        let service = try await CloudAPI.discover(cached: disk.service)
        let api = CloudAPI(service); let bootstrap = try await api.bootstrap()
        // Argon2's 64 MiB work runs outside the main actor.
        var credentials = try await Task.detached(priority: .userInitiated) { try SyncCrypto.derive(password, bootstrap: bootstrap) }.value
        defer { credentials.wrap.resetBytes(in: credentials.wrap.indices) }
        let session = try await api.signIn(email, auth: credentials.auth)
        let state: VaultState = try await api.rpc("get_vault")
        guard let vault = state.vault else { throw JNotesError("Set up your password and notebook on the website first, then sign in here.") }
        let owner = session.user.id.lowercased()
        guard disk.owner.isEmpty || (disk.owner == owner && disk.deployment == service.deployment_id && disk.keyID == vault.key_id && disk.root == store.rootURL.standardizedFileURL.path) else { throw JNotesError("Account, key, or notes folder differs. Disconnect before linking another notebook.") }
        let aad = try SyncCrypto.aad(service.deployment_id, owner, vault.key_id, "password", 0, false, vault: true)
        let key: Data
        do { key = try SyncCrypto.open(credentials.wrap, envelope: vault.password_envelope, aad: aad) }
        catch {
            guard let staged = vault.staged_envelope, let transition = vault.transition_id else { throw JNotesError("Notebook unlock failed. Recover it on the website; local notes are safe.") }
            key = try SyncCrypto.open(credentials.wrap, envelope: staged, aad: aad)
            let _: RPCVoid = try await api.rpc("finish_password", ["p_transition_id": transition])
        }
        guard key.count == 32, disk.epoch.isEmpty || (disk.epoch == state.epoch && state.high_water >= disk.floor) else { throw JNotesError("Cloud state changed. Sync paused; local files are safe.") }
        let secret = SyncSecret(key: key, session: session, origin: service.supabase_url, owner: owner, keyID: vault.key_id)
        try Keychain.save(JSONEncoder().encode(secret))
        disk.owner = owner; disk.deployment = service.deployment_id; disk.epoch = state.epoch; disk.keyID = vault.key_id
        disk.root = store.rootURL.standardizedFileURL.path; disk.service = service; try persist()
        connected = true; UserDefaults.standard.set(true, forKey: "syncHasSession"); website = service.web_url
        status = "Connected — review first sync"
    }
    func start() async { enabled = true; await sync() }
    func disconnect() throws {
        guard !busy else { throw JNotesError("Wait for sync to finish") }
        enabled = false; scheduled?.cancel(); Keychain.clear(); UserDefaults.standard.set(false, forKey: "syncHasSession")
        // Preserve cloud identities and conflicts. Reconnect can safely resume pending operations.
        connected = false; status = "Disconnected. All local files are available."; try persist()
    }
    func sync() async {
        guard !busy, enabled, connected, let store, store.loaded, store.error == nil else { return }
        if let stateError { status = stateError; return }
        busy = true; status = "Syncing…"; defer { busy = false }
        do {
            guard disk.root == store.rootURL.standardizedFileURL.path else { throw JNotesError("Notes folder differs. Reconnect to sync this folder.") }
            guard let data = try Keychain.read() else { throw JNotesError("Sign in again. Local notes are safe.") }
            var secret = try JSONDecoder().decode(SyncSecret.self, from: data)
            let service = try await CloudAPI.discover(cached: disk.service)
            guard service.supabase_url == secret.origin else { throw JNotesError("The service moved. Reconnect with your email and password. Files and pending edits are safe.") }
            let api = CloudAPI(service, session: secret.session)
            let bootstrap = try await api.bootstrap()
            guard bootstrap.deployment_id == disk.deployment else { throw JNotesError("Cloud identity changed. Sync paused.") }
            secret.session = try await api.refresh()
            guard secret.session.user.id.lowercased() == disk.owner, secret.owner == disk.owner, secret.keyID == disk.keyID else { throw JNotesError("Cloud account differs") }
            try Keychain.save(JSONEncoder().encode(secret))
            let state: VaultState = try await api.rpc("get_vault")
            guard state.epoch == disk.epoch, state.high_water >= disk.floor, state.vault?.key_id == disk.keyID else { throw JNotesError("Cloud state changed. Repair required; local files are safe.") }
            disk.service = service; website = service.web_url
            // Resolve immutable operations first: network timeouts must not cause a new operation/nonce.
            for pending in Array(disk.pending.values) {
                guard enabled else { return }
                try await send(pending, api: api, key: secret.key)
            }
            var pages = 0
            while enabled {
                try Task.checkCancellation()
                let page: ChangePage = try await api.rpc("get_changes", ["p_epoch": disk.epoch, "p_after": disk.cursor, "p_limit": 20])
                guard page.epoch == disk.epoch, page.high_water >= disk.floor, page.next_cursor >= disk.cursor, page.next_cursor <= page.high_water else { throw JNotesError("Invalid cloud cursor. Sync paused.") }
                for remote in page.changes {
                    guard enabled else { return }
                    guard remote.seq == disk.cursor + 1 else { throw JNotesError("Missing cloud history. Repair required.") }
                    try accept(remote, key: secret.key, store: store)
                    disk.cursor = remote.seq; disk.floor = max(disk.floor, remote.seq); try persist()
                }
                if disk.cursor >= page.high_water { break }
                guard !page.changes.isEmpty else { throw JNotesError("Incomplete cloud history") }
                pages += 1
                if pages >= 100 { status = "Saved on phone — continuing sync"; schedule(); return }
            }
            for local in store.records {
                guard enabled else { return }
                try Task.checkCancellation()
                if disk.conflicts[local.id] != nil || store.editingID == local.id { continue }
                let base = disk.bases[local.id]
                if base?.deleted == local.deleted, base?.payload.sameContent(as: local.payload) == true { continue }
                if base == nil && local.deleted { continue }
                let bytes = try local.payload.canonical()
                guard bytes.count <= 2 * 1024 * 1024 else { throw JNotesError("An item exceeds the sync size limit. Its local file is safe.") }
                let revision = base?.revision ?? 0
                let aad = try SyncCrypto.aad(disk.deployment, disk.owner, disk.keyID, local.id, revision + 1, local.deleted)
                let envelope = try SyncCrypto.seal(secret.key, keyID: disk.keyID, plaintext: bytes, aad: aad)
                let operation = PendingOperation(op: UUID().uuidString.lowercased(), id: local.id, payload: local.payload, envelope: envelope, deleted: local.deleted, base: revision)
                disk.pending[local.id] = operation; try persist()
                try await send(operation, api: api, key: secret.key)
            }
            let _: RPCVoid = try await api.rpc("ack_cursor", ["p_device_id": disk.device, "p_epoch": disk.epoch, "p_cursor": disk.cursor])
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "lastSync")
            try persist()
            status = disk.conflicts.isEmpty ? "Synced" : "Conflict — action needed"
        } catch is CancellationError { status = "Saved on phone — sync paused" }
        catch { status = error.localizedDescription }
    }
    private func decrypt(_ r: RemoteRecord, key: Data) throws -> Payload {
        guard r.revision > 0, r.revision < 9_007_199_254_740_991, UUID(uuidString: r.id) != nil else { throw JNotesError("Invalid cloud record") }
        let aad = try SyncCrypto.aad(disk.deployment, disk.owner, disk.keyID, r.id, r.revision, r.deleted)
        let bytes = try SyncCrypto.open(key, envelope: r.envelope, aad: aad)
        guard bytes.count <= 2 * 1024 * 1024 else { throw JNotesError("Cloud record is too large") }
        return try JSONDecoder().decode(Payload.self, from: bytes).validated(expectedID: r.id)
    }
    private func accept(_ remote: RemoteRecord, key: Data, store: NotesStore) throws {
        let p = try decrypt(remote, key: key), base = disk.bases[remote.id]
        if let base, base.revision >= remote.revision { return }
        if store.editingID == remote.id { throw JNotesError("Finish editing before applying cloud changes. Your draft is safe.") }
        let local = store.records.first { $0.id == remote.id }
        if let local {
            let dirty = base == nil || base!.deleted != local.deleted || !local.payload.sameContent(as: base!.payload)
            if local.deleted == remote.deleted && local.payload.sameContent(as: p) {
                // Same content from another device: simply advance its base.
            } else if dirty {
                if !local.deleted, !remote.deleted, let base, let merged = Payload.mergeTask(base: base.payload, local: local.payload, remote: p) {
                    try store.save(merged, touch: false)
                } else {
                    disk.conflicts[remote.id] = SyncConflict(id: remote.id, local: local.payload, localDeleted: local.deleted, remote: remote, remotePayload: p)
                    try persist(); return
                }
            } else { try store.save(p, deleted: remote.deleted, touch: false) }
        } else { try store.save(p, deleted: remote.deleted, touch: false) }
        disk.bases[remote.id] = CloudBase(payload: p, deleted: remote.deleted, revision: remote.revision)
    }
    private func send(_ pending: PendingOperation, api: CloudAPI, key: Data) async throws {
        let envelope = try JSONSerialization.jsonObject(with: JSONEncoder().encode(pending.envelope))
        let reply: CommitReply = try await api.rpc("commit_operation", ["p_epoch": disk.epoch, "p_floor": disk.cursor, "p_op_id": pending.op, "p_device_id": disk.device, "p_id": pending.id, "p_expected_revision": pending.base, "p_envelope": envelope, "p_deleted": pending.deleted])
        if reply.status == "OK", let revision = reply.revision {
            disk.bases[pending.id] = CloudBase(payload: pending.payload, deleted: pending.deleted, revision: revision)
            if let seq = reply.seq { disk.floor = max(disk.floor, seq) }
            disk.pending.removeValue(forKey: pending.id); try persist(); return
        }
        guard let remote = reply.current, let store else { throw JNotesError("Cloud record changed unexpectedly") }
        disk.pending.removeValue(forKey: pending.id)
        // Store conflict evidence before advancing any cursor.
        let p = try decrypt(remote, key: key)
        let local = store.records.first { $0.id == pending.id }
        disk.conflicts[pending.id] = SyncConflict(id: pending.id, local: local?.payload ?? pending.payload, localDeleted: local?.deleted ?? pending.deleted, remote: remote, remotePayload: p)
        try persist()
    }
    func resolve(_ id: String, choice: String) throws {
        guard !busy, let c = disk.conflicts[id], let store, store.editingID != id else { throw JNotesError("Finish editing and wait for sync before resolving") }
        // Re-read current local content, which may have changed since the conflict was recorded.
        let current = store.records.first { $0.id == id }
        if choice == "remote" {
            var copy = current?.payload ?? c.local; copy.id = UUID().uuidString.lowercased(); copy.title += " (conflict backup)"
            try store.save(copy, deleted: true, touch: false)
            try store.save(c.remotePayload, deleted: c.remote.deleted, touch: false)
        } else if choice == "both" {
            var copy = c.remotePayload; copy.id = UUID().uuidString.lowercased(); copy.title += " (conflict copy)"
            try store.save(copy, deleted: c.remote.deleted, touch: false)
        }
        disk.bases[id] = CloudBase(payload: c.remotePayload, deleted: c.remote.deleted, revision: c.remote.revision)
        disk.conflicts.removeValue(forKey: id); try persist(); schedule()
    }
    func history(_ id: String) async throws -> [(HistoryRevision, Payload)] {
        guard let data = try Keychain.read(), let service = disk.service else { throw JNotesError("Sign in first") }
        var secret = try JSONDecoder().decode(SyncSecret.self, from: data)
        let api = CloudAPI(service, session: secret.session); secret.session = try await api.refresh(); try Keychain.save(JSONEncoder().encode(secret))
        let revisions: [HistoryRevision] = try await api.rpc("get_history", ["p_id": id])
        return try revisions.map { h in
            let remote = RemoteRecord(id: h.id, revision: h.revision, seq: h.seq, envelope: h.envelope, deleted: h.deleted, op_id: nil)
            return (h, try decrypt(remote, key: secret.key))
        }
    }
}
