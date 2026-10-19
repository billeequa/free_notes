import Foundation

enum FileFormats {
    static let todoName = "to_do_jnotes.txt"
    static func escape(_ value: String) -> String { value.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\n", with: "\\n").replacingOccurrences(of: "\r", with: "\\r") }
    static func unescape(_ value: String) throws -> String {
        var result = "", escaped = false
        for c in value {
            if escaped {
                switch c { case "n": result += "\n"; case "r": result += "\r"; case "\\": result += "\\"; default: throw JNotesError("Unknown task escape. Original file unchanged.") }
                escaped = false
            } else if c == "\\" { escaped = true } else { result.append(c) }
        }
        guard !escaped else { throw JNotesError("Incomplete task escape. Original file unchanged.") }; return result
    }
    static func serializeTasks(_ tasks: [Payload]) -> String {
        "Jnotes To-do: 2\n" + tasks.map { p in
            "\nid: \(p.id)\ntitle: \(escape(p.title))\ndescription: \(escape(p.description))\nadded: \(p.created_at)\ncompleted: \(p.completed_at ?? "")\nflagged: \(p.flagged)\nflags: \(TaskFlag.allCases.map(\.rawValue).filter { p.flags.contains($0) }.joined(separator: ","))\n"
        }.joined()
    }
    static func parseTasks(_ raw: String) throws -> [Payload] {
        let lines = raw.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        let legacy = lines.first == "Jnotes To-do: 1"
        guard legacy || lines.first == "Jnotes To-do: 2" else { throw JNotesError("Unrecognized to-do file. Original file unchanged.") }
        let rows = lines.dropFirst().filter { !$0.isEmpty }, count = legacy ? 6 : 7
        guard rows.count % count == 0 else { throw JNotesError("Incomplete to-do record. Original file unchanged.") }
        var result: [Payload] = [], ids = Set<String>()
        for start in stride(from: 0, to: rows.count, by: count) {
            let row = Array(rows[start..<(start + count)])
            func field(_ index: Int, _ name: String) throws -> String {
                let prefix = name + ": "
                guard row[index].hasPrefix(prefix) else { throw JNotesError("Invalid task field: \(name)") }
                return String(row[index].dropFirst(prefix.count))
            }
            var p = Payload.new(.task)
            p.id = try field(0,"id").lowercased(); p.title = try unescape(field(1,"title")); p.description = try unescape(field(2,"description"))
            p.created_at = try field(3,"added"); p.modified_at = p.created_at
            let completed = try field(4,"completed"); p.completed_at = completed.isEmpty ? nil : completed
            let flagged = try field(5,"flagged"); guard ["true","false"].contains(flagged) else { throw JNotesError("Invalid legacy flag") }; p.flagged = flagged == "true"
            if !legacy { let f = try field(6,"flags"); p.flags = f.isEmpty ? [] : f.components(separatedBy: ",") }
            p.order = Dates.epochMillis(p.created_at)
            p = try p.validated(); guard ids.insert(p.id).inserted else { throw JNotesError("Duplicate task IDs") }; result.append(p)
        }
        return result
    }
    static func serializeNote(_ p: Payload) -> String {
        var header = "Title: \(p.title)\nCreated: \(Dates.local(p.created_at))\nModified: \(Dates.local(p.modified_at))\n"
        if p.subtype == "double_x_day", let day = p.double_x_date {
            header += "Note-Type: DOUBLE_X_DAY\nDouble-X-Date: \(day)\n"
            if p.template_version > 0 { header += "Double-X-Template: \(p.template_version)\n" }
        }
        header += "Category: \(p.kind == .journal ? "JOURNAL" : "NOTES")\n"
        if let day = p.journal_date { header += "Journal-Date: \(day)\n" }
        return header + "\n" + p.body
    }
    static func parseNote(_ raw: String, filename: String, modified: Date = Date(), id: String = UUID().uuidString.lowercased()) -> Payload {
        let normalized = raw.replacingOccurrences(of: "\r\n", with: "\n")
        var p = Payload.new(.note); p.id = id; p.created_at = Dates.iso(modified); p.modified_at = p.created_at
        let keys = Set(["title","created","modified","note-type","double-x-date","category","journal-date","double-x-template"])
        let lines = normalized.components(separatedBy: "\n")
        var fields: [String: String] = [:], bodyStart = 0, valid = true
        for (i, line) in lines.enumerated() {
            if line.trimmingCharacters(in: .whitespaces).isEmpty { bodyStart = fields.isEmpty ? 0 : i + 1; break }
            guard let colon = line.firstIndex(of: ":") else { valid = false; break }
            let key = String(line[..<colon]).lowercased()
            guard keys.contains(key) else { valid = false; break }
            fields[key] = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            bodyStart = i + 1
        }
        p.body = valid && !fields.isEmpty ? lines.dropFirst(bodyStart).joined(separator: "\n") : normalized
        p.title = fields["title"] ?? p.body.components(separatedBy: "\n").first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty })?.trimmingCharacters(in: .whitespaces) ?? URL(fileURLWithPath: filename).deletingPathExtension().lastPathComponent.replacingOccurrences(of: "-", with: " ")
        if let date = fields["created"].flatMap(Dates.parse) ?? fields["modified"].flatMap(Dates.parse) { p.created_at = Dates.iso(date) }
        if let date = fields["modified"].flatMap(Dates.parse) { p.modified_at = Dates.iso(date) }
        p.double_x_date = fields["double-x-date"].flatMap { Dates.validDay($0) ? $0 : nil }
        if fields["note-type"] == "DOUBLE_X_DAY", p.double_x_date != nil { p.subtype = "double_x_day" }
        p.kind = fields["category"] == "JOURNAL" || (fields["category"] == nil && p.subtype == "double_x_day") ? .journal : .note
        p.journal_date = fields["journal-date"].flatMap { Dates.validDay($0) ? $0 : nil } ?? p.double_x_date ?? (p.kind == .journal ? Dates.parse(p.created_at).map(Dates.day) : nil)
        p.template_version = max(0, Int(fields["double-x-template"] ?? "0") ?? 0)
        if fields["category"] == nil && p.subtype == "double_x_day" { p.title = "\(p.double_x_date!) Double X Day" }
        return p
    }
    static func filename(_ p: Payload) -> String {
        let stamp = Dates.formatter("yyyyMMdd-HHmmss").string(from: Dates.parse(p.created_at) ?? Date())
        let slug = p.displayTitle.lowercased().map { $0.isLetter || $0.isNumber ? String($0) : "-" }.joined()
        return stamp + "-" + String(slug.prefix(65)) + ".txt"
    }
}
