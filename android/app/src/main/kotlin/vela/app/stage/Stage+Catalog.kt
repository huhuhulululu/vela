package vela.app.stage

import android.util.Log
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import vela.app.infra.Settings
import vela.app.sync.rejectWhileSyncJoining
import vela.app.sync.syncSetting
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Region
import vela.core.base.model.Room
import vela.core.base.net.EndpointBackoff
import vela.core.base.net.EndpointBackedOff
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery
import vela.core.curation.LoadCommit
import vela.core.feed.CatalogWindow
import vela.core.feed.SiteSearch
import vela.core.hls.LoadFollowUp
import vela.core.hls.NetworkFailure
import vela.core.lifecycle.ChallengeGate
import vela.core.lifecycle.ChallengeResume
import vela.core.recommend.Recommender
import vela.core.selection.LaneSelection
import vela.core.taste.TagRarity
import vela.core.taste.TasteTargeting

internal enum class PageOutcome { Loaded, Busy, Skipped, Failed }

internal data class TargetedPlan(val base: CatalogQuery, val rarity: TagRarity)

internal data class CatalogRest(
    val query: CatalogQuery,
    val pages: Int,
    val fetched: Int,
    val last: CatalogPage,
)

internal data class FetchedPage(
    val page: CatalogPage,
    val regular: List<Room>,
    val targeted: TargetedPlan? = null,
    val rest: CatalogRest? = null,
)

internal suspend fun StageViewModel.reload(keepingSearchTask: Boolean = false) {
    if (!ageConfirmed) return
    val life = lifecycleEpoch.begin() ?: return
    endSiteSearch(cancelTask = !keepingSearchTask)
    loadGeneration += 1
    val token = loadGeneration
    loading = true
    catalogError = null
    val previousPaging = paging.restart()
    targetedJob?.cancel()
    targetedJob = null
    invalidateCatalogRest()
    idleStart.clear()
    publish()
    try {
        if (!lifecycleEpoch.allows(vela.core.lifecycle.LifecycleEpoch.Gate.BEFORE_REQUEST, life)) {
            loading = false
            publish()
            return
        }
        val fetched = fetchOpeningRetryingOnce(token, life)
        if (token != loadGeneration || !lifecycleEpoch.allows(vela.core.lifecycle.LifecycleEpoch.Gate.BEFORE_COMMIT, life)) {
            loading = false
            publish()
            return
        }
        challengeGate.reset()
        val raw = fetched.page
        updateCandidates(fetched.regular, replacing = true)
        val page = arranged(raw)
        commitFetched(raw, page, offset = 0, token = token, replacing = true)
        updateFeed(installing = source, intent = LaneSelection.Intent.Reload) { store ->
            store.replace(page)
            store.setQuery(currentQuery())
        }
        loadedOffset = page.nextOffset
        loading = false
        publish()
        launchCatalogRest(fetched.rest, token)
        if (catalogRestJob == null) launchTargeted(fetched.targeted, token)
        if (feed.rooms.isEmpty()) {
            catalogRestJob?.join()
            if (token != loadGeneration) return
        }
        loadUntilVisible(token)
        if (token != loadGeneration) return
        requestPlay()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (token != loadGeneration) return
        paging.restore(previousPaging)
        if (error is CatalogFailure.Challenge) {
            if (allowChallengePrompt()) {
                challengeResume = ChallengeResume.Reload
                presentCatalogChallenge()
            } else {
                failReload(text.challengeLater())
            }
        } else {
            Log.w(LOG, "catalog failed ${error.javaClass.simpleName}")
            failReload(catalogMessage(error))
        }
    }
}

internal fun StageViewModel.failReload(message: String) {
    loading = false
    favoritesChecking = false
    if (source != loadedSource && feed.rooms.isNotEmpty()) {
        source = loadedSource
        settings.source = loadedSource
        showToast(text.rollback(message, loadedSource))
        return
    }
    catalogError = message
    if (feed.rooms.isNotEmpty()) showToast(message) else publish()
}

