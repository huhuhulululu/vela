import AVFoundation
import AppKit
import ImageIO
import QuartzCore
import SwiftUI

@MainActor
final class RoomThumbnailCache {
    static let shared = RoomThumbnailCache()

    /// 缩略图长边最多这么多像素。垫底还会再模糊（约 30pt），菜单栏只画约 64×36。
    private static let maxPixelSize = 192
    /// 约 2 MB。入缓存时按位图字节计费（宽 × 高 × 4）；张数上限仍是 20。
    private static let byteBudget = 2 * 1024 * 1024

    private let cache = NSCache<NSURL, NSImage>()
    private var inflight = [NSURL: Task<NSImage?, Never>]()

    private init() {
        cache.countLimit = 20
        cache.totalCostLimit = Self.byteBudget
    }

    func image(for url: URL?) -> NSImage? {
        guard let url else { return nil }
        return cache.object(forKey: url as NSURL)
    }

    /// 命中缓存则直接返回，否则等待（或与预取共用的）在途下载。
    func imageOrFetch(for url: URL?) async -> NSImage? {
        guard let url else { return nil }
        if let cached = image(for: url) { return cached }
        return await beginFetch(url: url)?.value
    }

    /// 预取缩略图到内存，切房时垫底可立即命中。
    func prefetch(url: URL?) {
        guard let url, image(for: url) == nil else { return }
        _ = beginFetch(url: url)
    }

    @discardableResult
    private func beginFetch(url: URL) -> Task<NSImage?, Never>? {
        let key = url as NSURL
        if let cached = cache.object(forKey: key) { return Task { cached } }
        if let existing = inflight[key] { return existing }
        let task = Task<NSImage?, Never> { @MainActor in
            defer { inflight[key] = nil }
            guard let (data, response) = try? await URLSession.shared.data(from: url),
                  let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode) else { return nil }
            let maxPixelSize = Self.maxPixelSize
            // 下载在 URLSession 里挂起，不占主线程；解码放在后台，避免 ImageIO 挡住界面。
            guard let cgImage = await Task.detached { () -> CGImage? in
                RoomThumbnailCache.decodeThumbnail(from: data, maxPixelSize: maxPixelSize)
            }.value else { return nil }
            let image = NSImage(
                cgImage: cgImage,
                size: NSSize(width: CGFloat(cgImage.width), height: CGFloat(cgImage.height))
            )
            if cache.object(forKey: key) == nil {
                cache.setObject(image, forKey: key, cost: cgImage.width * cgImage.height * 4)
            }
            return image
        }
        inflight[key] = task
        return task
    }

    /// 把 JPEG 一次性解成小位图。只在后台任务里调用；不保留原始 Data，也不碰缓存。
    private nonisolated static func decodeThumbnail(from data: Data, maxPixelSize: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            kCGImageSourceShouldCacheImmediately: true
        ] as CFDictionary
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else { return nil }
        guard cgImage.width > 0, cgImage.height > 0 else { return nil }
        return cgImage
    }
}

struct RoomCanvas: View {
    var room: Room
    var active: Bool
    @ObservedObject var model: StageModel
    /// 不观察 `PlayerController`：它在播放中会反复发布相同的就绪 / 失败状态，整页画面会跟着失效。
    @ObservedObject var playback: PlaybackFace
    @ObservedObject private var info = RoomInfoPrefs.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            SwapBackdrop(room: room)
            if active {
                PlayerLayerView(player: model.player.player, fill: model.effectiveFill)
                    .opacity(videoReady ? 1 : 0)
                    .animation(reduceMotion ? nil : .easeOut(duration: Theme.videoFade), value: videoReady)
            }
            Group {
                LinearGradient(
                    colors: [.clear, .black.opacity(0.18), .black.opacity(0.78)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(height: 280)
                .frame(maxHeight: .infinity, alignment: .bottom)
                RoomCaption(room: room, pulse: pulseLive, reason: model.reason(for: room))
            }
            .opacity(infoVisible ? 1 : 0)
            .animation(reduceMotion ? nil : .easeOut(duration: Theme.infoFade), value: infoVisible)
            .allowsHitTesting(false)
            if active && model.manuallyPaused {
                pauseMark
            } else if active, let note = model.playbackNote, !videoReady || playback.failed {
                noteMark(note)
            }
            if active && !videoReady && model.playbackNote == nil && !model.manuallyPaused {
                LoadingLine()
                    .frame(maxHeight: .infinity, alignment: .top)
                    .padding(.top, 8)
            }
        }
        .clipped()
    }

