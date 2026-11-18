import Combine
import CoreGraphics
import Foundation

/// 一路此刻的状态（§4.2）。只给界面与日志看，不参与闸门判断。
enum LaneState: Equatable {
    case idle
    case playing
    case failed
    /// 双窗副路因解码器资源不足被释放（§4.4），等用户重试或回前台再试一次。
    case degraded
    case empty
}

/// 一路播放自己持有的全部状态（§4.2）。macOS 只有主路；iOS 双窗再加副路。
/// 列表、口味、收藏、侧车、退避等两路共享的状态留在 `StageModel`。
///
/// 原来在 `StageModel` 上是 `@Published` 的三项（`playbackNote`、`portraitFit`、`manuallyPaused`）在这里同样发布，
/// `StageModel` 把本路的 `objectWillChange` 转发出去，界面失效的时机与原来一致。其余字段与原来一样不发布。
@MainActor
final class PlaybackLane: ObservableObject {
    let id: LaneID

    // 播放与画面
    let player = PlayerController()
    /// 画面订阅这一份，不直接订阅播放器：播放器会把没变的就绪 / 失败状态反复写进 `@Published`。
    let playback = PlaybackFace()
    /// 播放器已报告的状态，来自订阅收到的新值（@Published 在 willSet 发出，回读属性拿到的是旧值）。
    var readyName: String?
    var playingName: String?
    /// 最近一次由播放器失败状态写进 playbackNote 的文案；失败恢复时据此清掉，不动其他来源的提示。
    var playerNote: String?
    @Published var playbackNote: String?
    /// 竖屏房间自动完整画面（只对本路当前房间，不改全局 `fitFill`）。
    @Published var portraitFit = PortraitFit()

    // 起播流程
    var playGeneration = 0
    /// 当前这一轮起播（解析流地址、取档）：同一时间只保留一个，新的起播取消旧的。
    var playTask: Task<Void, Never>?
    var skipTask: Task<Void, Never>?
    /// 起播成功后为下一间房的预取。属于这一路的任务：挂起、取消本路工作时一并取消。
    var prefetchTask: Task<Void, Never>?
    /// 房间暂时离开时的一次性延迟重试；切房（`playCurrent`）时取消。
    var awayTask: Task<Void, Never>?
    var awayRetry = AwayRetry()
    var awayTimer = AwayRetryTimer()
    var skips = SkipTracker()

    // 停留与学习
    var dwell = DwellTracker()
    /// 本路停留记账的房间是在哪个来源里出现的（换来源时 `source` 先变、列表后换，结算要用旧来源）。
    var dwellSource: FeedSource = .guess
    var relatedTask: Task<Void, Never>?

    // 画质
    var quality = QualityGovernor(now: Date())
    var qualityTask: Task<Void, Never>?
    var qualitySignature = ""
    var currentMaster: URL?
    var currentPlayable: StreamRendition?
    var currentCap = Double.infinity
    /// 热状态升降档引起的换档任务；切房、释放时取消。
    var capTask: Task<Void, Never>?
    /// 换档请求次数（含因播放项未就绪而没能应用的），测试与日志用。
    var capSyncRequests = 0

    // 身份与状态
    /// 本路当前房间。主路与 `feed.currentUsername` 同步（`updateFeed` 写入）。
    var username: String?
    @Published var manuallyPaused = false
    /// 本路窗格对用户可见。macOS 恒为 true；闸门 = 全局闸门 ∧ !manuallyPaused ∧ visible。
    var visible = true
    var state: LaneState = .idle
    /// 资源降级的独立门控：`laneAllowed` 读它，普通 `applyPower` 不清除（§4.4）。
    var degradedHold = DegradedHold()

    // 尺寸
    var paneSize: CGSize = .zero
    /// 画面实际显示的矩形尺寸（`videoRect`，裁决 5）。降档与日志按它算。
    var displaySize: CGSize = .zero

    init(id: LaneID) {
        self.id = id
        player.laneLabel = id == .primary ? "primary" : "secondary"
    }
}

// MARK: - 模型上的按路入口

