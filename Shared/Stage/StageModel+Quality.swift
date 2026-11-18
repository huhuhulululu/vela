import Foundation
import os

@MainActor
extension StageModel {
    // MARK: 离开后降低画质

    var canSwitchQuality: Bool {
        guard playbackAllowed, player.hasItem, let username = feed.currentUsername else { return false }
        return readyName == username
    }

    /// 开关、指针、暂停、老板键、起播就绪任一变化时调用；状态没变不重排计时。
    func refreshQuality() {
        let engaged = resolvedPresence().engaged
        let now = Date()
        quality.observe(enabled: restSavesPower, engaged: engaged, now: now)
        let signature = "\(restSavesPower)|\(engaged)|\(canSwitchQuality)|\(playGeneration)|\(quality.tier)"
        guard signature != qualitySignature else { return }
        qualitySignature = signature
        qualityTask?.cancel()
        guard canSwitchQuality, let wait = quality.delay(now: now) else { return }
        let token = playGeneration
        qualityTask = Task { [weak self] in
            if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000) + 50_000_000) }
            guard let self, !Task.isCancelled, token == self.playGeneration else { return }
            await self.applyQuality(token: token)
        }
    }

    func applyQuality(token: Int) async {
        let now = Date()
        quality.observe(enabled: restSavesPower, engaged: resolvedPresence().engaged, now: now)
        guard canSwitchQuality, let wait = quality.delay(now: now), wait <= 0.001 else {
            qualitySignature = ""
            refreshQuality()
            return
        }
        guard let username = feed.currentUsername else { return }
        // 主列表地址 30 秒就过期：换档前一律现取；取不到再回落到起播时留下的主列表（可能没有）。
        let fresh = try? await resolve(username)
        guard let master = (fresh?.isPlayable == true ? fresh?.url : nil) ?? currentMaster else {
            qualitySignature = ""
            return
        }
        let cachedMaster = currentMaster
        guard token == playGeneration, !Task.isCancelled else { return }
        let target = quality.desired(now: now)
        guard target != quality.tier else { return }
        let cap = target == .low ? PowerPolicy.peakBitrate(pixelHeight: pixelHeight, resting: true) : .infinity
        let playable = await selectRendition(master: master, cap: cap)
        // 取档期间切房、暂停、隐藏或指针状态变了：放弃本次，按最新状态重新排。
        let after = Date()
        quality.observe(enabled: restSavesPower, engaged: resolvedPresence().engaged, now: after)
        guard token == playGeneration, !Task.isCancelled, canSwitchQuality,
              feed.currentUsername == username, quality.desired(now: after) == target else {
            qualitySignature = ""
            refreshQuality()
            return
        }
        var rebuilt = false
        // 取档失败时 selectRendition 回落到主列表地址：地址不变但档位上限变了，仍要重建（由中继按新上限选档）。
        if master != cachedMaster {
            currentMaster = master
        }
        if !playable.isUnselected { prepared.set(playable, cap: cap, for: username, now: Date()) }
        if playable != currentPlayable || (playable.isUnselected && cap != currentCap) {
            guard player.retier(rendition: playable, username: username, peakBitrate: cap) else {
                qualitySignature = ""
                return
            }
            currentPlayable = playable
            currentCap = cap
            rebuilt = true
        }
        Self.qualityLog.info("quality -> \(target == .low ? "low" : "full", privacy: .public) cap=\(cap.isFinite ? Int(cap) : -1) rebuilt=\(rebuilt) fallbackMaster=\(playable.isUnselected)")
        quality.didSwitch(to: target, now: Date())
        qualitySignature = ""
        refreshQuality()
    }
}
