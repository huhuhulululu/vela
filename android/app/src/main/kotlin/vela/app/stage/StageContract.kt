package vela.app.stage

import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import vela.app.infra.Settings
import vela.core.base.lane.LaneID
import vela.core.base.lane.LaneLayout
import vela.core.base.lane.RoomTarget
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Region
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.site.CatalogFailure
import vela.core.curation.Curation
import vela.core.curation.FavoritesBoard
import vela.core.hls.PhoneThermalState
import vela.core.ledger.EmptyListCopy
import vela.core.lifecycle.ChallengeResume

/**
 * 舞台界面契约。界面只读 [state]、只发 [StageIntent]；`StageViewModel` 实现本接口。
 *
 * 这里没有老板键、置顶、透明度、指针、铺满。双窗与三窗的路数、焦点、横屏可见性和降级占位
 * 由 [LaneWindow] 纯函数决定，不在这里选房或放播放器。画中画字段与意图由 PiP1 加在末尾。
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

/** 只给界面看的播放状态。不参与闸门以外的结算。 */
enum class LanePlayback {
    Idle,
    Playing,
    Failed,
    Empty,

    /**
     * 非焦点路因解码资源不足被释放。不记失败、不自动跳过。
     * 文案用 [StageRules.DUAL_UNAVAILABLE]（冻结裁决；iOS 三窗写成「资源不足」）。
     */
    Degraded,
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
    /** 横屏时非焦点路为假：逻辑房间还在，播放资源应已释放。 */
    val visible: Boolean = true,
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
    /** 副路。null 表示这路没开。不要把主路改成列表。 */
    val secondary: LaneFace? = null,
    /** 第三路。null 表示没开。矮屏最多两路，见 [LaneWindow]。 */
    val tertiary: LaneFace? = null,
    val focusLane: LaneID = LaneID.PRIMARY,
    /** 当前实际打开的路数，1…3。单窗为 1。 */
    val laneCount: Int = 1,
    /** 用户记住的路数。临时少开（矮屏、尚未量到尺寸）不覆盖它。 */
    val rememberedLaneCount: Int = 1,
    /** 竖屏舞台放不放得下三格。还没量到时按放得下，但启动补路最多先开两路。 */
    val threeLanesFit: Boolean = true,
    /** 最近一次竖屏实测宽。0 表示还没量到。横屏量到的尺寸不写进来。 */
    val portraitStageWidth: Double = 0.0,
    /** 最近一次竖屏实测高。0 表示还没量到。 */
    val portraitStageHeight: Double = 0.0,
    val landscape: Boolean = false,
    /** 画中画还没回到空闲时为真：这段时间不改路数。 */
    val laneCountLocked: Boolean = false,
    /** 小窗开着时不能换焦点。PiP1 负责把它写成真。 */
    val focusLocked: Boolean = false,
    /** 设备热状态。降档算法在 `LaneQuality`，这里只给界面和后续包一个稳定的槽。 */
    val thermal: PhoneThermalState = PhoneThermalState.NOMINAL,
    /** 回桌面自动进入画中画。缺省开。 */
    val autoEnterPip: Boolean = true,
    /** 画中画正在显示或正在启动。 */
    val pictureInPicture: Boolean = false,
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
        get() = focusRoom?.let { curation.isFavorite(it.username) } ?: false

    /** 多于一路。单窗为假。 */
    val dualWindow: Boolean
        get() = laneCount > 1

    /**
     * 焦点路此刻的房间。收藏提示、心形在多路时看它；单窗或焦点在主路时就是 [current]。
     */
    val focusRoom: Room?
        get() {
            if (laneCount <= 1 || focusLane == LaneID.PRIMARY) return current
            val name = face(focusLane)?.username ?: return null
            return rooms.firstOrNull { it.username.equals(name, ignoreCase = true) }
        }

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

