import Foundation
import os

@MainActor
extension StageModel {
    func retryAfterChallenge() {
        skips.reset()
        showChallenge = false
        // 被确认打断的目录加载 / 收藏查询要重跑：否则首次加载的列表停在空白，切来源后还在播旧来源的列表。
        let resume = challengeResume
        challengeResume = nil
        switch resume {
        case .reload:
            Task { await reload() }
        case .favorites:
            // 等待期间切到了收藏来源：只补查状态不会装入列表，改为整体重载，保证列表与来源一致。
            if source == .favorites {
                Task { await reload() }
            } else {
                Task { await checkFavorites() }
                requestPlay()
            }
        case .siteSearch(let keyword):
            startSiteSearch(keyword, fallback: Room.direct(username: keyword))
        case nil:
            requestPlay()
        }
    }

    /// 登记一次站点质询：返回 true 才弹确认页；连续质询到上限（或确认后又立刻被拦）返回 false。
    func allowChallengePrompt() -> Bool {
        challengeGate.register(now: Date()) == .present
    }

    /// 重试目标那一路。点下去之后那一路换了房间或换了一次播放就放弃。
    func retryPlayback(_ target: RoomTarget?) {
        guard let lane = matchedLane(target) else { return }
        lane.degradedHold.release(.retry)
        lane.skips.reset()
        if let username = name(of: lane) {
            prepared.remove(username)
        }
        requestPlay(on: lane)
    }

    /// 起播只保留一个任务：新的划动、选择、重试取消上一轮还没完成的解析（连同在途请求），
    /// 取消按取消处理，不当网络故障。
    func requestPlay(on given: PlaybackLane? = nil) {
        let lane = given ?? primary
        // 横屏时被释放的路不起播；回竖屏由 `setLandscape` 重新请求。
        guard lane.visible else { return }
        lane.playTask?.cancel()
        lane.playTask = Task { await playCurrent(on: lane) }
    }

    /// 同 `requestPlay`，并等这一轮结束（目录加载与刷新要在起播结束后继续收尾）。
    func requestPlayAndWait(isRetry: Bool = false, on given: PlaybackLane? = nil) async {
        let lane = given ?? primary
        guard lane.visible else { return }
        lane.playTask?.cancel()
        let task = Task { await playCurrent(isRetry: isRetry, on: lane) }
        lane.playTask = task
        await task.value
    }

    /// 播放器报告失败（播放项失败或起播超时，不只是解析失败）：停在当前房间并说明原因，
    /// 这个房间的停留也不再记学习信号。`failed` 取订阅收到的新值。
    func notePlaybackFailed(_ failed: Bool, on given: PlaybackLane? = nil) {
        let lane = given ?? primary
        // 播放中每次进入 playing 都会把 failed 再写成 false。画面只在标志变化时失效。
        lane.playback.setFailed(failed)
        guard failed else {
            // 新项成功起播：清掉由旧失败带来的文案。
            if PlaybackNoteRule.clearOnRecovery(note: lane.playbackNote, playerNote: lane.playerNote) { lane.playbackNote = nil }
            lane.playerNote = nil
            return
        }
        lane.dwell.markFailed()
        lane.state = .failed
        noteLaneStopped()
        // Swiping away on every stall turns a hiccup into a freeze. Stay on the room and show why.
        let note = lane.player.lastError ?? "画面中断了"
        lane.playerNote = note
        // 失败状态可能被连着写好几次；文案和控制栏没变就不要再发通知。
        AssignIf.changed(from: lane.playbackNote, to: note) { lane.playbackNote = $0 }
        AssignIf.changed(from: chromeVisible, to: true) { chromeVisible = $0 }
    }

