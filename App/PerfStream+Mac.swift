#if DEBUG || PERF_PROBE
import Foundation

/// 性能实测用的固定测试流入口（仅 DEBUG 或 PERF_PROBE 构建，Release 产物不含）。
/// 启动参数 `--perf-stream <主列表地址>`：跳过目录与站点解析，直接用该主列表起播，
/// 其余走正常的 playCurrent、本地中继与 PlayerController 路径，因此测得到中继开销。
/// 只接受回环地址，避免被误用来拉外网内容。
enum PerfStream {
    static let username = "perf-stream"
    /// 日志与二进制里的识别串。Swift 把 15 字节以内的字符串直接编进指令，`strings` 看不见，
    /// 所以参数名本身不能当探针与安全门的检测依据；这串够长，会进字符串表。
    static let marker = "vela-perf-stream-hook-enabled"

    static let master: URL? = {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "--perf-stream"), i + 1 < args.count,
              let url = URL(string: args[i + 1]), url.scheme == "http",
              url.host == "127.0.0.1" || url.host == "localhost"
        else { return nil }
        return url
    }()
}

extension StageModel {
    /// 有 `--perf-stream` 且年龄已确认时：占掉目录请求的闸门，把测试房间塞进列表并起播。返回 true 表示已接管，不再加载目录。
    func startPerfStreamIfRequested() -> Bool {
        guard PerfStream.master != nil, ageConfirmed else { return false }
        _ = startupGate.claimCatalog(ageConfirmed: true)
        log.info("\(PerfStream.marker, privacy: .public)")
        Task { @MainActor [weak self] in
            // 等窗口与播放门控就绪，与线上路径里目录返回的时机相当。
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard let self else { return }
            self.updateFeed { $0.insertDirect(Room(username: PerfStream.username, show: .publicShow)) }
            self.requestPlay()
        }
        return true
    }

    func perfStreamTarget(for username: String) -> PlayTarget? {
        guard username == PerfStream.username, let master = PerfStream.master else { return nil }
        return PlayTarget(master: master, playable: .unselected(master))
    }
}
#endif
