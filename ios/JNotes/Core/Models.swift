import Foundation

struct JNotesError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

enum RecordKind: String, Codable { case note, journal, task }
enum TaskFlag: String, Codable, CaseIterable, Identifiable {
    case urgent, important, longTerm = "long_term"
    var id: String { rawValue }
    var label: String { switch self { case .urgent: return "urgent"; case .important: return "important"; case .longTerm: return "as long term" } }
    var emoji: String { switch self { case .urgent: return "🚨"; case .important: return "❗"; case .longTerm: return "🎯" } }
}

/// Wire keys intentionally match protocol/types.ts. Dates are strings to preserve Android precision.
struct Payload: Codable, Equatable, Identifiable {
    var id = UUID().uuidString.lowercased()
    var kind: RecordKind = .note
    var title = ""
    var body = ""
    var created_at = Dates.iso(Date())
    var modified_at = Dates.iso(Date())
    var subtype = "standard"
    var journal_date: String?
    var double_x_date: String?
    var template_version = 0
    var description = ""
    var completed_at: String?
    var flagged = false
    var flags: [String] = []
    var order: Int64 = 0
    var displayTitle: String { title.isEmpty ? "Untitled" : title }
    var done: Bool { completed_at != nil }
    var symbols: String {
        let symbols = TaskFlag.allCases.filter { flags.contains($0.rawValue) }.map(\.emoji)
        return symbols.isEmpty && flagged ? "⚑" : symbols.joined(separator: " ")
    }
    var isFlagged: Bool { flagged || !flags.isEmpty }
    static func new(_ kind: RecordKind, title: String = "") -> Payload {
        var p = Payload(); p.kind = kind; p.title = title
        if kind == .task { p.created_at = Dates.taskISO(Date()); p.modified_at = p.created_at; p.order = Dates.epochMillis(p.created_at) }
        if kind == .journal { p.journal_date = Dates.day(Date()); p.title = title.isEmpty ? p.journal_date! : title }
        return p
    }
    func validated(expectedID: String? = nil) throws -> Payload {
        guard UUID(uuidString: id) != nil, expectedID == nil || id == expectedID,
              !title.contains("\r") || kind == .task, !title.contains("\n") || kind == .task,
              ["standard", "double_x_day"].contains(subtype), template_version >= 0,
              Dates.parse(created_at) != nil, Dates.parse(modified_at) != nil,
              completed_at == nil || Dates.parse(completed_at!) != nil,
              flags.allSatisfy({ f in TaskFlag.allCases.contains { $0.rawValue == f } }),
              [journal_date, double_x_date].compactMap({$0}).allSatisfy(Dates.validDay),
              order >= -9_007_199_254_740_991, order <= 9_007_199_254_740_991 else { throw JNotesError("Unsupported or damaged record. Files are preserved.") }
        var p = self
        p.flags = TaskFlag.allCases.map(\.rawValue).filter { flags.contains($0) }
        if kind != .task {
            p.created_at = Dates.iso(Dates.parse(created_at)!); p.modified_at = Dates.iso(Dates.parse(modified_at)!); p.order = 0
        } else {
            p.created_at = Dates.normalizedInstant(created_at); p.modified_at = Dates.normalizedInstant(modified_at)
            p.completed_at = completed_at.map(Dates.normalizedInstant); p.order = Dates.epochMillis(p.created_at)
        }
        return p
    }
    func canonical() throws -> Data {
        let p = try validated()
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        // JSONEncoder omits nil; protocol canonical comparisons use explicit nulls.
        var object = try JSONSerialization.jsonObject(with: encoder.encode(p)) as! [String: Any]
        for name in ["journal_date", "double_x_date", "completed_at"] where object[name] == nil { object[name] = NSNull() }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
    }
    func sameContent(as other: Payload) -> Bool {
        var a = self, b = other
        if kind == .task { a.modified_at = "2000-01-01T00:00:00Z"; b.modified_at = a.modified_at }
        return (try? a.canonical()) == (try? b.canonical())
    }
    static func mergeTask(base: Payload, local: Payload, remote: Payload) -> Payload? {
        guard base.kind == .task, local.kind == .task, remote.kind == .task,
              base.id == local.id, base.id == remote.id,
              let bd = try? base.canonical(), let ld = try? local.canonical(), let rd = try? remote.canonical(),
              let b = try? JSONSerialization.jsonObject(with: bd) as? [String: Any],
              let l = try? JSONSerialization.jsonObject(with: ld) as? [String: Any],
              var r = try? JSONSerialization.jsonObject(with: rd) as? [String: Any] else { return nil }
        for (k, v) in l where k != "modified_at" {
            let bv = b[k] as? NSObject, rv = r[k] as? NSObject, lv = v as? NSObject
            if lv == bv { continue }
            if rv != bv && rv != lv { return nil }
            r[k] = v
        }
        r["modified_at"] = Dates.iso(Date())
        guard let data = try? JSONSerialization.data(withJSONObject: r) else { return nil }
        return try? JSONDecoder().decode(Payload.self, from: data)
    }
}