    /// 播放器要换新地址（失败后的一次自动重试，或长时间暂停后恢复）：流地址会过期（实测约几分钟后 403），
    /// 所以一律重新解析当前房间，不复用旧地址。仍受本路 lane.playGeneration 与播放门控保护。
    func refreshCurrent(_ username: String, isRetry: Bool, on given: PlaybackLane? = nil) {
        let lane = given ?? primary
        guard name(of: lane) == username else {
            lane.player.refreshFinished(reason: nil, report: false)
            return
        }
        prepared.remove(username)
        let generation = lane.playGeneration
        Task { [weak self] in
            guard let self else { return }
            // 排队的任务执行时房间或代次已变就放弃，免得废掉新房间的请求、误耗重试预算；
            // 放弃也要清掉待刷新，旧项不能被永远挡住（取代它的起播会自己换项）。
            guard lane.playGeneration == generation, self.name(of: lane) == username, self.laneToken(lane) != nil else {
                lane.player.refreshFinished(reason: nil, report: false)
                return
            }
            await self.requestPlayAndWait(isRetry: isRetry, on: lane)
            // 刷新没换来新项（失败、确认页、没有可播房间）：清掉待刷新并显示原因与重试。
            // 期间被更新的起播取代时不归本任务，由取代者收尾。
            guard RefreshTracker.ownsOutcome(taskGeneration: generation, currentGeneration: lane.playGeneration) else { return }
            lane.player.refreshFinished(reason: lane.playbackNote, report: true)
        }
    }

    func playCurrent(isRetry: Bool = false, on given: PlaybackLane? = nil) async {
        let lane = given ?? primary
        guard ageConfirmed else { return }
        // 挂起期间（iOS 后台）不起播，回前台由生命周期重新起播。macOS 恒放行。
        guard let life = laneToken(lane) else { return }
        lane.playGeneration += 1
        let token = lane.playGeneration
        lane.skipTask?.cancel()
        lane.awayTask?.cancel()
        lane.awayTimer.clear()
        // 没有失败文案时再赋 nil 也会发 objectWillChange，起播瞬间多一次整树失效。
        AssignIf.changed(from: lane.playbackNote, to: nil as String?) { lane.playbackNote = $0 }
        guard let room = room(of: lane) else {
            stopLane(lane)
            lane.state = .empty
            if lane.id == .primary { idleStart.markIdle() }
            return
        }
        lane.state = .idle
        if lane.id == .primary { idleStart.clear() }
        lane.awayRetry.enter(room.username)
        do {
            let cap = playbackCap(on: lane)
            guard let target = try await playTarget(for: room.username, cap: cap, token: token, lane: lane, life: life) else { return }
            guard token == lane.playGeneration, !Task.isCancelled else { return }
            guard await relayReady(token: token, lane: lane, life: life) else { return }
            guard lifecycleAllows(.beforeRelayRegister, life) else { return }
            startPlayback(room: room, target: target, cap: cap, isRetry: isRetry, lane: lane)
        } catch {
            // 挂起时被取消的请求不是故障：不提示、不跳过。
            guard lifecycleAllows(.beforeCommit, life) else { return }
            await handlePlayFailure(error, token: token, lane: lane)
        }
    }

    /// 本地中继的端口没绑好时短暂等监听就绪：低延迟主列表直接交给 AVPlayer 会卡住甚至不起播。
    /// 返回 true 才可起播；失败时已提示并停播，期间被取代或中继被挂起则静默返回 false。
    private func relayReady(token: Int, lane: PlaybackLane, life: LifecycleEpoch.Token) async -> Bool {
        guard lifecycleAllows(.beforeRelayStart, life) else { return false }
        let listening = await PlaylistRelay.shared.waitUntilListening()
        guard token == lane.playGeneration, !Task.isCancelled else { return false }
        guard listening else {
            // 中继被挂起（不允许起监听）时不是故障：静默放弃，恢复后由生命周期重新起播。macOS 恒允许，不会走到这里。
            guard PlaylistRelay.shared.allowStart else { return false }
            stopLane(lane)
            lane.playbackNote = "本地中继没能启动，暂时无法播放"
            chromeVisible = true
            log.error("relay not listening")
            return false
        }
        return true
    }