internal fun StageViewModel.selectSource(next: FeedSource) {
    if (source == next) return
    source = next
    settings.source = next
    track { reload() }
}

internal fun StageViewModel.chooseGender(value: Gender?) {
    if (gender == value) return
    gender = value
    settings.gender = value
    track { reload() }
}

internal fun StageViewModel.chooseRegion(value: Region?) {
    if (region == value) return
    region = value
    settings.region = value
    track { reload() }
}

internal fun StageViewModel.setPublicOnly(value: Boolean) {
    if (rejectWhileSyncJoining()) return
    if (publicOnly == value) return
    publicOnly = value
    settings.publicOnly = value
    syncSetting(Settings.KEY_PUBLIC_ONLY, value)
    applySearch()
}

internal fun StageViewModel.previewSearch() {
    when (SiteSearch.onEdit(searchText, siteSearch)) {
        SiteSearch.Edit.Filter -> {
            if (SiteSearch.cancelsInflight(searchText)) cancelSiteSearch()
            updateFeed { store -> store.previewQuery(currentQuery()) }
        }
        SiteSearch.Edit.Keep -> Unit
        SiteSearch.Edit.Exit -> track { reload() }
    }
}

internal fun StageViewModel.applySearch() {
    if (SiteSearch.onEdit(searchText, siteSearch) == SiteSearch.Edit.Exit) {
        track { reload() }
        return
    }
    val previous = feed.currentUsername
    updateFeed { store -> store.setQuery(currentQuery()) }
    if (feed.currentUsername != previous) {
        requestPlay()
        track { loadMoreIfNeeded() }
    }
}

internal fun StageViewModel.submitSearch() {
    val typed = searchText.stageTrim()
    val probe = feed.copy()
    if (probe.focus(typed)) {
        val targetName = probe.currentUsername ?: typed
        val beforePrimary = feed.currentUsername
        val onSecondary = laneCount > 1 &&
            secondaryCursor?.username?.equals(targetName, ignoreCase = true) == true
        updateFeed(intent = LaneSelection.Intent.Reveal(targetName)) { store ->
            if (!onSecondary) store.focus(typed)
        }
        cancelSiteSearch()
        if (surface == StageSurface.Filters) surface = StageSurface.None
        val focusedSecondary = laneCount > 1 &&
            focusLane == vela.core.base.lane.LaneID.SECONDARY &&
            secondaryCursor?.username?.equals(targetName, ignoreCase = true) == true
        if (!focusedSecondary || !beforePrimary.equals(feed.currentUsername, ignoreCase = true)) {
            requestPlay()
        }
        return
    }
    when (val decision = SiteSearch.onSubmit(typed, feed.localMatches, siteSearch)) {
        SiteSearch.Submit.Ignore -> return
        is SiteSearch.Submit.Remote -> {
            startSiteSearch(decision.keyword, Room.direct(typed))
            return
        }
        SiteSearch.Submit.Local -> cancelSiteSearch()
    }
    val direct = Room.direct(typed)
    if (direct != null) {
        if (curation.isBlocked(direct.username)) {
            showToast(text.inBlocklist(direct.username))
            return
        }
        updateFeed { store -> store.insertDirect(direct) }
        if (surface == StageSurface.Filters) surface = StageSurface.None
        requestPlay()
        return
    }
    applySearch()
}

internal fun StageViewModel.startSiteSearch(keyword: String, fallback: Room?) {
    siteSearchJob?.cancel()
    invalidateCatalogRest()
    val epoch = searchEpoch.begin()
    siteSearchJob = track { searchSite(keyword, fallback, epoch) }
}

internal fun StageViewModel.cancelSiteSearch() {
    searchEpoch.invalidate()
    siteSearchJob?.cancel()
    challengeResume = challengeResume?.droppingSiteSearch()
    if (searchEpoch.releaseLoading()) loading = false
    siteSearchJob = null
}

