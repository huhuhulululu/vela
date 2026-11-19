import Foundation

/// 当前看哪个站点。缺省与认不出的值都是 Chaturbate。
public enum SiteMode: String, CaseIterable, Codable, Sendable {
    case chaturbate
    case cam4
    case mixed

    public var sites: [SiteID] {
        switch self {
        case .chaturbate: return [.chaturbate]
        case .cam4: return [.cam4]
        case .mixed: return [.chaturbate, .cam4]
        }
    }

    @_optimize(size)
    public static func restore(_ raw: String?) -> SiteMode {
        guard let raw, let mode = SiteMode(rawValue: raw) else { return .chaturbate }
        return mode
    }
}

/// 混合目录的各站游标。单站模式不靠它分页（直接用 `CatalogQuery.offset`）。
public struct MixedCursor: Equatable, Sendable {
    public var offsets: [SiteID: Int]
    public var totals: [SiteID: Int]
    public var ended: Set<SiteID>

    @_optimize(size)
    public init(sites: [SiteID]) {
        offsets = Dictionary(uniqueKeysWithValues: sites.map { ($0, 0) })
        totals = Dictionary(uniqueKeysWithValues: sites.map { ($0, 0) })
        ended = []
    }

    public var isFinished: Bool {
        !offsets.isEmpty && offsets.keys.allSatisfy { ended.contains($0) }
    }
}

public enum SiteCatalog {
    /// 按 mode 向各站取一页（并发），合并成一个 `CatalogPage`。
    /// 各站保持自己的顺序，轮流各取一间交错；按 ref 去重。
    /// `total` 是各站 total 之和，`nextOffset` 是各站 offset 之和。
    /// 混合模式一站失败另一站成功：返回成功那站，并在 `failures` 里记下失败站。全失败抛第一站的错误。
    /// 单站模式的页与直接调那一站的 `fetchDirectory` 相同。
    @_optimize(size)
    public static func fetch(
        mode: SiteMode,
        query: CatalogQuery,
        cursor: MixedCursor,
        registry: SiteRegistry
    ) async throws -> (page: CatalogPage, cursor: MixedCursor, failures: [SiteID: Error]) {
        let sites = mode.sites
        var results: [SiteID: Result<CatalogPage, Error>] = [:]
        await withTaskGroup(of: (SiteID, Result<CatalogPage, Error>).self) { group in
            for site in sites where !cursor.ended.contains(site) {
                let siteQuery = queryFor(site, mode: mode, query: query, cursor: cursor)
                group.addTask {
                    guard let adapter = registry.adapter(for: site) else {
                        return (site, .failure(SiteError.unknownSite(site.rawValue)))
                    }
                    do {
                        return (site, .success(try await adapter.fetchDirectory(siteQuery)))
                    } catch {
                        return (site, .failure(error))
                    }
                }
            }
            for await (site, result) in group {
                results[site] = result
            }
        }

        var failures: [SiteID: Error] = [:]
        var firstError: Error?
        for site in sites {
            if case .failure(let error) = results[site] {
                failures[site] = error
                if firstError == nil { firstError = error }
            }
        }
        let successes = sites.filter { if case .success = results[$0] { return true } else { return false } }
        if successes.isEmpty, let firstError, !sites.allSatisfy({ cursor.ended.contains($0) }) {
            throw firstError
        }

        var next = cursor
        var lists: [[Room]] = []
        for site in sites {
            guard case .success(let page) = results[site] else { continue }
            next.offsets[site] = page.nextOffset
            next.totals[site] = page.total
            if page.rooms.isEmpty || page.nextOffset >= page.total {
                next.ended.insert(site)
            } else {
                next.ended.remove(site)
            }
            lists.append(page.rooms)
        }

        let merged = interleave(lists)
        let total = sites.reduce(0) { $0 + (next.totals[$1] ?? 0) }
        let nextOffset = sites.reduce(0) { $0 + (next.offsets[$1] ?? 0) }
        return (CatalogPage(rooms: merged, total: total, nextOffset: nextOffset), next, failures)
    }

    @_optimize(size)
    private static func queryFor(_ site: SiteID, mode: SiteMode, query: CatalogQuery, cursor: MixedCursor) -> CatalogQuery {
        var copy = query
        if mode == .mixed {
            copy.offset = cursor.offsets[site] ?? 0
        }
        return copy
    }

    /// 各站顺序不变，轮流各取一间；同一 ref 只留先出现的。
    @_optimize(size)
    static func interleave(_ lists: [[Room]]) -> [Room] {
        var merged: [Room] = []
        var seen = Set<String>()
        var index = 0
        var produced = true
        while produced {
            produced = false
            for list in lists where index < list.count {
                produced = true
                let room = list[index]
                if seen.insert(room.identity).inserted { merged.append(room) }
            }
            index += 1
        }
        return merged
    }
}
