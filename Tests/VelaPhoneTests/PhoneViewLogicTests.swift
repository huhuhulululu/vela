import AVFoundation
import SwiftUI
import UIKit
import XCTest
@testable import VelaPhone

/// 单窗视图的纯逻辑（T14）：分页阈值判定、要渲染的邻页、`display-check` 日志拼装。
final class PhoneViewLogicTests: XCTestCase {
    private let height: CGFloat = 800

    // MARK: 翻页判定

    func testShortDragSnapsBack() {
        XCTAssertEqual(decide(-100, predicted: -150), 0)
        XCTAssertEqual(decide(100, predicted: 150), 0)
    }

    func testDragPastThresholdTurnsPage() {
        // 阈值是高度的 20%：800 × 0.2 = 160。
        XCTAssertEqual(decide(-160, predicted: -160), 1, "向上拖到下一间")
        XCTAssertEqual(decide(160, predicted: 160), -1, "向下拖到上一间")
        XCTAssertEqual(decide(-159, predicted: -159), 0)
    }

    func testFlingTurnsPageEvenWhenShort() {
        XCTAssertEqual(decide(-30, predicted: -420), 1)
        XCTAssertEqual(decide(30, predicted: 420), -1)
    }

    func testFlingNeedsMinimumTravel() {
        XCTAssertEqual(decide(-20, predicted: -600), 0, "轻触带出的速度不算甩动")
    }

    func testFlingAgainstDragDirectionDoesNotTurn() {
        // 先往上拖，松手前又往回甩：预计终点在反方向，不翻页。
        XCTAssertEqual(decide(-40, predicted: 500), 0)
    }

    func testNoNeighbourMeansSnapBack() {
        XCTAssertEqual(decide(-400, predicted: -600, hasNext: false), 0, "最后一间往上拖回弹")
        XCTAssertEqual(decide(400, predicted: 600, hasPrevious: false), 0, "第一间往下拖回弹")
        XCTAssertEqual(decide(-400, predicted: -600, hasPrevious: false), 1, "第一间仍可往下一间翻")
    }

    func testZeroHeightOrNoMovementNeverTurns() {
        XCTAssertEqual(PageSwipe.decision(translation: -400, predictedEnd: -600, height: 0, hasPrevious: true, hasNext: true), 0)
        XCTAssertEqual(decide(0, predicted: -600), 0)
    }

    func testEdgeDragIsDamped() {
        XCTAssertEqual(PageSwipe.offset(translation: -90, hasPrevious: true, hasNext: true), -90)
        XCTAssertEqual(PageSwipe.offset(translation: -90, hasPrevious: true, hasNext: false), -30, accuracy: 0.001)
        XCTAssertEqual(PageSwipe.offset(translation: 90, hasPrevious: false, hasNext: true), 30, accuracy: 0.001)
    }

    // MARK: 只渲染三页

    func testSlotsRenderAtMostThreePages() {
        XCTAssertEqual(PageSwipe.slots(count: 10, index: 4), [
            .init(index: 3, offset: -1), .init(index: 4, offset: 0), .init(index: 5, offset: 1)
        ])
    }

    func testSlotsAtEdges() {
        XCTAssertEqual(PageSwipe.slots(count: 10, index: 0), [.init(index: 0, offset: 0), .init(index: 1, offset: 1)])
        XCTAssertEqual(PageSwipe.slots(count: 10, index: 9), [.init(index: 8, offset: -1), .init(index: 9, offset: 0)])
        XCTAssertEqual(PageSwipe.slots(count: 1, index: 0), [.init(index: 0, offset: 0)])
        XCTAssertEqual(PageSwipe.slots(count: 0, index: 0), [])
        XCTAssertEqual(PageSwipe.slots(count: 3, index: 7), [.init(index: 1, offset: -1), .init(index: 2, offset: 0)], "越界下标夹到末尾")
    }

    // MARK: display-check