internal fun StageViewModel.endSiteSearch(cancelTask: Boolean = true) {
    if (cancelTask) cancelSiteSearch()
    if (siteSearch == null) return
    siteSearch = null
    if (searchText.isNotEmpty()) searchText = ""
}

private suspend fun StageViewModel.searchSite(keyword: String, fallback: Room?, epoch: Int) {
    try {
        if (!searchEpoch.canStart(epoch, !coroutineContext.isActive) || !ageConfirmed) return
        loadGeneration += 1
        val token = loadGeneration
        invalidateCatalogRest()
        loading = true
        searchEpoch.claimLoading(epoch)
        publish()
        try {
            val raw = catalogAPI.fetch(SiteSearch.query(keyword))
            if (token != loadGeneration || !searchEpoch.isCurrent(epoch)) return
            if (searchEpoch.releaseLoading(epoch)) {
                loading = false
                publish()
            }
            challengeGate.reset()
            val page = SiteSearch.page(raw.rooms, curation.blockedSet)
            if (page.rooms.isEmpty()) {
                siteSearchMissed(keyword, fallback, failed = false, epoch = epoch)
                return
            }
            siteSearch = keyword
            updateFeed(intent = LaneSelection.Intent.SearchInstall) { store ->
                store.replace(page)
                store.setQuery(currentQuery())
            }
            if (surface == StageSurface.Filters) surface = StageSurface.None
            requestPlay()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CatalogFailure.Challenge) {
            if (token != loadGeneration || !searchEpoch.isCurrent(epoch)) return
            if (!allowChallengePrompt()) {
                if (searchEpoch.releaseLoading(epoch)) loading = false
                showToast(text.challengeSearch())
                return
            }
            val next = ChallengeResume.SiteSearch(keyword)
            challengeResume = challengeResume?.merging(next) ?: next
            searchEpoch.releaseLoading(epoch)
            presentCatalogChallenge()
        } catch (error: Exception) {
            if (token != loadGeneration || !searchEpoch.isCurrent(epoch)) return
            if (searchEpoch.releaseLoading(epoch)) loading = false
            Log.w(LOG, "site search failed ${error.javaClass.simpleName}")
            siteSearchMissed(keyword, fallback, failed = true, epoch = epoch)
        }
    } finally {
        if (searchEpoch.isCurrent(epoch)) siteSearchJob = null
    }
}

private suspend fun StageViewModel.siteSearchMissed(keyword: String, fallback: Room?, failed: Boolean, epoch: Int) {
    if (SiteSearch.missRecovery(siteSearch) == SiteSearch.MissRecovery.RestoreSource) {
        reload(keepingSearchTask = true)
    }
    when (
        val outcome = SiteSearch.missOutcome(
            keyword = keyword,
            fallback = fallback,
            fallbackBlocked = fallback?.let { curation.isBlocked(it.username) } ?: false,
            failed = failed,
            stillCurrent = searchEpoch.isCurrent(epoch) && coroutineContext.isActive,
        )
    ) {
        SiteSearch.MissOutcome.Abandon -> Unit
        is SiteSearch.MissOutcome.Direct -> {
            updateFeed { store -> store.insertDirect(outcome.room) }
            if (surface == StageSurface.Filters) surface = StageSurface.None
            requestPlay()
        }
        is SiteSearch.MissOutcome.Notify -> showToast(outcome.message)
    }
}

internal suspend fun StageViewModel.loadUntilVisible(token: Int) {
    var pages = 0
    while (token == loadGeneration && feed.rooms.isEmpty() && feed.shouldLoadMore(blankPages = pages)) {
        val before = loadedOffset
        when (loadPage()) {
            PageOutcome.Loaded -> {
                pages += 1
                if (loadedOffset == before) return
            }
            PageOutcome.Busy, PageOutcome.Skipped, PageOutcome.Failed -> return
        }
    }
}

