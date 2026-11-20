package vela.core.base.model

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Gender {
    @SerialName("f")
    female,

    @SerialName("m")
    male,

    @SerialName("t")
    trans,

    @SerialName("c")
    couple,

    ;

    val wire: String
        get() = when (this) {
            female -> "f"
            male -> "m"
            trans -> "t"
            couple -> "c"
        }

    companion object {
        fun fromApi(apiValue: String?): Gender? = when (apiValue?.lowercase(Locale.ROOT)) {
            "f" -> female
            "m" -> male
            "t", "s" -> trans
            "c" -> couple
            else -> null
        }
    }
}

@Serializable
enum class Region {
    @SerialName("asia")
    asia,

    @SerialName("europe_russia")
    europeRussia,

    @SerialName("northamerica")
    northAmerica,

    @SerialName("southamerica")
    southAmerica,

    @SerialName("other")
    other,

    ;

    /** 站点列表接口的 regions 参数值（AS / ER / NA / SA / O；单值）。 */
    val siteCode: String
        get() = when (this) {
            asia -> "AS"
            europeRussia -> "ER"
            northAmerica -> "NA"
            southAmerica -> "SA"
            other -> "O"
        }
}

enum class ShowState {
    publicShow,
    privateShow,
    group,
    away,
    offline,
    hidden,
    password,
    unknown,

    ;

    val isPlayable: Boolean
        get() = this == publicShow

    companion object {
        fun fromApi(apiValue: String?): ShowState = when (apiValue?.lowercase(Locale.ROOT)) {
            "public" -> publicShow
            "private" -> privateShow
            "group" -> group
            "away" -> away
            "offline" -> offline
            "hidden" -> hidden
            "password", "password protected" -> password
            else -> unknown
        }
    }
}
