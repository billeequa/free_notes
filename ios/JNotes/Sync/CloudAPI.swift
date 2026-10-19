import Foundation

struct ServiceDirectory: Codable, Equatable {
    let schema_version: Int, revision: Int, protocol_version: Int
    let deployment_id: String, web_url: String, supabase_url: String, publishable_key: String
    static let directoryURL = URL(string: "https://raw.githubusercontent.com/billeequa/free_notes/main/config/jnotes-service.json")!
    static func bundled() throws -> ServiceDirectory {
        try JSONDecoder().decode(Self.self, from: Data(contentsOf: Bundle.main.url(forResource: "jnotes-service", withExtension: "json")!)).validated()
    }
    func validated() throws -> Self {
        guard schema_version == 1, protocol_version == 1, revision > 0, UUID(uuidString: deployment_id) != nil,
              publishable_key.hasPrefix("sb_publishable_"), !publishable_key.contains(where: { $0.isWhitespace }) else { throw JNotesError("Invalid service configuration") }
        for address in [web_url, supabase_url] {
            guard let u = URLComponents(string: address), u.scheme == "https", u.host != nil, u.user == nil, u.password == nil, u.query == nil, u.fragment == nil, u.path.isEmpty || u.path == "/" else { throw JNotesError("Invalid service address") }
        }
        return self
    }
}
struct AuthUser: Codable { let id: String; let email: String? }
struct AuthSession: Codable { let access_token: String, refresh_token: String; let user: AuthUser }
struct Vault: Codable { let key_id: String; let password_envelope: Envelope; let staged_envelope: Envelope?; let transition_id: String? }
struct VaultState: Codable { let vault: Vault?; let epoch: String; let high_water: Int64 }
struct RemoteRecord: Codable { let id: String; let revision: Int64; let seq: Int64; let envelope: Envelope; let deleted: Bool; let op_id: String? }
struct ChangePage: Codable { let changes: [RemoteRecord]; let epoch: String; let high_water: Int64; let next_cursor: Int64 }
struct CommitReply: Codable { let status: String; let revision: Int64?; let seq: Int64?; let current: RemoteRecord? }

final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
final class CloudAPI {
    let service: ServiceDirectory
    var session: AuthSession?
    private let transport: URLSession
    init(_ service: ServiceDirectory, session: AuthSession? = nil, configuration: URLSessionConfiguration? = nil) {
        self.service = service; self.session = session
        let config = configuration ?? URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = 40; config.httpShouldSetCookies = false; config.urlCache = nil
        transport = URLSession(configuration: config, delegate: NoRedirects(), delegateQueue: nil)
    }
    func request<T: Decodable>(_ path: String, body: [String: Any]? = nil, authorized: Bool = true, as type: T.Type = T.self) async throws -> T {
        let url = URL(string: service.supabase_url.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path)!
        var r = URLRequest(url: url); r.httpMethod = body == nil ? "GET" : "POST"
        r.setValue(service.publishable_key, forHTTPHeaderField: "apikey"); r.setValue("application/json", forHTTPHeaderField: "Content-Type"); r.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if authorized, let session { r.setValue("Bearer \(session.access_token)", forHTTPHeaderField: "Authorization") }
        if let body { r.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.withoutEscapingSlashes]) }
        let (data, response) = try await transport.data(for: r)
        guard data.count <= 4 * 1024 * 1024, let http = response as? HTTPURLResponse else { throw JNotesError("Cloud response is too large") }
        guard (200..<300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8) ?? ""
            if message.contains("CLOUD_STATE_CHANGED") { throw JNotesError("Cloud state changed. Sync paused; local files are safe.") }
            if path.contains("grant_type=password") { throw JNotesError("Email or password is incorrect. First time? Set up your password on the website.") }
            if http.statusCode == 401 || http.statusCode == 403 { throw JNotesError("Sign in again in Web Sync. Local notes remain available.") }
            throw JNotesError("Cloud request failed (HTTP \(http.statusCode)). Local files are safe.")
        }
        return try JSONDecoder().decode(T.self, from: data.isEmpty ? Data("null".utf8) : data)
    }
    func rpc<T: Decodable>(_ name: String, _ args: [String: Any] = [:], as type: T.Type = T.self) async throws -> T { try await request("/rest/v1/rpc/" + name, body: args, as: type) }
    func bootstrap() async throws -> Bootstrap {
        let list: [Bootstrap] = try await request("/rest/v1/public_bootstrap?select=*", authorized: false)
        guard list.count == 1, list[0].deployment_id == service.deployment_id else { throw JNotesError("Cloud deployment differs. Sync paused.") }; return list[0]
    }
    func signIn(_ email: String, auth: String) async throws -> AuthSession {
        let s: AuthSession = try await request("/auth/v1/token?grant_type=password", body: ["email": email.trimmingCharacters(in: .whitespacesAndNewlines), "password": auth], authorized: false)
        session = s; return s
    }
    func refresh() async throws -> AuthSession {
        guard let session else { throw JNotesError("Sign in first") }
        let s: AuthSession = try await request("/auth/v1/token?grant_type=refresh_token", body: ["refresh_token": session.refresh_token], authorized: false)
        self.session = s; return s
    }
    static func discover(cached: ServiceDirectory?) async throws -> ServiceDirectory {
        let bundled = try ServiceDirectory.bundled(), last = cached ?? bundled
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 10
        let transport = URLSession(configuration: config, delegate: NoRedirects(), delegateQueue: nil)
        let fresh: Data
        do {
            let (data, response) = try await transport.data(from: ServiceDirectory.directoryURL)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return last }; fresh = data
        } catch { return last }
        guard fresh.count <= 8192 else { throw JNotesError("Invalid service directory") }
        let value = try JSONDecoder().decode(ServiceDirectory.self, from: fresh).validated()
        guard value.deployment_id == bundled.deployment_id, value.deployment_id == last.deployment_id,
              value.revision != last.revision || value == last else { throw JNotesError("Service configuration changed unexpectedly. Sync paused.") }
        return value.revision < last.revision ? last : value
    }
}
