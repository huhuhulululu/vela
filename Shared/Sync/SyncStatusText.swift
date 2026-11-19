import Foundation
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 同步的用户可见文案（docs/plans/sync.md 第 11.4、12.1、16 节）。只出现短指纹，从不出现同步码、K 或完整 blobID。
enum SyncStatusText {
    static func halt(_ halt: SyncHalt, locale: Locale = Locale(identifier: "zh_CN")) -> String {
        switch halt {
        case .forbidden: return "云端拒绝了这台设备的写入。请关闭同步后重新开启。"
        case .gone(let deletedAt):
            let date = Date(timeIntervalSince1970: TimeInterval(deletedAt) / 1000)
            let formatter = DateFormatter()
            formatter.locale = locale
            formatter.dateStyle = .medium
            formatter.timeStyle = .none
            return "这个同步空间已于 \(formatter.string(from: date)) 被清除。请关闭同步后重新开启（会生成新的同步码），或输入新的同步码。本机数据都还在。"
        case .missing: return "云端同步空间不见了。请关闭同步后重新开启。"
        case .epochChanged: return "云端已重置。请关闭同步后重新开启。"
        case .rollback: return "云端数据比本机看到过的旧，已停止写入。请关闭同步后重新开启。"
        case .pubMismatch: return "同步空间被占用。请关闭同步后重新开启。"
        case .decryptFailed: return "连续两次无法解密云端数据，同步已暂停。本机数据都还在。"
        }
    }

    /// 周期结果里需要显示的失败类别；成功返回 nil。
    static func cycle(_ result: SyncCycleResult) -> String? {
        switch result {
        case .upToDate, .pushed, .created, .cancelled: return nil
        case .halted(let h): return halt(h)
        case .readOnly: return "其他设备的 App 更新，请更新本机 App。"
        case .invalidRemote: return "云端数据不合法，已暂停合并，本机数据保留。"
        case .decryptFailed: return "暂时无法解密云端数据，下次再试。"
        case .clockSkew: return "本机时间不准，校准后再同步。"
        case .oversize, .payloadTooLarge: return "同步数据已满，请删掉一些收藏、屏蔽或不想看。"
        case .transient, .conflictLimit: return "暂时连不上同步服务器，稍后自动重试。"
        case .rateLimited: return "请求太频繁，稍后自动重试。"
        case .quotaFull: return "服务器空间已满，一小时后再试。"
        case .storageFailed: return "本机保存失败，下次操作时重试。"
        }
    }

    /// 加入提交没收尾、换回快照也失败时的提示（第 12.4 节）。
    static let joinRepair = "上次加入同步没有完成，本机数据恢复失败，需要修复。请保持 App 打开后重试；仍不行请联系支持。"

    /// 关闭、清除、启动恢复等流程的结果（第 12 节），按实际发生的事说。
    static func flow(_ result: SyncFlowResult) -> String {
        switch result {
        case .done: return ""
        case .localSaveFailed: return "本机保存失败，未做任何更改。"
        case .storageFailed: return "读写本机同步状态或钥匙串失败，没有完成的步骤会在下次启动时继续。"
        case .remotePending: return "暂时连不上服务器，清除尚未完成，下次启动时继续。"
        case .remoteRejected: return "云端拒绝了清除请求。"
        }
    }

    static func startup(_ action: SyncStartupAction) -> String? {
        switch action {
        case .keyLost: return "同步密钥丢失，请重新输入同步码。"
        case .mismatch: return "同步状态与钥匙串不一致，同步已停止。请关闭同步后重新开启。"
        case .stateCorrupted: return "暂停：同步状态损坏。请关闭同步后重新开启。"
        default: return nil
        }
    }

    /// 隐私说明要点（第 16 节），开启与加入时展示，设置页可再看。
    static let privacyPoints = [
        "同步的内容：收藏、屏蔽、「不想看」的标签与性别、两个显示设置；不同步观看记录与学习数据。",
        "内容在本机加密后才上传；服务器与网络运营方看不到内容，但能看到访问时间、大致数据量，并且有能力删除数据或让同步暂时失败。",
        "同步码就是钥匙：谁拿到谁就能看到和修改全部同步数据。不要截图分享，不要发给别人。",
        "丢了同步码、又没有任何一台已加入的设备，数据无法找回。",
        "怀疑同步码泄露或设备丢失：清除云端，重新开启，再在每台设备上输入新码。",
        "「清除云端数据」会让所有设备停止同步，各设备本地数据保留；若服务器开启了冷备份，备份中的密文最多再保留 7 天。",
        "远端改动在下次打开 App、回到前台、打开收藏或口味面板，或点「立即同步」时出现。",
        "收藏与屏蔽各最多保留 300 个，同步的「不想看」最多 450 个，超出时丢掉最旧的。",
    ]

    /// 加入确认页的数量行（第 12.4 节第 5 步）。
    static func joinLines(_ p: SyncJoinPreview, host: String) -> [String] {
        var lines = ["将上传本机 \(p.n) 个收藏、\(p.m) 个屏蔽到 \(host)"]
        if p.v > 0 { lines.append("另上传 \(p.v) 个「不想看」") }
        if p.x > 0 { lines.append("合并后超过 300 个，\(p.x) 个最旧的收藏不再保留") }
        if p.y > 0 { lines.append("合并后超过 300 个，\(p.y) 个最旧的屏蔽不再保留") }
        if p.z > 0 { lines.append("\(p.z) 个本机收藏在云端已屏蔽，将按屏蔽处理") }
        lines.append("持有这个同步码的人能看到并修改这些数据")
        return lines
    }
}
