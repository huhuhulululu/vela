import Foundation

final class SettingsStore {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = UserDefaults(suiteName: "com.vela.stage") ?? .standard) {
        self.defaults = defaults
    }

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
