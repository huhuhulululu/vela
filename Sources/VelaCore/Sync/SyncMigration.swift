/// 开启或加入时给已有本地数据盖章（docs/plans/sync.md 第 12.4 节第 5–6 步、第 14.1 节）。
public struct SyncLocalData: Equatable, Sendable {
    public var curation: Curation
    /// `Taste` 中 `vetoed == true` 的本机键。
    public var vetoedKeys: [String]
    /// `hasExplicitValue` 为真的同步设置（`publicOnly`、`infoAlwaysOn`）。
    public var explicitSettings: [String: Bool]

    public init(curation: Curation = Curation(), vetoedKeys: [String] = [], explicitSettings: [String: Bool] = [:]) {
        self.curation = curation
        self.vetoedKeys = vetoedKeys
        self.explicitSettings = explicitSettings
    }

    public init(curation: Curation, taste: Taste, explicitSettings: [String: Bool]) {
        self.init(curation: curation, vetoedKeys: Array(taste.vetoedKeys), explicitSettings: explicitSettings)
    }
}

/// 开启前说明页要列出的内容。
public struct SyncMigrationReport: Equatable, Sendable {
    /// 格式不合规、不会同步并将从本机移除的旧名字。
    public var invalidNames: [String] = []
    /// 同步域内超过 450 个、将被取消的「不想看」（同步键）。
    public var droppedSyncVetoes: [String] = []
    /// 同步域外超过 50 个、将被取消的「不想看」（本机键）。
    public var droppedLocalOnlyVetoes: [String] = []
    /// 留在本机的专用「不想看」（本机键）。
    public var localOnlyVetoes: [String] = []
}

/// 加入确认页的计数。
public struct SyncJoinPreview: Codable, Equatable, Sendable {
    /// 将上传的本机收藏、屏蔽、「不想看」数（实际盖章的数量）。
    public var n = 0, m = 0, v = 0
    /// 合并后因超过 300 不再保留的收藏、屏蔽数。
    public var x = 0, y = 0
    /// 本机收藏在云端已屏蔽、将按屏蔽处理的数量。
    public var z = 0

    public init(n: Int = 0, m: Int = 0, v: Int = 0, x: Int = 0, y: Int = 0, z: Int = 0) {
        (self.n, self.m, self.v, self.x, self.y, self.z) = (n, m, v, x, y, z)
    }
}

public enum SyncMigration {
    /// 前置：两个 store 启动时的读取结果都是「正常读取」或「文件不存在」。
    public static func canEnable(curation: PreferenceLoadOutcome?, taste: PreferenceLoadOutcome?) -> Bool {
        let ok: Set<PreferenceLoadOutcome> = [.loaded, .missing]
        guard let curation, let taste else { return false }
        return ok.contains(curation) && ok.contains(taste)
    }

    struct Prepared {
        /// 原数组顺序（最新在前）。
        var favorites: [(name: String, key: String)] = []
        var blocked: [(name: String, key: String)] = []
        /// 同步键，UTF-8 字节升序，至多 450 个。
        var vetoes: [String] = []
        var settings: [(key: String, value: Bool)] = []
        var report = SyncMigrationReport()
    }

    static func prepare(_ local: SyncLocalData) -> Prepared {
        var p = Prepared()
        func split(_ names: [String]) -> [(name: String, key: String)] {
            var out: [(name: String, key: String)] = []
            for name in names {
                if let key = SyncKeys.roomKey(localName: name) { out.append((name, key)) } else { p.report.invalidNames.append(name) }
            }
            return out
        }
        p.favorites = split(local.curation.favorites)
        p.blocked = split(local.curation.blocked)
        var synced = Set<String>()
        var localOnly: [String] = []
        for key in local.vetoedKeys {
            if let s = SyncKeys.syncVetoKey(localKey: key) { synced.insert(s) } else { localOnly.append(key) }
        }
        let sortedSynced = SyncDocument.sortedKeys(synced)
        p.vetoes = Array(sortedSynced.prefix(SyncLimits.activeVetoes))
        p.report.droppedSyncVetoes = Array(sortedSynced.dropFirst(SyncLimits.activeVetoes))
        let sortedLocal = SyncDocument.sortedKeys(localOnly)
        p.report.localOnlyVetoes = Array(sortedLocal.prefix(SyncLimits.localOnlyVetoes))
        p.report.droppedLocalOnlyVetoes = Array(sortedLocal.dropFirst(SyncLimits.localOnlyVetoes))
        p.settings = SyncDocument.sortedKeys(local.explicitSettings.keys.filter { SyncKeys.settingKeys.contains($0) })
            .map { ($0, local.explicitSettings[$0]!) }
        return p
    }

