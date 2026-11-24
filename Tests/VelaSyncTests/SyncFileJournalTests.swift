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
                                      writeSettings: { values in
                                          for (k, v) in values { settings[k] = v }
                                          return true
                                      })
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
                            writeSettings: { values in
                                for (k, v) in values { settings[k] = v }
                                return true
                            })
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
        XCTAssertEqual(restarted.status, .incomplete)
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

    /// 验收 2：覆盖「既有文件 + 原本不存在文件」场景，对换回与删快照流程里的每一个文件操作步骤（含列目录）逐一注入中断，
    /// 验证每处中断后重启都能收敛到「已换回并清掉快照」，无永久卡死状态。
    func testRollbackConvergesAfterFaultAtEveryFileStep_ExistingAndAbsent() throws {
        let trace = try runRollbackScenario(tasteInitiallyExists: false, failAtStep: nil)
        XCTAssertEqual(trace.steps, [
            "rename:feed-curation.json",
            "remove:feed-taste.json",
            "remove:absent-feed-taste.json",
            "read:settings.json",
            "list:sync-join-snapshot",
            "remove:settings.json",
            "remove:complete",
            "remove:sync-join-snapshot",
        ], "未提交快照删除顺序：列目录 → 删 settings.json → 删 complete → 删目录")
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
            "list:sync-join-snapshot",
            "remove:settings.json",
            "remove:complete",
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
                                writeSettings: { values in
                                    for (k, v) in values { settings[k] = v }
                                    return true
                                })
            }
            let j = make()
            XCTAssertTrue(j.snapshot())
            XCTAssertTrue(j.markCommitted())
            try Data("projected".utf8).write(to: curation)
            settings = ["publicOnly": true, "infoAlwaysOn": true]

            var steps: [String] = []
            let origList = j.listDirectory
            let origRemove = j.removeItem
            j.listDirectory = { url in
                let idx = steps.count
                steps.append("list:" + url.lastPathComponent)
                if failAtStep == idx { return nil }
                return origList(url)
            }
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
            "list:sync-join-snapshot",
            "remove:complete",
            "remove:feed-curation.json",
            "remove:settings.json",
            "remove:committed",
            "remove:sync-join-snapshot",
        ], "committed 必须在 settings.json 与 complete 之后删除，避免退回 .pending")
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
        var statusAfterFault: SyncJoinJournalStatus
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
                                if dropFlush { return false }
                                persisted = cached
                                return true
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
        let origList = j.listDirectory
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
        j.listDirectory = { url in
            let idx = steps.count
            steps.append("list:" + url.lastPathComponent)
            if failAtStep == idx { return nil }
            return origList(url)
        }

        let completed = j.restore() && j.discard()

        // 模拟进程被杀后重启：内存设置缓存丢失（仅保留已落盘值），新造实例按 SyncService.start() 规则收尾。
        cached = persisted
        dropFlush = false
        let restarted = make()
        let afterFault = restarted.status
        switch afterFault {
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
            statusAfterFault: afterFault,
            finalStatus: restarted.status,
            finalCuration: try String(contentsOf: curation, encoding: .utf8),
            finalTaste: finalTaste,
            finalSettings: persisted
        )
    }

    /// 审计 A/B：设置刷盘失败（`writeSettings` 返回 false）必须由 `restore()` 传播出来，
    /// 不得丢弃快照，快照保持 `.pending`（可再次换回）；重启后刷盘恢复即完整换回并清理。
    func testRestorePropagatesWriteSettingsFailureAndKeepsSnapshotPending() throws {
        let outcome = try runRollbackScenario(tasteInitiallyExists: false, failAtStep: nil, dropFirstFlush: true)
        XCTAssertFalse(outcome.firstAttemptCompleted, "设置刷盘失败时 restore() 必须返回 false，不得进入 discard()")
        XCTAssertEqual(outcome.statusAfterFault, .pending, "刷盘失败不得丢弃快照，状态保持可再次换回")
        XCTAssertEqual(outcome.finalStatus, .none)
        XCTAssertEqual(outcome.finalCuration, "old-curation")
        XCTAssertNil(outcome.finalTaste)
        XCTAssertEqual(outcome.finalSettings, ["publicOnly": false], "重启重试后设置须完整恢复")
    }

    /// 审计 C：列目录失败时 `discard()` 必须直接返回 false，绝不退回无序的递归删除。
    func testDiscardFailsWithoutUnorderedDeleteWhenListDirectoryFails() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("journal-listfail-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let curation = dir.appendingPathComponent("feed-curation.json")
        try Data("old-curation".utf8).write(to: curation)
        var settings: [String: Bool] = ["publicOnly": false]
        let j = FileJoinJournal(dir: dir.appendingPathComponent("sync-join-snapshot"), files: [curation],
                                settingKeys: ["publicOnly", "infoAlwaysOn"],
                                readSettings: { settings },
                                writeSettings: { values in
                                    for (k, v) in values { settings[k] = v }
                                    return true
                                })
        XCTAssertTrue(j.snapshot())
        var removedCount = 0
        j.listDirectory = { _ in nil }
        j.removeItem = { _ in removedCount += 1; return true }
        XCTAssertFalse(j.discard(), "列目录失败必须直接返回 false")
        XCTAssertEqual(removedCount, 0, "不得退回无序删除")
        XCTAssertEqual(j.status, .pending, "目录与关键标记完好保留")
    }

    /// 审计 D（情形 1）：创建阶段中断留下的半份快照，哪怕已经写了部分文件与合法的 `settings.json`，
    /// 只要缺少 `complete` 完整性标记就必须判 `.incomplete`：绝不得重放，只能丢弃。
    func testHalfBuiltSnapshotWithSettingsButNoCompleteIsIncompleteAndNeverReplayed() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("journal-half-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let curation = dir.appendingPathComponent("feed-curation.json")
        try Data("current-curation".utf8).write(to: curation)
        let snapDir = dir.appendingPathComponent("sync-join-snapshot")
        try FileManager.default.createDirectory(at: snapDir, withIntermediateDirectories: true)
        // 半份快照：已有旧收藏副本与合法 settings.json，但从未写 complete 标记。
        try Data("stale-curation".utf8).write(to: snapDir.appendingPathComponent("feed-curation.json"))
        try JSONEncoder().encode(["publicOnly": false]).write(to: snapDir.appendingPathComponent(FileJoinJournal.settingsFile))

        var settings: [String: Bool] = ["publicOnly": true, "infoAlwaysOn": true]
        let restarted = FileJoinJournal(dir: snapDir, files: [curation],
                                        settingKeys: ["publicOnly", "infoAlwaysOn"],
                                        readSettings: { settings },
                                        writeSettings: { values in
                                            for (k, v) in values { settings[k] = v }
                                            return true
                                        })
        XCTAssertEqual(restarted.status, .incomplete, "缺 complete 标记的半份快照不得判 .pending")
        XCTAssertFalse(restarted.restore(), "半份快照绝不得重放")
        XCTAssertTrue(restarted.discard())
        XCTAssertEqual(restarted.status, .none)
        XCTAssertEqual(try String(contentsOf: curation, encoding: .utf8), "current-curation", "不得用残缺副本覆盖本机文件")
        XCTAssertEqual(settings, ["publicOnly": true, "infoAlwaysOn": true], "不得用残缺快照覆盖本机设置")
    }

    /// 审计 D（情形 2）：快照曾经完整、正在丢弃途中，删 `settings.json` 前中断仍为 `.pending` 可重放，
    /// 删 `settings.json` 后中断（设置已刷盘）转为 `.incomplete` 安全清理，任一步均收敛且不丢设置。
    func testCompleteSnapshotMidDiscardRemainsPendingBeforeSettingsRemovedThenConverges() throws {
        let trace = try runRollbackScenario(tasteInitiallyExists: false, failAtStep: nil)
        let settingsRemoveStep = try XCTUnwrap(trace.steps.firstIndex(of: "remove:settings.json"))
        let completeRemoveStep = try XCTUnwrap(trace.steps.firstIndex(of: "remove:complete"))
        XCTAssertLessThan(settingsRemoveStep, completeRemoveStep, "未提交快照须先删 settings.json 再删 complete")

        // 删 settings.json 之前中断：complete 与 settings.json 都在，保持 .pending 可再次换回。
        let beforeSettings = try runRollbackScenario(tasteInitiallyExists: false, failAtStep: settingsRemoveStep)
        XCTAssertEqual(beforeSettings.statusAfterFault, .pending)
        XCTAssertEqual(beforeSettings.finalStatus, .none)
        XCTAssertEqual(beforeSettings.finalSettings, ["publicOnly": false])

        // 删 settings.json 之后、删 complete 之前中断：设置已刷盘，转为 .incomplete，重启安全删干净。
        let afterSettings = try runRollbackScenario(tasteInitiallyExists: false, failAtStep: completeRemoveStep)
        XCTAssertEqual(afterSettings.statusAfterFault, .incomplete)
        XCTAssertEqual(afterSettings.finalStatus, .none)
        XCTAssertEqual(afterSettings.finalSettings, ["publicOnly": false])
    }

    /// 审计 E：`settings.json` 损坏不可解码时重启按 `.incomplete` 只删不换回，不会覆盖现有数据也不会永久卡在需要修复。
    func testCorruptSettingsJsonIsIncompleteAndConverges() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("journal-corrupt-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let curation = dir.appendingPathComponent("feed-curation.json")
        try Data("old-curation".utf8).write(to: curation)
        var settings: [String: Bool] = ["publicOnly": false]
        let make = {
            FileJoinJournal(dir: dir.appendingPathComponent("sync-join-snapshot"), files: [curation],
                            settingKeys: ["publicOnly", "infoAlwaysOn"],
                            readSettings: { settings },
                            writeSettings: { values in
                                for (k, v) in values { settings[k] = v }
                                return true
                            })
        }
        let j = make()
        XCTAssertTrue(j.snapshot())
        // 快照后本机已被新数据改写，随后快照里的 settings.json 损坏。
        try Data("projected".utf8).write(to: curation)
        settings = ["publicOnly": true, "infoAlwaysOn": true]
        try Data("{corrupt".utf8).write(to: j.dir.appendingPathComponent(FileJoinJournal.settingsFile))

        let restarted = make()
        XCTAssertEqual(restarted.status, .incomplete)
        XCTAssertFalse(restarted.restore(), "损坏快照不得重放")
        XCTAssertTrue(restarted.discard())
        XCTAssertEqual(restarted.status, .none)
        XCTAssertEqual(try String(contentsOf: curation, encoding: .utf8), "projected", "只删不换回")
        XCTAssertEqual(settings, ["publicOnly": true, "infoAlwaysOn": true], "只删不换回")
    }
}
