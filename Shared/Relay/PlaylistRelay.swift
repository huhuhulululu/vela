#if canImport(Network)
import Foundation
import Network
import os
#if SWIFT_PACKAGE
import VelaCore
#endif

/// 本地中继的主机名：监听只绑回环，本地地址一律由 `PlaylistRelay.localBase(port:)` 生成。
enum RelayHost {
    static let name = "127.0.0.1"
}

/// Serves normal, absolute HLS playlists on localhost. AVPlayer loads `/<id>/master.m3u8`.
/// The relay selects one rung at start (one master fetch; the master URL expires in ~30 s while the
/// video / audio media playlists stay valid), then refreshes only those two playlists in the
/// background (one request in flight per track, results never move progress backwards). While
/// audio is suppressed (muted, and the player can leave the audible group empty) the audio
/// loop stays off; a player request for audio.m3u8 still fetches on demand. The relay drops
/// low-latency partials, and answers AVPlayer from the newest known content. When the rung has a
/// separate audio rendition, AVPlayer gets a local master with exactly that video + audio pair;
/// muxed sources are served as a single media playlist. Freshness is judged per track by the last
/// full segment advancing, not by upstream returning 200: past the grace (see `RelayPolicy`) the
/// request is held for a bounded wait for the next segment (`RelayWait`), and only then does
/// AVPlayer get a 503 and recover. The relay never redirects to upstream: a track with no list
/// at all answers 503 and playback failure / retry handles it. Only events are logged.
///
/// 多源：每个源有自己的 id、列表、循环与在途请求，刷新与静音标志记在 `RelaySourceTable`（最多两个源，
/// 再登记时淘汰最旧的）。调用方只能动自己登记的源（`source:`）。监听有代次：`start` 与 `stopListener`
/// 各加一，旧监听的回调核对代次后不再改状态；`stopListener` 同时关掉已登记的连接与应答任务并清空源表。
/// `allowStart` 关闭期间（iOS 挂起）不起监听；macOS 恒为开。
final class PlaylistRelay: @unchecked Sendable {
    static let shared = PlaylistRelay()

    private typealias Track = RelayTrack

    /// 一个播放源的全部可变状态；只在 `lock` 内读写。
    private struct Entry {
        var maxBandwidth: Double
        var rendition: StreamRendition
        var feeds: [Track: RelayFeed] = [.video: RelayFeed()]
        var inflight: [Track: Task<Void, Never>] = [:]
        /// 上游地址 403 时请上层重新解析当前房间、返回新的子列表地址（拿不到返回 nil）。
        var renew: (@Sendable () async -> StreamRendition?)?
        /// 续期的共享任务、预算与「续期后重取」状态；随源作废时取消。
        let renewal = RenewalCoordinator()
        /// 刚换过子列表地址、还没成功采纳过新列表的路：下一次成功取回直接采纳（新旧列表进度不可比）。
        var freshAddress: Set<Track> = []
    }

    private enum Reply {
        case body(Data)
        case unavailable(noAdvanceFor: TimeInterval, failure: RelayFailure?)
    }

    private static let log = Logger(subsystem: "com.vela.stage", category: "relay")

    private let lock = NSLock()
    private let queue = DispatchQueue(label: "vela.playlist-relay")
    private let session: URLSession
    /// 每个源的刷新与静音标志（静音且播放器能不选独立音轨时不后台刷新 audio，按需应答不受影响）。
    private var sources = RelaySourceTable()
    private var entries: [String: Entry] = [:]
    private var loops: [String: [Track: Task<Void, Never>]] = [:]
    private var listener: NWListener?
    private var port: UInt16 = 0
    /// 监听代次：`start` 起新监听与 `stopListener` 时加一；监听回调与新连接先核对代次。
    private var listenerEpoch = 0
    /// 当前监听收下的连接与正在应答的任务；`stopListener` 时一并取消。
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    private var replies: [UUID: Task<Void, Never>] = [:]
    private var startAllowed = true

