package vela.core.base.site

import java.util.Locale
import vela.core.base.net.ResponseShape

/**
 * 正文像站点质询页：Cloudflare 的 Just a moment，或年龄确认页 Verify your age。
 * JSON 正文从不算质询。只看前 4096 字节。
 */
fun looksLikeChallenge(data: ByteArray): Boolean {
    if (ResponseShape.looksLikeJSON(data)) return false
    val length = minOf(data.size, 4096)
    val text = String(data, 0, length, Charsets.UTF_8).lowercase(Locale.ROOT)
    if ("just a moment" in text) return true
    if ("cf-mitigated" in text || "challenges.cloudflare.com" in text) return true
    if ("verify your age" in text) return true
    return false
}
