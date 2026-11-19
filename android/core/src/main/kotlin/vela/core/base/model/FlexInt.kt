package vela.core.base.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** 站点字段：整数、小数、数字字符串或空，都收成一个可空 Long。不合格式时为 null，不抛。Swift `Int` 是 64 位。 */
@Serializable(with = FlexIntSerializer::class)
data class FlexInt(val value: Long? = null) {
    /** 放进 32 位字段（年龄等）。超出 `Int` 范围为 null，不截断，避免误落进 18…80。 */
    fun toIntExactOrNull(): Int? = value.toIntExactOrNull()
}

/** 超出 32 位则为 null，不截断。 */
fun Long?.toIntExactOrNull(): Int? {
    val n = this ?: return null
    if (n < Int.MIN_VALUE.toLong() || n > Int.MAX_VALUE.toLong()) return null
    return n.toInt()
}

object FlexIntSerializer : KSerializer<FlexInt> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("vela.core.base.model.FlexInt", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): FlexInt {
        val json = decoder as? JsonDecoder ?: return FlexInt(null)
        return FlexInt(longFromElement(json.decodeJsonElement()))
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: FlexInt) {
        val long = value.value
        if (long == null) encoder.encodeNull() else encoder.encodeLong(long)
    }

    private fun longFromElement(element: kotlinx.serialization.json.JsonElement): Long? = when (element) {
        is JsonNull -> null
        is JsonObject, is JsonArray -> null
        is JsonPrimitive -> if (element.isString) {
            element.content.toLongOrNull()
        } else {
            element.longOrNull ?: element.doubleOrNull?.let(::longFromDouble)
        }
    }

    /** 向 0 截断，对齐 Swift `Int(Double)`。非有限数或超出 `Long` 范围为 null（Swift 超出会陷阱）。 */
    private fun longFromDouble(value: Double): Long? {
        if (!value.isFinite()) return null
        if (value >= 9223372036854775808.0) return null
        if (value < -9223372036854775808.0) return null
        return value.toLong()
    }
}
