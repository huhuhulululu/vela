import Foundation
import UIKit
import os

/// 启动与内存警告时的杂务：数据目录排除备份、清缓存。
enum PhoneHousekeeping {
    private static let log = Logger(subsystem: "com.vela.stage", category: "housekeeping")

    /// 数据目录：与 `PreferenceStore` 同一个 `Application Support/Vela`。
    static func dataDirectory() -> URL {
        PreferenceStore.defaultURL().deletingLastPathComponent()
    }

    /// 目录不存在就建，再设 `isExcludedFromBackup`，数据不进 iCloud 备份。每次启动都执行（系统恢复后标志可能丢）。
    @discardableResult
    static func ensureBackupExclusion(at directory: URL = dataDirectory()) -> Bool {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var url = directory
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try url.setResourceValues(values)
            log.info("backup exclusion set")
            return true
        } catch {
            log.error("backup exclusion failed")
            return false
        }
    }

    static func isExcludedFromBackup(_ directory: URL = dataDirectory()) -> Bool {
        (try? directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup) ?? false
    }

    /// 内存警告：清空缩略图缓存与预取缓存。
    @MainActor
    static func handleMemoryWarning(stage: StageModel) {
        RoomThumbnailCache.shared.removeAll()
        stage.prepared = PreparedCache()
        log.info("memory warning: caches cleared")
    }

    /// 订阅内存警告。返回的令牌由调用方持有。
    @MainActor
    static func observeMemoryWarnings(stage: StageModel) -> NSObjectProtocol {
        NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main
        ) { [weak stage] _ in
            MainActor.assumeIsolated {
                if let stage { handleMemoryWarning(stage: stage) }
            }
        }
    }
}