internal suspend fun StageViewModel.loadMoreIfNeeded() {
    val token = loadGeneration
    loadPage()
    if (!LoadFollowUp.needsFill(token == loadGeneration, feed.rooms.size)) return
    loadUntilVisible(token)
}

internal suspend fun StageViewModel.loadPage(): PageOutcome {
    if (!ageConfirmed || !feed.shouldLoadMore() || loading) return PageOutcome.Skipped
    val life = lifecycleEpoch.begin() ?: return PageOutcome.Skipped
    val token = loadGeneration
    if (!loadSlot.acquire(token)) return PageOutcome.Busy
    try {
        val offset = loadedOffset
        return try {
            if (!lifecycleEpoch.allows(vela.core.lifecycle.LifecycleEpoch.Gate.BEFORE_REQUEST, life)) {
                return PageOutcome.Skipped
            }
            val fetched = fetchPage(offset)
            if (token != loadGeneration) return PageOutcome.Skipped
            if (!lifecycleEpoch.allows(vela.core.lifecycle.LifecycleEpoch.Gate.BEFORE_COMMIT, life)) {
                return PageOutcome.Skipped
            }
            challengeGate.reset()
            val raw = fetched.page
            updateCandidates(fetched.regular, replacing = false)
            val page = arranged(raw)
            if (!commitFetched(raw, page, offset, token, replacing = false)) return PageOutcome.Skipped
            val reorder = source == FeedSource.fresh
            updateFeed(intent = LaneSelection.Intent.Merge) { store ->
                store.append(page, offset)
                if (reorder) store.reorderAfterCurrent(Recommender::newest)
            }
            loadedOffset = page.nextOffset
            if (idleStart.takeStart(feed.current != null)) requestPlay()
            PageOutcome.Loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (token != loadGeneration) return PageOutcome.Skipped
            Log.w(LOG, "page failed ${error.javaClass.simpleName}")
            showToast(text.moreFailed())
            PageOutcome.Failed
        }
    } finally {
        loadSlot.release(token)
    }
}

internal fun StageViewModel.catalogQuery(offset: Int): CatalogQuery = CatalogQuery(
    gender = gender,
    region = region,
    tag = normalizedTag(),
    limit = source.pageLimit,
    offset = offset,
    newCams = source.usesNewCams,
)

internal suspend fun StageViewModel.fetchPage(offset: Int): FetchedPage {
    if (source == FeedSource.favorites) {
        val page = fetchFavoritesPage()
        return FetchedPage(page = page, regular = page.rooms)
    }
    val query = catalogQuery(offset)
    val regular = catalogAPI.fetchPages(query, source.pageCount)
    val plan = if (source == FeedSource.guess && offset == 0) {
        TargetedPlan(query, TagRarity(regular.rooms))
    } else {
        null
    }
    return FetchedPage(page = regular, regular = regular.rooms, targeted = plan)
}

/**
 * 首屏目录遇到超时或连接类失败（[IOException]）自动再取一次，期间仍是加载态，不闪错误页。
 * 质询、非 2xx、格式错误、退避都不是 [IOException]，原样抛出。代次已变或已挂起时不再重试。
 */
private suspend fun StageViewModel.fetchOpeningRetryingOnce(
    token: Int,
    life: vela.core.lifecycle.LifecycleEpoch.Token,
): FetchedPage {
    try {
        return fetchOpening()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        if (token != loadGeneration ||
            !lifecycleEpoch.allows(vela.core.lifecycle.LifecycleEpoch.Gate.BEFORE_REQUEST, life)
        ) {
            throw error
        }
        Log.w(LOG, "catalog retry ${error.javaClass.simpleName}")
        return fetchOpening()
    }
}

/** 冷启动只等首页。后续页在后台续拉，不另发第二次「启动」请求。 */
internal suspend fun StageViewModel.fetchOpening(): FetchedPage {
    if (source == FeedSource.favorites || source.pageCount <= 1) return fetchPage(0)
    val query = catalogQuery(0)
    val first = catalogAPI.fetch(query)
    val plan = if (source == FeedSource.guess) TargetedPlan(query, TagRarity(first.rooms)) else null
    return FetchedPage(
        page = first,
        regular = first.rooms,
        targeted = plan,
        rest = CatalogRest(query = query, pages = source.pageCount, fetched = 1, last = first),
    )
}