    func testDisplayCheckLineForLetterboxedLandscape() {
        let pane = CGSize(width: 402, height: 874)
        let presentation = CGSize(width: 1920, height: 1080)
        let rect = DisplayFit.fitted(presentation: presentation, bounds: CGRect(origin: .zero, size: pane))
        let line = DisplayCheck.line(
            lane: "primary", gravity: DisplayCheck.gravityName(.resizeAspect),
            presentation: presentation, container: pane, layer: pane, videoRect: rect
        )
        XCTAssertEqual(
            line,
            "display-check lane=primary gravity=resizeAspect presentation=1920x1080 bounds=402x874 layer=402x874 videoRect=402x226 uncropped=true"
        )
    }

    func testDisplayCheckFlagsCroppedRect() {
        // 铺满式的矩形超出窗格：必须判为裁切（负样本）。
        let line = DisplayCheck.line(
            lane: "secondary", gravity: DisplayCheck.gravityName(.resizeAspectFill),
            presentation: CGSize(width: 1920, height: 1080), container: CGSize(width: 390, height: 844),
            layer: CGSize(width: 390, height: 844), videoRect: CGRect(x: -555, y: 0, width: 1500, height: 844)
        )
        XCTAssertTrue(line.hasPrefix("display-check lane=secondary gravity=fill "))
        XCTAssertTrue(line.hasSuffix("videoRect=1500x844 uncropped=false"))
    }

    func testDisplayCheckFlagsLayerInflatedBeyondPane() {
        // 实跑发现的情形（负样本）：播放层被撑到 1554x874，videoRect 在播放层里看是完整的，
        // 换到窗格坐标后左右各超出 576 点，必须判为裁切。
        let pane = CGSize(width: 402, height: 874)
        let line = DisplayCheck.line(
            lane: "primary", gravity: DisplayCheck.gravityName(.resizeAspect),
            presentation: CGSize(width: 1920, height: 1080), container: pane,
            layer: CGSize(width: 1554, height: 874), videoRect: CGRect(x: -576, y: 0, width: 1554, height: 874)
        )
        XCTAssertTrue(line.contains("bounds=402x874 layer=1554x874"))
        XCTAssertTrue(line.hasSuffix("uncropped=false"))
    }

    func testDisplayCheckRequiresLayerToMatchPane() {
        // 画面本身落在窗格内、比例也对，但播放层比窗格大出超过 1 点：同样不算通过。
        let pane = CGSize(width: 402, height: 874)
        let presentation = CGSize(width: 1920, height: 1080)
        let rect = DisplayFit.fitted(presentation: presentation, bounds: CGRect(origin: .zero, size: pane))
        let inflated = DisplayCheck.line(
            lane: "primary", gravity: "resizeAspect", presentation: presentation,
            container: pane, layer: CGSize(width: 404, height: 874), videoRect: rect
        )
        XCTAssertTrue(inflated.hasSuffix("uncropped=false"))
        let withinPoint = DisplayCheck.line(
            lane: "primary", gravity: "resizeAspect", presentation: presentation,
            container: pane, layer: CGSize(width: 402.6, height: 873.4), videoRect: rect
        )
        XCTAssertTrue(withinPoint.hasSuffix("uncropped=true"), "1 点以内是取整误差")
        XCTAssertFalse(DisplayCheck.layerMatches(CGSize(width: 403.5, height: 874), container: pane))
        XCTAssertTrue(DisplayCheck.layerMatches(CGSize(width: 403, height: 874), container: pane))
    }

    func testGravityNames() {
        XCTAssertEqual(DisplayCheck.gravityName(.resizeAspect), "resizeAspect")
        XCTAssertEqual(DisplayCheck.gravityName(.resize), "resize")
    }

    func testDisplaySizeUsesVideoRectOrEstimatesSixteenByNine() {
        let bounds = CGSize(width: 390, height: 844)
        XCTAssertEqual(DisplayCheck.displaySize(videoRect: CGRect(x: 0, y: 100, width: 390, height: 693), bounds: bounds),
                       CGSize(width: 390, height: 693), "竖屏流按实际矩形")
        let estimated = DisplayCheck.displaySize(videoRect: .zero, bounds: bounds)
        XCTAssertEqual(estimated.width, 390, accuracy: 0.001)
        XCTAssertEqual(estimated.height, 219.375, accuracy: 0.001, "还没有画面时按 16:9 估算")
    }

