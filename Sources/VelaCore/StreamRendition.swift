import Foundation

public struct HLSAttribute: Equatable, Sendable {
    public var key: String
    /// 原样保留（含引号）。
    public var value: String

    public var unquoted: String {
        value.count >= 2 && value.hasPrefix("\"") && value.hasSuffix("\"") ? String(value.dropFirst().dropLast()) : value
    }

    /// 解析 `KEY=VALUE,KEY="a,b"`，引号里的逗号不分割。
    public static func parse(_ text: Substring) -> [HLSAttribute] {
        var result: [HLSAttribute] = []
        var token = ""
        var quoted = false
        func flush() {
            if let eq = token.firstIndex(of: "=") {
                result.append(HLSAttribute(key: String(token[..<eq]), value: String(token[token.index(after: eq)...])))
            }
            token = ""
        }
        for character in text {
            if character == "\"" { quoted.toggle() }
            if character == ",", !quoted { flush() } else { token.append(character) }
        }
        flush()
        return result
    }

    public static func value(_ key: String, in attributes: [HLSAttribute]) -> String? {
        attributes.first { $0.key == key }?.unquoted
    }
}

/// 选中的一个画质档：视频子列表，以及它引用的独立音轨子列表（音频混在视频里时为 nil）。
/// 主列表 30 秒就过期，子列表地址长期有效：起播时取一次主列表记下这两个地址，之后只刷新它们。
public struct StreamRendition: Equatable, Sendable {
    public var video: URL
    public var audio: URL?
    /// 选中档的 EXT-X-STREAM-INF 属性（BANDWIDTH / RESOLUTION / CODECS / FRAME-RATE / AUDIO 等）。
    public var videoAttributes: [HLSAttribute]
    /// 选中音轨的 EXT-X-MEDIA 属性（不含 URI）。
    public var audioAttributes: [HLSAttribute]
    /// 还没从主列表选档：`video` 是主列表（或已是媒体列表）地址，由中继起播时取一次并选档。
    public var isUnselected: Bool

    public init(video: URL, audio: URL? = nil, videoAttributes: [HLSAttribute] = [], audioAttributes: [HLSAttribute] = [], isUnselected: Bool = false) {
        self.video = video
        self.audio = audio
        self.videoAttributes = videoAttributes
        self.audioAttributes = audioAttributes
        self.isUnselected = isUnselected
    }

    public static func unselected(_ master: URL) -> StreamRendition {
        StreamRendition(video: master, isUnselected: true)
    }

    private static let streamKeys: Set<String> = ["BANDWIDTH", "AVERAGE-BANDWIDTH", "RESOLUTION", "CODECS", "FRAME-RATE", "VIDEO-RANGE", "AUDIO"]

    /// 最高的不超过 `maxBandwidth` 的档（都超过则取最低），连同它引用的默认音轨。
    public static func select(in playlist: String, master: URL, maxBandwidth: Double) -> StreamRendition? {
        let lines = playlist.split(whereSeparator: \.isNewline).map { String($0).trimmingCharacters(in: .whitespaces) }
        var variants: [(bandwidth: Int, attributes: [HLSAttribute], uri: String)] = []
        var audioMedia: [[HLSAttribute]] = []
        for (index, line) in lines.enumerated() {
            if line.hasPrefix("#EXT-X-MEDIA:") {
                let attributes = HLSAttribute.parse(line.dropFirst("#EXT-X-MEDIA:".count))
                if HLSAttribute.value("TYPE", in: attributes) == "AUDIO" { audioMedia.append(attributes) }
            } else if line.hasPrefix("#EXT-X-STREAM-INF:") {
                let attributes = HLSAttribute.parse(line.dropFirst("#EXT-X-STREAM-INF:".count))
                guard let bandwidth = HLSAttribute.value("BANDWIDTH", in: attributes).flatMap(Int.init),
                      let uri = lines.dropFirst(index + 1).first(where: { !$0.isEmpty && !$0.hasPrefix("#") }) else { continue }
                variants.append((bandwidth, attributes, uri))
            }
        }
        let eligible = variants.filter { Double($0.bandwidth) <= maxBandwidth + 20_000 }
        guard let chosen = eligible.max(by: { $0.bandwidth < $1.bandwidth }) ?? variants.min(by: { $0.bandwidth < $1.bandwidth }),
              let video = resolve(chosen.uri, base: master) else { return nil }
        var rendition = StreamRendition(video: video, videoAttributes: chosen.attributes.filter { streamKeys.contains($0.key) })
        if let group = HLSAttribute.value("AUDIO", in: chosen.attributes) {
            let candidates = audioMedia.filter {
                HLSAttribute.value("GROUP-ID", in: $0) == group && HLSAttribute.value("URI", in: $0) != nil
            }
            let pick = candidates.first { HLSAttribute.value("DEFAULT", in: $0) == "YES" } ?? candidates.first
            if let pick, let uri = HLSAttribute.value("URI", in: pick), let audio = resolve(uri, base: master) {
                rendition.audio = audio
                rendition.audioAttributes = pick.filter { $0.key != "URI" }
            } else {
                rendition.videoAttributes.removeAll { $0.key == "AUDIO" }
            }
        }
        return rendition
    }

    /// 给 AVPlayer 的本地主列表：只含选中的这一档，视频与音轨都指向本地中继地址。
    /// 没有独立音轨时不需要主列表（直接给视频媒体列表）。
    public func localMaster(base: String) -> String? {
        guard audio != nil else { return nil }
        var lines = ["#EXTM3U", "#EXT-X-VERSION:7"]
        let media = audioAttributes + [HLSAttribute(key: "URI", value: "\"\(base)/audio.m3u8\"")]
        lines.append("#EXT-X-MEDIA:" + media.map { "\($0.key)=\($0.value)" }.joined(separator: ","))
        lines.append("#EXT-X-STREAM-INF:" + videoAttributes.map { "\($0.key)=\($0.value)" }.joined(separator: ","))
        lines.append("\(base)/video.m3u8")
        return lines.joined(separator: "\n") + "\n"
    }

    private static func resolve(_ reference: String, base: URL) -> URL? {
        if let absolute = URL(string: reference), absolute.scheme != nil { return absolute }
        return URL(string: reference, relativeTo: base)?.absoluteURL
    }
}
