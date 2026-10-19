import Foundation
import CryptoKit
import Security

struct Bootstrap: Codable {
    let protocol_version: Int, deployment_id: String, kdf_salt: String
    let memory_kib: Int, iterations: Int, parallelism: Int
}
struct Envelope: Codable, Equatable {
    var protocol_version = 1
    let key_id: String
    var algorithm = "A256GCM"
    let nonce: String, ciphertext_with_tag: String
}
enum SyncCrypto {
    static func random(_ count: Int = 32) throws -> Data {
        var bytes = Data(count: count)
        let status = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        guard status == errSecSuccess else { throw JNotesError("Secure randomness unavailable") }; return bytes
    }
    static func unb64(_ value: String) throws -> Data {
        guard let data = Data(base64Encoded: value), data.base64EncodedString() == value else { throw JNotesError("Invalid Base64") }; return data
    }
    static func expand(_ material: Data, label: String) -> Data {
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: material), salt: Data(repeating: 0, count: 32), info: Data("jnotes.v1/\(label)".utf8), outputByteCount: 32)
        return key.withUnsafeBytes { Data($0) }
    }
    static func derive(_ password: String, bootstrap b: Bootstrap) throws -> (auth: String, wrap: Data) {
        guard b.protocol_version == 1, b.memory_kib == 65536, b.iterations == 3, b.parallelism == 1 else { throw JNotesError("Unsupported cloud KDF configuration") }
        let salt = try unb64(b.kdf_salt); guard salt.count == 16 else { throw JNotesError("Invalid KDF salt") }
        var bytes = Data(password.utf8), intermediate = Data(count: 32)
        defer { bytes.resetBytes(in: bytes.indices); intermediate.resetBytes(in: intermediate.indices) }
        let status = intermediate.withUnsafeMutableBytes { out in bytes.withUnsafeBytes { pwd in salt.withUnsafeBytes { s in
            argon2id_hash_raw(3, 65536, 1, pwd.baseAddress, bytes.count, s.baseAddress, salt.count, out.baseAddress, 32)
        } } }
        guard status == ARGON2_OK else { throw JNotesError("Password key derivation failed") }
        return (expand(intermediate, label: "auth").base64EncodedString(), expand(intermediate, label: "wrap"))
    }
    static func aad(_ deployment: String, _ owner: String, _ key: String, _ id: String, _ revision: Int64, _ deleted: Bool, vault: Bool = false) throws -> Data {
        try JSONSerialization.data(withJSONObject: [vault ? "jnotes.vault" : "jnotes.record", 1, deployment, owner, key, id, revision, deleted], options: [.fragmentsAllowed, .withoutEscapingSlashes])
    }
    static func seal(_ key: Data, keyID: String, plaintext: Data, aad: Data, nonce: Data? = nil) throws -> Envelope {
        guard key.count == 32 else { throw JNotesError("Invalid key") }
        let nonce = try nonce ?? random(12)
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(data: nonce), authenticating: aad)
        return Envelope(key_id: keyID, nonce: nonce.base64EncodedString(), ciphertext_with_tag: (box.ciphertext + box.tag).base64EncodedString())
    }
    static func open(_ key: Data, envelope e: Envelope, aad: Data) throws -> Data {
        let a = try JSONSerialization.jsonObject(with: aad) as? [Any]
        guard key.count == 32, e.protocol_version == 1, e.algorithm == "A256GCM", a?[4] as? String == e.key_id else { throw JNotesError("Invalid encrypted envelope") }
        let nonce = try unb64(e.nonce), ciphertext = try unb64(e.ciphertext_with_tag)
        guard nonce.count == 12, ciphertext.count >= 16 else { throw JNotesError("Invalid encrypted envelope") }
        return try AES.GCM.open(AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: ciphertext.dropLast(16), tag: ciphertext.suffix(16)), using: SymmetricKey(data: key), authenticating: aad)
    }
    static func recoveryCode(_ secret: Data) throws -> String {
        guard secret.count == 32 else { throw JNotesError("Invalid recovery secret") }
        let text = (secret + Data(SHA256.hash(data: secret).prefix(4))).map { String(format: "%02X", $0) }.joined()
        return "JN1-" + stride(from: 0, to: text.count, by: 8).map { start in String(text.dropFirst(start).prefix(8)) }.joined(separator: "-")
    }
    static func recoveryKey(_ code: String) throws -> Data {
        let compact = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().replacingOccurrences(of: "JN1-", with: "").replacingOccurrences(of: "-", with: "")
        guard compact.count == 72 else { throw JNotesError("Invalid recovery code") }
        var secret = Data()
        for start in stride(from: 0, to: 64, by: 2) {
            guard let byte = UInt8(compact.dropFirst(start).prefix(2), radix: 16) else { throw JNotesError("Invalid recovery code") }; secret.append(byte)
        }
        guard try recoveryCode(secret).replacingOccurrences(of: "JN1-", with: "").replacingOccurrences(of: "-", with: "") == compact else { throw JNotesError("Recovery checksum failed") }
        return expand(secret, label: "recovery")
    }
}
enum Keychain {
    static let service = "com.jnotes.ios.sync.v1"
    static func read() throws -> Data? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "session", kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?; let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw JNotesError("Unlock your iPhone to access sync credentials") }; return result as? Data
    }
    static func save(_ data: Data) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "session"]
        let values: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if status == errSecItemNotFound {
            guard SecItemAdd(query.merging(values) { _, new in new } as CFDictionary, nil) == errSecSuccess else { throw JNotesError("Could not protect sync credentials") }
        } else if status != errSecSuccess { throw JNotesError("Could not protect sync credentials") }
    }
    static func clear() { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "session"] as CFDictionary) }
}
