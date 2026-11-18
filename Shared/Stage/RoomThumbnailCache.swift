import Foundation
import ImageIO

/// 房间缩略图的内存缓存。存解码好的 `CGImage`，两个平台都用 `Image(decorative:scale:)` 展示。
@MainActor
final class RoomThumbnailCache {
    static let shared = RoomThumbnailCache()

    /// 缩略图长边最多这么多像素。垫底还会再模糊（约 30pt），菜单栏只画约 64×36。
    private static let maxPixelSize = 192
    /// 约 2 MB。入缓存时按位图字节计费（宽 × 高 × 4）；张数上限仍是 20。
    private static let byteBudget = 2 * 1024 * 1024

    private let cache = NSCache<NSURL, CGImage>()
    private var inflight = [NSURL: Task<CGImage?, Never>]()

    private init() {
        cache.countLimit = 20
        cache.totalCostLimit = Self.byteBudget
    }

    func image(for url: URL?) -> CGImage? {
        guard let url else { return nil }
        return cache.object(forKey: url as NSURL)
    }

    /// 命中缓存则直接返回，否则等待（或与预取共用的）在途下载。
    func imageOrFetch(for url: URL?) async -> CGImage? {
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
    private func beginFetch(url: URL) -> Task<CGImage?, Never>? {
        let key = url as NSURL
        if let cached = cache.object(forKey: key) { return Task { cached } }
        if let existing = inflight[key] { return existing }
        let task = Task<CGImage?, Never> { @MainActor in
            defer { inflight[key] = nil }
            guard let (data, response) = try? await URLSession.shared.data(from: url),
                  let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode) else { return nil }
            let maxPixelSize = Self.maxPixelSize
            // 下载在 URLSession 里挂起，不占主线程；解码放在后台，避免 ImageIO 挡住界面。
            guard let cgImage = await Task.detached { () -> CGImage? in
                RoomThumbnailCache.decodeThumbnail(from: data, maxPixelSize: maxPixelSize)
            }.value else { return nil }
            if cache.object(forKey: key) == nil {
                cache.setObject(cgImage, forKey: key, cost: cgImage.width * cgImage.height * 4)
            }
            return cgImage
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
