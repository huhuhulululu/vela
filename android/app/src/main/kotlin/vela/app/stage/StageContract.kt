package vela.app.stage

import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import vela.app.infra.Settings
import vela.core.base.lane.LaneID
import vela.core.base.lane.RoomTarget
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Region
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.site.CatalogFailure
import vela.core.curation.Curation
import vela.core.curation.FavoritesBoard
import vela.core.ledger.EmptyListCopy
import vela.core.lifecycle.ChallengeResume

/**
 * 单窗界面契约。A6 只读 [state]、只发 [StageIntent]；A5a 的 `StageViewModel` 实现本接口。
 *
 * 这里没有老板键、置顶、透明度、指针、铺满。双窗（副路、焦点、热降档）和画中画由 B0、PiP1
 * 按串行重叠表扩展本文件：新字段加在数据类末尾并带默认值，新意图加在 [StageIntent] 末尾。
 * 假实现见 `FakeStage`：年龄确认前不发目录和搜索；静音与音量按 `AudioPolicy` 的规则。
 */
interface StageContract {
    val state: StateFlow<StageUiState>

    fun dispatch(intent: StageIntent)

    /** 播放器回调。必须用订阅收到的新值，不能回读播放器上的旧属性。单窗只有主路。 */
    fun noteReady(username: String?)

    fun notePlaying(username: String?)

    fun noteFailed(failed: Boolean, note: String = StageRules.PLAYBACK_INTERRUPTED)
}

/** 同一时间只开一个面板。质询页不算普通面板：收起筛选时它还在。 */
enum class StageSurface {
    None,
    Filters,
    Curation,
    Taste,
    Challenge,
}

/** 只给界面看的播放状态。双窗的「降级」留给 B0，不放在这里。 */
enum class LanePlayback {
    Idle,
    Playing,
    Failed,
    Empty,
}

/** 主路画面。B0 再加 `secondary`，不要把这一份改成列表。 */
data class LaneFace(
    val id: LaneID = LaneID.PRIMARY,
    val username: String? = null,
    val playback: LanePlayback = LanePlayback.Idle,
    val manuallyPaused: Boolean = false,
    val playbackNote: String? = null,
    val readyUsername: String? = null,
    val playingUsername: String? = null,
    /** 这一轮起播的代次。异步按钮用它核对，对不上就放弃。 */
    val generation: Int = 0,
)

/** 常看收藏提示。两条顶部横幅不同时出现。 */
data class FavoriteSuggest(
    val username: String,
    val displayName: String,
)

/**
 * 口味面板的一行。数值由 C6a 的 `Taste` 算；契约只搬运界面要画的字段，
 * 键用 `TasteKeys`（`t:` / `g:`）。
 */
data class TasteRowState(
    val key: String,
    val name: String,
    val mean: Double,
    val hits: Int,
    val vetoed: Boolean,
)

data class DayBarState(
    val weekday: Int,
    val hit: Int,
    val miss: Int,
)

/** 口味面板的快照，外加冷启动建议和是否暂停学习（Swift 里这两项不在 `TasteSummary` 里）。 */
data class TastePanelState(
    val liked: List<TasteRowState> = emptyList(),
    val disliked: List<TasteRowState> = emptyList(),
    val genders: List<TasteRowState> = emptyList(),
    val tagCount: Int = 0,
    val week: List<DayBarState> = emptyList(),
    val hitRate: Double? = null,
    val suggestions: List<String> = emptyList(),
    val paused: Boolean = false,
)

/**
 * 界面一次能画完的舞台。列表已经按查询滤好；[catalog] 是滤之前的目录，空列表文案用它。
 * [retainedUsername] 只在搜索框打字期间留住当前房间，回车或换房后清空。
 */
