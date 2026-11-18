@testable import VelaCore

/// 同步测试的小工具：按 `(l, c, dev)` 造寄存器与文档。
enum SyncFixture {
    static func reg(_ l: UInt64, _ payload: String, c: UInt16 = 0, dev: UInt32 = 1) -> SyncRegister {
        SyncRegister(hlc: HLC(l: l, c: c, dev: dev), payload: payload)
    }

    static func doc(_ text: String) -> SyncDocument {
        guard case .valid(let d) = SyncDocument.decode(Array(text.utf8)) else { fatalError("bad fixture: \(text)") }
        return d
    }

    static func text(_ doc: SyncDocument) -> String { String(decoding: doc.encoded(), as: UTF8.self) }
}
