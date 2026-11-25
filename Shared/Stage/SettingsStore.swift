import Foundation

final class SettingsStore {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = UserDefaults(suiteName: "com.vela.stage") ?? .standard) {
        self.defaults = defaults
    }

    /// 用户是否明确设过这个键（docs/plans/sync.md 第 14.1 节）：未设过的缺省值开启同步时不盖章。
    func hasExplicitValue(forKey key: String) -> Bool { defaults.object(forKey: key) != nil }

    /// 实验：跨设备同步（默认关）。关着时不显示同步入口、不联网、不读钥匙串。
    var experimentSync: Bool {
        get { defaults.bool(forKey: "experimentSync") }
        set { defaults.set(newValue, forKey: "experimentSync") }
    }

    /// 明确设过的同步设置值（加入快照用）；nil 表示没设过。
    func explicitBool(forKey key: String) -> Bool? { defaults.object(forKey: key) as? Bool }

    /// 按快照写回：nil 表示恢复成「没设过」。
    func setExplicit(_ key: String, _ value: Bool?) {
        if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
    }

    /// 同步投影写入后提示系统落盘（第 12.2 节第 3 步）。
    @discardableResult
    func synchronize() -> Bool { defaults.synchronize() }

    var ageConfirmed: Bool {
        get { defaults.bool(forKey: "ageConfirmed") }
        set { defaults.set(newValue, forKey: "ageConfirmed") }
    }

    var pinned: Bool {
        get { defaults.object(forKey: "pinned") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "pinned") }
    }

    var conditionalOpacity: Bool {
        get { defaults.object(forKey: "conditionalOpacity") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "conditionalOpacity") }
    }

    var clickThrough: Bool {
        get { defaults.object(forKey: "clickThrough") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "clickThrough") }
    }

    var restingOpacity: Double {
        get { defaults.object(forKey: "restingOpacity") as? Double ?? 0.42 }
        set { defaults.set(newValue, forKey: "restingOpacity") }
    }

    var engagedOpacity: Double {
        get { defaults.object(forKey: "engagedOpacity") as? Double ?? 1 }
        set { defaults.set(newValue, forKey: "engagedOpacity") }
    }

    var restSavesPower: Bool {
        get { defaults.object(forKey: "restSavesPower") as? Bool ?? false }
        set { defaults.set(newValue, forKey: "restSavesPower") }
    }

    var publicOnly: Bool {
        get { defaults.object(forKey: "publicOnly") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "publicOnly") }
    }

    var fitFill: Bool {
        get { defaults.object(forKey: "fitFill") as? Bool ?? false }
        set { defaults.set(newValue, forKey: "fitFill") }
    }

    /// 房间信息是否始终显示；默认 false，即进入播放 5 秒后自动隐藏。
    var infoAlwaysOn: Bool {
        get { defaults.bool(forKey: "infoAlwaysOn") }
        set { defaults.set(newValue, forKey: "infoAlwaysOn") }
    }

    /// 菜单栏图标是否显示，默认开。
    var showMenuBar: Bool {
        get { defaults.object(forKey: "showMenuBar") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "showMenuBar") }
    }

    var volume: Double {
        get { defaults.object(forKey: "volume") as? Double ?? 0.85 }
        set { defaults.set(newValue, forKey: "volume") }
    }

    /// 键不存在（首次启动）视为静音；用户存过的值原样保留。
    var muted: Bool {
        get { AudioPolicy.initialMuted(stored: defaults.object(forKey: "muted") as? Bool) }
        set { defaults.set(newValue, forKey: "muted") }
    }

    /// iOS：正在播放时回桌面自动进入画中画（修订 A2 的 7a），默认开。macOS 不读。
    var pipAutoEnter: Bool {
        get { defaults.object(forKey: "pipAutoEnter") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "pipAutoEnter") }
    }

    /// iOS 竖屏路数 1 / 2 / 3，默认 1（裁决 1），选择要记住。macOS 不读。
    /// 迁移（修订 v2 M9）：新键存在且合法（1 到 3）就用它；新键存在但非法按 1；
    /// 只有新键不存在时读旧键 `phoneDual`（true → 2，false → 1），迁移结果立即落盘。
    var laneCount: Int {
        get {
            if let stored = defaults.object(forKey: Self.laneCountKey) {
                guard let value = stored as? Int, (1...LaneID.maxCount).contains(value) else { return 1 }
                return value
            }
            guard let old = defaults.object(forKey: Self.legacyDualKey) as? Bool else { return 1 }
            let migrated = old ? 2 : 1
            defaults.set(migrated, forKey: Self.laneCountKey)
            return migrated
        }
        set { defaults.set(min(max(newValue, 1), LaneID.maxCount), forKey: Self.laneCountKey) }
    }

    /// 旧的双窗开关，只作兼容入口：读写都落到 `laneCount`（开 = 至少 2 路，关 = 1 路）。
    var phoneDual: Bool {
        get { laneCount > 1 }
        set {
            if !newValue { laneCount = 1 } else if laneCount < 2 { laneCount = 2 }
        }
    }

    /// 看哪个站点（Chaturbate / Cam4 / 混合），缺省与认不出的值都是 Chaturbate。
    var siteMode: SiteMode {
        get { SiteMode.restore(defaults.string(forKey: "siteMode")) }
        set { defaults.set(newValue.rawValue, forKey: "siteMode") }
    }

    /// 本机偏好文件的房间键格式已迁移到哪一版（`SiteKeyMigration.version`）；没迁移过是 0。
    var siteKeyVersion: Int {
        get { defaults.integer(forKey: "siteKeyVersion") }
        set { defaults.set(newValue, forKey: "siteKeyVersion") }
    }

    private static let laneCountKey = "laneCount"
    private static let legacyDualKey = "phoneDual"

    var hintSeen: Bool {
        get { defaults.bool(forKey: "hintSeen") }
        set { defaults.set(newValue, forKey: "hintSeen") }
    }

    var layoutRevision: Int {
        get { defaults.integer(forKey: "layoutRevision") }
        set { defaults.set(newValue, forKey: "layoutRevision") }
    }

    var gender: Gender? {
        get { defaults.string(forKey: "gender").flatMap(Gender.init(rawValue:)) }
        set { defaults.set(newValue?.rawValue, forKey: "gender") }
    }

    var region: Region? {
        get { defaults.string(forKey: "region").flatMap(Region.init(rawValue:)) }
        set { defaults.set(newValue?.rawValue, forKey: "region") }
    }

    var tag: String {
        get { defaults.string(forKey: "tag") ?? "" }
        set { defaults.set(newValue, forKey: "tag") }
    }

    var source: FeedSource {
        get { FeedSource.restore(defaults.string(forKey: "source")) ?? .guess }
        set { defaults.set(newValue.rawValue, forKey: "source") }
    }

    /// 旧版账号会话是否已清理过。
    var legacyLoginCleared: Bool {
        get { defaults.bool(forKey: "legacyLoginCleared") }
        set { defaults.set(newValue, forKey: "legacyLoginCleared") }
    }
}