internal fun StageViewModel.invalidateCatalogRest() {
    catalogRestJob?.cancel()
    catalogRestJob = null
}

internal fun StageViewModel.launchCatalogRest(rest: CatalogRest?, token: Int) {
    invalidateCatalogRest()
    if (rest == null) return
    val pageSize = minOf(maxOf(rest.query.limit, 1), CatalogQuery.maxPageSize)
    if (!CatalogWindow.shouldContinue(
            fetched = rest.fetched,
            pages = rest.pages,
            lastCount = rest.last.rooms.size,
            pageSize = pageSize,
            nextOffset = rest.last.nextOffset,
            total = rest.last.total,
        )
    ) {
        return
    }
    catalogRestJob = track { followCatalogRest(rest, pageSize, token) }
}

private suspend fun StageViewModel.followCatalogRest(rest: CatalogRest, pageSize: Int, token: Int) {
    if (!loadSlot.acquire(token)) {
        if (coroutineContext.isActive && token == loadGeneration && source == FeedSource.guess) {
            launchTargeted(TargetedPlan(rest.query, tagRarity), token)
        }
        return
    }
    try {
        var query = rest.query
        var last = rest.last
        var fetched = rest.fetched
        while (CatalogWindow.shouldContinue(
                fetched = fetched,
                pages = rest.pages,
                lastCount = last.rooms.size,
                pageSize = pageSize,
                nextOffset = last.nextOffset,
                total = last.total,
            )
        ) {
            if (!coroutineContext.isActive || token != loadGeneration) return
            query = query.copy(offset = last.nextOffset)
            val page = try {
                catalogAPI.fetch(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!coroutineContext.isActive || token != loadGeneration) return
                Log.w(LOG, "catalog rest page failed ${error.javaClass.simpleName}")
                break
            }
            if (!coroutineContext.isActive || token != loadGeneration) return
            val offset = query.offset
            if (page.rooms.isEmpty()) {
                updateFeed(intent = LaneSelection.Intent.Merge) { store ->
                    store.mergePageAfterCurrent(page, offset) { rooms -> rooms }
                }
                loadedOffset = page.nextOffset
                break
            }
            updateCandidates(page.rooms, replacing = false)
            val ordered = arranged(page)
            if (!commitFetched(page, ordered, offset, token, replacing = false)) return
            updateFeed(intent = LaneSelection.Intent.Merge) { store ->
                store.mergePageAfterCurrent(page, offset) { rooms ->
                    arranged(CatalogPage(rooms, total = 0, nextOffset = 0)).rooms
                }
            }
            loadedOffset = page.nextOffset
            if (idleStart.takeStart(feed.current != null)) requestPlay()
            last = page
            fetched += 1
        }
        if (coroutineContext.isActive && token == loadGeneration && source == FeedSource.guess) {
            launchTargeted(TargetedPlan(rest.query, tagRarity), token)
        }
    } finally {
        loadSlot.release(token)
    }
}

internal fun StageViewModel.launchTargeted(plan: TargetedPlan?, token: Int) {
    targetedJob?.cancel()
    targetedJob = null
    if (plan == null) return
    targetedJob = track { mergeTargeted(plan, token) }
}