    private var chromeOn: Bool { model.chromeVisible || model.isInteracting }

    /// 当前页的房间信息跟控制栏一起显隐（「始终显示」除外）；邻页照旧带信息。
    private var infoVisible: Bool {
        InfoVisibility.visible(isCurrent: active, alwaysShow: info.alwaysShow, chromeOn: chromeOn)
    }

    private var videoReady: Bool { active && playback.readyUsername == room.username }

    /// 只有当前页、这一间正在播、信息块看得见时才做脉冲。停播、信息被隐藏、App 隐藏后停播都要停。
    /// `.id` 用这个条件：条件变假时拆掉旧视图，避免动画值没变、repeatForever 还在跑。
    private var pulseLive: Bool {
        active && playback.playingUsername == room.username && infoVisible
    }

    private var pauseMark: some View {
        Image(systemName: "pause.fill")
            .font(.system(size: 22, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 64, height: 64)
            .background(.ultraThinMaterial, in: Circle())
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .allowsHitTesting(false)
    }

    private func noteMark(_ note: String) -> some View {
        VStack(spacing: 14) {
            Text(note)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .frame(maxWidth: 440)
                .fixedSize(horizontal: false, vertical: true)
                // 固定深色底垫在材质下面：亮画面上材质会发灰，白字对比度不能只靠它。
                .background {
                    ZStack {
                        Rectangle().fill(.ultraThinMaterial)
                        Rectangle().fill(Theme.hintFill)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
            Button("重试") { model.retryPlayback() }
                .buttonStyle(ChampagneButton())
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct SwapBackdrop: View {
    let room: Room
    /// 带房间标识的下载结果：切房复用同一视图时，只有标识与当前房间一致才显示，迟到的旧图不会盖住新图。
    @State private var loaded: (username: String, image: NSImage)?

    /// 当前房间的缩略图：先用同房间的下载结果，其次用缓存；都没有时为 nil（显示柔和渐变，不显示上一间的图）。
    private var image: NSImage? {
        if let loaded, loaded.username == room.username { return loaded.image }
        return RoomThumbnailCache.shared.image(for: room.thumbnailURL)
    }

    var body: some View {
        ZStack {
            backdropLayer
            Theme.backdropScrim
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .task(id: room.username) {
            let name = room.username
            if loaded?.username != name { loaded = nil }
            guard let fetched = await RoomThumbnailCache.shared.imageOrFetch(for: room.thumbnailURL),
                  !Task.isCancelled else { return }
            loaded = (name, fetched)
        }
    }

    @ViewBuilder
    private var backdropLayer: some View {
        if let image {
            Image(nsImage: image)
                .resizable()
                .scaledToFill()
                .scaleEffect(Theme.backdropScale)
                .blur(radius: Theme.backdropBlurRadius)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            LinearGradient(
                colors: [Theme.ink, Theme.backdropWarm, Theme.ink.opacity(0.92)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Text(room.displayName.isEmpty ? room.username : room.displayName)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
        }
    }
}

struct LoadingLine: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var wide = false

    var body: some View {
        Capsule()
            .fill(Theme.champagne)
            .frame(width: wide ? 92 : 28, height: 2)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.85).repeatForever(autoreverses: true), value: wide)
            .onAppear { wide = true }
            .allowsHitTesting(false)
    }
}

struct PlayerLayerView: NSViewRepresentable {
    var player: AVPlayer
    var fill: Bool

    func makeNSView(context: Context) -> PlayerNSView {
        let view = PlayerNSView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
        return view
    }

    func updateNSView(_ view: PlayerNSView, context: Context) {
        // 父视图刷新也会进来。播放器实例和重力没变就不要写 layer，同值写入会把 AVPlayerLayer 标脏。
        if view.playerLayer.player !== player {
            view.playerLayer.player = player
        }
        let gravity: AVLayerVideoGravity = fill ? .resizeAspectFill : .resizeAspect
        if view.playerLayer.videoGravity != gravity {
            view.playerLayer.videoGravity = gravity
        }
    }
}

final class PlayerNSView: NSView {
    let playerLayer = AVPlayerLayer()

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.backgroundColor = NSColor.clear.cgColor
        playerLayer.backgroundColor = NSColor.clear.cgColor
        playerLayer.isOpaque = false
        playerLayer.drawsAsynchronously = true
        playerLayer.videoGravity = .resizeAspect
        layer?.addSublayer(playerLayer)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layout() {
        super.layout()
        // 宿主视图在播放中仍可能反复 layout。frame 没变就不要再写，避免隐式动画把图层再标脏。
        guard playerLayer.frame != bounds else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}