import Foundation
import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 文件快照：同目录、0600，换回用原子 rename；原本不存在的文件换回后删除；设置按快照恢复（未设过的键恢复成未设置）。
final class SyncFileJournalTests: XCTestCase {
    func testSnapshotAndRestoreRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("journal-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let curation = dir.appendingPathComponent("feed-curation.json"), taste = dir.appendingPathComponent("feed-taste.json")
        try Data("old-curation".utf8).write(to: curation)
        var settings: [String: Bool] = ["publicOnly": false]
        let journal = FileJoinJournal(dir: dir.appendingPathComponent("sync-join-snapshot"), files: [curation, taste],
                                      settingKeys: ["publicOnly", "infoAlwaysOn"],
                                      readSettings: { settings },
                                      writeSettings: { values in for (k, v) in values { settings[k] = v } })
        XCTAssertFalse(journal.exists)
        XCTAssertTrue(journal.snapshot())
        XCTAssertTrue(journal.exists)
        let attrs = try FileManager.default.attributesOfItem(atPath: journal.dir.appendingPathComponent("feed-curation.json").path)
        XCTAssertEqual((attrs[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        try Data("projected".utf8).write(to: curation)
        try Data("new-taste".utf8).write(to: taste)
        settings = ["publicOnly": true, "infoAlwaysOn": true]
        XCTAssertTrue(journal.restore())
        XCTAssertEqual(try String(contentsOf: curation, encoding: .utf8), "old-curation")
        XCTAssertFalse(FileManager.default.fileExists(atPath: taste.path), "原本没有的文件换回后删除")
        XCTAssertEqual(settings, ["publicOnly": false])
        XCTAssertTrue(journal.restore(), "换回可重复执行")
        XCTAssertEqual(try String(contentsOf: curation, encoding: .utf8), "old-curation")
        XCTAssertEqual(journal.status, .pending, "换回不删快照：收尾完成后由调用方删除")
        XCTAssertTrue(journal.discard())
        XCTAssertFalse(journal.exists)
        XCTAssertFalse(journal.restore(), "没有快照时拒绝换回")

        // 已提交的快照只能删除，不能换回。
        XCTAssertTrue(journal.snapshot())
        XCTAssertTrue(journal.markCommitted())
        try Data("projected".utf8).write(to: curation)
        XCTAssertFalse(journal.restore())
        XCTAssertEqual(try String(contentsOf: curation, encoding: .utf8), "projected")
        XCTAssertTrue(journal.discard())
    }

    /// 审查待办：换回文件后删快照中途中断，若 `settings.json` 已删而 `complete` 仍在，
    /// 重启不得永久停在 `.pending` 且 `restore()` 一直失败，必须能收敛到已清掉快照。
    func testInterruptedDiscardMissingSettingsConvergesOnRestart() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("journal-stuck-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let curation = dir.appendingPathComponent("feed-curation.json")
        try Data("old-curation".utf8).write(to: curation)
        var settings: [String: Bool] = ["publicOnly": false]
        let makeJournal = {
            FileJoinJournal(dir: dir.appendingPathComponent("sync-join-snapshot"), files: [curation],
                            settingKeys: ["publicOnly", "infoAlwaysOn"],
                            readSettings: { settings },
                            writeSettings: { values in for (k, v) in values { settings[k] = v } })
        }
        let j1 = makeJournal()
        XCTAssertTrue(j1.snapshot())
        try Data("projected".utf8).write(to: curation)
        settings = ["publicOnly": true, "infoAlwaysOn": true]
        XCTAssertTrue(j1.restore())
        // 模拟删快照中途先删掉了 settings.json 后进程中断（complete 仍在）。
        try FileManager.default.removeItem(at: j1.dir.appendingPathComponent("settings.json"))

        // 重启后按 SyncService.start() 规则收尾：不得永久卡在 restore() == false。
        let restarted = makeJournal()
        switch restarted.status {
        case .none: break
        case .incomplete, .committed:
            XCTAssertTrue(restarted.discard())
        case .pending:
            XCTAssertTrue(restarted.restore(), "不应永久卡死在需要修复")
            XCTAssertTrue(restarted.discard())
        }
        XCTAssertEqual(restarted.status, .none)
        XCTAssertEqual(try String(contentsOf: curation, encoding: .utf8), "old-curation")
        XCTAssertEqual(settings, ["publicOnly": false])
    }

    /// 验收 2：覆盖「既有文件 + 原本不存在文件」场景，对换回与删快照流程里的每一个文件操作步骤逐一注入中断，
    /// 验证每处中断后重启都能收敛到「已换回并清掉快照」，无永久卡死状态。
    func testRollbackConvergesAfterFaultAtEveryFileStep_ExistingAndAbsent() throws {
        let trace = try runRollbackScenario(tasteInitiallyExists: false, failAtStep: nil)
        XCTAssertEqual(trace.steps, [
            "rename:feed-curation.json",
            "remove:feed-taste.json",
            "remove:absent-feed-taste.json",
            "read:settings.json",
            "remove:complete",
            "remove:settings.json",
            "remove:sync-join-snapshot",
        ], "删快照必须先删 complete 再删 settings.json")
        for step in 0..<trace.steps.count {
            let outcome = try runRollbackScenario(tasteInitiallyExists: false, failAtStep: step)
            XCTAssertFalse(outcome.firstAttemptCompleted, "第 \(step) 步（\(trace.steps[step])）必须触发失败")
            XCTAssertEqual(outcome.finalStatus, .none, "第 \(step) 步（\(trace.steps[step])）中断后重启须收敛到 .none")
            XCTAssertEqual(outcome.finalCuration, "old-curation", "第 \(step) 步（\(trace.steps[step])）")
            XCTAssertNil(outcome.finalTaste, "第 \(step) 步（\(trace.steps[step])）：原本没有的文件须已删除")
            XCTAssertEqual(outcome.finalSettings, ["publicOnly": false], "第 \(step) 步（\(trace.steps[step])）")
        }
    }

    /// 验收 2：覆盖「两个文件原本都存在」场景，逐个文件操作步骤注入中断并验证重启收敛。
    func testRollbackConvergesAfterFaultAtEveryFileStep_TwoExistingFiles() throws {
        let trace = try runRollbackScenario(tasteInitiallyExists: true, failAtStep: nil)
        XCTAssertEqual(trace.steps, [
            "rename:feed-curation.json",
            "rename:feed-taste.json",
            "read:settings.json",
            "remove:complete",
            "remove:settings.json",
            "remove:sync-join-snapshot",
        ])
        for step in 0..<trace.steps.count {
            let outcome = try runRollbackScenario(tasteInitiallyExists: true, failAtStep: step)
            XCTAssertFalse(outcome.firstAttemptCompleted, "第 \(step) 步（\(trace.steps[step])）必须触发失败")
            XCTAssertEqual(outcome.finalStatus, .none, "第 \(step) 步（\(trace.steps[step])）中断后重启须收敛到 .none")
            XCTAssertEqual(outcome.finalCuration, "old-curation", "第 \(step) 步（\(trace.steps[step])）")
            XCTAssertEqual(outcome.finalTaste, "old-taste", "第 \(step) 步（\(trace.steps[step])）")
            XCTAssertEqual(outcome.finalSettings, ["publicOnly": false], "第 \(step) 步（\(trace.steps[step])）")
        }
    }

    /// 已提交快照在删除的任意一步中断，重启后绝不能退回 `.pending`（不得换回旧文件或旧设置），只能继续删干净。
    func testCommittedDiscardNeverRevertsToPendingAtAnyStep() throws {
        func run(failAtStep: Int?) throws -> (steps: [String], statusAfterFault: SyncJoinJournalStatus, finalStatus: SyncJoinJournalStatus, curation: String, settings: [String: Bool]) {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("journal-commit-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: dir) }
            let curation = dir.appendingPathComponent("feed-curation.json")
            try Data("old-curation".utf8).write(to: curation)
            var settings: [String: Bool] = ["publicOnly": false]
            let make = {
                FileJoinJournal(dir: dir.appendingPathComponent("sync-join-snapshot"), files: [curation],
                                settingKeys: ["publicOnly", "infoAlwaysOn"],
                                readSettings: { settings },
                                writeSettings: { values in for (k, v) in values { settings[k] = v } })
            }
            let j = make()
            XCTAssertTrue(j.snapshot())
            XCTAssertTrue(j.markCommitted())
            try Data("projected".utf8).write(to: curation)
            settings = ["publicOnly": true, "infoAlwaysOn": true]

            var steps: [String] = []
            let origRemove = j.removeItem
            j.removeItem = { url in
                let idx = steps.count
                steps.append("remove:" + url.lastPathComponent)
                if failAtStep == idx { return false }
                return origRemove(url)
            }
            let ok = j.discard()
            if failAtStep != nil { XCTAssertFalse(ok) } else { XCTAssertTrue(ok) }

            let restarted = make()
            let afterFault = restarted.status
            XCTAssertNotEqual(afterFault, .pending, "已提交快照删到一半绝不能退回 .pending")
            if afterFault != .none { XCTAssertTrue(restarted.discard()) }
            return (steps, afterFault, restarted.status, try String(contentsOf: curation, encoding: .utf8), settings)
        }

        let base = try run(failAtStep: nil)
        XCTAssertEqual(base.steps, [
            "remove:complete",
            "remove:feed-curation.json",
            "remove:settings.json",
            "remove:committed",
            "remove:sync-join-snapshot",
        ], "committed 必须在 settings.json 之后删除，避免退回 .pending")
        for step in 0..<base.steps.count {
            let r = try run(failAtStep: step)
            XCTAssertEqual(r.finalStatus, .none, "第 \(step) 步（\(base.steps[step])）")
            XCTAssertEqual(r.curation, "projected", "第 \(step) 步（\(base.steps[step])）：不换回旧文件")
            XCTAssertEqual(r.settings, ["publicOnly": true, "infoAlwaysOn": true], "第 \(step) 步（\(base.steps[step])）：不换回旧设置")
        }
    }

    private struct RollbackRun {
        var steps: [String]
        var firstAttemptCompleted: Bool
        var finalStatus: SyncJoinJournalStatus
        var finalCuration: String
        var finalTaste: String?
        var finalSettings: [String: Bool]
    }

    private func runRollbackScenario(tasteInitiallyExists: Bool, failAtStep: Int?, dropFirstFlush: Bool = false) throws -> RollbackRun {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("journal-step-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let curation = dir.appendingPathComponent("feed-curation.json")
        let taste = dir.appendingPathComponent("feed-taste.json")
        try Data("old-curation".utf8).write(to: curation)
        if tasteInitiallyExists { try Data("old-taste".utf8).write(to: taste) }
        var persisted: [String: Bool] = ["publicOnly": false]
        var cached: [String: Bool] = persisted
        var dropFlush = false

        let make = {
            FileJoinJournal(dir: dir.appendingPathComponent("sync-join-snapshot"), files: [curation, taste],
                            settingKeys: ["publicOnly", "infoAlwaysOn"],
                            readSettings: { cached },
                            writeSettings: { values in
                                for (k, v) in values { cached[k] = v }
                                if !dropFlush { persisted = cached }
                            })
        }

        let j = make()
        XCTAssertTrue(j.snapshot())
        try Data("projected-curation".utf8).write(to: curation)
        try Data("projected-taste".utf8).write(to: taste)
        persisted = ["publicOnly": true, "infoAlwaysOn": true]
        cached = persisted
        dropFlush = dropFirstFlush

        var steps: [String] = []
        let origRename = j.renameItem
        let origRemove = j.removeItem
        let origRead = j.readFile
        j.renameItem = { from, to in
            let idx = steps.count
            steps.append("rename:" + to.lastPathComponent)
            if failAtStep == idx { return false }
            return origRename(from, to)
        }
        j.removeItem = { url in
            let idx = steps.count
            steps.append("remove:" + url.lastPathComponent)
            if failAtStep == idx { return false }
            return origRemove(url)
        }
        j.readFile = { url in
            let idx = steps.count
            steps.append("read:" + url.lastPathComponent)
            if failAtStep == idx { return nil }
            return origRead(url)
        }

        let completed = j.restore() && j.discard()

        // 模拟进程被杀后重启：内存设置缓存丢失（仅保留已落盘值），新造实例按 SyncService.start() 规则收尾。
        cached = persisted
        dropFlush = false
        let restarted = make()
        switch restarted.status {
        case .none:
            break
        case .incomplete, .committed:
            XCTAssertTrue(restarted.discard())
        case .pending:
            XCTAssertTrue(restarted.restore())
            XCTAssertTrue(restarted.discard())
        }

        let finalTaste: String? = FileManager.default.fileExists(atPath: taste.path)
            ? try String(contentsOf: taste, encoding: .utf8)
            : nil
        return RollbackRun(
            steps: steps,
            firstAttemptCompleted: completed,
            finalStatus: restarted.status,
            finalCuration: try String(contentsOf: curation, encoding: .utf8),
            finalTaste: finalTaste,
            finalSettings: persisted
        )
    }

    /// 审计 high：换回后删快照若在「complete 已删、UserDefaults 设置尚未刷盘」之间被杀进程，
    /// 残留的 `settings.json`（以及任何未换回的文件副本）仍须被重启流程重放并落盘，不能只删目录把投影设置留在磁盘上。
    func testCompleteDeletedBeforeSettingsFlushReplaysSettingsOnRestart() throws {
        // 覆盖「仅剩 settings.json」以及「complete 已删但 settings.json 仍在」两种中断点，且首次写设置未刷盘。
        let trace = try runRollbackScenario(tasteInitiallyExists: false, failAtStep: nil)
        let settingsRemoveStep = try XCTUnwrap(trace.steps.firstIndex(of: "remove:settings.json"))
        for step in 0...settingsRemoveStep {
            let outcome = try runRollbackScenario(tasteInitiallyExists: false, failAtStep: step, dropFirstFlush: true)
            XCTAssertFalse(outcome.firstAttemptCompleted, "第 \(step) 步（\(trace.steps[step])）")
            XCTAssertEqual(outcome.finalStatus, .none, "第 \(step) 步（\(trace.steps[step])）")
            XCTAssertEqual(outcome.finalCuration, "old-curation", "第 \(step) 步（\(trace.steps[step])）")
            XCTAssertNil(outcome.finalTaste, "第 \(step) 步（\(trace.steps[step])）")
            XCTAssertEqual(outcome.finalSettings, ["publicOnly": false], "第 \(step) 步（\(trace.steps[step])）：未刷盘被杀重启须重放设置")
        }
    }

    /// `settings.json` 损坏不可解码时按 `.incomplete` 只删不换回，不会永久卡在需要修复。
    func testCorruptSettingsJsonIsIncompleteAndConverges() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("journal-corrupt-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let curation = dir.appendingPathComponent("feed-curation.json")
        try Data("old-curation".utf8).write(to: curation)
        var settings: [String: Bool] = ["publicOnly": false]
        let j = FileJoinJournal(dir: dir.appendingPathComponent("sync-join-snapshot"), files: [curation],
                                settingKeys: ["publicOnly", "infoAlwaysOn"],
                                readSettings: { settings },
                                writeSettings: { values in for (k, v) in values { settings[k] = v } })
        XCTAssertTrue(j.snapshot())
        try Data("{corrupt".utf8).write(to: j.dir.appendingPathComponent(FileJoinJournal.settingsFile))
        XCTAssertEqual(j.status, .incomplete)
        XCTAssertTrue(j.discard())
        XCTAssertEqual(j.status, .none)
    }
}
