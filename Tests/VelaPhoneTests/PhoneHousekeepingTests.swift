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
