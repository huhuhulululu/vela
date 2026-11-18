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
import kotlinx.serialization.json.intOrNull

/** 站点字段：整数、小数、数字字符串或空，都收成一个可空 Int。不合格式时为 null，不抛。 */
@Serializable(with = FlexIntSerializer::class)
data class FlexInt(val value: Int? = null)

object FlexIntSerializer : KSerializer<FlexInt> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("vela.core.base.model.FlexInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): FlexInt {
        val json = decoder as? JsonDecoder ?: return FlexInt(null)
        return FlexInt(intFromElement(json.decodeJsonElement()))
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: FlexInt) {
        val int = value.value
        if (int == null) encoder.encodeNull() else encoder.encodeInt(int)
    }

    private fun intFromElement(element: kotlinx.serialization.json.JsonElement): Int? = when (element) {
        is JsonNull -> null
        is JsonObject, is JsonArray -> null
        is JsonPrimitive -> if (element.isString) {
            element.content.toIntOrNull()
        } else {
            element.intOrNull ?: element.doubleOrNull?.toInt()
        }
    }
}