data class StageUiState(
    val ageConfirmed: Boolean = false,
    val loading: Boolean = false,
    val catalogError: String? = null,
    val source: FeedSource = FeedSource.guess,
    /** 最近一次成功装入的来源。切来源失败、列表又非空时回滚到它。 */
    val loadedSource: FeedSource = FeedSource.guess,
    val catalog: List<Room> = emptyList(),
    val rooms: List<Room> = emptyList(),
    val currentUsername: String? = null,
    val retainedUsername: String? = null,
    val searchText: String = "",
    val siteSearch: String? = null,
    val tagText: String = "",
    val gender: Gender? = null,
    val region: Region? = null,
    val publicOnly: Boolean = true,
    /** 猜你喜欢下「不想看」的键。其他来源不参与过滤。 */
    val hiddenKeys: Set<String> = emptySet(),
    val muted: Boolean = true,
    val volume: Double = Settings.DEFAULT_VOLUME,
    val chromeVisible: Boolean = true,
    val surface: StageSurface = StageSurface.None,
    val hintVisible: Boolean = false,
    val hintSeen: Boolean = false,
    val toast: String? = null,
    val toastUndoName: String? = null,
    val favoriteSuggest: FavoriteSuggest? = null,
    val curation: Curation = Curation(),
    val favoritesBoard: FavoritesBoard = FavoritesBoard(emptyList(), emptyList()),
    val favoritesChecking: Boolean = false,
    val favoriteDraftError: String? = null,
    val taste: TastePanelState = TastePanelState(),
    val lane: LaneFace = LaneFace(),
    val infoAlwaysOn: Boolean = false,
    /** 猜你喜欢的推荐理由。键是小写用户名。全站搜索的临时列表没有理由。 */
    val reasons: Map<String, String> = emptyMap(),
    val startupNotices: List<String> = emptyList(),
    /** 冷启动目录只领一次。确认年龄或更早的 bootstrap 领走之后，再确认不再发。 */
    val catalogClaimed: Boolean = false,
    val challengeResume: ChallengeResume? = null,
) {
    val index: Int
        get() {
            if (rooms.isEmpty()) return 0
            val name = currentUsername ?: return 0
            val at = rooms.indexOfFirst { it.username == name }
            return if (at < 0) 0 else at
        }

    /** 当前房间必须真的在可见列表里。对不上时不退回第 0 间，免得界面播错人。 */
    val current: Room?
        get() {
            val name = currentUsername ?: return null
            return rooms.firstOrNull { it.username == name }
        }

    val currentIsFavorite: Boolean
        get() = current?.let { curation.isFavorite(it.username) } ?: false

    val isInteracting: Boolean
        get() = surface != StageSurface.None

    /** 面板开着时控制栏不收。 */
    val chromeShown: Boolean
        get() = chromeVisible || isInteracting

    /** 年龄已确认、没被手动暂停、有房间、不在失败态，才允许出声出画。 */
    val playbackAllowed: Boolean
        get() = ageConfirmed &&
            !lane.manuallyPaused &&
            lane.playback != LanePlayback.Failed &&
            current != null

    /** 按当前查询真实匹配的间数，不含打字期间硬留下的当前房间。 */
    val localMatches: Int
        get() {
            val query = roomQuery()
            return catalog.count { query.matches(it) }
        }

    fun roomQuery(): RoomQuery = RoomQuery(
        text = if (siteSearch == null) searchText else "",
        publicOnly = publicOnly,
        blocked = curation.blockedSet,
        only = if (source == FeedSource.favorites && siteSearch == null) curation.favoriteSet else null,
        hidden = if (source == FeedSource.guess) hiddenKeys else emptySet(),
    )

    fun emptyMessage(): String = EmptyListCopy.message(
        catalogError = catalogError,
        query = roomQuery(),
        catalog = catalog,
        source = source,
        favoriteCount = curation.favorites.size,
    )

    /** 垂直分页只渲染当前页和上下各一页。 */
    fun pagerRooms(): List<Room> {
        if (rooms.isEmpty()) return emptyList()
        val at = index
        val from = (at - 1).coerceAtLeast(0)
        val to = (at + 1).coerceAtMost(rooms.lastIndex)
        return rooms.subList(from, to + 1).toList()
    }

    fun reasonFor(room: Room): String? {
        if (source != FeedSource.guess || siteSearch != null) return null
        return reasons[room.username.lowercase(Locale.ROOT)]
    }

    /** 主路、用户名和代次都对得上才接受。没有目标（null）一律不接受；暂停键是例外，见 [StageIntent.TogglePause]。 */
    fun accepts(target: RoomTarget?): Boolean {
        if (target == null || target.laneID != LaneID.PRIMARY) return false
        val name = currentUsername ?: return false
        return target.matches(LaneID.PRIMARY, name, lane.generation)
    }
}

/** 界面发给舞台的动作。新意图加在末尾，假实现用 `else` 忽略还不认识的。 */
sealed interface StageIntent {
    data object ConfirmAge : StageIntent

    data object Quit : StageIntent

    data class SelectSource(val source: FeedSource) : StageIntent

    data class SetSearchText(val text: String) : StageIntent

    data object SubmitSearch : StageIntent

    data class ChooseGender(val gender: Gender?) : StageIntent

    data class ChooseRegion(val region: Region?) : StageIntent

    data class SetPublicOnly(val value: Boolean) : StageIntent

    data class SetTagText(val text: String) : StageIntent

    data object ApplyTag : StageIntent

    data object ToggleMute : StageIntent

    data class SetVolume(val value: Double) : StageIntent