struct LocalRecord: Codable, Identifiable {
    var payload: Payload
    var deleted = false
    var filename: String?
    var id: String { payload.id }
}

enum Dates {
    static func formatter(_ format: String) -> DateFormatter {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = format; f.isLenient = false; return f
    }
    static func iso(_ date: Date) -> String { ISO8601DateFormatter().string(from: date) }
    static func taskISO(_ date: Date) -> String {
        let f = ISO8601DateFormatter(); f.formatOptions.insert(.withFractionalSeconds)
        return normalizedInstant(f.string(from: date))
    }
    /// Preserve Android nanoseconds while normalizing the timezone, without a floating-point round trip.
    static func normalizedInstant(_ text: String) -> String {
        let pattern = #"^(.{19})(?:\.([0-9]{1,9}))?(Z|[+-][0-9]{2}:[0-9]{2})$"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let baseRange = Range(match.range(at: 1), in: text), let zoneRange = Range(match.range(at: 3), in: text),
              let whole = ISO8601DateFormatter().date(from: String(text[baseRange]) + String(text[zoneRange])) else { return parse(text).map(iso) ?? text }
        var fraction = Range(match.range(at: 2), in: text).map { String(text[$0]) } ?? ""
        while fraction.last == "0" { fraction.removeLast() }
        return String(iso(whole).dropLast()) + (fraction.isEmpty ? "" : "." + fraction) + "Z"
    }
    static func epochMillis(_ text: String) -> Int64 {
        let value = normalizedInstant(text)
        let pieces = value.dropLast().components(separatedBy: ".")
        guard let whole = ISO8601DateFormatter().date(from: pieces[0] + "Z") else { return 0 }
        let millis = pieces.count > 1 ? Int64(String((pieces[1] + "000").prefix(3))) ?? 0 : 0
        return Int64(whole.timeIntervalSince1970) * 1000 + millis
    }
    static func parse(_ text: String) -> Date? {
        let f = ISO8601DateFormatter(); if let d = f.date(from: text) { return d }
        f.formatOptions.insert(.withFractionalSeconds)
        return f.date(from: text) ?? formatter("yyyy-MM-dd HH:mm:ss").date(from: text)
    }
    static func day(_ date: Date) -> String { formatter("yyyy-MM-dd").string(from: date) }
    static func validDay(_ text: String) -> Bool {
        let f = formatter("yyyy-MM-dd"); return text.count == 10 && f.date(from: text).map { f.string(from: $0) == text } == true
    }
    static func display(_ text: String) -> String { parse(text).map { formatter("MMM d, yyyy h:mm a").string(from: $0) } ?? text }
    static func local(_ text: String) -> String { parse(text).map { formatter("yyyy-MM-dd HH:mm:ss").string(from: $0) } ?? text }
}

enum Journal {
    static let parts: [(Int, String)] = [(1000,"M"),(900,"CM"),(500,"D"),(400,"CD"),(100,"C"),(90,"XC"),(50,"L"),(40,"XL"),(10,"X"),(9,"IX"),(5,"V"),(4,"IV"),(1,"I")]
    static func roman(_ value: Int) -> String {
        var n = value, result = ""
        for (amount, symbol) in parts { while n >= amount { result += symbol; n -= amount } }
        return result
    }
    static func ordinal(_ text: String) -> Int? {
        if text.isEmpty { return 1 }; var rest = text, n = 0
        for (amount, symbol) in parts { while rest.hasPrefix(symbol) { n += amount; rest.removeFirst(symbol.count) } }
        return rest.isEmpty && n > 1 && roman(n) == text ? n : nil
    }
    static func nextTitle(_ day: String, existing: [String]) -> String {
        let sequence = existing.compactMap { title -> Int? in
            let text = title.replacingOccurrences(of: " Double X Day", with: "")
            if text == day { return 1 }
            guard text.hasPrefix(day + " ") else { return nil }
            return ordinal(String(text.dropFirst(day.count + 1)))
        }.max() ?? 0
        return sequence == 0 ? day : "\(day) \(roman(sequence + 1))"
    }
    static func markDoubleX(_ original: Payload, tasks: [Payload]) -> Payload {
        var p = original
        let day = p.journal_date ?? Dates.day(Date())
        p.kind = .journal; p.journal_date = day; p.double_x_date = day; p.subtype = "double_x_day"
        if !p.title.hasSuffix(" Double X Day") { p.title += " Double X Day" }
        if p.template_version < 1 {
            let completed = tasks.filter { $0.completed_at.flatMap(Dates.parse).map(Dates.day) == day }
                .sorted { ($0.completed_at ?? "") < ($1.completed_at ?? "") }
            let lines = completed.isEmpty ? "No completed tasks yet.\n" : completed.map { "✓ \($0.title)\n" }.joined()
            p.body = "Things Accomplished\n" + lines + "\nNotes on the Day\n\n" + p.body
            p.template_version = 1
        }
        return p
    }
}
