#if PERF_PROBE
import Foundation

/// iOS 性能实测用的固定测试流入口（仅 `Perf` 配置，Release 产物不含；安全门会在 Release 里搜这个标识串）。
/// 启动参数 `--perf-stream <主列表地址>`：跳过目录与站点解析，三个测试房间都用该主列表起播，
/// 其余走正常的 playCurrent、本地中继与 PlayerController 路径，因此测得到中继开销。
/// 只接受回环地址，避免被误用来拉外网内容；测试房间的名字以 `perf-stream` 开头，预取与续期都不会为它们发请求。
/// 再加 `--perf-site c4` 时三间测试房间是 Cam4 房间（`c4:perf_stream…`），走 Cam4 的身份、请求头与中继登记，仍用同一回环测试流。
enum PerfStream {
    static let username = "perf-stream"
    static let secondUsername = "perf-stream-2"
    static let thirdUsername = "perf-stream-3"
    /// 日志与二进制里的识别串：够长才会进字符串表，供 `scripts/check-ios-release-no-perf.sh` 查找。
    static let marker = "vela-perf-stream-hook-enabled"

    static let master: URL? = {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "--perf-stream"), i + 1 < args.count,
              let url = URL(string: args[i + 1]), url.scheme == "http",
              url.host == "127.0.0.1" || url.host == "localhost"
        else { return nil }
        return url
    }()

    /// Cam4 测试房间的用户名前缀（Cam4 用户名只收小写字母、数字、下划线）。
    static let cam4Name = "perf_stream"

    static let site: SiteID = {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "--perf-site"), i + 1 < args.count else { return .chaturbate }
        return SiteID(rawValue: args[i + 1]) ?? .chaturbate
    }()

    static func isTestRoom(_ username: String) -> Bool {
        username.hasPrefix(Self.username) || username.hasPrefix("c4:" + Self.cam4Name)
    }
}

extension StageModel {
    /// 有 `--perf-stream` 且年龄已确认时：占掉目录请求的闸门，把三个测试房间装进列表并起播。返回 true 表示已接管，不再加载目录。
    /// 三间房用同一个测试流：单窗只播第一间；`--perf-dual` / `--perf-lanes N` 时其余各路依次取后面的房间。
    func startPerfStreamIfRequested() -> Bool {
        guard PerfStream.master != nil, ageConfirmed else { return false }
        _ = startupGate.claimCatalog(ageConfirmed: true)
        log.info("\(PerfStream.marker, privacy: .public)")
        Task { @MainActor [weak self] in
            // 等窗口与播放门控就绪，与线上路径里目录返回的时机相当。
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard let self else { return }
            let names = [PerfStream.username, PerfStream.secondUsername, PerfStream.thirdUsername]
            let rooms = PerfStream.site == .cam4
                ? ["", "_2", "_3"].map { Room(username: PerfStream.cam4Name + $0, show: .publicShow, site: .cam4) }
                : names.map { Room(username: $0, show: .publicShow) }
            self.updateFeed(intent: .reload) {
                $0.replace(with: CatalogPage(rooms: rooms, total: rooms.count, nextOffset: rooms.count))
            }
            self.requestPlay()
        }
        return true
    }

    func perfStreamTarget(for username: String) -> PlayTarget? {
        guard PerfStream.isTestRoom(username), let master = PerfStream.master else { return nil }
        return PlayTarget(master: master, playable: .unselected(master))
    }
}
#endif
