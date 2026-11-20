package vela.core.curation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vela.core.base.model.Prefs
import vela.core.base.model.Room
import vela.core.base.model.trimSwiftWhitespace
import vela.core.sites.RoomKey
import vela.core.sites.SiteKeys

/** 收藏夹导出与导入：一个 JSON 文件，只含用户名。 */
object FavoriteExchange {
    data class Imported(val names: List<String>, val rejected: Int)

    sealed class Failure : Exception() {
        data object TooLarge : Failure()

        data object Malformed : Failure()
    }

    fun encode(names: List<String>): ByteArray =
        json.encodeToString(
            File.serializer(),
            File(app = "Vela", version = 1, favorites = names.map(SiteKeys::disk)),
        ).toByteArray(Charsets.UTF_8)

    /** 接受 `{"app","version","favorites"}`，也接受直接的字符串数组。 */
    fun decode(data: ByteArray): Imported {
        if (data.size > Prefs.maxFileBytes) throw Failure.TooLarge
        val text = data.toString(Charsets.UTF_8)
        val raw = try {
            json.decodeFromString(File.serializer(), text).favorites
        } catch (_: Exception) {
            try {
                json.decodeFromString<List<String>>(text)
            } catch (_: Exception) {
                throw Failure.Malformed
            }
        }
        val names = ArrayList<String>()
        var rejected = 0
        for (item in raw) {
            val memory = SiteKeys.memory(item.trimSwiftWhitespace())
            if (RoomKey.parsing(memory) != null || Room.isValidUsername(memory)) {
                names.add(memory)
            } else {
                rejected += 1
            }
        }
        return Imported(names, rejected)
    }

    @Serializable
    private data class File(val app: String, val version: Int, val favorites: List<String>)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
}