    data object ToggleInfo : StageIntent

    data class Step(val delta: Int, val lane: LaneID = LaneID.PRIMARY) : StageIntent

    data class TogglePause(val target: RoomTarget?) : StageIntent

    data class ToggleFavorite(val target: RoomTarget?) : StageIntent

    data class BlockCurrent(val target: RoomTarget?) : StageIntent

    data class MarkNotInterested(val target: RoomTarget?) : StageIntent

    data class RetryPlayback(val target: RoomTarget?) : StageIntent

    data class RemoveFavorite(val name: String) : StageIntent

    data class RestoreFavorite(val name: String) : StageIntent

    data class Unblock(val name: String) : StageIntent

    data class AddFavorite(val name: String) : StageIntent

    data class PlayFavorite(val name: String) : StageIntent

    data object RefreshFavorites : StageIntent

    data object ExportFavorites : StageIntent

    class ImportFavorites(val bytes: ByteArray) : StageIntent

    data object Retry : StageIntent

    data object RetryAfterChallenge : StageIntent

    data object DismissChallenge : StageIntent

    data object DismissHint : StageIntent

    data object UndoToast : StageIntent

    data object DismissToast : StageIntent

    data object AcceptFavoriteSuggest : StageIntent

    data object CloseFavoriteSuggest : StageIntent

    data class ShowSurface(val surface: StageSurface) : StageIntent

    data object DismissSurfaces : StageIntent

    data object NoteActivity : StageIntent

    data object ChromeIdle : StageIntent

    data class BoostTaste(val key: String) : StageIntent

    data class VetoTaste(val key: String) : StageIntent

    data class RestoreTaste(val key: String) : StageIntent

    data class RemoveTaste(val key: String) : StageIntent

    data class SetLearningPaused(val paused: Boolean) : StageIntent

    data object ResetTaste : StageIntent

    data object ClearPreferences : StageIntent
}

/** 假舞台记下的副作用。真 ViewModel 自己去做，不积累这份列表。 */
sealed interface StageEffect {
    data object CatalogReload : StageEffect

    data class SiteSearch(val keyword: String) : StageEffect

    data object ExitSiteSearch : StageEffect

    data class Play(val username: String) : StageEffect

    data object RefreshFavorites : StageEffect

    data object CheckFavorites : StageEffect

    data object Quit : StageEffect

    class ExportedFavorites(val bytes: ByteArray) : StageEffect {
        override fun equals(other: Any?): Boolean =
            other is ExportedFavorites && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/** 与 Swift 对齐的界面常数。音量恢复值同 `AudioPolicy.restoreVolume`（C2 落地后由那边引用）。 */
object StageRules {
    const val RESTORE_VOLUME: Double = 0.2
    const val TOAST_SECONDS: Double = 3.5
    const val UNFAVORITE_TOAST_SECONDS: Double = 4.0
    const val CHROME_HIDE_SECONDS: Double = 2.4
    const val PLAYBACK_INTERRUPTED: String = "画面中断了"
    const val CATALOG_NEEDS_CHALLENGE: String = "目录需要先通过站点确认"

    fun catalogMessage(failure: CatalogFailure): String = when (failure) {
        is CatalogFailure.Challenge -> "站点暂时拦下了目录"
        is CatalogFailure.Malformed -> "目录数据无法识别"
        is CatalogFailure.Status -> "目录请求失败（${failure.code}）"
    }
}

/** 可见列表上的落点。没有要跳过的房间时，就是夹紧后的下标。 */
internal fun landingIndex(from: Int, delta: Int, names: List<String>, skipping: Set<String>): Int {
    val count = names.size
    if (count == 0) return 0
    val origin = from.coerceIn(0, count - 1)
    if (delta == 0) return origin
    var next = (origin + delta).coerceIn(0, count - 1)
    if (skipping.isEmpty()) return next
    val banned = skipping.map { it.lowercase(Locale.ROOT) }.toHashSet()
    val direction = if (delta > 0) 1 else -1
    while (names[next].lowercase(Locale.ROOT) in banned) {
        val candidate = next + direction
        if (candidate !in 0 until count) break
        next = candidate
    }
    if (names[next].lowercase(Locale.ROOT) in banned && names[origin].lowercase(Locale.ROOT) !in banned) {
        return origin
    }
    return next
}

/** 打字期间 [retained] 那一间即使不匹配也留在列表里。 */
internal fun visibleRooms(catalog: List<Room>, query: RoomQuery, retained: String?): List<Room> {
    if (retained == null) return catalog.filter { query.matches(it) }
    return catalog.filter { query.matches(it) || it.username == retained }
}
