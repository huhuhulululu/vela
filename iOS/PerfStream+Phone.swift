#if PERF_PROBE
import Foundation

/// iOS 性能实测用的固定测试流入口（仅 `Perf` 配置，Release 产物不含；安全门会在 Release 里搜这个标识串）。
/// 启动参数 `--perf-stream <主列表地址>`：跳过目录与站点解析，两个测试房间都用该主列表起播，
/// 其余走正常的 playCurrent、本地中继与 PlayerController 路径，因此测得到中继开销。
/// 只接受回环地址，避免被误用来拉外网内容；测试房间的名字以 `perf-stream` 开头，预取与续期都不会为它们发请求。
enum PerfStream {
    static let username = "perf-stream"
    static let secondUsername = "perf-stream-2"
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

    static func isTestRoom(_ username: String) -> Bool { username.hasPrefix(Self.username) }
}

extension StageModel {
    /// 有 `--perf-stream` 且年龄已确认时：占掉目录请求的闸门，把两个测试房间装进列表并起播。返回 true 表示已接管，不再加载目录。
    /// 两间房用同一个测试流：单窗只播第一间；`--perf-dual` 时副路取第二间。
    func startPerfStreamIfRequested() -> Bool {
        guard PerfStream.master != nil, ageConfirmed else { return false }
        _ = startupGate.claimCatalog(ageConfirmed: true)
        log.info("\(PerfStream.marker, privacy: .public)")
        Task { @MainActor [weak self] in
            // 等窗口与播放门控就绪，与线上路径里目录返回的时机相当。
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard let self else { return }
            let rooms = [PerfStream.username, PerfStream.secondUsername].map { Room(username: $0, show: .publicShow) }
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
