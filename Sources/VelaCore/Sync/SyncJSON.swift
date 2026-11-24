/// 同步文档专用的严格 JSON（docs/plans/sync.md 第 7.3 节）：RFC 8259 的严格子集。
/// - 输入必须是合法 UTF-8，不带 BOM，不超过上限字节数；
/// - 对象按出现顺序保留成员，重复键（按 UTF-8 字节）拒绝；
/// - 字符串里不允许任何反斜杠转义与控制字符（合法文档的字符串不需要转义，写出时也不转义）；
/// - 容器（对象与数组）嵌套不超过 3 层。
/// 不用 `JSONSerialization`：它对重复键沉默。
public enum SyncJSON {
    public struct Member: Equatable, Sendable {
        public let key: String
        public let value: Value
    }

    public indirect enum Value: Equatable, Sendable {
        case object([Member])
        case array([Value])
        case string(String)
        /// 原始数字文本（RFC 8259 语法），由调用方决定是否接受。
        case number(String)
        case bool(Bool)
        case null
    }

    public enum ParseError: Error, Equatable {
        case tooLarge, bom, invalidUTF8, syntax, duplicateKey, escape, controlCharacter, tooDeep

        /// 解码失败原因里的显式标签。
        public var label: String {
            switch self {
            case .tooLarge: return "tooLarge"
            case .bom: return "bom"
            case .invalidUTF8: return "invalidUTF8"
            case .syntax: return "syntax"
            case .duplicateKey: return "duplicateKey"
            case .escape: return "escape"
            case .controlCharacter: return "controlCharacter"
            case .tooDeep: return "tooDeep"
            }
        }
    }

    public static func parse(_ input: [UInt8], limit: Int = SyncLimits.plaintextBytes, maxDepth: Int = 3) throws -> Value {
        guard input.count <= limit else { throw ParseError.tooLarge }
        if input.starts(with: [0xEF, 0xBB, 0xBF]) { throw ParseError.bom }
        guard isValidUTF8(input) else { throw ParseError.invalidUTF8 }
        var parser = Parser(bytes: input, maxDepth: maxDepth)
        parser.skipWhitespace()
        let value = try parser.value(depth: 0)
        parser.skipWhitespace()
        guard parser.atEnd else { throw ParseError.syntax }
        return value
    }

    /// 严格 UTF-8：拒绝过长编码、代理码点、超过 U+10FFFF 与截断序列。
    public static func isValidUTF8(_ b: [UInt8]) -> Bool {
        var i = 0
        let n = b.count
        while i < n {
            let c = b[i]
            if c < 0x80 { i += 1; continue }
            let need: Int
            var lo: UInt8 = 0x80, hi: UInt8 = 0xBF
            switch c {
            case 0xC2...0xDF: need = 1
            case 0xE0: need = 2; lo = 0xA0
            case 0xE1...0xEC, 0xEE...0xEF: need = 2
            case 0xED: need = 2; hi = 0x9F
            case 0xF0: need = 3; lo = 0x90
            case 0xF1...0xF3: need = 3
            case 0xF4: need = 3; hi = 0x8F
            default: return false
            }
            guard i + need < n else { return false }
            guard b[i + 1] >= lo, b[i + 1] <= hi else { return false }
            var k = 2
            while k <= need {
                guard b[i + k] >= 0x80, b[i + k] <= 0xBF else { return false }
                k += 1
            }
            i += need + 1
        }
        return true
    }

    /// 紧凑写出，对象键按 UTF-8 字节升序（键序在协议里不限，固定下来便于比较）。
    /// 调用方保证字符串里没有 `"`、`\` 与控制字符（合法文档必然如此）。
    public static func encode(_ value: Value) -> [UInt8] {
        var out: [UInt8] = []
        write(value, into: &out)
        return out
    }

    static func write(_ value: Value, into out: inout [UInt8]) {
        switch value {
        case .object(let members):
            out.append(UInt8(ascii: "{"))
            for (i, m) in members.sorted(by: { $0.key.utf8.lexicographicallyPrecedes($1.key.utf8) }).enumerated() {
                if i > 0 { out.append(UInt8(ascii: ",")) }
                writeString(m.key, into: &out)
                out.append(UInt8(ascii: ":"))
                write(m.value, into: &out)
            }
            out.append(UInt8(ascii: "}"))
        case .array(let items):
            out.append(UInt8(ascii: "["))
            for (i, item) in items.enumerated() {
                if i > 0 { out.append(UInt8(ascii: ",")) }
                write(item, into: &out)
            }
            out.append(UInt8(ascii: "]"))
        case .string(let s): writeString(s, into: &out)
        case .number(let n): out += Array(n.utf8)
        case .bool(let b): out += Array((b ? "true" : "false").utf8)
        case .null: out += Array("null".utf8)
        }
    }

