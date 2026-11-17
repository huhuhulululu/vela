import AppKit
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

    func retryPlayback() {
        skips.reset()
        if let username = feed.currentUsername {
            prepared.remove(username)
        }
        requestPlay()
    }

    /// 起播只保留一个任务：新的划动、选择、重试取消上一轮还没完成的解析（连同在途请求），
    /// 取消按取消处理，不当网络故障。
    func requestPlay() {
        playTask?.cancel()
        playTask = Task { await playCurrent() }
    }

    /// 同 `requestPlay`，并等这一轮结束（目录加载与刷新要在起播结束后继续收尾）。
    func requestPlayAndWait(isRetry: Bool = false) async {
        playTask?.cancel()
        let task = Task { await playCurrent(isRetry: isRetry) }
        playTask = task
        await task.value
    }

    /// 播放器报告失败（播放项失败或起播超时，不只是解析失败）：停在当前房间并说明原因，
    /// 这个房间的停留也不再记学习信号。`failed` 取订阅收到的新值。
    func notePlaybackFailed(_ failed: Bool) {
        // 播放中每次进入 playing 都会把 failed 再写成 false。画面只在标志变化时失效。
        playback.setFailed(failed)
        guard failed else {
            // 新项成功起播：清掉由旧失败带来的文案。
            if PlaybackNoteRule.clearOnRecovery(note: playbackNote, playerNote: playerNote) { playbackNote = nil }
            playerNote = nil
            return
        }
        dwell.markFailed()
        // Swiping away on every stall turns a hiccup into a freeze. Stay on the room and show why.
        let note = player.lastError ?? "画面中断了"
        playerNote = note
        // 失败状态可能被连着写好几次；文案和控制栏没变就不要再发通知。
        AssignIf.changed(from: playbackNote, to: note) { playbackNote = $0 }
        AssignIf.changed(from: chromeVisible, to: true) { chromeVisible = $0 }
    }

    /// 播放器要换新地址（失败后的一次自动重试，或长时间暂停后恢复）：流地址会过期（实测约几分钟后 403），
    /// 所以一律重新解析当前房间，不复用旧地址。仍受 playGeneration 与播放门控保护。
    func refreshCurrent(_ username: String, isRetry: Bool) {
        guard feed.currentUsername == username else {
            player.refreshFinished(reason: nil, report: false)
            return
        }
        prepared.remove(username)
        let generation = playGeneration
        Task { [weak self] in
            guard let self else { return }
            // 排队的任务执行时房间或代次已变就放弃，免得废掉新房间的请求、误耗重试预算；
            // 放弃也要清掉待刷新，旧项不能被永远挡住（取代它的起播会自己换项）。
            guard self.playGeneration == generation, self.feed.currentUsername == username else {
                self.player.refreshFinished(reason: nil, report: false)
                return
            }
            await self.requestPlayAndWait(isRetry: isRetry)
            // 刷新没换来新项（失败、确认页、没有可播房间）：清掉待刷新并显示原因与重试。
            // 期间被更新的起播取代时不归本任务，由取代者收尾。
            guard RefreshTracker.ownsOutcome(taskGeneration: generation, currentGeneration: self.playGeneration) else { return }
            self.player.refreshFinished(reason: self.playbackNote, report: true)
        }
    }

    func playCurrent(isRetry: Bool = false) async {
        guard ageConfirmed else { return }
        playGeneration += 1
        let token = playGeneration
        skipTask?.cancel()
        awayTask?.cancel()
        awayTimer.clear()
        // 没有失败文案时再赋 nil 也会发 objectWillChange，起播瞬间多一次整树失效。
        AssignIf.changed(from: playbackNote, to: nil as String?) { playbackNote = $0 }
        guard let room = feed.current else {
            player.stop()
            idleStart.markIdle()
            return
        }
        idleStart.clear()
        awayRetry.enter(room.username)
        do {
            let cap = playbackCap()
            guard let target = try await playTarget(for: room.username, cap: cap, token: token) else { return }
            guard token == playGeneration, !Task.isCancelled else { return }
            guard await relayReady(token: token) else { return }
            startPlayback(room: room, target: target, cap: cap, isRetry: isRetry)
        } catch {
            await handlePlayFailure(error, token: token)
        }
    }

    /// 本地中继的端口没绑好时短暂等监听就绪：低延迟主列表直接交给 AVPlayer 会卡住甚至不起播。
    /// 返回 true 才可起播；失败时已提示并停播，期间被取代或中继被挂起则静默返回 false。
    private func relayReady(token: Int) async -> Bool {
        let listening = await PlaylistRelay.shared.waitUntilListening()
        guard token == playGeneration, !Task.isCancelled else { return false }
        guard listening else {
            // 中继被挂起（不允许起监听）时不是故障：静默放弃，恢复后由生命周期重新起播。macOS 恒允许，不会走到这里。
            guard PlaylistRelay.shared.allowStart else { return false }
            player.stop()
            playbackNote = "本地中继没能启动，暂时无法播放"
            chromeVisible = true
            log.error("relay not listening")
            return false
        }
        return true
    }

    private func handlePlayFailure(_ error: Error, token: Int) async {
        // 被新的起播取代（划动、选择）而取消：不是网络故障，不停播、不提示、不跳过，由取代者收尾。
        if RenewalPolicy.isCancellation(error) || Task.isCancelled { return }
        guard token == playGeneration else { return }
        switch error {
        case SidecarError.challenge, StreamDecodeError.challenge:
            await presentPlaybackChallenge(token: token)
        case is EndpointBackedOff:
            // 退避窗口内没发请求：不是这一间不可播。留在当前房间，窗口结束后再解析，不跳房、不耗 SkipTracker。
            player.stop()
            playbackNote = EndpointBackoff.pendingNote(playbackAllowed: playbackAllowed)
            chromeVisible = true
            dwell.markFailed()
            if playbackAllowed, let username = feed.currentUsername {
                scheduleAwayRetry(username, token: token, after: EndpointBackoff.window, countsAsAway: false)
            }
        case StreamDecodeError.unavailable:
            // 普通 403 / 503（限流、关播、地区封锁）：不是质询，不弹确认页，走现有的提示与自动跳过。
            player.stop()
            playbackNote = Copy.failure(.unavailable)
            scheduleSkip(token: token)
        default:
            player.stop()
            if NetworkFailure.isOffline(error) {
                // 断网：说清楚并停在这一间等重试；连跳几间只会各失败一遍。
                playbackNote = NetworkFailure.message
                chromeVisible = true
                dwell.markFailed()
                return
            }
            playbackNote = "连不上这路画面"
            scheduleSkip(token: token)
        }
    }

    private func presentPlaybackChallenge(token: Int) async {
        // 无论弹不弹确认页，先停掉当前播放：确认页下面不能继续放上一间的声音。
        player.stop()
        guard allowChallengePrompt() else {
            // 连续质询已达上限：不再弹确认页，提示后按失败处理自动跳过。
            playbackNote = "站点连续要求确认，这一间先跳过"
            chromeVisible = true
            scheduleSkip(token: token)
            return
        }
        playbackNote = "需要确认访问后才能播放"
        sidecar.suspend()
        chromeVisible = true
        // 与目录确认路径一致：先预热网页组件再弹出，避免确认页白屏或卡顿。
        await WebKitWarmup.prepare()
        guard token == playGeneration else {
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
    private func playTarget(for username: String, cap: Double, token: Int) async throws -> PlayTarget? {
        if let cached = prepared.fresh(username, cap: cap, now: Date()) {
            return PlayTarget(master: nil, playable: cached)
        }
        let sourced = try await resolveSourced(username)
        let ticket = sourced.ticket
        guard token == playGeneration, !Task.isCancelled else { return nil }
        challengeGate.reset()
        // 事件日志只记来源，不记地址。
        let origin = sourced.origin.rawValue
        Self.playbackLog.info("start-source \(origin, privacy: .public)")
        guard ticket.isPlayable, let master = ticket.url else {
            player.stop()
            noteUnplayable(ticket.failure, username: username, token: token)
            return nil
        }
        let playable = await selectRendition(master: master, cap: cap)
        guard token == playGeneration, !Task.isCancelled else { return nil }
        if !playable.isUnselected { prepared.set(playable, cap: cap, for: username, now: Date()) }
        return PlayTarget(master: master, playable: playable)
    }

    /// 解析后不可播放：暂时离开时停在房间并按 `AwayRetry` 最多自动重试一次，其余原因说明后安排跳过。
    private func noteUnplayable(_ failure: StreamFailure?, username: String, token: Int) {
        guard failure == .away else {
            playbackNote = Copy.failure(failure)
            scheduleSkip(token: token)
            return
        }
        dwell.markFailed()
        chromeVisible = true
        let decision = AwayRetry.gated(awayRetry.onAway(username), playbackAllowed: playbackAllowed)
        playbackNote = decision.note
        if case .retryLater(let after) = decision {
            scheduleAwayRetry(username, token: token, after: after)
        }
    }

    /// 一次性延迟任务：到点仍在同一房间、代次没变、允许播放才重新起播；切房、暂停或有新起播时作废。
    func scheduleAwayRetry(_ username: String, token: Int, after: TimeInterval, countsAsAway: Bool = true) {
        awayTask?.cancel()
        awayTimer.schedule()
        awayTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(after * 1_000_000_000))
            guard let self else { return }
            let fire = AwayRetry.shouldFire(
                scheduledRoom: username, currentRoom: self.feed.currentUsername,
                scheduledGeneration: token, currentGeneration: self.playGeneration,
                playbackAllowed: self.playbackAllowed, cancelled: Task.isCancelled || !self.awayTimer.pending
            )
            guard fire else { return }
            self.awayTimer.clear()
            if countsAsAway { self.awayRetry.beginRetry() }
            self.requestPlay()
        }
    }

    private func startPlayback(room: Room, target: PlayTarget, cap: Double, isRetry: Bool) {
        awayRetry.clear()
        currentMaster = target.master
        currentPlayable = target.playable
        currentCap = cap
        player.play(
            rendition: target.playable, username: room.username, peakBitrate: cap,
            allowed: playbackAllowed, isRetry: isRetry
        )
        player.setVolume(volume, muted: muted)
        quality.reset(tier: cap.isFinite ? .low : .full, now: Date())
        qualitySignature = ""
        applyPower()
        scheduleChromeHide()
        Task { await prefetchAhead() }
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
    func playbackCap() -> Double {
        let resting = restSavesPower && !resolvedPresence().engaged
        if resting {
            return PowerPolicy.peakBitrate(pixelHeight: pixelHeight, resting: true)
        }
        return .infinity
    }

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
    func prefetchAhead() async {
        let token = playGeneration
        let cap = playbackCap()
        guard let next = feed.prefetchUsernames().first, prepared.fresh(next, cap: cap, now: Date()) == nil else { return }
        guard let ticket = try? await resolve(next), ticket.isPlayable, let master = ticket.url else { return }
        guard token == playGeneration else { return }
        let playable = await selectRendition(master: master, cap: cap)
        guard token == playGeneration, !playable.isUnselected else { return }
        prepared.set(playable, cap: cap, for: next, now: Date())
        RoomThumbnailCache.shared.prefetch(url: feed.rooms.first { $0.username == next }?.thumbnailURL)
    }

    /// 中继撞上 403 时的续期：重新解析当前房间并选档，返回新的子列表地址（房间已变、解析或选档失败返回 nil）。
    /// 不动 playGeneration 与播放器：同一个本地主列表地址继续用，由中继原地换上。
    func renewRendition(_ username: String, cap: Double) async -> StreamRendition? {
        let token = playGeneration
        // 网络偶发抖动（大陆线路会掉包）时多试一次，免得一次失败就让 AVPlayer 看到 503。
        for attempt in 1...2 {
            if let renewed = await renewRenditionOnce(username, cap: cap, token: token) { return renewed }
            guard attempt < 2, renewalIsCurrent(username, token) else { break }
            try? await Task.sleep(nanoseconds: 400_000_000)
        }
        return nil
    }

    /// 续期的发起方还是当前源：代次没变、仍是同一房间、任务没被中继取消。过期结果一律丢弃。
    private func renewalIsCurrent(_ username: String, _ token: Int) -> Bool {
        RenewalFreshness.isCurrent(
            startedGeneration: token, currentGeneration: playGeneration,
            startedRoom: username, currentRoom: feed.currentUsername, cancelled: Task.isCancelled
        )
    }

    private func renewRenditionOnce(_ username: String, cap: Double, token: Int) async -> StreamRendition? {
        guard renewalIsCurrent(username, token) else {
            Self.playbackLog.info("renew-abort room-changed")
            return nil
        }
        guard let ticket = try? await resolve(username), ticket.isPlayable, let master = ticket.url else {
            Self.playbackLog.info("renew-abort resolve")
            return nil
        }
        guard renewalIsCurrent(username, token) else {
            Self.playbackLog.info("renew-abort room-changed")
            return nil
        }
        let playable = await selectRendition(master: master, cap: cap)
        guard !playable.isUnselected, renewalIsCurrent(username, token) else {
            Self.playbackLog.info("renew-abort select")
            return nil
        }
        prepared.set(playable, cap: cap, for: username, now: Date())
        if cap == currentCap { currentPlayable = playable }
        return playable
    }

    func scheduleSkip(token: Int) {
        dwell.markFailed()
        skipTask?.cancel()
        skipTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(SkipTracker.noticeSeconds * 1_000_000_000))
            guard let self, token == self.playGeneration, !Task.isCancelled else { return }
            self.autoSkip()
        }
    }

    /// 沿用户最后一次划动的方向跳过；连续跳过到上限就停在当前房间并提示重试；
    /// 已在列表尽头无处可跳时保留原失败原因并说明「已到列表尽头」，不占用连续跳过的次数。
    func autoSkip() {
        let direction = skips.direction
        let outcome = skips.attemptAuto { move(direction, userInitiated: false) }
        guard let notice = outcome.notice(keeping: playbackNote) else { return }
        playbackNote = notice
        chromeVisible = true
    }
}