    /**
     * 路、用户名和代次都对得上才接受。没有目标（null）一律不接受；暂停键是例外，见 [StageIntent.TogglePause]。
     * 单窗只接受主路，口径与原来一致（看 [currentUsername]，不看画面上的用户名缓存）。
     * 多路时接受那一路画面上仍显示的房间。
     */
    fun accepts(target: RoomTarget?): Boolean {
        if (target == null) return false
        if (target.laneID == LaneID.PRIMARY || laneCount <= 1) {
            if (target.laneID != LaneID.PRIMARY) return false
            val name = currentUsername ?: return false
            return target.matches(LaneID.PRIMARY, name, lane.generation)
        }
        if (target.laneID !in LaneID.active(laneCount)) return false
        val shown = face(target.laneID) ?: return false
        val name = shown.username ?: return false
        return target.matches(target.laneID, name, shown.generation)
    }

    /** 打开着的这一路。路数不够时为 null，即使字段里还留着旧画面。 */
    fun face(id: LaneID): LaneFace? = when (id) {
        LaneID.PRIMARY -> lane
        LaneID.SECONDARY -> if (laneCount >= 2) secondary else null
        LaneID.TERTIARY -> if (laneCount >= 3) tertiary else null
    }

    /** 这一路的房间在可见列表里的位置。主路就是 [index]。没有房间时为 0。 */
    fun indexOfLane(id: LaneID): Int {
        if (id == LaneID.PRIMARY) return index
        val name = face(id)?.username ?: return 0
        val at = rooms.indexOfFirst { it.username.equals(name, ignoreCase = true) }
        return if (at < 0) 0 else at
    }

    /** 各路房间是否两两不同。空路不参与，算作不重复。 */
    fun lanesDistinct(): Boolean {
        val taken = LaneID.active(laneCount).mapNotNull { face(it)?.username?.lowercase(Locale.ROOT) }
        return taken.size == taken.toSet().size
    }

    /** 多路里这格没有房间时的说明。单窗空列表仍走 [emptyMessage]。 */
    fun emptyLaneMessage(id: LaneID): String? {
        if (laneCount <= 1) return null
        val shown = face(id) ?: return null
        if (shown.username != null) return null
        return StageRules.NO_MORE_ROOMS
    }

