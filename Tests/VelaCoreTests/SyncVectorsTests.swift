import Foundation
import XCTest
@testable import VelaCore

/// 金样本入口（docs/plans/sync.md 第 17、18 节 S1 ②）：`Tests/Fixtures/sync/vectors.json` 由 S0 的 Go 生成器产出。
/// 文件不存在时跳过并打印原因；存在时逐段逐条比对 S1 负责的段落，形状以 S0 生成器的实际输出为准
/// （`scripts/sync/vectors/build.go`）。解析不了的条目记为失败，不静默通过。
/// 加密相关段（`kdf` 除指纹外、`payload`、`envelope`、`sign`）属于 S2，在 Apple 加密目标里比对。
final class SyncVectorsTests: XCTestCase {
    static var fixtureURL: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Fixtures/sync/vectors.json")
    }

    /// S1 负责逐条比对的段落；每段都必须出现、至少通过 1 条、失败为 0。
    static let s1Sections = [
        "json", "decimal", "decimalInvalid", "base64url", "base64urlInvalid", "code", "codeInvalid", "kdf",
        "tagKey", "hlc", "merge", "size", "projection", "ops", "migration", "simulation",
    ]

    func testGoldenVectors() throws {
        let url = Self.fixtureURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            let reason = "金样本 \(url.lastPathComponent) 尚未生成（S0 产出后自动启用），跳过"
            print(reason)
            throw XCTSkip(reason)
        }
        let report = try Vectors.run(url: url)
        for section in Self.s1Sections {
            let c = report.counts[section] ?? (0, 0)
            print("金样本 \(section)：通过 \(c.pass)，失败 \(c.fail)")
            XCTAssertGreaterThan(c.pass, 0, "金样本段 \(section) 没有被比对")
        }
        for f in report.failures.prefix(80) { XCTFail(f) }
    }

    /// 加载器自测：用本端按文档规则自造的小样本（不是伪造的 vectors.json），证明正例能过、负例会失败。
    func testLoaderAcceptsGoodAndCatchesBadSamples() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sync-vectors-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let a: [String: Any] = ["schema": 2, "rooms": ["cb:alice": ["f": "000000000005000000000001+"]], "veto": [:], "settings": [:]]
        let b: [String: Any] = ["schema": 2, "rooms": ["cb:alice": ["b": "000000000006000000000002+"]], "veto": [:], "settings": [:]]
        let ab: [String: Any] = ["schema": 2, "rooms": ["cb:alice": ["f": "000000000005000000000001+", "b": "000000000006000000000002+"]], "veto": [:], "settings": [:]]
        let good: [String: Any] = [
            "json": [["text": "{\"rooms\":{},\"schema\":2,\"settings\":{},\"veto\":{}}", "valid": true], ["text": "{\"schema\":3}", "valid": false, "readonly": true]],
            "base64url": [["text": "-_8", "bytes_hex": "fbff"]],
            "tagKey": [["raw": "Asian", "key": "t:asian"], ["raw": "a b", "local_only": true], ["cps": [0xD800], "local_only": true]],
            "merge": [["a": a, "b": b, "expected": ab]],
            "projection": [["doc": ab, "expected": ["favorites": [], "blocked": [["room": "cb:alice", "name": "alice", "local": true]], "vetoed": [], "settings": [:]]]],
            "hlc": [["state": ["l": 5, "c": 0, "dev": 1], "events": [["type": "local", "pt": 5, "expected": ["l": 5, "c": 1, "dev": 1]]]]],
        ]
        let goodURL = dir.appendingPathComponent("good.json")
        try JSONSerialization.data(withJSONObject: good, options: [.withoutEscapingSlashes]).write(to: goodURL)
        let ok = try Vectors.run(url: goodURL)
        XCTAssertEqual(ok.failures, [])
        XCTAssertEqual(ok.counts.values.map(\.pass).reduce(0, +), 9)

        var bad = good
        bad["merge"] = [["a": a, "b": b, "expected": a]]
        bad["tagKey"] = [["raw": "Asian", "key": "t:Asian"]]
        bad["hlc"] = [["state": ["l": 5, "c": 0, "dev": 1], "events": [["type": "local", "pt": 5, "expected": ["l": 5, "c": 0, "dev": 1]]]]]
        bad["ops"] = [["unexpected": true]]
        let badURL = dir.appendingPathComponent("bad.json")
        try JSONSerialization.data(withJSONObject: bad, options: [.withoutEscapingSlashes]).write(to: badURL)
        XCTAssertEqual(try Vectors.run(url: badURL).failures.count, 4, "负样本必须失败")
    }
}