    static func writeString(_ s: String, into out: inout [UInt8]) {
        out.append(UInt8(ascii: "\""))
        out += Array(s.utf8)
        out.append(UInt8(ascii: "\""))
    }

    private struct Parser {
        let bytes: [UInt8]
        let maxDepth: Int
        var pos = 0

        init(bytes: [UInt8], maxDepth: Int) {
            self.bytes = bytes
            self.maxDepth = maxDepth
        }

        var atEnd: Bool { pos >= bytes.count }

        mutating func skipWhitespace() {
            while pos < bytes.count, [0x20, 0x09, 0x0A, 0x0D].contains(bytes[pos]) { pos += 1 }
        }

        func peek() -> UInt8? { pos < bytes.count ? bytes[pos] : nil }

        mutating func expect(_ c: UInt8) throws {
            guard peek() == c else { throw ParseError.syntax }
            pos += 1
        }

        mutating func value(depth: Int) throws -> Value {
            guard let c = peek() else { throw ParseError.syntax }
            switch c {
            case UInt8(ascii: "{"): return try object(depth: depth + 1)
            case UInt8(ascii: "["): return try array(depth: depth + 1)
            case UInt8(ascii: "\""): return .string(try string())
            case UInt8(ascii: "t"): try literal("true"); return .bool(true)
            case UInt8(ascii: "f"): try literal("false"); return .bool(false)
            case UInt8(ascii: "n"): try literal("null"); return .null
            default: return .number(try number())
            }
        }

        mutating func literal(_ word: String) throws {
            let w = Array(word.utf8)
            guard pos + w.count <= bytes.count, Array(bytes[pos..<pos + w.count]) == w else { throw ParseError.syntax }
            pos += w.count
        }

        mutating func object(depth: Int) throws -> Value {
            guard depth <= maxDepth else { throw ParseError.tooDeep }
            try expect(UInt8(ascii: "{"))
            var members: [Member] = []
            var seen = Set<[UInt8]>()
            skipWhitespace()
            if peek() == UInt8(ascii: "}") {
                pos += 1
                return .object(members)
            }
            while true {
                skipWhitespace()
                guard peek() == UInt8(ascii: "\"") else { throw ParseError.syntax }
                let key = try string()
                guard seen.insert(Array(key.utf8)).inserted else { throw ParseError.duplicateKey }
                skipWhitespace()
                try expect(UInt8(ascii: ":"))
                skipWhitespace()
                members.append(Member(key: key, value: try value(depth: depth)))
                skipWhitespace()
                guard let c = peek() else { throw ParseError.syntax }
                pos += 1
                if c == UInt8(ascii: "}") { return .object(members) }
                guard c == UInt8(ascii: ",") else { throw ParseError.syntax }
            }
        }

        mutating func array(depth: Int) throws -> Value {
            guard depth <= maxDepth else { throw ParseError.tooDeep }
            try expect(UInt8(ascii: "["))
            var items: [Value] = []
            skipWhitespace()
            if peek() == UInt8(ascii: "]") {
                pos += 1
                return .array(items)
            }
            while true {
                skipWhitespace()
                items.append(try value(depth: depth))
                skipWhitespace()
                guard let c = peek() else { throw ParseError.syntax }
                pos += 1
                if c == UInt8(ascii: "]") { return .array(items) }
                guard c == UInt8(ascii: ",") else { throw ParseError.syntax }
            }
        }

        mutating func string() throws -> String {
            try expect(UInt8(ascii: "\""))
            let start = pos
            while let c = peek() {
                if c == UInt8(ascii: "\"") {
                    let s = String(decoding: bytes[start..<pos], as: UTF8.self)
                    pos += 1
                    return s
                }
                if c == UInt8(ascii: "\\") { throw ParseError.escape }
                if c < 0x20 { throw ParseError.controlCharacter }
                pos += 1
            }
            throw ParseError.syntax
        }

        mutating func number() throws -> String {
            let start = pos
            func digits() -> Int {
                let s = pos
                while let c = peek(), c >= UInt8(ascii: "0"), c <= UInt8(ascii: "9") { pos += 1 }
                return pos - s
            }
            if peek() == UInt8(ascii: "-") { pos += 1 }
            if peek() == UInt8(ascii: "0") {
                pos += 1
            } else {
                guard digits() > 0 else { throw ParseError.syntax }
            }
            if peek() == UInt8(ascii: ".") {
                pos += 1
                guard digits() > 0 else { throw ParseError.syntax }
            }
            if let c = peek(), c == UInt8(ascii: "e") || c == UInt8(ascii: "E") {
                pos += 1
                if let s = peek(), s == UInt8(ascii: "+") || s == UInt8(ascii: "-") { pos += 1 }
                guard digits() > 0 else { throw ParseError.syntax }
            }
            if let c = peek(), c >= UInt8(ascii: "0"), c <= UInt8(ascii: "9") { throw ParseError.syntax }
            return String(decoding: bytes[start..<pos], as: UTF8.self)
        }
    }
}