private suspend fun StageViewModel.mergeTargeted(plan: TargetedPlan, token: Int) {
    val tags = TasteTargeting.favoriteTags(taste, now(), plan.rarity)
    val queries = TasteTargeting.queries(tags, plan.base)
    if (queries.isEmpty()) return
    val api = catalogAPI
    val pages = vela.core.base.io.TimedFanOut.run(queries, TARGETED_TIMEOUT) { query ->
        api.fetchPages(query, pages = 1).rooms
    }
    if (!coroutineContext.isActive || token != loadGeneration || source != FeedSource.guess) return
    for (page in pages) if (page == null) Log.w(LOG, "targeted page failed")
    val rooms = TasteTargeting.merging(emptyList(), pages.map { it.orEmpty() })
    if (rooms.isEmpty()) return
    val at = now()
    val favorites = curation.favoriteSet
    val current = taste
    knownCatalog.record(rooms, firstPage = false, at = at)
    for (room in rooms) {
        reasons[room.username.stageLower()] = Recommender.reason(room, current, favorites, at)
    }
    updateFeed(intent = LaneSelection.Intent.Merge) { store ->
        store.mergeAfterCurrent(rooms) { tail ->
            arranged(CatalogPage(tail, total = 0, nextOffset = 0)).rooms
        }
    }
    if (idleStart.takeStart(feed.current != null)) requestPlay()
}

internal fun StageViewModel.updateCandidates(rooms: List<Room>, replacing: Boolean) {
    val all = if (replacing) rooms else TasteTargeting.merging(regularCandidates, listOf(rooms))
    regularCandidates = all.takeLast(600)
    tagRarity = TagRarity(regularCandidates)
}

internal fun StageViewModel.commitFetched(
    raw: CatalogPage,
    arrangedPage: CatalogPage,
    offset: Int,
    token: Int,
    replacing: Boolean,
): Boolean {
    val favorites = curation.favoriteSet
    val current = taste
    val at = now()
    return LoadCommit.apply(
        raw = raw,
        arranged = arrangedPage,
        offset = offset,
        source = source,
        token = token,
        current = loadGeneration,
        replacing = replacing,
        now = at,
        catalog = knownCatalog,
        reasons = reasons,
    ) { room -> Recommender.reason(room, current, favorites, at) }
}

internal fun StageViewModel.normalizedTag(): String? {
    val trimmed = tagText.stageTrim()
    return trimmed.ifEmpty { null }
}

internal fun StageViewModel.allowChallengePrompt(): Boolean =
    challengeGate.register(now()) == ChallengeGate.Decision.Present

internal fun StageViewModel.presentCatalogChallenge() {
    loading = false
    catalogError = text.needsChallenge()
    chromeVisible = true
    surface = StageSurface.Challenge
    publish()
}

internal suspend fun StageViewModel.retry() {
    if (source == FeedSource.favorites) refreshFavorites() else reload()
}

internal fun StageViewModel.retryAfterChallenge() {
    val resume = challengeResume
    surface = StageSurface.None
    challengeResume = null
    when (resume) {
        null -> requestPlay()
        is ChallengeResume.Reload -> {
            catalogError = null
            track { reload() }
        }
        is ChallengeResume.Favorites -> {
            if (source != FeedSource.favorites) {
                favoritesChecking = true
                publish()
                track { checkFavorites() }
            } else {
                catalogError = null
                track { reload() }
            }
        }
        is ChallengeResume.SiteSearch -> startSiteSearch(resume.query, fallback = null)
    }
}

internal fun StageViewModel.retryPlayback(target: vela.core.base.lane.RoomTarget?) {
    if (!accepts(target)) return
    failureNote = null
    laneFace = laneFace.copy(
        playback = if (laneFace.playback == LanePlayback.Failed) LanePlayback.Idle else laneFace.playback,
        playbackNote = null,
    )
    requestPlay()
}

internal fun catalogMessage(error: Throwable): String = when (error) {
    is CatalogFailure -> StageRules.catalogMessage(error)
    is EndpointBackedOff -> EndpointBackoff.catalogNote
    else -> NetworkFailure.message
}

private fun CatalogQuery.copy(offset: Int): CatalogQuery = CatalogQuery(
    gender = gender,
    region = region,
    tag = tag,
    limit = limit,
    offset = offset,
    newCams = newCams,
    keywords = keywords,
)

internal const val LOG: String = "vela.stage"
private const val TARGETED_TIMEOUT: Double = 6.0
