import SwiftUI
import UIKit
import XCTest
@testable import VelaPhone

@MainActor
final class PhoneHousekeepingTests: XCTestCase {
    func testBackupExclusionIsSetAndReadableBack() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("vela-t15-\(UUID().uuidString)/Vela")
        defer { try? FileManager.default.removeItem(at: dir.deletingLastPathComponent()) }
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.path))
        XCTAssertTrue(PhoneHousekeeping.ensureBackupExclusion(at: dir))
        XCTAssertTrue(PhoneHousekeeping.isExcludedFromBackup(dir))
        // 每次启动都执行：重复调用不报错，标志仍在。
        XCTAssertTrue(PhoneHousekeeping.ensureBackupExclusion(at: dir))
        XCTAssertTrue(PhoneHousekeeping.isExcludedFromBackup(dir))
    }

    func testDataDirectoryMatchesPreferenceStoreDirectory() {
        XCTAssertEqual(PhoneHousekeeping.dataDirectory().lastPathComponent, "Vela")
        XCTAssertEqual(PhoneHousekeeping.dataDirectory(), PreferenceStore.defaultURL().deletingLastPathComponent())
    }

    func testMemoryWarningClearsPreparedCache() {
        let name = "vela-phone-mem-\(UUID().uuidString)"
        defer { UserDefaults().removePersistentDomain(forName: name) }
        let stage = StageModel(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        let rendition = StreamRendition(video: URL(string: "https://example.invalid/v.m3u8")!)
        stage.prepared.set(rendition, cap: 1, for: "alice", now: Date())
        XCTAssertNotNil(stage.prepared.fresh("alice", cap: 1, now: Date()))
        PhoneHousekeeping.handleMemoryWarning(stage: stage)
        XCTAssertNil(stage.prepared.fresh("alice", cap: 1, now: Date()))
    }
}

/// 真机诊断的本机存储与入口（MetricKit 摘要只留最近 7 天，展开才读文件）。
@MainActor
final class DiagnosticsStoreTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("vela-diag-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    private func metric(_ day: String) -> Data {
        try! JSONSerialization.data(withJSONObject: [
            "timeStampEnd": "\(day) 23:59:00", "cpuMetrics": ["cumulativeCPUTime": "60 sec"]
        ])
    }

    func testRecordKeepsSevenDaysOnDiskAndSurvivesABadFile() throws {
        let url = dir.appendingPathComponent("diagnostics.json")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("garbage".utf8).write(to: url)
        XCTAssertEqual(DiagnosticsStore.load(from: url), [], "坏文件当没有记录")
        for n in 1...9 {
            DiagnosticsStore.record(metrics: [metric(String(format: "2026-11-%02d", n))], diagnostics: [], at: url)
        }
        let days = DiagnosticsStore.load(from: url)
        XCTAssertEqual(days.map(\.day), (3...9).reversed().map { String(format: "2026-11-%02d", $0) })
        let diag = try JSONSerialization.data(withJSONObject: ["timeStampEnd": "2026-11-09 10:00:00", "hangDiagnostics": [[:]]])
        DiagnosticsStore.record(metrics: [], diagnostics: [diag], at: url)
        XCTAssertEqual(DiagnosticsStore.load(from: url).first?.hangs, 1)
        let share = DiagnosticsStore.shareText(DiagnosticsStore.load(from: url))
        XCTAssertTrue(share.hasPrefix("Vela 诊断（本机最近 7 天）"))
        XCTAssertTrue(share.contains("[2026-11-09 指标]"))
        XCTAssertTrue(share.contains("[2026-11-09 诊断]"))
    }

    func testDataDirectoryHoldingDiagnosticsIsExcludedFromBackup() {
        XCTAssertEqual(DiagnosticsStore.defaultURL().deletingLastPathComponent(), PhoneHousekeeping.dataDirectory())
        XCTAssertTrue(PhoneHousekeeping.ensureBackupExclusion())
        XCTAssertTrue(PhoneHousekeeping.isExcludedFromBackup())
    }

    func testSectionRendersEmptyAndFilledWithoutCrashing() throws {
        let url = dir.appendingPathComponent("diagnostics.json")
        for expanded in [false, true] {
            let host = UIHostingController(rootView: PhoneDiagnosticsSection(expanded: expanded, url: url))
            host.view.frame = CGRect(x: 0, y: 0, width: 393, height: 500)
            host.view.layoutIfNeeded()
        }
        DiagnosticsStore.record(metrics: [metric("2026-11-17")], diagnostics: [], at: url)
        let host = UIHostingController(rootView: PhoneDiagnosticsSection(expanded: true, url: url))
        host.view.frame = CGRect(x: 0, y: 0, width: 393, height: 500)
        host.view.layoutIfNeeded()
        XCTAssertNotNil(host.view)
    }
}