    private func handlePlayFailure(_ error: Error, token: Int, lane: PlaybackLane) async {
        // 被新的起播取代（划动、选择）而取消：不是网络故障，不停播、不提示、不跳过，由取代者收尾。
        if RenewalPolicy.isCancellation(error) || Task.isCancelled { return }
        guard token == lane.playGeneration else { return }
        let allowed = laneAllowed(lane)
        switch error {
        case SidecarError.challenge, StreamDecodeError.challenge:
            await presentPlaybackChallenge(token: token, lane: lane)
        case is EndpointBackedOff:
            // 退避窗口内没发请求：不是这一间不可播。留在当前房间，窗口结束后再解析，不跳房、不耗 SkipTracker。
            stopLane(lane)
            lane.playbackNote = EndpointBackoff.pendingNote(playbackAllowed: allowed)
            chromeVisible = true
            lane.dwell.markFailed()
            if allowed, let username = name(of: lane) {
                scheduleAwayRetry(username, token: token, after: EndpointBackoff.window, countsAsAway: false, on: lane)
            }
        case StreamDecodeError.unavailable:
            // 普通 403 / 503（限流、关播、地区封锁）：不是质询，不弹确认页，走现有的提示与自动跳过。
            stopLane(lane)
            lane.playbackNote = Copy.failure(.unavailable)
            scheduleSkip(token: token, on: lane)
        default:
            stopLane(lane)
            if NetworkFailure.isOffline(error) {
                // 断网：说清楚并停在这一间等重试；连跳几间只会各失败一遍。
                lane.playbackNote = NetworkFailure.message
                chromeVisible = true
                lane.dwell.markFailed()
                return
            }
            lane.playbackNote = "连不上这路画面"
            scheduleSkip(token: token, on: lane)
        }
    }

    private func presentPlaybackChallenge(token: Int, lane: PlaybackLane) async {
        // 无论弹不弹确认页，先停掉当前播放：确认页下面不能继续放上一间的声音。
        stopLane(lane)
        guard allowChallengePrompt() else {
            // 连续质询已达上限：不再弹确认页，提示后按失败处理自动跳过。
            lane.playbackNote = "站点连续要求确认，这一间先跳过"
            chromeVisible = true
            scheduleSkip(token: token, on: lane)
            return
        }
        lane.playbackNote = "需要确认访问后才能播放"
        sidecar.suspend()
        chromeVisible = true
        // 与目录确认路径一致：先预热网页组件再弹出，避免确认页白屏或卡顿。
        await WebKitWarmup.prepare()
        guard token == lane.playGeneration else {
            // 等待期间被换房取代，确认页不会再弹：与关闭确认页相同，把摘除的侧车恢复回来。
            sidecar.resumeIfNeeded()
            return
        }
        showChallenge = true
    }

    /// 一次起播要用的地址：已选好档位的子列表（`playable`），以及取到它时用的主列表（缓存命中时没有）。
    struct PlayTarget {
        var master: URL?
        var playable: StreamRendition
    }

    /// 取播放地址：命中预取缓存（子列表，同档位上限）就直接用，省掉解析与取主列表两次往返；
    /// 否则解析主列表再选档。缓存里的地址若 403 / 失败，由播放器的一次重试走 `refreshCurrent` 清缓存重新解析。
    /// 解析后不可播放时已处理（停播、提示、安排跳过），返回 nil。
    private func playTarget(
        for username: String, cap: Double, token: Int, lane: PlaybackLane, life: LifecycleEpoch.Token
    ) async throws -> PlayTarget? {
        #if (os(macOS) && (DEBUG || PERF_PROBE)) || (os(iOS) && PERF_PROBE)
        if let fixed = perfStreamTarget(for: username) { return fixed }
        #endif
        if let cached = prepared.fresh(username, cap: cap, now: Date()) {
            return PlayTarget(master: nil, playable: cached)
        }
        guard lifecycleAllows(.beforeRequest, life) else { return nil }
        let sourced = try await resolveSourced(username)
        let ticket = sourced.ticket
        guard token == lane.playGeneration, !Task.isCancelled, lifecycleAllows(.beforeCommit, life) else { return nil }
        challengeGate.reset()
        // 事件日志只记来源，不记地址。
        let origin = sourced.origin.rawValue
        Self.playbackLog.info("start-source \(origin, privacy: .public)")
        guard ticket.isPlayable, let master = ticket.url else {
            stopLane(lane)
            noteUnplayable(ticket.failure, username: username, token: token, lane: lane)
            return nil
        }
        let playable = await selectRendition(master: master, cap: cap)
        guard token == lane.playGeneration, !Task.isCancelled, lifecycleAllows(.beforeCommit, life) else { return nil }
        if !playable.isUnselected { prepared.set(playable, cap: cap, for: username, now: Date()) }
        return PlayTarget(master: master, playable: playable)
    }

