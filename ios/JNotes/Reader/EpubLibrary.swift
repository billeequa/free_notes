import Foundation
import SwiftUI
import CryptoKit

struct BookEntry: Codable, Identifiable {
    let id: String, filename: String
    var title: String, author: String, cover: String?
    var chapter = 0, scroll = 0.0, progress = 0.0, lastRead = 0.0
}
struct EpubPublication {
    let root: URL, title: String, author: String
    let chapters: [URL], names: [String]
    let cover: URL?
}
final class EPUBXML: NSObject, XMLParserDelegate {
    var rootfile: String?, title = "", author = ""
    var manifest: [String: (String, String, String)] = [:], spine: [String] = [], coverID: String?
    private var capture: String?, buffer = ""
    func parse(_ url: URL) throws {
        let parser = XMLParser(data: try Data(contentsOf: url)); parser.shouldResolveExternalEntities = false; parser.delegate = self
        guard parser.parse() else { throw JNotesError("Invalid EPUB XML") }
    }
    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName qName: String?, attributes a: [String: String]) {
        let local = name.components(separatedBy: ":").last!
        if local == "rootfile" { rootfile = a["full-path"] }
        if local == "item", let id = a["id"], let href = a["href"] { manifest[id] = (href, a["media-type"] ?? "", a["properties"] ?? "") }
        if local == "itemref", let id = a["idref"], a["linear"] != "no" { spine.append(id) }
        if local == "meta", a["name"] == "cover" { coverID = a["content"] }
        if local == "title" || local == "creator" { capture = local; buffer = "" }
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) { if capture != nil { buffer += string } }
    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName qName: String?) {
        let local = name.components(separatedBy: ":").last!
        if local == capture {
            let text = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
            if local == "title" && title.isEmpty { title = text }
            if local == "creator" && !text.isEmpty { author += author.isEmpty ? text : ", " + text }
            capture = nil
        }
    }
}
enum EPUB {
    static func inside(_ href: String, relativeTo base: URL, root: URL) throws -> URL {
        guard let u = URL(string: href, relativeTo: base)?.absoluteURL, u.isFileURL,
              u.standardizedFileURL.path.hasPrefix(root.standardizedFileURL.path + "/") else { throw JNotesError("Unsafe EPUB resource") }
        return u
    }
    static func open(_ file: URL, cache: URL) throws -> EpubPublication {
        let fm = FileManager.default
        if !fm.fileExists(atPath: cache.appendingPathComponent(".complete").path) {
            if fm.fileExists(atPath: cache.path) { try fm.removeItem(at: cache) }
            try fm.createDirectory(at: cache, withIntermediateDirectories: true)
            try SafeZIP.extract(file, to: cache)
            try Data().write(to: cache.appendingPathComponent(".complete"), options: .atomic)
        }
        let container = EPUBXML(); try container.parse(cache.appendingPathComponent("META-INF/container.xml"))
        guard let path = container.rootfile else { throw JNotesError("EPUB package is missing") }
        let packageURL = try inside(path, relativeTo: cache.appendingPathComponent("placeholder.opf"), root: cache)
        let package = EPUBXML(); try package.parse(packageURL)
        var chapters: [URL] = [], names: [String] = []
        for id in package.spine {
            guard let item = package.manifest[id], ["application/xhtml+xml", "text/html"].contains(item.1) else { continue }
            chapters.append(try inside(item.0, relativeTo: packageURL, root: cache))
            names.append(URL(fileURLWithPath: item.0).deletingPathExtension().lastPathComponent)
        }
        guard !chapters.isEmpty else { throw JNotesError("EPUB has no readable chapters. DRM-protected books are not supported.") }
        let coverItem = package.manifest.values.first { $0.2.components(separatedBy: " ").contains("cover-image") } ?? package.coverID.flatMap { package.manifest[$0] }
        let cover = try coverItem.map { try inside($0.0, relativeTo: packageURL, root: cache) }
        return EpubPublication(root: cache, title: package.title.isEmpty ? file.deletingPathExtension().lastPathComponent : package.title, author: package.author, chapters: chapters, names: names, cover: cover)
    }
}
@MainActor final class EpubLibrary: ObservableObject {
    @Published var books: [BookEntry] = []
    @Published var busy = false
    @Published var error: String?
    private let root: URL
    init() {
        root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Ebooks", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let index = root.appendingPathComponent(".library.json")
            if FileManager.default.fileExists(atPath: index.path) { books = try JSONDecoder().decode([BookEntry].self, from: Data(contentsOf: index)) }
        } catch { self.error = error.localizedDescription }
    }
    func save() throws { try JSONEncoder().encode(books).write(to: root.appendingPathComponent(".library.json"), options: .atomic) }
    func importBooks(_ urls: [URL], folder: Bool = false) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do {
            for source in urls {
                let access = source.startAccessingSecurityScopedResource(); defer { if access { source.stopAccessingSecurityScopedResource() } }
                var files = [source]
                if folder {
                    files = FileManager.default.enumerator(at: source, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles])?.compactMap { $0 as? URL }.filter { $0.pathExtension.lowercased() == "epub" } ?? []
                }
                for file in files where file.pathExtension.lowercased() == "epub" {
                    let id = SHA256.hash(data: Data(file.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
                    if books.contains(where: { $0.id == id }) { continue }
                    let target = root.appendingPathComponent(id + ".epub"), cache = root.appendingPathComponent(id, isDirectory: true)
                    if !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.copyItem(at: file, to: target) }
                    let publication = try await Task.detached { try EPUB.open(target, cache: cache) }.value
                    books.append(BookEntry(id: id, filename: target.lastPathComponent, title: publication.title, author: publication.author, cover: publication.cover?.path))
                    try save()
                }
            }
        } catch { self.error = error.localizedDescription }
    }
    func open(_ book: BookEntry) async throws -> EpubPublication {
        let file = root.appendingPathComponent(book.filename), cache = root.appendingPathComponent(book.id, isDirectory: true)
        return try await Task.detached { try EPUB.open(file, cache: cache) }.value
    }
    func progress(_ id: String, chapter: Int, scroll: Double, count: Int) {
        guard let index = books.firstIndex(where: { $0.id == id }) else { return }
        books[index].chapter = chapter; books[index].scroll = scroll; books[index].progress = min(1, (Double(chapter) + scroll) / Double(max(1, count))); books[index].lastRead = Date().timeIntervalSince1970
        do { try save() } catch { self.error = error.localizedDescription }
    }
    func remove(_ book: BookEntry) {
        do {
            try FileManager.default.removeItem(at: root.appendingPathComponent(book.filename))
            try? FileManager.default.removeItem(at: root.appendingPathComponent(book.id))
            books.removeAll { $0.id == book.id }; try save()
        } catch { self.error = error.localizedDescription }
    }
}
