import Foundation
import XCTest
@testable import VelaCore

/// 金样本入口（docs/plans/sync.md 第 17、18 节 S1 ②）：`Tests/Fixtures/sync/vectors.json` 由 S0 的 Go 生成器产出。
/// 文件不存在时跳过并打印原因；存在时逐段校验 S1 负责的段落。各段的字段名按第 17 节；
/// 第 17 节没写死的嵌套形状（HLC 事件、操作、模拟步骤）按下面 `Vectors` 里注明的猜测解析，
/// 解析不了的条目记为失败（「格式与加载器不符」），不静默通过。
final class SyncVectorsTests: XCTestCase {
    static var fixtureURL: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Fixtures/sync/vectors.json")
    }

    /// S1 负责逐项相等的段落（第 18 节 S1 ②）。
    static let s1Sections = ["json", "decimal", "base64url", "code", "tagKey", "hlc", "merge", "size", "projection", "ops", "migration", "simulation"]

    func testGoldenVectors() throws {
        let url = Self.fixtureURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            let reason = "金样本 \(url.lastPathComponent) 尚未生成（S0 产出后自动启用），跳过"
            print(reason)
            throw XCTSkip(reason)
        }
        let report = try Vectors.run(url: url)
        print("金样本：通过 \(report.passed) 条，失败 \(report.failures.count) 条")
        for f in report.failures.prefix(50) { XCTFail(f) }
        for section in Self.s1Sections where !report.sections.contains(section) {
            XCTFail("金样本缺少段落 \(section)")
        }
        XCTAssertGreaterThan(report.passed, 0)
    }

    /// 加载器自测：用本端按文档规则自造的小样本（不是伪造的 vectors.json），证明正例能过、负例会失败。
    func testLoaderAcceptsGoodAndCatchesBadSamples() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sync-vectors-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let a = "{\"schema\":2,\"rooms\":{\"cb:alice\":{\"f\":\"000000000005000000000001+\"}},\"veto\":{},\"settings\":{}}"
        let b = "{\"schema\":2,\"rooms\":{\"cb:alice\":{\"b\":\"000000000006000000000002+\"}},\"veto\":{},\"settings\":{}}"
        let ab = "{\"schema\":2,\"rooms\":{\"cb:alice\":{\"f\":\"000000000005000000000001+\",\"b\":\"000000000006000000000002+\"}},\"veto\":{},\"settings\":{}}"
        let good: [String: Any] = [
            "json": [["text": a, "valid": true], ["text": "{\"schema\":2,\"schema\":2}", "valid": false, "reason": "duplicate"]],
            "decimal": [["text": "10", "value": 10]], "decimalInvalid": ["01"],
            "base64url": [["text": "-_8", "hex": "fbff"]], "base64urlInvalid": ["-_8="],
            "tagKey": [["raw": "Asian", "key": "t:asian"], ["raw": "a b", "local_only": true]],
            "merge": [["a": a, "b": b, "expected": ab]],
            "projection": [["doc": ab, "expected": ["favorites": [], "blocked": ["cb:alice"], "vetoed": [], "settings": [:]]]],
            "hlc": [["state": ["l": 5, "c": 0, "dev": 1], "events": [["type": "local", "pt": 5, "expected": ["l": 5, "c": 1]]]]],
        ]
        let goodURL = dir.appendingPathComponent("good.json")
        try JSONSerialization.data(withJSONObject: good, options: [.withoutEscapingSlashes]).write(to: goodURL)
        let ok = try Vectors.run(url: goodURL)
        XCTAssertEqual(ok.failures, [])
        XCTAssertEqual(ok.passed, 11)

        var bad = good
        bad["merge"] = [["a": a, "b": b, "expected": a]]
        bad["tagKey"] = [["raw": "Asian", "key": "t:Asian"]]
        bad["hlc"] = [["state": ["l": 5, "c": 0, "dev": 1], "events": [["type": "local", "pt": 5, "expected": ["l": 5, "c": 0]]]]]
        bad["ops"] = [["unexpected": true]]
        let badURL = dir.appendingPathComponent("bad.json")
        try JSONSerialization.data(withJSONObject: bad, options: [.withoutEscapingSlashes]).write(to: badURL)
        XCTAssertEqual(try Vectors.run(url: badURL).failures.count, 4, "负样本必须失败")
    }
}