    /**
     * 这一路能不能出声出画：已确认年龄、窗格可见、没被手动暂停、有房间、不在失败或降级。
     * 单窗主路仍以 [playbackAllowed] 为准，另外要求可见且未降级。
     */
    fun lanePlaybackAllowed(id: LaneID): Boolean {
        if (laneCount <= 1 && id == LaneID.PRIMARY) {
            return playbackAllowed && lane.visible && lane.playback != LanePlayback.Degraded
        }
        val shown = face(id) ?: return false
        if (!ageConfirmed || !shown.visible || shown.manuallyPaused) return false
        if (shown.playback == LanePlayback.Failed || shown.playback == LanePlayback.Degraded) return false
        val name = shown.username ?: return false
        return rooms.any { it.username.equals(name, ignoreCase = true) }
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

    /** 开 = 至少 2 路（已是 3 路不降），关 = 单窗。`remember` 为假时不覆盖用户记住的路数。 */
    data class SetDual(val on: Boolean, val remember: Boolean = true) : StageIntent

    /** 选择路数。夹紧到 1…当前屏幕放得下的上限。同值且是用户明确选的，仍更新记住的路数。 */
    data class SetLaneCount(val count: Int, val remember: Boolean = true) : StageIntent

    /** 焦点只能落到已经打开的路上。单窗忽略。 */
    data class SetFocus(val lane: LaneID) : StageIntent

    /** 横屏只显示焦点路。单窗不隐藏主路。同值忽略。 */
    data class SetLandscape(val on: Boolean) : StageIntent

    /**
     * 竖屏舞台尺寸（点）。高小于宽的测量丢掉，避免横竖屏在阈值附近来回翻转。
     */
    data class NotePortraitStageSize(val width: Double, val height: Double) : StageIntent

    /** 设置「回桌面自动进入画中画」。 */
    data class SetAutoEnterPip(val enabled: Boolean) : StageIntent

    /** 控制栏按钮：进入画中画。已在画中画里时忽略（退出走系统手势）。 */
    data object EnterPictureInPicture : StageIntent

    /** 控制栏按钮：在画中画与全屏之间切换。 */
    data object TogglePictureInPicture : StageIntent
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
    const val NO_MORE_ROOMS: String = "没有更多房间"
    const val DUAL_UNAVAILABLE: String = "双窗暂不可用"

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

/**
 * 多路窗口的纯规则：路数增减、焦点、横屏可见性、矮屏上限、资源降级占位。
 * 不选房（那是 `LaneSelection`），不起播。同值输入返回原来的那一份，方便界面判断没有变化。
 *
 * 安卓手机始终支持多路。关双窗等于回到 1 路；开双窗是至少 2 路，已经开着第三路时不降。
 */
object LaneWindow {
    fun maxLaneCount(threeLanesFit: Boolean): Int = if (threeLanesFit) LaneID.maxCount else 2

    fun setDual(state: StageUiState, on: Boolean, remember: Boolean = true): StageUiState {
        return if (on) {
            if (state.laneCount >= 2) state else setLaneCount(state, 2, remember)
        } else {
            setLaneCount(state, 1, remember)
        }
    }

    fun setLaneCount(state: StageUiState, requested: Int, remember: Boolean = true): StageUiState {
        if (state.laneCountLocked || state.focusLocked) return state
        val next = requested.coerceIn(1, maxLaneCount(state.threeLanesFit))
        if (next == state.laneCount) {
            if (remember && requested == next && state.rememberedLaneCount != next) {
                return state.copy(rememberedLaneCount = next)
            }
            return state
        }
        val resized = resize(state, next)
        return if (remember) resized.copy(rememberedLaneCount = next) else resized
    }

    /**
     * 尺寸还没量到时最多先开 2 路，避免矮屏先开第三路再关掉。
     * 已经量到、并且放得下时，按记住的路数补开。不覆盖记住的值。
     */
    fun restoreRemembered(state: StageUiState): StageUiState {
        if (state.laneCountLocked || state.focusLocked) return state
        val cap = maxLaneCount(state.threeLanesFit)
        val ceiling = if (state.portraitStageHeight <= 0.0) minOf(cap, 2) else cap
        val remembered = minOf(state.rememberedLaneCount, ceiling)
        if (remembered > state.laneCount) return setLaneCount(state, remembered, remember = false)
        return state
    }

    fun notePortraitStageSize(state: StageUiState, width: Double, height: Double): StageUiState {
        if (height < width || width <= 0.0) return state
        if (state.portraitStageHeight > 0.0 &&
            state.portraitStageWidth == width &&
            state.portraitStageHeight == height
        ) {
            return state
        }
        val first = state.portraitStageHeight <= 0.0
        val fits = LaneLayout.fitsThreeLanes(height)
        val sized = state.copy(portraitStageWidth = width, portraitStageHeight = height)
        if (fits == state.threeLanesFit && !first) return sized
        return applyStageFit(sized.copy(threeLanesFit = fits))
    }

    fun setFocus(state: StageUiState, id: LaneID): StageUiState {
        if (state.laneCount <= 1 || state.focusLocked || id == state.focusLane) return state
        if (id !in LaneID.active(state.laneCount)) return state
        var next = state.copy(focusLane = id)
        val shown = next.face(id)
        if (shown?.playback == LanePlayback.Degraded) {
            val note = shown.playbackNote?.takeUnless { it == StageRules.DUAL_UNAVAILABLE }
            next = next.withFace(id, shown.copy(playback = LanePlayback.Idle, playbackNote = note))
        }
        return applyVisibility(next)
    }

    fun setLandscape(state: StageUiState, on: Boolean): StageUiState {
        if (on == state.landscape) return state
        return applyVisibility(state.copy(landscape = on))
    }

    /**
     * 非焦点路资源不足：只给这一路挂降级占位，不改房间、不改另一路。
     * 单窗和焦点路不动（焦点路受保护；双窗里唯一的非焦点路就是副路）。
     */
    fun degrade(state: StageUiState, id: LaneID): StageUiState {
        if (state.laneCount <= 1 || id == state.focusLane) return state
        val shown = state.face(id) ?: return state
        if (shown.username == null) return state
        if (shown.playback == LanePlayback.Degraded && shown.playbackNote == StageRules.DUAL_UNAVAILABLE) {
            return state
        }
        return state.withFace(
            id,
            shown.copy(playback = LanePlayback.Degraded, playbackNote = StageRules.DUAL_UNAVAILABLE),
        )
    }

    fun noteThermal(state: StageUiState, thermal: PhoneThermalState): StageUiState {
        if (state.thermal == thermal) return state
        return state.copy(thermal = thermal)
    }

    private fun applyStageFit(state: StageUiState): StageUiState {
        if (state.laneCountLocked || state.focusLocked) return state
        if (state.threeLanesFit) return restoreRemembered(state)
        val cap = maxLaneCount(threeLanesFit = false)
        return if (state.laneCount > cap) setLaneCount(state, cap, remember = false) else state
    }

    private fun resize(state: StageUiState, count: Int): StageUiState {
        val focusFace = rawFace(state, state.focusLane)
        var focus = state.focusLane
        var primary = state.lane
        var secondary = state.secondary
        var tertiary = state.tertiary
        var currentUsername = state.currentUsername
        if (count == 2 && focus == LaneID.TERTIARY && tertiary != null) {
            secondary = tertiary.copy(id = LaneID.SECONDARY)
            focus = LaneID.SECONDARY
        }
        if (count < 3) tertiary = null
        if (count >= 2 && secondary == null) {
            secondary = LaneFace(id = LaneID.SECONDARY, playback = LanePlayback.Empty)
        }
        if (count >= 3 && tertiary == null) {
            tertiary = LaneFace(id = LaneID.TERTIARY, playback = LanePlayback.Empty)
        }
        if (count < 2) {
            val moved = focusFace?.username
            if (focus != LaneID.PRIMARY && moved != null && !moved.equals(primary.username, ignoreCase = true)) {
                primary = primary.copy(
                    username = moved,
                    generation = primary.generation + 1,
                    playback = LanePlayback.Idle,
                    manuallyPaused = false,
                    playbackNote = null,
                    readyUsername = null,
                    playingUsername = null,
                )
                currentUsername = moved
            }
            focus = LaneID.PRIMARY
            secondary = null
            tertiary = null
            primary = primary.copy(visible = true)
        }
        return applyVisibility(
            state.copy(
                lane = primary.copy(id = LaneID.PRIMARY),
                secondary = if (count >= 2) secondary?.copy(id = LaneID.SECONDARY) else null,
                tertiary = if (count >= 3) tertiary?.copy(id = LaneID.TERTIARY) else null,
                focusLane = if (count <= 1) LaneID.PRIMARY else focus,
                laneCount = count,
                currentUsername = currentUsername,
            ),
        )
    }

    private fun applyVisibility(state: StageUiState): StageUiState {
        if (state.laneCount <= 1) {
            if (state.lane.visible) return state
            return state.copy(lane = state.lane.copy(visible = true))
        }
        val solo = state.landscape
        fun shown(id: LaneID, face: LaneFace): LaneFace {
            val visible = !solo || id == state.focusLane
            return if (face.visible == visible) face else face.copy(visible = visible)
        }
        val lane = shown(LaneID.PRIMARY, state.lane)
        val secondary = state.secondary?.let { shown(LaneID.SECONDARY, it) }
        val tertiary = state.tertiary?.let { shown(LaneID.TERTIARY, it) }
        if (lane == state.lane && secondary == state.secondary && tertiary == state.tertiary) return state
        return state.copy(lane = lane, secondary = secondary, tertiary = tertiary)
    }

    private fun rawFace(state: StageUiState, id: LaneID): LaneFace? = when (id) {
        LaneID.PRIMARY -> state.lane
        LaneID.SECONDARY -> state.secondary
        LaneID.TERTIARY -> state.tertiary
    }
}

private fun StageUiState.withFace(id: LaneID, face: LaneFace): StageUiState = when (id) {
    LaneID.PRIMARY -> copy(lane = face.copy(id = LaneID.PRIMARY))
    LaneID.SECONDARY -> copy(secondary = face.copy(id = LaneID.SECONDARY))
    LaneID.TERTIARY -> copy(tertiary = face.copy(id = LaneID.TERTIARY))
}