@MainActor
extension StageModel {
    /// 把一路接到模型上：音量、播放器回调、失败分类钩子与 `objectWillChange` 转发。
    func attach(_ lane: PlaybackLane) {
        pushVolume(to: lane)
        lane.player.refreshRequested = { [weak self, weak lane] name, isRetry in
            guard let lane else { return }
            self?.refreshCurrent(name, isRetry: isRetry, on: lane)
        }
        lane.player.noAudioDetected = { [weak self, weak lane] name in
            guard let lane else { return }
            self?.noteNoAudio(name, on: lane)
        }
        lane.player.renewRendition = { [weak self, weak lane] name, cap in
            guard let lane else { return nil }
            return await self?.renewRendition(name, cap: cap, on: lane)
        }
        lane.player.failureFilter = { [weak self, weak lane] info in
            guard let self, let lane else { return .roomRetry }
            return self.classifyFailure(info, on: lane)
        }
        lane.player.resourceDegraded = { [weak self, weak lane] _ in
            guard let lane else { return }
            self?.degrade(lane)
        }
        laneForwarders[lane.id] = lane.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }
    }

    /// 播放失败交给 `PlaybackFailureClassifier`：单窗恒为房间重试，行为与原来一致。
    func classifyFailure(_ info: PlaybackFailureInfo, on lane: PlaybackLane) -> PlaybackFailureClassifier.Outcome {
        let other = lanes.first { $0 !== lane }
        let context = PlaybackFailureClassifier.Context(
            isDualWindow: secondary != nil, isSecondary: lane.id == .secondary,
            otherIsPlaying: other?.playingName != nil, hasPlayed: lane.readyName != nil
        )
        return PlaybackFailureClassifier.classify(chain: info.chain, context: context)
    }

    /// 资源降级（§4.4）：释放这一路的播放项和中继源，提示「双窗暂不可用」。
    /// 不记停留失败、不自动跳过；本路闸门由 `degradedHold` 扣住，用户点重试、切房或回前台恢复时才清除再起播。
    func degrade(_ lane: PlaybackLane) {
        lane.playTask?.cancel()
        lane.skipTask?.cancel()
        lane.degradedHold.degrade()
        lane.player.suspendNow()
        lane.state = .degraded
        lane.playbackNote = "双窗暂不可用"
    }

    // MARK: 转发到主路（macOS 只有主路；尽量不改原有调用处）

    var player: PlayerController { primary.player }
    var playback: PlaybackFace { primary.playback }
    var readyName: String? {
        get { primary.readyName }
        set { primary.readyName = newValue }
    }
    var playingName: String? {
        get { primary.playingName }
        set { primary.playingName = newValue }
    }
    var playerNote: String? {
        get { primary.playerNote }
        set { primary.playerNote = newValue }
    }
    var playbackNote: String? {
        get { primary.playbackNote }
        set { primary.playbackNote = newValue }
    }
    var portraitFit: PortraitFit {
        get { primary.portraitFit }
        set { primary.portraitFit = newValue }
    }
    var manuallyPaused: Bool {
        get { primary.manuallyPaused }
        set { primary.manuallyPaused = newValue }
    }
    var awayTask: Task<Void, Never>? {
        get { primary.awayTask }
        set { primary.awayTask = newValue }
    }
    var awayRetry: AwayRetry {
        get { primary.awayRetry }
        set { primary.awayRetry = newValue }
    }
    var awayTimer: AwayRetryTimer {
        get { primary.awayTimer }
        set { primary.awayTimer = newValue }
    }
    var skips: SkipTracker {
        get { primary.skips }
        set { primary.skips = newValue }
    }
    var dwell: DwellTracker {
        get { primary.dwell }
        set { primary.dwell = newValue }
    }
    var relatedTask: Task<Void, Never>? {
        get { primary.relatedTask }
        set { primary.relatedTask = newValue }
    }
    var quality: QualityGovernor {
        get { primary.quality }
        set { primary.quality = newValue }
    }
    var qualityTask: Task<Void, Never>? {
        get { primary.qualityTask }
        set { primary.qualityTask = newValue }
    }
    var currentMaster: URL? {
        get { primary.currentMaster }
        set { primary.currentMaster = newValue }
    }
    var currentPlayable: StreamRendition? {
        get { primary.currentPlayable }
        set { primary.currentPlayable = newValue }
    }

    // MARK: 每路的房间与目标

    func findLane(_ id: LaneID) -> PlaybackLane? {
        lanes.first { $0.id == id }
    }

    /// 这一路此刻的房间。主路就是列表的当前房间；副路按用户名在可见列表里找。
    func room(of lane: PlaybackLane) -> Room? {
        // 单窗主路就是列表当前房间；双窗里主路可能被置空（另一路占着唯一的房间），
        // 此时 `feed.current` 会回落到首间，必须看这一路自己的游标。
        if lane.id == .primary, secondary == nil { return feed.current }
        guard let name = lane.username?.lowercased() else { return nil }
        return feed.rooms.first { $0.username.lowercased() == name }
    }

    /// 这一路此刻的用户名。主路与原来一样取 `feed.currentUsername`。
    func name(of lane: PlaybackLane) -> String? {
        lane.id == .primary ? feed.currentUsername : lane.username
    }

    /// 点下去这一刻的目标（默认主路；macOS 恒为主路）。这一路没有房间时为 nil。
    func target(_ id: LaneID = .primary) -> RoomTarget? {
        guard let lane = findLane(id), let current = room(of: lane) else { return nil }
        return RoomTarget(laneID: id, username: current.username, generation: lane.playGeneration)
    }

    /// 目标那一路仍停在点下去时的房间、仍是同一次播放，才返回这一路；对不上就放弃。
    func matchedLane(_ target: RoomTarget?) -> PlaybackLane? {
        guard let target, let lane = findLane(target.laneID), let current = room(of: lane),
              target.matches(laneID: lane.id, username: current.username, generation: lane.playGeneration)
        else { return nil }
        return lane
    }

    func refreshAllQuality() {
        for lane in lanes { refreshQuality(on: lane) }
    }

    // MARK: 联合选房（§4.1）

    func laneSnapshot(of store: FeedStore) -> LaneSelection.Snapshot {
        LaneSelection.Snapshot(
            rooms: store.rooms.map(\.username),
            primary: LaneCursor(username: store.current?.username, direction: primary.skips.direction),
            secondary: secondary.map { LaneCursor(username: $0.username, direction: $0.skips.direction) },
            focus: focusLane
        )
    }

    /// 把 `LaneSelection` 的结果写回。单窗时它与 FeedStore 的选择必须一致：不一致只记日志、保留 FeedStore 的选择，
    /// macOS 行为不变。双窗时主路改用联合结果（两路不重复）。
    func applySelection(_ outcome: LaneSelection.Outcome, to store: inout FeedStore) {
        let chosen = store.current?.username
        guard outcome.primary.username?.lowercased() != chosen?.lowercased() else { return }
        guard secondary != nil else {
            log.error("lane-selection mismatch single-window")
            return
        }
        store.select(outcome.primary.username)
    }

    /// 双窗副路：写回游标，房间变了就结算并起播。
    func settleSecondary(
        _ lane: PlaybackLane, outcome: LaneSelection.Outcome, reason: LaneSettlement.EndReason = .swipe,
        sourceChanged: Bool = false
    ) {
        let before = lane.username
        lane.username = outcome.secondary?.username
        let roomChanged = before?.lowercased() != lane.username?.lowercased()
        // 同名换来源：房间没变不重新起播，但旧来源下的停留要结算、来源要更新（与主路的 `shouldSettle` 同一判定）。
        guard DwellTracker.shouldSettle(before: before, after: lane.username, sourceChanged: sourceChanged) else { return }
        guard roomChanged else {
            currentRoomChanged(on: lane, reason: reason)
            return
        }
        lane.degradedHold.release(.roomSwitch)
        currentRoomChanged(on: lane, reason: reason)
        if lane.username == nil { lane.state = .empty }
        requestPlay(on: lane)
    }
}