    private convenience init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 4
        configuration.httpShouldSetCookies = false
        self.init(session: URLSession(configuration: configuration))
    }

    /// 测试用：注入上游会话（例如挂了 `URLProtocol` 假上游的会话）。
    init(session: URLSession) {
        self.session = session
    }

    /// 本地地址前缀：`http://<RelayHost.name>:<port>`。所有本地地址都由它生成。
    static func localBase(port: UInt16) -> String {
        "http://\(RelayHost.name):\(port)"
    }

    /// 关闭期间（iOS 挂起）`start` 与 `waitUntilListening` 直接返回 false；已在监听的不受影响，要停用 `stopListener`。
    var allowStart: Bool {
        get { lock.withLock { startAllowed } }
        set { lock.withLock { startAllowed = newValue } }
    }

    /// 登记一个新源，返回它的 id 与本地主列表地址。端口还没绑好时触发监听并返回 nil。
    /// 表里已满两个源时淘汰最旧的那个（它的循环与在途一并取消）；调用方换源前应先作废自己的旧源。
    func localURL(
        for rendition: StreamRendition, maxBandwidth: Double, audioSuppressed: Bool,
        renew: (@Sendable () async -> StreamRendition?)? = nil
    ) -> (id: String, url: URL)? {
        lock.lock()
        let bound = port
        let id = UUID().uuidString
        guard bound != 0, let url = URL(string: "\(Self.localBase(port: bound))/\(id)/master.m3u8") else {
            lock.unlock()
            start()
            return nil
        }
        if let evicted = sources.register(id: id, audioSuppressed: audioSuppressed) { cancelSourceLocked(evicted) }
        var entry = Entry(maxBandwidth: maxBandwidth, rendition: rendition, renew: renew)
        if !rendition.isUnselected, rendition.audio != nil { entry.feeds[.audio] = RelayFeed() }
        entries[id] = entry
        lock.unlock()
        return (id, url)
    }

    /// 等本地监听就绪（最多 `timeout` 秒）：起播时端口可能还没绑好。已取消、不允许起监听、监听失败或超时返回 false。
    func waitUntilListening(timeout: TimeInterval = 1.5) async -> Bool {
        guard !Task.isCancelled, start() else { return false }
        let deadline = Date().addingTimeInterval(timeout)
        while lock.withLock({ port == 0 && startAllowed }), Date() < deadline, !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 25_000_000)
        }
        return lock.withLock { startAllowed && port != 0 }
    }

    /// 这个源是否在后台刷新。暂停 / 老板键 / 休眠 / 停止时关掉（不拉流）；恢复时打开。源不在表里时不做事。
    func setRefreshing(_ on: Bool, source id: String) {
        lock.lock()
        defer { lock.unlock() }
        guard let current = entries[id] else { return }
        sources.setRefreshing(on, id: id)
        guard on else {
            cancelRefreshLocked(id)
            return
        }
        current.renewal.resume()
        startLoopLocked(id, .video)
        startAudioLoopIfAllowedLocked(id)
    }

    /// 静音且能够取消独立音轨时为 true。关掉时停掉这个源音频清单的后台刷新（不取消在途、不清已有列表，
    /// 避免正在应答的 audio.m3u8 突然 503）；打开且此刻允许播放时立刻恢复，取消静音才能马上拿到新清单。
    func setAudioSuppressed(_ suppressed: Bool, source id: String) {
        lock.lock()
        defer { lock.unlock() }
        guard sources.contains(id: id), sources.isAudioSuppressed(id: id) != suppressed else { return }
        sources.setAudioSuppressed(suppressed, id: id)
        if suppressed {
            stopAudioLoopLocked(id)
        } else {
            startAudioLoopIfAllowedLocked(id)
        }
    }

    /// 这个源此刻是否有续期在进行（卡住看门狗据此重新计时，不把续期中的等待当成卡住）。
    func isRenewing(source id: String) -> Bool {
        lock.withLock { entries[id]?.renewal.isActive == true }
    }

    /// 停止播放：作废这个源，不再刷新。别的源不受影响。
    func deactivate(source id: String) {
        lock.lock()
        cancelSourceLocked(id)
        lock.unlock()
    }

    /// 有独立音轨、这个源在刷新、且没有因为静音停掉音频时，才跑音频清单循环。
    private func startAudioLoopIfAllowedLocked(_ id: String) {
        guard entries[id]?.feeds[.audio] != nil, sources.shouldRunLoop(id: id, track: .audio) else { return }
        startLoopLocked(id, .audio)
    }

    /// 只停后台循环。在途请求让它走完，已缓存的列表留着，按需应答还能用。
    private func stopAudioLoopLocked(_ id: String) {
        loops[id]?[.audio]?.cancel()
        loops[id]?[.audio] = nil
    }

    private func startLoopLocked(_ id: String, _ track: Track) {
        guard entries[id]?.feeds[track] != nil, loops[id]?[track] == nil else { return }
        loops[id, default: [:]][track] = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.refresh(id, track)
                let delay = self.lock.withLock { self.entries[id]?.feeds[track]?.nextRefreshDelay() }
                guard let delay else { return }
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            }
        }
    }

    private func cancelRefreshLocked(_ id: String) {
        entries[id]?.renewal.cancel()
        for task in (loops.removeValue(forKey: id) ?? [:]).values { task.cancel() }
        guard var current = entries[id] else { return }
        for track in Array(current.feeds.keys) {
            current.inflight[track]?.cancel()
            current.feeds[track]?.cancel()
        }
        current.inflight.removeAll()
        entries[id] = current
    }

    private func cancelSourceLocked(_ id: String) {
        cancelRefreshLocked(id)
        entries[id] = nil
        sources.remove(id: id)
    }

    // MARK: 刷新（每路单次在途、作废后结果丢弃）

    /// 同一路只有一个在途请求；已有在途时等它，不另发。
    private func refresh(_ id: String, _ track: Track) async {
        let task: Task<Void, Never>? = lock.withLock {
            guard var current = entries[id], current.feeds[track] != nil else { return nil }
            if let running = current.inflight[track] { return running }
            guard let ticket = current.feeds[track]?.begin() else { return nil }
            let created = Task { [weak self] in
                guard let self else { return }
                await self.perform(id, track, ticket: ticket)
            }
            current.inflight[track] = created
            entries[id] = current
            return created
        }
        await task?.value
    }

    private func perform(_ id: String, _ track: Track, ticket: RelayFeed.Ticket) async {
        let started = Date()
        let outcome: RelayFeed.Outcome
        do {
            let (body, info) = try await fetchTrack(id, track)
            outcome = .success(body: body, info: info)
        } catch {
            outcome = .failure(RelayFailure.classify(error))
        }
        let now = Date()
        let applied: RelayFeed.Applied? = lock.withLock {
            guard var current = entries[id] else { return nil }
            let result = current.feeds[track]?.finish(
                ticket, outcome: outcome, now: now, addressChanged: current.freshAddress.contains(track)
            )
            if result != .discarded { current.inflight[track] = nil }
            if case .success = outcome, result != .discarded { current.freshAddress.remove(track) }
            entries[id] = current
            return result
        }
        guard let applied else { return }
        record(applied, track: track, elapsed: now.timeIntervalSince(started))
    }

    /// 取一路媒体列表；上游 403（地址闲置过期）时请上层续期，拿到新地址后同一次刷新内重试一次。
    private func fetchTrack(_ id: String, _ track: Track) async throws -> (Data, MediaPlaylistInfo) {
        let (stale, renewal) = lock.withLock { (entries[id]?.rendition, entries[id]?.renewal) }
        do {
            return try await fetchTrackOnce(id, track)
        } catch let error as RelayFailure where RenewalPolicy.isExpiry(error) {
            guard let stale, let renewal else { throw error }
            return try await renewal.recovering {
                guard await renewRendition(id, stale: stale, renewal: renewal) else { throw error }
                return try await fetchTrackOnce(id, track)
            }
        }
    }

    private func fetchTrackOnce(_ id: String, _ track: Track) async throws -> (Data, MediaPlaylistInfo) {
        let rendition = try await selectedRendition(id)
        guard let url = track == .video ? rendition.video : rendition.audio else { throw RelayFailure.noSegments }
        return try await mediaPlaylist(url)
    }

    /// 续期：视频与音轨可能先后撞上 403，共用一次（在途就等它）；`stale` 之后源已被换过也算成功。
    /// 新地址由共享任务写入（`install`），返回 true 即已写入。受 `RenewalBudget` 限次；
    /// 换上的是同一个活跃源，本地主列表地址不变，AVPlayer 无感。
    private func renewRendition(_ id: String, stale: StreamRendition, renewal: RenewalCoordinator) async -> Bool {
        let source: (renew: (@Sendable () async -> StreamRendition?)?, replaced: Bool, epoch: Int)? = lock.withLock {
            guard let current = entries[id] else { return nil }
            return (current.renew, current.rendition != stale, current.renewal.epoch)
        }
        guard let source else { return false }
        if source.replaced { return true }
        guard let renew = source.renew else { return false }
        let ok = await renewal.renew(fetch: renew, apply: { [weak self] fresh in
            self?.install(fresh, id: id, stale: stale, epoch: source.epoch) ?? false
        })
        Self.log.info("renew \(ok ? "ok" : "failed", privacy: .public)")
        return ok
    }

    /// 把续期拿到的新地址写进活跃源（源已换走、或 `stale` 之后已被别处换过则不覆盖）。
    /// `epoch` 是注册续期时的取消代次；取消（在源锁内发生）之后到达的结果一律拒绝。
    private func install(_ fresh: StreamRendition, id: String, stale: StreamRendition, epoch: Int) -> Bool {
        lock.withLock {
            guard var current = entries[id], !fresh.isUnselected, current.renewal.isCurrent(epoch) else { return false }
            guard current.rendition == stale else { return true }
            current.rendition = fresh
            // 子列表地址换了：每一路的下一次成功取回都直接采纳，不拿新列表去和旧地址的进度比。
            current.freshAddress = Set(current.feeds.keys)
            if fresh.audio != nil, current.feeds[.audio] == nil {
                current.feeds[.audio] = RelayFeed()
            }
            entries[id] = current
            if fresh.audio != nil { startAudioLoopIfAllowedLocked(id) }
            return true
        }
    }

    /// 起播时从主列表选一次档并记下视频 / 音轨子列表地址；之后不再碰主列表（30 秒就过期）。
    private func selectedRendition(_ id: String) async throws -> StreamRendition {
        let pending: (StreamRendition, Double)? = lock.withLock {
            guard let current = entries[id] else { return nil }
            return (current.rendition, current.maxBandwidth)
        }
        guard let (rendition, maxBandwidth) = pending else { throw CancellationError() }
        guard rendition.isUnselected else { return rendition }
        let fetched = try await fetch(rendition.video)
        let chosen: StreamRendition
        if PlaylistLadder.steadyPlaylist(fetched.text, base: fetched.final) != nil {
            chosen = StreamRendition(video: fetched.final)
        } else if let picked = StreamRendition.select(in: fetched.text, master: fetched.final, maxBandwidth: maxBandwidth) {
            chosen = picked
        } else {
            throw RelayFailure.noSegments
        }
        return lock.withLock {
            guard var current = entries[id] else { return chosen }
            guard current.rendition.isUnselected else { return current.rendition }
            current.rendition = chosen
            if chosen.audio != nil {
                current.feeds[.audio] = RelayFeed()
            }
            entries[id] = current
            if chosen.audio != nil { startAudioLoopIfAllowedLocked(id) }
            return chosen
        }
    }

    private func record(_ applied: RelayFeed.Applied, track: Track, elapsed: TimeInterval) {
        let ms = Int(elapsed * 1000)
        let name = track.label
        #if DEBUG
        if track == .audio { selftestAudioFetchCount.withLock { $0 += 1 } }
        #endif
        switch applied {
        case .failed(let failure):
            Self.log.error("fetch-failed \(name, privacy: .public) \(failure.label, privacy: .public) \(ms)ms")
        case .advanced(let gap):
            // 恢复前间隔超过一个分片周期才值得记；正常前进不写。
            if gap > RelayPolicy.defaultTargetDuration {
                Self.log.info("segment-resumed \(name, privacy: .public) after \(gap, format: .fixed(precision: 1))s \(ms)ms")
            } else if ms > 1000 {
                Self.log.info("fetch-slow \(name, privacy: .public) \(ms)ms")
            }
        case .older:
            Self.log.info("fetch-older \(name, privacy: .public) ignored \(ms)ms")
        case .unchanged, .discarded:
            if ms > 1000 { Self.log.info("fetch-slow \(name, privacy: .public) \(ms)ms") }
        }
    }

    private func mediaPlaylist(_ url: URL) async throws -> (Data, MediaPlaylistInfo) {
        let fetched = try await fetch(url)
        guard let steady = PlaylistLadder.steadyPlaylist(fetched.text, base: fetched.final),
              let info = MediaPlaylistInfo.parse(steady) else { throw RelayFailure.noSegments }
        return (Data(steady.utf8), info)
    }

    private func fetch(_ url: URL) async throws -> (text: String, final: URL) {
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 4
        request.setValue(UserAgent.safari, forHTTPHeaderField: "User-Agent")
        request.setValue("https://chaturbate.com/", forHTTPHeaderField: "Referer")
        request.setValue("https://chaturbate.com", forHTTPHeaderField: "Origin")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw RelayFailure.badBody }
        guard (200..<300).contains(http.statusCode) else { throw RelayFailure.http(http.statusCode) }
        guard let text = String(data: data, encoding: .utf8), text.contains("#EXTM3U") else { throw RelayFailure.badBody }
        return (text, http.url ?? url)
    }

    // MARK: 回答 AVPlayer

    private func reply(_ id: String, _ track: Track) async -> Reply {
        var failure: RelayFailure?
        let target = lock.withLock { entries[id]?.feeds[track]?.info?.targetDuration } ?? RelayPolicy.defaultTargetDuration
        // 宽限内直接回；过期或还没有内容时挂起等分片前进（有界），等不到才失败。
        let serve = await RelayWait.resolve(
            targetDuration: target,
            decide: {
                let decision = self.decide(id, track)
                failure = decision.failure
                return decision.serve
            },
            // 单次上游请求最长 4 秒：刷新也受剩余预算约束，总等待才不会超出 waitBound。
            refresh: { budget in
                await RelayWait.bounded(budget) { await self.refresh(id, track) }
                // 刷新撞上 403 正在续期：在常规预算之外再等一次续期的时间，等它换上新地址并重取，别让 AVPlayer 看到 503。
                if self.isRenewing(source: id) {
                    await RelayWait.bounded(RenewalPolicy.waitBound) { await self.refresh(id, track) }
                }
            },
            now: { Date() },
            sleep: { try? await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }
        )
        switch serve {
        case .body(let body, let age, let replay):
            if replay { Self.log.info("replay-old \(track.label, privacy: .public) age=\(age, format: .fixed(precision: 1))s") }
            return .body(body)
        case .expired(let age):
            return .unavailable(noAdvanceFor: age, failure: failure)
        case .empty:
            return .unavailable(noAdvanceFor: 0, failure: failure)
        }
    }

    private func decide(_ id: String, _ track: Track) -> (serve: RelayFeed.Serve, failure: RelayFailure?) {
        lock.withLock {
            guard var current = entries[id], current.feeds[track] != nil else { return (.empty, nil) }
            let serve = current.feeds[track]?.serve(now: Date()) ?? .empty
            let failure = current.feeds[track]?.lastFailure
            entries[id] = current
            return (serve, failure)
        }
    }

    private func rendition(_ id: String) -> StreamRendition? {
        lock.withLock { entries[id]?.rendition }
    }

    /// 主列表路由：先确保已选档；有独立音轨就给本地主列表（视频 + 音轨），否则直接给视频媒体列表。
    private func masterReply(_ id: String) async -> Reply {
        if rendition(id)?.isUnselected == true { await refresh(id, .video) }
        guard let current = rendition(id), !current.isUnselected else { return .unavailable(noAdvanceFor: 0, failure: nil) }
        let bound = lock.withLock { port }
        if let text = current.localMaster(base: "\(Self.localBase(port: bound))/\(id)") { return .body(Data(text.utf8)) }
        return await reply(id, .video)
    }

    private func respond(_ connection: NWConnection, head: Data) {
        guard let (id, name) = route(in: head) else {
            send(connection, status: 400, body: Data())
            return
        }
        guard let known = rendition(id) else {
            send(connection, status: 404, body: Data())
            return
        }
        let token = UUID()
        lock.withLock {
            // 登记在锁内完成：任务结束时的注销一定排在登记之后。
            replies[token] = Task {
                await self.answer(connection, id: id, name: name, known: known)
                self.lock.withLock { _ = self.replies.removeValue(forKey: token) }
            }
        }
    }

    private func answer(_ connection: NWConnection, id: String, name: String, known: StreamRendition) async {
        let result: Reply
        switch name {
        case "master.m3u8": result = await masterReply(id)
        case "video.m3u8": result = await reply(id, .video)
        case "audio.m3u8": result = known.audio == nil && !known.isUnselected ? .unavailable(noAdvanceFor: 0, failure: nil) : await reply(id, .audio)
        default:
            send(connection, status: 404, body: Data())
            return
        }
        switch result {
        case .body(let body):
            send(connection, status: 200, body: body)
        case .unavailable(let age, let failure):
            // No new full segment within the grace: let AVPlayer see the failure and recover
            // instead of replaying a playlist it will report as unchanged (-12888).
            Self.log.error("serve-503 \(name, privacy: .public) noAdvance=\(age, format: .fixed(precision: 1))s upstream=\(failure?.label ?? "200", privacy: .public)")
            send(connection, status: 503, body: Data())
        }
    }

    // MARK: 本地监听

    /// Binds `RelayHost.name` (loopback) only. Never blocks: call early (app start); until the port is bound
    /// `localURL` returns nil. 已在监听（或正在起）返回 true；不允许起监听或建监听失败返回 false。
    /// 每起一个新监听代次加一，旧监听迟到的回调不会清掉新端口。
    @discardableResult
    func start() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard startAllowed else { return false }
        guard listener == nil else { return true }
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(RelayHost.name), port: .any)
        guard let listener = try? NWListener(using: parameters) else { return false }
        listenerEpoch += 1
        let epoch = listenerEpoch
        self.listener = listener
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self else { return }
            self.lock.lock()
            defer { self.lock.unlock() }
            guard self.listenerEpoch == epoch else { return }
            switch state {
            case .ready:
                self.port = listener?.port?.rawValue ?? 0
            case .failed, .cancelled:
                self.port = 0
                self.listener = nil
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.serve(connection, epoch: epoch)
        }
        listener.start(queue: queue)
        return true
    }

    /// 停掉监听：代次加一 → 取消监听 → 取消已登记的连接与应答任务 → 清空源表（取消每个源的循环与在途）。
    /// 之后旧地址都失效；再 `start` 会换一个新端口。
    func stopListener() {
        lock.lock()
        listenerEpoch += 1
        let old = listener
        listener = nil
        port = 0
        let open = Array(connections.values)
        connections.removeAll()
        let pending = Array(replies.values)
        replies.removeAll()
        lock.unlock()
        old?.cancel()
        for connection in open { connection.cancel() }
        for task in pending { task.cancel() }
        lock.lock()
        for id in Array(entries.keys) { cancelSourceLocked(id) }
        sources.removeAll()
        lock.unlock()
    }

    /// 此刻绑定的端口（没在监听为 0）。
    var listeningPort: UInt16 {
        lock.withLock { port }
    }

    private func serve(_ connection: NWConnection, epoch: Int) {
        let current: Bool = lock.withLock {
            guard listenerEpoch == epoch else { return false }
            connections[ObjectIdentifier(connection)] = connection
            return true
        }
        guard current else {
            connection.cancel()
            return
        }
        connection.start(queue: queue)
        receive(connection, buffer: Data())
    }

    /// 关闭连接并注销登记。
    private func close(_ connection: NWConnection) {
        connection.cancel()
        lock.withLock { _ = connections.removeValue(forKey: ObjectIdentifier(connection)) }
    }

    private func receive(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, complete, error in
            guard let self else {
                connection.cancel()
                return
            }
            var buffer = buffer
            if let data { buffer.append(data) }
            if buffer.count > 16 * 1024 {
                self.send(connection, status: 400, body: Data())
                return
            }
            if buffer.range(of: Data("\r\n\r\n".utf8)) != nil {
                self.respond(connection, head: buffer)
                return
            }
            if complete || error != nil {
                self.close(connection)
                return
            }
            self.receive(connection, buffer: buffer)
        }
    }

    /// `/<id>/<name>.m3u8` -> (id, name)。
    private func route(in head: Data) -> (String, String)? {
        guard let text = String(data: head, encoding: .utf8) else { return nil }
        let requestLine = text.split(separator: "\r\n", maxSplits: 1).first ?? ""
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else { return nil }
        let path = parts[1].split(separator: "?").first.map(String.init) ?? ""
        let components = path.split(separator: "/")
        guard components.count >= 2, let name = components.last, name.hasSuffix(".m3u8") else { return nil }
        return (String(components[components.count - 2]), String(name))
    }

    private func send(_ connection: NWConnection, status: Int, body: Data) {
        let reason = status == 200 ? "OK" : (status == 503 ? "Service Unavailable" : "Error")
        var header = "HTTP/1.1 \(status) \(reason)\r\n"
        header += "Content-Type: application/vnd.apple.mpegurl\r\n"
        header += "Content-Length: \(body.count)\r\n"
        header += "Cache-Control: no-store\r\n"
        header += "Connection: close\r\n\r\n"
        var payload = Data(header.utf8)
        payload.append(body)
        connection.send(content: payload, completion: .contentProcessed { [weak self] _ in
            guard let self else {
                connection.cancel()
                return
            }
            self.close(connection)
        })
    }
}

private extension RelayTrack {
    /// 日志里的轨名。
    var label: String {
        switch self {
        case .video: return "video"
        case .audio: return "audio"
        }
    }
}
#endif

#if DEBUG
/// 音频清单拉取次数（自测用；Debug 专有）。
let selftestAudioFetchCount = OSAllocatedUnfairLock(initialState: 0)

extension PlaylistRelay {
    var selftestAudioFetches: Int { selftestAudioFetchCount.withLock { $0 } }

    /// 指定源是否带独立音频清单、音频刷新是否被抑制。
    func selftestAudioState(source id: String) -> (hasFeed: Bool, suppressed: Bool) {
        lock.withLock { (entries[id]?.feeds[.audio] != nil, sources.isAudioSuppressed(id: id)) }
    }
}
#endif