/// 金样本的解析与比对。
enum Vectors {
    struct Report {
        var passed = 0
        var failures: [String] = []
        var sections: Set<String> = []
    }

    static func run(url: URL) throws -> Report {
        let root = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any] ?? [:]
        var report = Report()
        report.sections = Set(root.keys)
        func check(_ section: String, _ body: (Any) throws -> Bool) {
            guard let items = root[section] as? [Any] else { return }
            for (i, item) in items.enumerated() {
                do {
                    if try body(item) { report.passed += 1 } else { report.failures.append("\(section)[\(i)] 不一致") }
                } catch {
                    report.failures.append("\(section)[\(i)] 格式与加载器不符：\(error)")
                }
            }
        }
        check("json") { item in
            let d = try dict(item)
            let valid: Bool = try field(d, "valid")
            if case .valid = SyncDocument.decode(try bytes(d, "text")) { return valid }
            return !valid
        }
        check("decimal") { item in
            let d = try dict(item)
            return SyncEncoding.parseDecimal(try field(d, "text")) == UInt64(try field(d, "value") as Int)
        }
        check("decimalInvalid") { SyncEncoding.parseDecimal(try text($0)) == nil }
        check("base64url") { item in
            let d = try dict(item)
            return SyncEncoding.decodeBase64url(try field(d, "text")) == SyncEncoding.decodeHex(try field(d, "hex"))
        }
        check("base64urlInvalid") { SyncEncoding.decodeBase64url(try text($0)) == nil }
        check("code") { item in
            let d = try dict(item)
            let parsed = SyncCode.parse(try field(d, "code"), sha256: TestSHA256.hash)
            guard let parsed else { return false }
            if let k: String = d["K_hex"] as? String, SyncEncoding.decodeHex(k) != parsed.key { return false }
            if let o = d["origin"] as? String, o != parsed.origin { return false }
            return true
        }
        check("codeInvalid") { SyncCode.parse(try text($0, key: "code"), sha256: TestSHA256.hash) == nil }
        check("tagKey") { item in
            let d = try dict(item)
            let key = SyncKeys.syncTagKey(raw: try field(d, "raw"))
            if d["local_only"] as? Bool == true { return key == nil }
            let expected: String = try field(d, "key")
            return key.map { Array($0.utf8) } == Array(expected.utf8)
        }
        check("merge") { item in
            let d = try dict(item)
            return SyncMerge.fitSize(SyncMerge.union(try doc(d, "a"), try doc(d, "b"))).doc == (try doc(d, "expected"))
        }
        check("size") { item in
            let d = try dict(item)
            let fitted = SyncMerge.fitSize(try doc(d, "doc"))
            if d["expected"] as? String == "reject" || d["reject"] as? Bool == true { return !fitted.fits }
            let expected = try doc(d, "expected")
            return fitted.fits && fitted.doc == expected
        }
        check("projection") { item in
            let d = try dict(item)
            let p = SyncProjection(try doc(d, "doc"))
            let e = try dict(d["expected"] as Any)
            func list(_ name: String, _ entries: [SyncProjection.Entry]) throws -> Bool {
                let want: [String] = try field(e, name)
                return want == entries.map(\.key) || want == entries.map(\.display)
            }
            guard try list("favorites", p.favorites), try list("blocked", p.blocked) else { return false }
            if let vetoed = e["vetoed"] as? [String], Set(vetoed) != Set(p.veto.filter(\.value).keys) { return false }
            if let settings = e["settings"] as? [String: Any] {
                let want = settings.mapValues { ($0 as? Bool) ?? (($0 as? String) == "1") }
                if want != p.settings { return false }
            }
            return true
        }
        check("hlc") { item in
            // 猜测形状：state {l,c,dev}；events[] 每项 {type: local|receive|presend, pt | remote | N,maxSentL,doc, expected {l,c}}。
            let d = try dict(item)
            var replica = SyncReplica(clock: try clock(try dict(d["state"] as Any)))
            for e in try field(d, "events") as [Any] {
                let ev = try dict(e)
                switch try field(ev, "type") as String {
                case "local": _ = replica.clock.tick(pt: UInt64(try field(ev, "pt") as Int))
                case "receive": replica.clock.receive(HLC(hex: try field(ev, "remote") as String))
                case "presend":
                    replica.doc = try doc(ev, "doc")
                    let n = UInt64(try field(ev, "N") as Int)
                    let sent = UInt64(try field(ev, "maxSentL") as Int)
                    _ = SyncRestamp.presend(&replica, serverNow: n, maxSentL: sent, pt: (ev["pt"] as? Int).map(UInt64.init) ?? n)
                    if ev["expected_doc"] != nil, replica.doc != (try doc(ev, "expected_doc")) { return false }
                default: throw VectorError.shape("未知事件")
                }
                let want = try dict(ev["expected"] as Any)
                let l = UInt64(try field(want, "l") as Int), c = UInt16(try field(want, "c") as Int)
                if replica.clock.l != l || replica.clock.c != c { return false }
            }
            return true
        }
        check("ops") { item in
            // 猜测形状：doc、state {l,c,dev}、ops[] 每项 {op: 本地函数名, arg, pt}、expected。
            let d = try dict(item)
            var replica = SyncReplica(doc: try doc(d, "doc"), clock: try clock(try dict(d["state"] as Any)))
            for o in try field(d, "ops") as [Any] {
                let od = try dict(o)
                if case .applied(let next) = SyncOps.apply(try op(od), to: replica, pt: UInt64(try field(od, "pt") as Int)) { replica = next }
            }
            return replica.doc == (try doc(d, "expected"))
        }
        check("migration") { item in
            // 猜测形状：curation {favorites, blocked}、vetoed[]、settings {}、可选 remote、state、pt、expected、counts {N,M,V,X,Y,Z}。
            let d = try dict(item)
            let cur = try dict(d["curation"] as Any)
            let local = SyncLocalData(
                curation: Curation(favorites: try field(cur, "favorites"), blocked: try field(cur, "blocked")),
                vetoedKeys: try field(d, "vetoed"),
                explicitSettings: ((d["settings"] as? [String: Any]) ?? [:]).mapValues { ($0 as? Bool) ?? false }
            )
            let c = try clock(try dict(d["state"] as Any))
            let pt = UInt64(try field(d, "pt") as Int)
            if d["remote"] != nil, !(d["remote"] is NSNull) {
                let r = SyncMigration.stampJoin(local, remote: try doc(d, "remote"), clock: c, pt: pt)
                let counts = try dict(d["counts"] as Any)
                let p = r.preview
                let got = ["N": p.n, "M": p.m, "V": p.v, "X": p.x, "Y": p.y, "Z": p.z]
                for (k, v) in got where (counts[k] as? Int) != v { return false }
                return r.replica.doc == (try doc(d, "expected"))
            }
            return SyncMigration.stampCreator(local, clock: c, pt: pt).replica.doc == (try doc(d, "expected"))
        }
        check("simulation") { item in
            // 猜测形状：replicas（数量）、devs[]、steps[] 每项 {replica, op, pt} 或 {merge: [i, j], into: "i"|"both"}，可选 after[]，final。
            let d = try dict(item)
            let count: Int = try field(d, "replicas")
            let devs = (d["devs"] as? [Int]) ?? (1...count).map { $0 }
            var reps = devs.map { SyncReplica(clock: SyncClock(dev: UInt32($0))) }
            for s in try field(d, "steps") as [Any] {
                let st = try dict(s)
                if let pair = st["merge"] as? [Int] {
                    let merged = SyncMerge.fitSize(SyncMerge.union(reps[pair[0]].doc, reps[pair[1]].doc)).doc
                    reps[pair[0]].doc = merged
                    reps[pair[0]].clock.receive(merged.maxHLC)
                    if st["into"] as? String == "both" {
                        reps[pair[1]].doc = merged
                        reps[pair[1]].clock.receive(merged.maxHLC)
                    }
                } else {
                    let i: Int = try field(st, "replica")
                    if case .applied(let next) = SyncOps.apply(try op(try dict(st["op"] as Any)), to: reps[i], pt: UInt64(try field(st, "pt") as Int)) { reps[i] = next }
                }
                if let after = st["after"] as? [Any] {
                    for (i, e) in after.enumerated() where reps[i].doc != (try docValue(e)) { return false }
                }
            }
            let final = try doc(d, "final")
            return reps.allSatisfy { $0.doc == final }
        }
        return report
    }

    enum VectorError: Error { case shape(String) }

    static func dict(_ v: Any) throws -> [String: Any] {
        guard let d = v as? [String: Any] else { throw VectorError.shape("不是对象") }
        return d
    }

    static func field<T>(_ d: [String: Any], _ key: String) throws -> T {
        guard let v = d[key] as? T else { throw VectorError.shape("缺字段 \(key)") }
        return v
    }

    static func text(_ v: Any, key: String = "text") throws -> String {
        if let s = v as? String { return s }
        return try field(try dict(v), key)
    }

    /// `text`，或二进制样本的 `text_hex` / `text_b64url`。
    static func bytes(_ d: [String: Any], _ key: String) throws -> [UInt8] {
        if let s = d[key] as? String { return Array(s.utf8) }
        if let h = d[key + "_hex"] as? String, let b = SyncEncoding.decodeHex(h) { return b }
        if let b = d[key + "_b64url"] as? String, let out = SyncEncoding.decodeBase64url(b) { return out }
        throw VectorError.shape("缺字段 \(key)")
    }

    static func doc(_ d: [String: Any], _ key: String) throws -> SyncDocument {
        guard let v = d[key] else { throw VectorError.shape("缺字段 \(key)") }
        return try docValue(v)
    }

    static func docValue(_ v: Any) throws -> SyncDocument {
        let raw: [UInt8]
        if let s = v as? String {
            raw = Array(s.utf8)
        } else {
            raw = Array(try JSONSerialization.data(withJSONObject: v, options: [.withoutEscapingSlashes]))
        }
        guard case .valid(let doc) = SyncDocument.decode(raw) else { throw VectorError.shape("文档不合法") }
        return doc
    }

    static func clock(_ d: [String: Any]) throws -> SyncClock {
        SyncClock(l: UInt64(try field(d, "l") as Int), c: UInt16(try field(d, "c") as Int), dev: UInt32(try field(d, "dev") as Int))
    }

    /// 第 6.4 节的本地函数名 → 操作。
    static func op(_ d: [String: Any]) throws -> SyncLocalOp {
        let name: String = try field(d, "op")
        let arg = d["arg"]
        func s() throws -> String {
            guard let v = arg as? String else { throw VectorError.shape("缺参数") }
            return v
        }
        switch name {
        case "favoriting": return .favorite(try s())
        case "restoringFavorite": return .restoreFavorite(try s())
        case "unfavoriting": return .unfavorite(try s())
        case "blocking": return .block(try s())
        case "unblocking": return .unblock(try s())
        case "importing":
            guard let names = arg as? [String] else { throw VectorError.shape("导入参数") }
            return .importing(names)
        case "vetoing": return .veto(try s())
        case "restoring", "boosting", "removing": return .unveto(try s())
        case "resettingProfile", "erased": return .clearVetoes
        case "setting":
            guard let kv = arg as? [String: Any], let k = kv["key"] as? String, let v = kv["value"] as? Bool else { throw VectorError.shape("设置参数") }
            return .setting(k, v)
        default: throw VectorError.shape("未知操作 \(name)")
        }
    }
}