    func testDimsRoundsAndGuardsNonFinite() {
        XCTAssertEqual(DisplayCheck.dims(CGSize(width: 219.6, height: 0.4)), "220x0")
        XCTAssertEqual(DisplayCheck.dims(CGSize(width: CGFloat.infinity, height: 1)), "0x0")
    }

    private func decide(_ translation: CGFloat, predicted: CGFloat, hasPrevious: Bool = true, hasNext: Bool = true) -> Int {
        PageSwipe.decision(
            translation: translation, predictedEnd: predicted, height: height,
            hasPrevious: hasPrevious, hasNext: hasNext
        )
    }
}

/// 画面不裁切的布局前提（裁决 5）：垫底的缩略图再宽，也不能把页面或播放层撑出窗格。
/// 用一张 16:9 的缩略图垫底，按 iPhone 与 iPad 的竖屏窗格真实布局一次，量播放层的 frame。
@MainActor
final class PhoneRoomPageLayoutTests: XCTestCase {
    private static let panes = [CGSize(width: 402, height: 874), CGSize(width: 834, height: 1210)]
    private var suites: [String] = []
    private var windows: [UIWindow] = []

    override func tearDown() {
        for window in windows { window.isHidden = true }
        windows = []
        for name in suites { UserDefaults().removePersistentDomain(forName: name) }
        suites = []
        super.tearDown()
    }

    func testPlayerLayerEqualsPaneWithWideThumbnail() throws {
        for pane in Self.panes {
            let room = try wideThumbnailRoom()
            let stage = makeStage()
            let page = PhoneRoomPage(
                room: room, active: true, model: stage, lane: stage.primary, playback: stage.primary.playback
            )
            .frame(width: pane.width, height: pane.height)
            let host = try host(page, size: pane)
            let player = try XCTUnwrap(Self.find(PlayerUIView.self, in: host.view), "当前页要挂播放层")
            let frame = player.convert(player.bounds, to: host.view)
            XCTAssertEqual(frame.width, pane.width, accuracy: 1, "窗格 \(pane) 下播放层宽度：\(frame)")
            XCTAssertEqual(frame.height, pane.height, accuracy: 1, "窗格 \(pane) 下播放层高度：\(frame)")
        }
    }

    func testSwapBackdropTakesProposedSizeWithWideThumbnail() throws {
        for pane in Self.panes {
            let renderer = ImageRenderer(content: SwapBackdrop(room: try wideThumbnailRoom()))
            renderer.proposedSize = ProposedViewSize(pane)
            renderer.scale = 1
            let image = try XCTUnwrap(renderer.cgImage)
            XCTAssertEqual(CGFloat(image.width), pane.width, accuracy: 1, "垫底按缩略图铺满后的宽度撑大了自己")
            XCTAssertEqual(CGFloat(image.height), pane.height, accuracy: 1)
        }
    }

    // MARK: 辅助

    private func makeStage() -> StageModel {
        let name = "vela-phone-layout-\(UUID().uuidString)"
        suites.append(name)
        return StageModel(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
    }

    /// 缓存里预置一张 192×108（16:9）的缩略图，垫底同步命中，不走网络。
    private func wideThumbnailRoom() throws -> Room {
        let room = try XCTUnwrap(Room.direct(username: "layout_\(UUID().uuidString.prefix(8))"))
        let context = try XCTUnwrap(CGContext(
            data: nil, width: 192, height: 108, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.setFillColor(CGColor(red: 0.8, green: 0.4, blue: 0.2, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 192, height: 108))
        RoomThumbnailCache.shared.store(try XCTUnwrap(context.makeImage()), for: try XCTUnwrap(room.thumbnailURL))
        return room
    }

    private func host(_ view: some View, size: CGSize) throws -> UIHostingController<some View> {
        let scene = try XCTUnwrap(
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first, "测试宿主没有窗口场景"
        )
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        let host = UIHostingController(rootView: view.ignoresSafeArea())
        window.rootViewController = host
        window.isHidden = false
        windows.append(window)
        host.view.frame = window.bounds
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        return host
    }

    private static func find<T: UIView>(_ type: T.Type, in view: UIView) -> T? {
        if let match = view as? T { return match }
        for child in view.subviews {
            if let match = find(type, in: child) { return match }
        }
        return nil
    }
}