    /// 只写 `+`（及需要时的 `n`），不写成对的 `-`。
    static func stampFavorite(_ item: (name: String, key: String), into doc: inout SyncDocument, h: HLC) {
        var room = doc.rooms[item.key] ?? SyncRoom()
        room.f = SyncRegister(hlc: h, payload: "+")
        stampDisplay(item, room: &room, h: h)
        doc.rooms[item.key] = room
    }

    static func stampBlock(_ item: (name: String, key: String), into doc: inout SyncDocument, h: HLC) {
        var room = doc.rooms[item.key] ?? SyncRoom()
        room.b = SyncRegister(hlc: h, payload: "+")
        stampDisplay(item, room: &room, h: h)
        doc.rooms[item.key] = room
    }

    static func stampDisplay(_ item: (name: String, key: String), room: inout SyncRoom, h: HLC) {
        SyncOps.writeDisplay(item.key, original: item.name, room: &room, h: h)
    }

    /// 创建方全部盖章：收藏从末尾往前逐个 tick，屏蔽同理，`veto` 按键字节升序，设置按键名升序。
    public static func stampCreator(_ local: SyncLocalData, clock: SyncClock, pt: UInt64) -> (replica: SyncReplica, report: SyncMigrationReport, fits: Bool) {
        let p = prepare(local)
        var clock = clock
        var doc = SyncDocument()
        for item in p.favorites.reversed() { stampFavorite(item, into: &doc, h: clock.tick(pt: pt)) }
        for item in p.blocked.reversed() { stampBlock(item, into: &doc, h: clock.tick(pt: pt)) }
        for key in p.vetoes { doc.veto[key] = SyncRegister(hlc: clock.tick(pt: pt), payload: "+") }
        for s in p.settings { doc.settings[s.key] = SyncRegister(hlc: clock.tick(pt: pt), payload: s.value ? "1" : "0") }
        let fitted = SyncMerge.fitSize(doc)
        return (SyncReplica(doc: fitted.doc, clock: clock), p.report, fitted.fits)
    }

    /// 加入方按第 12.4 节第 6 步盖章，再与远端合并并做体积处理。先让时钟看见远端，保证盖章排在远端之后。
    public static func stampJoin(
        _ local: SyncLocalData, remote: SyncDocument, clock: SyncClock, pt: UInt64
    ) -> (replica: SyncReplica, preview: SyncJoinPreview, report: SyncMigrationReport, fits: Bool) {
        let p = prepare(local)
        let theirs = SyncProjection(remote)
        var clock = clock
        clock.receive(remote.maxHLC)
        var doc = SyncDocument()
        var preview = SyncJoinPreview()
        for item in p.favorites.reversed() {
            if theirs.isBlocked(item.key) {
                preview.z += 1
            } else if !theirs.isFavorite(item.key) {
                stampFavorite(item, into: &doc, h: clock.tick(pt: pt))
                preview.n += 1
            }
        }
        for item in p.blocked.reversed() where !theirs.isBlocked(item.key) {
            stampBlock(item, into: &doc, h: clock.tick(pt: pt))
            preview.m += 1
        }
        for key in p.vetoes where theirs.veto[key] != true {
            doc.veto[key] = SyncRegister(hlc: clock.tick(pt: pt), payload: "+")
            preview.v += 1
        }
        for s in p.settings where remote.settings[s.key] == nil {
            doc.settings[s.key] = SyncRegister(hlc: clock.tick(pt: pt), payload: s.value ? "1" : "0")
        }
        let united = SyncMerge.union(doc, remote)
        let fitted = SyncMerge.fitSize(united)
        for (key, room) in united.rooms {
            let kept = fitted.doc.rooms[key]
            if room.isFavorite, kept?.isFavorite != true { preview.x += 1 }
            if room.isBlocked, kept?.isBlocked != true { preview.y += 1 }
        }
        return (SyncReplica(doc: fitted.doc, clock: clock), preview, p.report, fitted.fits)
    }
}
