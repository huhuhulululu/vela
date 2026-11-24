package vela.app.sync

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import vela.core.sync.SyncCycleResult
import vela.core.sync.SyncFlowResult
import vela.core.sync.SyncHalt
import vela.core.sync.SyncJoinPreview
import vela.core.sync.SyncMigrationReport
import vela.core.sync.SyncStartupAction

/**
 * 同步的用户可见文案（docs/plans/sync.md 第 11.4、12.1、16 节）。
 * 只出现短指纹，从不出现同步码、K 或完整 blobID。
 */
object SyncStatusText {
    fun halt(halt: SyncHalt, locale: Locale = Locale.SIMPLIFIED_CHINESE): String = when (halt) {
        SyncHalt.Forbidden -> "云端拒绝了这台设备的写入。请关闭同步后重新开启。"
        is SyncHalt.Gone -> {
            val formatted = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(halt.deletedAt))
            "这个同步空间已于 $formatted 被清除。请关闭同步后重新开启（会生成新的同步码），或输入新的同步码。本机数据都还在。"
        }
        SyncHalt.Missing -> "云端同步空间不见了。请关闭同步后重新开启。"
        SyncHalt.EpochChanged -> "云端已重置。请关闭同步后重新开启。"
        SyncHalt.Rollback -> "云端数据比本机看到过的旧，已停止写入。请关闭同步后重新开启。"
        SyncHalt.PubMismatch -> "同步空间被占用。请关闭同步后重新开启。"
        SyncHalt.DecryptFailed -> "连续两次无法解密云端数据，同步已暂停。本机数据都还在。"
    }

    /** 周期结果里需要显示的失败类别；成功或取消返回 null。 */
    fun cycle(result: SyncCycleResult): String? = when (result) {
        SyncCycleResult.UpToDate,
        SyncCycleResult.Pushed,
        SyncCycleResult.Created,
        SyncCycleResult.Cancelled,
        -> null
        is SyncCycleResult.Halted -> halt(result.halt)
        SyncCycleResult.ReadOnly -> "其他设备的 App 更新，请更新本机 App。"
        SyncCycleResult.InvalidRemote -> "云端数据不合法，已暂停合并，本机数据保留。"
        SyncCycleResult.DecryptFailed -> "暂时无法解密云端数据，下次再试。"
        SyncCycleResult.ClockSkew -> "本机时间不准，校准后再同步。"
        SyncCycleResult.Oversize, SyncCycleResult.PayloadTooLarge -> "同步数据已满，请删掉一些收藏、屏蔽或不想看。"
        SyncCycleResult.Transient, SyncCycleResult.ConflictLimit -> "暂时连不上同步服务器，稍后自动重试。"
        is SyncCycleResult.RateLimited -> "请求太频繁，稍后自动重试。"
        SyncCycleResult.QuotaFull -> "服务器空间已满，一小时后再试。"
        SyncCycleResult.StorageFailed -> "本机保存失败，下次操作时重试。"
    }

    /** 加入提交没收尾、换回快照也失败时的提示（第 12.4 节）。 */
    const val joinRepair: String =
        "上次加入同步没有完成，本机数据恢复失败，需要修复。恢复完成前收藏、屏蔽与口味暂不能修改；点「关闭同步」重试恢复。"

    const val joinRepairFirst: String =
        "上次加入同步没有完成，请先点「关闭同步」恢复本机数据。"

    /** 关闭、清除、启动恢复等流程的结果（第 12 节），按实际发生的事说。 */
    fun flow(result: SyncFlowResult): String = when (result) {
        SyncFlowResult.Done -> ""
        SyncFlowResult.LocalSaveFailed -> "本机保存失败，未做任何更改。"
        SyncFlowResult.StorageFailed -> "读写本机同步状态或系统密钥库失败，没有完成的步骤会在下次启动时继续。"
        SyncFlowResult.RemotePending -> "暂时连不上服务器，清除尚未完成，下次启动时继续。"
        SyncFlowResult.RemoteRejected -> "云端拒绝了清除请求。"
    }

    fun startup(action: SyncStartupAction): String? = when (action) {
        SyncStartupAction.KeyLost -> "同步密钥丢失，请重新输入同步码。"
        SyncStartupAction.Mismatch -> "同步状态与系统密钥库不一致，同步已停止。请关闭同步后重新开启。"
        SyncStartupAction.StateCorrupted -> "暂停：同步状态损坏。请关闭同步后重新开启。"
        else -> null
    }

    /** 隐私说明要点（第 16 节），开启与加入时展示，设置页可再看。 */
    val privacyPoints: List<String> = listOf(
        "同步的内容：收藏、屏蔽、「不想看」的标签与性别、两个显示设置；不同步观看记录与学习数据。",
        "内容在本机加密后才上传；服务器与网络运营方看不到内容，但能看到访问时间、大致数据量，并且有能力删除数据或让同步暂时失败。",
        "同步码就是钥匙：谁拿到谁就能看到和修改全部同步数据。不要截图分享，不要发给别人。",
        "丢了同步码、又没有任何一台已加入的设备，数据无法找回。",
        "怀疑同步码泄露或设备丢失：清除云端，重新开启，再在每台设备上输入新码。",
        "「清除云端数据」会让所有设备停止同步，各设备本地数据保留；若服务器开启了冷备份，备份中的密文最多再保留 7 天。",
        "远端改动在下次打开 App、回到前台、打开收藏或口味面板，或点「立即同步」时出现。",
        "收藏与屏蔽各最多保留 300 个，同步的「不想看」最多 450 个，超出时丢掉最旧的。",
    )

    /** 加入确认页的数量行（第 12.4 节第 5 步）。 */
    fun joinLines(preview: SyncJoinPreview, host: String): List<String> {
        val lines = mutableListOf("将上传本机 ${preview.n} 个收藏、${preview.m} 个屏蔽到 $host")
        if (preview.v > 0) lines.add("另上传 ${preview.v} 个「不想看」")
        if (preview.x > 0) lines.add("合并后超过 300 个，${preview.x} 个最旧的收藏不再保留")
        if (preview.y > 0) lines.add("合并后超过 300 个，${preview.y} 个最旧的屏蔽不再保留")
        if (preview.z > 0) lines.add("${preview.z} 个本机收藏在云端已屏蔽，将按屏蔽处理")
        lines.add("持有这个同步码的人能看到并修改这些数据")
        return lines
    }

    /** 迁移提示行（不合规名字、超额被取消的「不想看」）。 */
    fun reportLines(report: SyncMigrationReport): List<String> {
        val lines = mutableListOf<String>()
        if (report.invalidNames.isNotEmpty()) {
            lines.add("有 ${report.invalidNames.size} 个房间名不合规，未纳入同步。")
        }
        if (report.droppedSyncVetoes.isNotEmpty()) {
            lines.add("「不想看」超过 450 个，${report.droppedSyncVetoes.size} 个已取消。")
        }
        if (report.droppedLocalOnlyVetoes.isNotEmpty()) {
            lines.add("本机专用「不想看」超过 50 个，${report.droppedLocalOnlyVetoes.size} 个已取消。")
        }
        return lines
    }
}