/// 金样本的解析与比对（形状见 `scripts/sync/vectors/build.go`）。
enum Vectors {
    struct Report {
        var counts: [String: (pass: Int, fail: Int)] = [:]
        var failures: [String] = []
    }

    enum VectorError: Error, CustomStringConvertible {
        case shape(String)
        var description: String { if case .shape(let s) = self { return s }; return "" }
    }

    static func run(url: URL) throws -> Report {
        let data = try Data(contentsOf: url)
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        // `JSONSerialization` 在 Apple 平台上会吞掉字符串开头的 U+FEFF（BOM 样本因此变成合法文本），
        // `json` 段的原文改用 `JSONDecoder` 读取，它逐字保留。
        struct JSONCase: Decodable { let text: String? }
        struct JSONCases: Decodable { let json: [JSONCase]? }
        let jsonTexts = try JSONDecoder().decode(JSONCases.self, from: data).json ?? []
        var report = Report()
        func check(_ section: String, _ body: (Any) throws -> String?) {
            guard let items = root[section] as? [Any] else { return }
            var c = (pass: 0, fail: 0)
            for (i, item) in items.enumerated() {
                let name = (item as? [String: Any])?["name"] as? String ?? (item as? [String: Any])?["reason"] as? String ?? (item as? [String: Any])?["note"] as? String ?? ""
                do {
                    if let problem = try body(item) {
                        c.fail += 1
                        report.failures.append("\(section)[\(i)] \(name)：\(problem)")
                    } else {
                        c.pass += 1
                    }
                } catch {
                    c.fail += 1
                    report.failures.append("\(section)[\(i)] \(name) 格式与加载器不符：\(error)")
                }
            }
            report.counts[section] = c
        }
        func expect(_ ok: Bool, _ message: @autoclosure () -> String) -> String? { ok ? nil : message() }

        var jsonIndex = 0
        check("json") { item in
            var d = try dict(item)
            defer { jsonIndex += 1 }
            if jsonIndex < jsonTexts.count, let text = jsonTexts[jsonIndex].text { d["text"] = text }
            let valid: Bool = try field(d, "valid")
            let readonly = d["readonly"] as? Bool ?? false
            let got = SyncDocument.decode(try bytes(d))
            switch got {
            case .valid: return expect(valid, "期望不合法，实际合法")
            case .newerSchema: return expect(!valid && readonly, "实际只读（schema 更大）")
            case .invalid(let why): return expect(!valid && !readonly, "期望\(valid ? "合法" : "只读")，实际不合法（\(why)）")
            }
        }
        check("decimal") { item in
            let d = try dict(item)
            let want = UInt64(try field(d, "value") as Int)
            let got = SyncEncoding.parseDecimal(try field(d, "text"))
            return expect(got == want, "期望 \(want)，实际 \(String(describing: got))")
        }
        check("decimalInvalid") { item in
            expect(SyncEncoding.parseDecimal(try field(try dict(item), "text")) == nil, "应拒绝")
        }
        check("base64url") { item in
            let d = try dict(item)
            let text: String = try field(d, "text")
            let want = SyncEncoding.decodeHex(try field(d, "bytes_hex"))
            guard let want else { throw VectorError.shape("bytes_hex") }
            return expect(SyncEncoding.decodeBase64url(text) == want && SyncEncoding.base64url(want) == text, "编解码不一致")
        }
        check("base64urlInvalid") { item in
            expect(SyncEncoding.decodeBase64url(try field(try dict(item), "text")) == nil, "应拒绝")
        }
        check("code") { item in
            let d = try dict(item)
            guard let parsed = SyncCode.parse(try field(d, "code"), sha256: TestSHA256.hash) else { return "解析失败" }
            let key = SyncEncoding.decodeHex(try field(d, "K_hex"))
            let origin: String = try field(d, "origin")
            let again = SyncCode.format(key: parsed.key, origin: parsed.origin, sha256: TestSHA256.hash)
            let text: String = try field(d, "code")
            return expect(parsed.key == key && parsed.origin == origin && again == text, "K、origin 或重新写出不一致")
        }
        check("codeInvalid") { item in
            expect(SyncCode.parse(try field(try dict(item), "code"), sha256: TestSHA256.hash) == nil, "应拒绝")
        }
        check("kdf") { item in
            // 派生本身属于 S2（HKDF）；这里比对 S1 的短指纹函数。
            let d = try dict(item)
            let got = SyncCode.fingerprint(blobID: try field(d, "blobID"))
            return expect(got == (try field(d, "fingerprint") as String), "指纹 \(got)")
        }
        check("tagKey") { item in
            let d = try dict(item)
            let localOnly = d["local_only"] as? Bool ?? false
            let raw: String
            if let r = d["raw"] as? String {
                raw = r
            } else {
                let cps: [Int] = try field(d, "cps")
                let scalars = cps.compactMap { Unicode.Scalar(UInt32($0)) }
                // Swift 字符串装不下孤立代理：这类输入在本端不可能出现，按域外处理。
                guard scalars.count == cps.count else { return expect(localOnly, "不可表示的码点应为本机专用") }
                raw = String(String.UnicodeScalarView(scalars))
            }
            let got = SyncKeys.syncTagKey(raw: raw)
            if localOnly { return expect(got == nil, "期望本机专用，实际 \(got ?? "")") }
            let want: String = try field(d, "key")
            return expect(got.map { Array($0.utf8) } == Array(want.utf8), "期望 \(want)，实际 \(got ?? "nil")")
        }
        check("hlc") { item in
            let d = try dict(item)
            for b in (d["bounds"] as? [Any]) ?? [] {
                let bd = try dict(b)
                let valid: Bool = try field(bd, "valid")
                let got = HLC(hex: try field(bd, "wire") as String)?.isStaticallyValid ?? false
                if got != valid { return "界限 \(bd["wire"] ?? "") 期望 \(valid)" }
            }
            var replica = SyncReplica(clock: try clock(try dict(d["state"] as Any)))
            for e in try field(d, "events") as [Any] {
                let ev = try dict(e)
                switch try field(ev, "type") as String {
                case "local":
                    _ = replica.clock.tick(pt: UInt64(try field(ev, "pt") as Int))
                case "receive":
                    replica.clock.receive(try docValue(ev["doc"] as Any).maxHLC)
                case "presend":
                    let n = UInt64(try field(ev, "N") as Int)
                    replica.doc = try docValue(ev["doc"] as Any)
                    let result = SyncRestamp.presend(&replica, serverNow: n, maxSentL: UInt64(try field(ev, "maxSentL") as Int), pt: n)
                    if (result == .blockedBySent) != (try field(ev, "diagnostic") as Bool) { return "诊断标记不一致：\(result)" }
                    if replica.doc != (try docValue(ev["expected_doc"] as Any)) { return "重新盖章后的文档不一致：\(SyncFixture.text(replica.doc))" }
                default:
                    throw VectorError.shape("未知事件")
                }
                let want = try clock(try dict((ev["expected"] ?? ev["expected_state"]) as Any))
                if replica.clock != want { return "时钟期望 \(want)，实际 \(replica.clock)" }
            }
            return nil
        }
        check("merge") { item in
            let d = try dict(item)
            let got = SyncMerge.fitSize(SyncMerge.union(try docValue(d["a"] as Any), try docValue(d["b"] as Any))).doc
            return expect(got == (try docValue(d["expected"] as Any)), "合并结果 \(SyncFixture.text(got).prefix(200))")
        }
        check("size") { item in
            let d = try dict(item)
            let p: Int = try field(d, "p")
            guard let docValueRaw = d["doc"] else {
                // 实测汇总：与同段文档的实测字节数一致。
                let docs = (root["size"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }.filter { $0["doc"] != nil }
                let sizes = try docs.map { try docValue($0["doc"] as Any).encodedSize }
                let with: Int = try field(d, "with_tombstones"), live: Int = try field(d, "live_only")
                return expect(sizes.contains(with) && sizes.contains(live) && p == SyncLimits.plaintextBytes, "汇总 \(sizes)")
            }
            let doc = try docValue(docValueRaw)
            let bytes: Int = try field(d, "bytes")
            if doc.encodedSize != bytes || doc.encoded().count != bytes { return "实测 \(doc.encodedSize)，期望 \(bytes)" }
            let fitted = SyncMerge.fitSize(doc, limit: p)
            let result: String = try field(d, "result")
            let removed = doc.rooms.count + doc.veto.count - fitted.doc.rooms.count - fitted.doc.veto.count
            if removed != (try field(d, "tombstones_removed") as Int) { return "删除墓碑数 \(removed)" }
            if result == "reject" { return expect(!fitted.fits, "应拒绝") }
            let expected = try docValue(d["expected"] as Any)
            return expect(fitted.fits && fitted.doc == expected, "体积处理结果不一致")
        }
        check("projection") { item in
            let d = try dict(item)
            let p = SyncProjection(try docValue(d["doc"] as Any))
            let e = try dict(d["expected"] as Any)
            func entries(_ name: String, _ got: [SyncProjection.Entry]) throws -> String? {
                let want = try (field(e, name) as [Any]).map { try dict($0) }
                let wantKeys: [String] = try want.map { try field($0, "room") }
                let wantNames: [String] = try want.map { try field($0, "name") }
                let wantLocal: [Bool] = try want.map { try field($0, "local") }
                return expect(wantKeys == got.map(\.key) && wantNames == got.map(\.display) && wantLocal == got.map { SyncKeys.isProjected($0.key) }, "\(name) 不一致：\(got)")
            }
            if let problem = try entries("favorites", p.favorites) { return problem }
            if let problem = try entries("blocked", p.blocked) { return problem }
            let vetoed: [String] = try field(e, "vetoed")
            if Set(vetoed) != Set(p.veto.filter(\.value).keys) { return "vetoed 不一致" }
            let settings = try (field(e, "settings") as [String: Any]).mapValues { ($0 as? String) == "1" }
            return expect(settings == p.settings, "settings 不一致")
        }
        check("ops") { item in
            let d = try dict(item)
            var replica = SyncReplica(doc: try docValue(d["doc"] as Any), clock: try clock(try dict(d["hlc"] as Any)))
            let pt = UInt64(try field(d, "pt") as Int)
            for (i, o) in (try field(d, "ops") as [Any]).enumerated() {
                let od = try dict(o)
                let wrote = apply(try op(od), to: &replica, pt: pt)
                if wrote != (try field(od, "wrote") as Bool) { return "第 \(i) 步 \(od["fn"] ?? "") 写入标记不一致" }
                if replica.doc != (try docValue(od["doc"] as Any)) { return "第 \(i) 步 \(od["fn"] ?? "") 文档不一致：\(SyncFixture.text(replica.doc).prefix(300))" }
                if replica.clock != (try clock(try dict(od["hlc"] as Any))) { return "第 \(i) 步时钟不一致" }
            }
            if replica.clock != (try clock(try dict(d["expected_hlc"] as Any))) { return "最终时钟不一致" }
            return expect(replica.doc == (try docValue(d["expected"] as Any)), "最终文档不一致")
        }
        check("migration") { item in
            let d = try dict(item)
            let settings = ((d["settings"] as? [String: Any]) ?? [:]).mapValues { ($0 as? String) == "1" }
            let local = SyncLocalData(
                curation: Curation(favorites: (d["favorites"] as? [String]) ?? [], blocked: (d["blocked"] as? [String]) ?? []),
                vetoedKeys: (d["vetoed"] as? [String]) ?? [], explicitSettings: settings
            )
            let c = SyncClock(dev: UInt32(try field(d, "dev") as Int))
            let pt = UInt64(try field(d, "pt") as Int)
            let counts = try dict(d["counts"] as Any)
            let got: (SyncDocument, SyncJoinPreview)
            if let remote = d["remote"], !(remote is NSNull) {
                let r = SyncMigration.stampJoin(local, remote: try docValue(remote), clock: c, pt: pt)
                got = (r.replica.doc, r.preview)
            } else {
                // 创建方：N/M/V 为盖章数，X/Y 为 E 裁掉的收藏与屏蔽（第 23 节第 6 条同口径）。
                let r = SyncMigration.stampJoin(local, remote: .empty, clock: c, pt: pt)
                let created = SyncMigration.stampCreator(local, clock: c, pt: pt)
                if created.replica.doc != r.replica.doc { return "创建方盖章与空远端加入不一致" }
                got = (created.replica.doc, r.preview)
            }
            let p = got.1
            let have = ["N": p.n, "M": p.m, "V": p.v, "X": p.x, "Y": p.y, "Z": p.z]
            for (k, v) in have where (counts[k] as? Int) != v { return "计数 \(k) 期望 \(counts[k] ?? "nil")，实际 \(v)" }
            return expect(got.0 == (try docValue(d["expected"] as Any)), "盖章结果不一致：\(SyncFixture.text(got.0).prefix(300))")
        }
        check("simulation") { item in
            // 回放 S0 物化的脚本：op 步按本地函数映射；merge 步先让离开设备的一方执行发送前重新盖章并记已发出
            //（第 23 节第 5 条），再合并、写回 i 或双方。每步之后各副本文档都要与期望相等。
            let d = try dict(item)
            let n: Int = try field(d, "replicas")
            var reps = (0..<n).map { SyncReplica(clock: SyncClock(dev: UInt32($0 + 1))) }
            var sent = [UInt64](repeating: 0, count: n)
            func maxL(_ doc: SyncDocument) -> UInt64 { doc.allRegisters.map(\.hlc.l).max() ?? 0 }
            for (k, s) in (try field(d, "steps") as [Any]).enumerated() {
                let st = try dict(s)
                switch try field(st, "kind") as String {
                case "op":
                    let i: Int = try field(st, "replica")
                    _ = apply(try op(st), to: &reps[i], pt: UInt64(try field(st, "pt") as Int))
                case "merge":
                    let i: Int = try field(st, "i"), j: Int = try field(st, "j")
                    let write: String = try field(st, "write")
                    let now = UInt64(try field(st, "now") as Int)
                    let leaving = write == "both" ? [i, j] : write == "i" ? [j] : [i]
                    for s in leaving {
                        _ = SyncRestamp.presend(&reps[s], serverNow: now, maxSentL: sent[s], pt: now)
                        sent[s] = max(sent[s], maxL(reps[s].doc))
                    }
                    let di = reps[i].doc, dj = reps[j].doc
                    let merged = SyncMerge.fitSize(SyncMerge.union(di, dj)).doc
                    if write != "j" {
                        reps[i].clock.receive(dj.maxHLC)
                        reps[i].doc = merged
                    }
                    if write != "i" {
                        reps[j].clock.receive(di.maxHLC)
                        reps[j].doc = merged
                    }
                default:
                    throw VectorError.shape("未知步骤")
                }
                let docs = try (field(st, "docs") as [Any]).map(docValue)
                for (r, want) in docs.enumerated() where reps[r].doc != want {
                    return "第 \(k) 步后副本 \(r) 不一致：\(SyncFixture.text(reps[r].doc).prefix(300))"
                }
            }
            let final = try docValue(d["final"] as Any)
            return expect(reps.allSatisfy { $0.doc == final }, "最终文档不一致")
        }
        return report
    }

    /// 应用一个本地操作，返回是否写入了寄存器。
    static func apply(_ op: SyncLocalOp?, to replica: inout SyncReplica, pt: UInt64) -> Bool {
        guard let op, case .applied(let next) = SyncOps.apply(op, to: replica, pt: pt) else { return false }
        replica = next
        return true
    }

    static func dict(_ v: Any) throws -> [String: Any] {
        guard let d = v as? [String: Any] else { throw VectorError.shape("不是对象") }
        return d
    }

    static func field<T>(_ d: [String: Any], _ key: String) throws -> T {
        guard let v = d[key] as? T else { throw VectorError.shape("缺字段 \(key)") }
        return v
    }

    /// `text`，或二进制样本的 `bytes_hex`。
    static func bytes(_ d: [String: Any]) throws -> [UInt8] {
        if let s = d["text"] as? String { return Array(s.utf8) }
        if let h = d["bytes_hex"] as? String, let b = SyncEncoding.decodeHex(h) { return b }
        throw VectorError.shape("缺字段 text / bytes_hex")
    }

    static func docValue(_ v: Any) throws -> SyncDocument {
        let raw: [UInt8]
        if let s = v as? String {
            raw = Array(s.utf8)
        } else if v is [String: Any] {
            raw = Array(try JSONSerialization.data(withJSONObject: v, options: [.withoutEscapingSlashes]))
        } else {
            throw VectorError.shape("缺文档")
        }
        switch SyncDocument.decode(raw) {
        case .valid(let doc): return doc
        case .newerSchema: throw VectorError.shape("文档 schema 更大")
        case .invalid(let why): throw VectorError.shape("文档不合法（\(why)）")
        }
    }

    static func clock(_ d: [String: Any]) throws -> SyncClock {
        SyncClock(l: UInt64(try field(d, "l") as Int), c: UInt16(try field(d, "c") as Int), dev: UInt32(try field(d, "dev") as Int))
    }

    /// S0 的函数名 → 第 6.4 节操作；不写寄存器的函数（`marking`、`rejecting` 等）返回 nil。
    static func op(_ d: [String: Any]) throws -> SyncLocalOp? {
        let fn: String = try field(d, "fn")
        let name = d["name"] as? String ?? ""
        let key = d["key"] as? String ?? ""
        switch fn {
        case "Curation.favoriting": return .favorite(name)
        case "Curation.restoringFavorite": return .restoreFavorite(name)
        case "Curation.unfavoriting": return .unfavorite(name)
        case "Curation.blocking": return .block(name)
        case "Curation.unblocking": return .unblock(name)
        case "Curation.importing":
            guard let names = d["names"] as? [String] else { throw VectorError.shape("导入参数") }
            return .importing(names)
        case "Taste.vetoing": return .veto(key)
        case "Taste.restoring", "Taste.boosting", "Taste.removing": return .unveto(key)
        case "Taste.resettingProfile", "Taste.erased": return .clearVetoes
        case "settings": return .setting(key, (try field(d, "value") as String) == "1")
        case "marking", "rejecting", "following", "recording", "settingPaused": return nil
        default: throw VectorError.shape("未知操作 \(fn)")
        }
    }
}
