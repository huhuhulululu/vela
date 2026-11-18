import Foundation

/// 启动读偏好文件的现场判定：读失败时核心层把坏文件改名为 `.bad-<秒>` 备份；备份失败则存储只读。
/// 这里只把这两种结局翻成给用户看的一句话。
enum PreferenceHealth {
    static func backupNames(_ url: URL) -> Set<String> {
        Set(PreferenceQuarantine.backups(of: url).map(\.lastPathComponent))
    }

    /// `before` 是读取前的备份名单：读取后出现新备份，说明坏文件已改名保存；`readOnly` 为真说明改名失败。
    static func notice(label: String, url: URL, before: Set<String>, readOnly: Bool) -> [String] {
        if readOnly { return ["\(label)文件读不出来，也没能备份，这次的改动不会保存"] }
        if !backupNames(url).subtracting(before).isEmpty { return ["\(label)文件读不出来，已另存为备份并从空白开始"] }
        return []
    }
}
