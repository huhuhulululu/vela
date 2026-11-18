import SwiftUI

/// 竖屏双窗（§4.2、§4.5）：两个等高窗格上下排。每格都是一路独立的分页（`LanePager`）：
/// 模糊缩略图垫底，播放层固定 `.resizeAspect`，横屏流在半屏里上下留边、露出垫底，不裁切（裁决 5）。
/// 点窗格或在窗格里划动都会把焦点交给它；焦点路有香槟色细边，声音与画中画跟着焦点走。
struct PhoneDualStage: View {
    @ObservedObject var model: StageModel
    /// 控制栏此刻看得见：窗格自己的房间信息、心形与更多菜单跟着显示。
    var chromeOn: Bool
    private let gap: CGFloat = 2

    var body: some View {
        GeometryReader { geo in
            let paneHeight = max((geo.size.height - gap) / 2, 1)
            VStack(spacing: gap) {
                ForEach(model.lanes, id: \.id) { lane in
                    PhoneDualPane(
                        model: model, lane: lane, playback: lane.playback,
                        focused: model.focusLane == lane.id, chromeOn: chromeOn
                    )
                    .frame(height: paneHeight)
                }
            }
            .onAppear { model.notePointHeight(paneHeight) }
            .onChange(of: paneHeight) { _, height in model.notePointHeight(height) }
        }
    }
}

/// 双窗里的一格。没有房间时（列表只剩一间）显示说明；有房间时是这一路自己的分页。
struct PhoneDualPane: View {
    @ObservedObject var model: StageModel
    @ObservedObject var lane: PlaybackLane
    @ObservedObject var playback: PlaybackFace
    var focused: Bool
    var chromeOn: Bool

    var body: some View {
        ZStack {
            Theme.ink
            if lane.username != nil {
                pager
            } else {
                Text("没有更多房间")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Theme.textSecondary)
                    .onTapGesture { focusAndWake() }
            }
            if focused {
                Rectangle()
                    .strokeBorder(Theme.champagne.opacity(0.8), lineWidth: 1.5)
                    .allowsHitTesting(false)
            }
            if chromeOn, let room = model.room(of: lane) {
                PhonePaneBar(model: model, lane: lane, room: room, playing: playback.playingUsername == room.username)
                    .transition(.opacity)
            }
        }
        .clipped()
    }

    private var pager: some View {
        LanePager(
            rooms: model.feed.rooms, index: model.index(of: lane),
            step: { delta in
                model.setFocus(lane.id)
                let before = model.name(of: lane)
                model.step(delta, on: lane)
                return model.name(of: lane) != before
            },
            tap: { focusAndWake() }
        ) { room, active in
            PhoneRoomPage(room: room, active: active, model: model, lane: lane, playback: playback)
        }
    }

    private func focusAndWake() {
        model.setFocus(lane.id)
        model.noteActivity()
    }
}

/// 窗格底部：房间信息在左，心形与更多菜单在右。动作都带这一刻界面上看到的房间（`RoomTarget`），
/// 点下去之后这一路换了房间就放弃（S9）。
struct PhonePaneBar: View {
    @ObservedObject var model: StageModel
    @ObservedObject var lane: PlaybackLane
    var room: Room
    var playing: Bool

    var body: some View {
        VStack {
            Spacer(minLength: 0)
            HStack(alignment: .bottom, spacing: Theme.gap) {
                PhoneCaption(room: room, pulse: playing)
                Spacer(minLength: 0)
                moreMenu
                PhoneHeart(
                    isFavorite: model.curation.isFavorite(room.username), canToggle: true,
                    toggle: { model.toggleFavorite(model.target(lane.id, showing: room.username)) },
                    openList: { model.showCuration = true }
                )
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
            .background(alignment: .bottom) {
                LinearGradient(colors: [.clear, .black.opacity(0.6)], startPoint: .top, endPoint: .bottom)
                    .frame(height: 90)
                    .allowsHitTesting(false)
            }
        }
    }

    private var moreMenu: some View {
        Menu {
            Button(lane.manuallyPaused ? "继续" : "暂停") {
                model.togglePause(model.target(lane.id, showing: room.username))
            }
            Button("不感兴趣") { model.markNotInterested(model.target(lane.id, showing: room.username)) }
            Button("屏蔽", role: .destructive) { model.blockCurrent(model.target(lane.id, showing: room.username)) }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(Color(white: 0.08).opacity(0.55), in: Circle())
                .overlay(Circle().strokeBorder(.white.opacity(0.25), lineWidth: 1))
                .contentShape(Circle())
        }
        .accessibilityLabel("更多操作")
    }
}

/// 一路的播放事件订阅（失败、就绪、在播）。每路各挂一份，写回对应路的状态；不可见，不占布局。
/// 单窗只有主路；双窗的副路来去都由模型的 `lanes` 决定。
struct LaneEvents: View {
    @ObservedObject var model: StageModel
    let lane: PlaybackLane

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            // @Published 在 willSet 发出：回调里一律用收到的新值。
            .onReceive(lane.player.$failed) { model.notePlaybackFailed($0, on: lane) }
            .onReceive(lane.player.$readyUsername) { model.noteReady($0, on: lane) }
            .onReceive(lane.player.$playingUsername) { model.notePlaying($0, on: lane) }
    }
}