    /// 解析后不可播放：暂时离开时停在房间并按 `AwayRetry` 最多自动重试一次，其余原因说明后安排跳过。
    private func noteUnplayable(_ failure: StreamFailure?, username: String, token: Int, lane: PlaybackLane) {
        guard failure == .away else {
            lane.playbackNote = Copy.failure(failure)
            scheduleSkip(token: token, on: lane)
            return
        }
        lane.dwell.markFailed()
        chromeVisible = true
        let decision = AwayRetry.gated(lane.awayRetry.onAway(username), playbackAllowed: laneAllowed(lane))
        lane.playbackNote = decision.note
        if case .retryLater(let after) = decision {
            scheduleAwayRetry(username, token: token, after: after, on: lane)
        }
    }

    /// 一次性延迟任务：到点仍在同一房间、代次没变、允许播放才重新起播；切房、暂停或有新起播时作废。
    func scheduleAwayRetry(
        _ username: String, token: Int, after: TimeInterval, countsAsAway: Bool = true, on given: PlaybackLane? = nil
    ) {
        let lane = given ?? primary
        lane.awayTask?.cancel()
        lane.awayTimer.schedule()
        lane.awayTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(after * 1_000_000_000))
            guard let self else { return }
            let fire = AwayRetry.shouldFire(
                scheduledRoom: username, currentRoom: self.name(of: lane),
                scheduledGeneration: token, currentGeneration: lane.playGeneration,
                playbackAllowed: self.laneAllowed(lane), cancelled: Task.isCancelled || !lane.awayTimer.pending
            )
            guard fire else { return }
            lane.awayTimer.clear()
            if countsAsAway { lane.awayRetry.beginRetry() }
            self.requestPlay(on: lane)
        }
    }

    func startPlayback(room: Room, target: PlayTarget, cap: Double, isRetry: Bool, lane: PlaybackLane) {
        // 首次起播前先落实音频会话（iOS；macOS 没有控制器，不做事）。
        prepareAudioSession()
        lane.awayRetry.clear()
        lane.currentMaster = target.master
        lane.currentPlayable = target.playable
        lane.currentCap = cap
        // 每路起播的档位上限（-1 为不封顶）：双窗两路都应是 -1，热降档时才是有限值（§4.4）。
        Self.playbackLog.info("lane-start lane=\(Self.label(lane.id.role), privacy: .public) cap=\(cap.isFinite ? Int(cap) : -1)")
        lane.player.play(
            rendition: target.playable, username: room.username, peakBitrate: cap,
            allowed: laneAllowed(lane), isRetry: isRetry
        )
        pushVolume(to: lane)
        // 只有「离开后降画质」才算低档；多路的尺寸上限与热上限是有限值但不是低档，否则画质状态机会无谓地「升回」并重建播放项。
        lane.quality.reset(tier: restingCapped ? .low : .full, now: Date())
        lane.qualitySignature = ""
        applyPower()
        scheduleChromeHide()
        startPrefetch(on: lane)
        // 解析与等中继期间热状态或画面尺寸可能变了：起播用的 cap 是更早读的，就绪后补一次换档。
        if needsCapSync(lane) { retier(lane) }
    }

    /// 起播成功后预取下一间。任务登记在这一路上，挂起与取消本路工作时一并取消（§3.1）。
    /// 预取按共享工作校验生命周期：挂起期间（含画中画只放行一路）一律不预取。
    func startPrefetch(on lane: PlaybackLane) {
        lane.prefetchTask?.cancel()
        lane.prefetchTask = nil
        // 预取只跟主路：双窗时两路各预取同一间「下一个房间」只会多花一份请求。
        guard lane.id == .primary, sharedToken() != nil else { return }
        lane.prefetchTask = Task { [weak self, weak lane] in
            guard let self, let lane else { return }
            await self.prefetchAhead(on: lane)
        }
    }

    /// 停掉一路（没有房间、解析失败、进入访问确认等）。若各路都已停播，释放音频会话（§3.3「最后一路停播」）。
    func stopLane(_ lane: PlaybackLane) {
        lane.player.stop()
        noteLaneStopped()
    }

    /// 失败的路原播放项恢复在播（不经重新起播）：状态回到在播，按当前静音意图重新准备会话并同步各路音量。
    /// 恢复策略是「允许原项恢复」：超时与看门狗失败不拆播放项，画面能自己回来就接着播，失败信息随之清掉。
    func laneRecovered(_ lane: PlaybackLane) {
        lane.state = .playing
        Self.playbackLog.info("lane recovered lane=\(Self.label(lane.id.role), privacy: .public)")
        prepareAudioSession()
        for each in lanes { pushVolume(to: each) }
    }

    /// 没有哪一路还在出画面（都没有播放项，或都已失败）时，经音频控制器停用会话。macOS 没有控制器，不做事。
    func noteLaneStopped() {
        guard let audioFocus else { return }
        let anyLive = lanes.contains { $0.player.hasItem && $0.state != .failed }
        guard !anyLive, let outcome = audioFocus.releaseAfterLastLaneStopped() else { return }
        applyAudioOutcome(outcome)
    }

    func resolve(_ username: String) async throws -> StreamTicket {
        try await resolveSourced(username).ticket
    }

    /// 解析一个房间（起播、预取、续期、换档共用）：侧车冷却期内直接走侧车；否则先请求 `chatvideocontext`，
    /// 公开且有 `hls_source` 就直接用，非公开的已知状态直接给出原因；其余退回 `get_edge_hls_url_ajax`
    /// 与侧车备路（选择逻辑见 `StreamSourcing`）。
    func resolveSourced(_ username: String) async throws -> SourcedTicket {
        if Date() < sidecarUntil {
            return SourcedTicket(ticket: try await sidecar.resolve(username: username), origin: .sidecar)
        }
        let probe = try await ChatContext.probe(username: username, client: http)
        if case .use(let ticket) = StreamSourcing.first(probe) {
            return SourcedTicket(ticket: ticket, origin: .chatContext)
        }
        let fallback = try await resolveEdge(username)
        return SourcedTicket(ticket: StreamSourcing.settle(probe: probe, fallback: fallback.ticket), origin: fallback.origin)
    }

    /// 备路：直连 `get_edge_hls_url_ajax`；被质询或网络故障时改走侧车。
    private func resolveEdge(_ username: String) async throws -> SourcedTicket {
        do {
            return SourcedTicket(ticket: try await StreamDecoder.fetch(username: username, client: http), origin: .edge)
        } catch let error as StreamDecodeError where error == .challenge {
            sidecarUntil = Date().addingTimeInterval(600)
            return SourcedTicket(ticket: try await sidecar.resolve(username: username), origin: .sidecar)
        } catch let error as StreamDecodeError {
            // 普通不可用（403 / 503、非 JSON）与格式错误原样抛出：不走侧车。
            throw error
        } catch let error as EndpointBackedOff {
            // 退避中没发请求：原样抛出，不走侧车、不进侧车冷却。
            throw error
        } catch {
            // 续期被取消（暂停 / 换源）不是网络故障：不进侧车冷却，也不新建网页请求。
            if RenewalPolicy.isCancellation(error) || Task.isCancelled { throw error }
            // 明确断网：直接失败，不进侧车冷却（网络恢复后别白白走两分钟网页通道）。
            if NetworkFailure.isDisconnected(error) { throw error }
            sidecarUntil = Date().addingTimeInterval(120)
            return SourcedTicket(ticket: try await sidecar.resolve(username: username), origin: .sidecar)
        }
    }

    /// Watching uses the top rung. Window-height caps were selecting 540p and 720p, so the
    /// picture stayed soft. Power saving, when enabled, still starts a room on the bottom rung
    /// after the pointer has left.
    /// 多路时再叠上这一路的尺寸上限与热上限（§4.4，ios-triple 修订 v2 H2）。
    func playbackCap(on lane: PlaybackLane? = nil) -> Double {
        let base = restingCapped ? PowerPolicy.peakBitrate(pixelHeight: pixelHeight, resting: true) : Double.infinity
        guard let lane else { return base }
        return min(base, laneCap(for: lane))
    }

    /// 离开后降画质此刻生效（开着省电且指针不在）：起播上限里有省电档。
    var restingCapped: Bool { restSavesPower && !resolvedPresence().engaged }

    /// The master playlist is low-latency HLS. AVPlayer stalls and sometimes never starts on it.
    /// Pick one rung: its video media playlist plus the separate audio rendition it references
    /// (dropping the audio group leaves the picture silent). Falls back to the unselected master,
    /// which the relay then resolves itself.
    func selectRendition(master: URL, cap: Double) async -> StreamRendition {
        var request = URLRequest(url: master)
        request.timeoutInterval = 8
        request.setValue(UserAgent.safari, forHTTPHeaderField: "User-Agent")
        request.setValue("https://chaturbate.com/", forHTTPHeaderField: "Referer")
        guard let result = try? await http.send(request),
              (200..<300).contains(result.status),
              let text = String(data: result.data, encoding: .utf8),
              text.contains("#EXTM3U"),
              let rendition = StreamRendition.select(in: text, master: master, maxBandwidth: cap)
        else { return .unselected(master) }
        return rendition
    }

    /// 预取下一个房间：解析并选好档位，缓存子列表地址（主列表 30 秒就过期，不存）。
    /// 为别的房间发请求，属于共享工作：用共享作用域的 token，画中画只放行焦点路时也不预取。
    func prefetchAhead(on given: PlaybackLane? = nil) async {
        let lane = given ?? primary
        let token = lane.playGeneration
        let cap = playbackCap(on: lane)
        guard let life = sharedToken() else { return }
        guard let next = feed.prefetchUsernames().first, prepared.fresh(next, cap: cap, now: Date()) == nil else { return }
        #if (os(macOS) && (DEBUG || PERF_PROBE)) || (os(iOS) && PERF_PROBE)
        if next.hasPrefix(PerfStream.username) { return }
        #endif
        guard lifecycleAllows(.beforeRequest, life), !Task.isCancelled else { return }
        guard let ticket = try? await resolve(next), ticket.isPlayable, let master = ticket.url else { return }
        guard token == lane.playGeneration, !Task.isCancelled, lifecycleAllows(.beforeRequest, life) else { return }
        let playable = await selectRendition(master: master, cap: cap)
        guard token == lane.playGeneration, !Task.isCancelled, !playable.isUnselected, lifecycleAllows(.beforeCommit, life) else { return }
        prepared.set(playable, cap: cap, for: next, now: Date())
        RoomThumbnailCache.shared.prefetch(url: feed.rooms.first { $0.username == next }?.thumbnailURL)
    }

    /// 中继撞上 403 时的续期：重新解析当前房间并选档，返回新的子列表地址（房间已变、解析或选档失败返回 nil）。
    /// 不动 lane.playGeneration 与播放器：同一个本地主列表地址继续用，由中继原地换上。
    func renewRendition(_ username: String, cap: Double, on given: PlaybackLane? = nil) async -> StreamRendition? {
        let lane = given ?? primary
        let token = lane.playGeneration
        // 挂起期间不续期：画中画那一路仍放行。
        guard let life = laneToken(lane) else { return nil }
        // 网络偶发抖动（大陆线路会掉包）时多试一次，免得一次失败就让 AVPlayer 看到 503。
        for attempt in 1...2 {
            if let renewed = await renewRenditionOnce(username, cap: cap, token: token, lane: lane, life: life) { return renewed }
            guard attempt < 2, renewalIsCurrent(username, token, lane), lifecycleAllows(.beforeRequest, life) else { break }
            try? await Task.sleep(nanoseconds: 400_000_000)
        }
        return nil
    }

    /// 续期的发起方还是当前源：代次没变、仍是同一房间、任务没被中继取消。过期结果一律丢弃。
    private func renewalIsCurrent(_ username: String, _ token: Int, _ lane: PlaybackLane) -> Bool {
        RenewalFreshness.isCurrent(
            startedGeneration: token, currentGeneration: lane.playGeneration,
            startedRoom: username, currentRoom: name(of: lane), cancelled: Task.isCancelled
        )
    }

    private func renewRenditionOnce(
        _ username: String, cap: Double, token: Int, lane: PlaybackLane, life: LifecycleEpoch.Token
    ) async -> StreamRendition? {
        guard renewalIsCurrent(username, token, lane), lifecycleAllows(.beforeRequest, life) else {
            Self.playbackLog.info("renew-abort room-changed")
            return nil
        }
        #if (os(macOS) && (DEBUG || PERF_PROBE)) || (os(iOS) && PERF_PROBE)
        // 固定测试流不会 403，也不能为它请求真实站点。
        if username.hasPrefix(PerfStream.username) { return nil }
        #endif
        guard let ticket = try? await resolve(username), ticket.isPlayable, let master = ticket.url else {
            Self.playbackLog.info("renew-abort resolve")
            return nil
        }
        guard renewalIsCurrent(username, token, lane) else {
            Self.playbackLog.info("renew-abort room-changed")
            return nil
        }
        let playable = await selectRendition(master: master, cap: cap)
        guard !playable.isUnselected, renewalIsCurrent(username, token, lane), lifecycleAllows(.beforeCommit, life) else {
            Self.playbackLog.info("renew-abort select")
            return nil
        }
        prepared.set(playable, cap: cap, for: username, now: Date())
        if cap == lane.currentCap { lane.currentPlayable = playable }
        return playable
    }

    func scheduleSkip(token: Int, on given: PlaybackLane? = nil) {
        let lane = given ?? primary
        lane.dwell.markFailed()
        lane.skipTask?.cancel()
        lane.skipTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(SkipTracker.noticeSeconds * 1_000_000_000))
            guard let self, token == lane.playGeneration, !Task.isCancelled else { return }
            self.autoSkip(on: lane)
        }
    }

    /// 沿这一路用户最后一次划动的方向跳过；连续跳过到上限就停在当前房间并提示重试；
    /// 已在列表尽头无处可跳时保留原失败原因并说明「已到列表尽头」，不占用连续跳过的次数。
    func autoSkip(on given: PlaybackLane? = nil) {
        let lane = given ?? primary
        let direction = lane.skips.direction
        // `attemptAuto` 是 mutating：闭包里改的是本路的计数，不能在闭包里再经 lane 读写 skips。
        var skips = lane.skips
        let outcome = skips.attemptAuto { move(direction, userInitiated: false, on: lane) }
        lane.skips = skips
        guard let notice = outcome.notice(keeping: lane.playbackNote) else { return }
        lane.playbackNote = notice
        chromeVisible = true
    }
}
