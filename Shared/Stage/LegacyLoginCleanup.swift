import Foundation
import Security
import WebKit

/// 旧版本的账号会话清理：钥匙串里的会话与账号名，以及网页组件里的 sessionid。只做一次，失败不影响启动。
/// 完成标记在 cookie 删除确认后才写；没做完就退出，下次启动继续清。侧车在清理期间等待，不带旧 sessionid 发请求。
enum LegacyLoginCleanup {
    @MainActor private static var cleaning: Task<Void, Never>?

    @MainActor
    static func runOnce(settings: SettingsStore) {
        guard !settings.legacyLoginCleared, cleaning == nil else { return }
        for account in ["session", "username"] {
            let query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: "com.vela.stage",
                kSecAttrAccount as String: account
            ]
            SecItemDelete(query as CFDictionary)
        }
        cleaning = Task { @MainActor in
            let store = WKWebsiteDataStore.default().httpCookieStore
            for cookie in await store.allCookies() where cookie.name == "sessionid" && cookie.domain.contains("chaturbate") {
                await store.deleteCookie(cookie)
            }
            settings.legacyLoginCleared = true
            cleaning = nil
        }
    }

    /// 清理进行中则等它结束；已完成或无需清理立即返回。
    @MainActor
    static func waitUntilDone() async {
        await cleaning?.value
    }
}
