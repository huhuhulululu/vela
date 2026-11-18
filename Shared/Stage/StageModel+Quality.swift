import Foundation
import os

@MainActor
extension StageModel {
    // MARK: 离开后降低画质

    func canSwitchQuality(_ lane: PlaybackLane) -> Bool {
        guard laneAllowed(lane), lane.player.hasItem, let username = name(of: lane) else { return false }
        return lane.readyName == username
    }

    /// 开关、指针、暂停、老板键、起播就绪任一变化时调用；状态没变不重排计时。
    func refreshQuality(on given: PlaybackLane? = nil) {
        let lane = given ?? primary
        let engaged = resolvedPresence().engaged
        let now = Date()
        lane.quality.observe(enabled: restSavesPower, engaged: engaged, now: now)
        let canSwitch = canSwitchQuality(lane)
        let signature = "\(restSavesPower)|\(engaged)|\(canSwitch)|\(lane.playGeneration)|\(lane.quality.tier)"
        guard signature != lane.qualitySignature else { return }
        lane.qualitySignature = signature
        lane.qualityTask?.cancel()
        guard canSwitch, let wait = lane.quality.delay(now: now) else { return }
        let token = lane.playGeneration
        lane.qualityTask = Task { [weak self] in
            if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000) + 50_000_000) }
            guard let self, !Task.isCancelled, token == lane.playGeneration else { return }
            await self.applyQuality(token: token, on: lane)
        }
    }

    func applyQuality(token: Int, on given: PlaybackLane? = nil) async {
        let lane = given ?? primary
        let now = Date()
        lane.quality.observe(enabled: restSavesPower, engaged: resolvedPresence().engaged, now: now)
        guard canSwitchQuality(lane), let wait = lane.quality.delay(now: now), wait <= 0.001 else {
            lane.qualitySignature = ""
            refreshQuality(on: lane)
            return
        }
        guard let username = name(of: lane) else { return }
        // 主列表地址 30 秒就过期：换档前一律现取；取不到再回落到起播时留下的主列表（可能没有）。
        let fresh = try? await resolve(username)
        guard let master = (fresh?.isPlayable == true ? fresh?.url : nil) ?? lane.currentMaster else {
            lane.qualitySignature = ""
            return
        }
        let cachedMaster = lane.currentMaster
        guard token == lane.playGeneration, !Task.isCancelled else { return }
        let target = lane.quality.desired(now: now)
        guard target != lane.quality.tier else { return }
        let cap = min(target == .low ? PowerPolicy.peakBitrate(pixelHeight: pixelHeight, resting: true) : .infinity, laneCap(for: lane))
        let playable = await selectRendition(master: master, cap: cap)
        // 取档期间切房、暂停、隐藏或指针状态变了：放弃本次，按最新状态重新排。
        let after = Date()
        lane.quality.observe(enabled: restSavesPower, engaged: resolvedPresence().engaged, now: after)
        guard token == lane.playGeneration, !Task.isCancelled, canSwitchQuality(lane),
              name(of: lane) == username, lane.quality.desired(now: after) == target else {
            lane.qualitySignature = ""
            refreshQuality(on: lane)
            return
        }
        var rebuilt = false
        // 取档失败时 selectRendition 回落到主列表地址：地址不变但档位上限变了，仍要重建（由中继按新上限选档）。
        if master != cachedMaster {
            lane.currentMaster = master
        }
        if !playable.isUnselected { prepared.set(playable, cap: cap, for: username, now: Date()) }
        if playable != lane.currentPlayable || (playable.isUnselected && cap != lane.currentCap) {
            guard lane.player.retier(rendition: playable, username: username, peakBitrate: cap) else {
                lane.qualitySignature = ""
                return
            }
            lane.currentPlayable = playable
            lane.currentCap = cap
            rebuilt = true
        }
        Self.qualityLog.info("quality -> \(target == .low ? "low" : "full", privacy: .public) cap=\(cap.isFinite ? Int(cap) : -1) rebuilt=\(rebuilt) fallbackMaster=\(playable.isUnselected)")
        lane.quality.didSwitch(to: target, now: Date())
        lane.qualitySignature = ""
        refreshQuality(on: lane)
    }
}
