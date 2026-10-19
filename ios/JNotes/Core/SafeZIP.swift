import Foundation
import ZIPFoundation

enum SafeZIP {
    /// Bound imported ZIP/EPUB extraction and reject traversal, symlinks and zip bombs.
    static func extract(_ source: URL, to root: URL) throws {
        let archive = try Archive(url: source, accessMode: .read)
        let entries = Array(archive)
        guard entries.count <= 10_000 else { throw JNotesError("Archive has too many entries") }
        var size: UInt64 = 0
        for e in entries {
            size += UInt64(e.uncompressedSize)
            guard size <= 512 * 1024 * 1024, e.type != .symlink,
                  !e.path.hasPrefix("/"), !e.path.contains("\\"),
                  !e.path.components(separatedBy: "/").contains("..") else { throw JNotesError("Unsafe or oversized archive") }
        }
        for e in entries { _ = try archive.extract(e, to: root.appendingPathComponent(e.path)) }
    }
}
