package vela.core.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class BadJsonField : Exception()

internal fun jsonObject(data: ByteArray): JsonObject? {
    val element = try {
        Json.parseToJsonElement(data.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        return null
    }
    return element as? JsonObject
}

/** 缺键或 null 为 null。类型不对抛 [BadJsonField]，与 Swift 可选 `String` 解码失败一样。 */
internal fun optionalString(obj: JsonObject, key: String): String? {
    if (!obj.containsKey(key)) return null
    val element = obj[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: throw BadJsonField()
    if (!primitive.isString) throw BadJsonField()
    return primitive.content
}

internal data class StreamPayloadFields(
    val success: Boolean,
    val url: String?,
    val roomStatus: String?,
    val hiddenMessage: String?,
)

/** 解不出对象、缺 `success`、或字段类型不对时返回 null。 */
internal fun streamPayload(data: ByteArray): StreamPayloadFields? {
    val obj = jsonObject(data) ?: return null
    if (!obj.containsKey("success")) return null
    val success = try {
        looseOk(obj["success"])
    } catch (_: StreamDecodeError) {
        return null
    }
    return try {
        StreamPayloadFields(
            success = success,
            url = optionalString(obj, "url"),
            roomStatus = optionalString(obj, "room_status"),
            hiddenMessage = optionalString(obj, "hidden_message"),
        )
    } catch (_: BadJsonField) {
        null
    }
}

internal data class ProbeFields(val roomStatus: String?, val hlsSource: String?)

/** 起播探测只取 `room_status` 与 `hls_source`。不合格返回 null，调用方当成失败，不抛。 */
internal fun probeFields(data: ByteArray): ProbeFields? {
    val obj = jsonObject(data) ?: return null
    return try {
        ProbeFields(
            roomStatus = optionalString(obj, "room_status"),
            hlsSource = optionalString(obj, "hls_source"),
        )
    } catch (_: BadJsonField) {
        null
    }
}
