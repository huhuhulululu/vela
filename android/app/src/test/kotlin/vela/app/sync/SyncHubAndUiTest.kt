package vela.app.sync

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.stage.StageUiState
import vela.app.stage.StageViewModel
import vela.app.ui.TasteSheet
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.curation.Curation
import vela.core.sync.PreferenceLoadOutcome
import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncDocument
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncLocalOp
import vela.core.sync.SyncOpOutcome
import vela.core.sync.SyncOps
import vela.core.sync.SyncPhase
import vela.core.sync.SyncReplica
import vela.core.sync.SyncScheduler
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncState
import vela.core.sync.SyncTransport
import vela.core.sync.deriveSyncKeys
import vela.core.sync.settingSyncedVeto
import vela.core.sync.sha256
import vela.core.taste.Taste

/**
 * 验收 1、2、5、7：
 * - 实验开关默认关闭；关闭时不读 Keystore、不发任何同步请求
 * - 同步开启/有存储状态/待修复时拒绝关闭实验开关
 * - minSdk < 26、探针 DISABLED、未配置服务端地址时不显示同步入口；JOIN_ONLY 隐藏创建与 404 创建入口
 * - SyncStageAdapter 与真实 StageViewModel 交互：投影落盘、去抖落盘、回滚不污染未设置键、清理超额本机不想看
 * - SyncClipboard 写入敏感剪贴板标记
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class SyncHubAndUiTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var dir: Path
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(AndroidSyncKeyStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        dir = Files.createTempDirectory("vela-sync-hub-")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private fun countingEnv(): Triple<CountingKeyStore, CountingTransport, SyncService> {
        val keyStore = CountingKeyStore()
        val transport = CountingTransport()
        val stateStore = TestMemoryStateStore()
        val service = SyncService(
            SyncService.Environment(
                stateStore = stateStore,
                keyStore = keyStore,
                makeTransport = { _, _ -> transport },
                wallClock = { 1_800_000_000_000L },
            ),
        )
        return Triple(keyStore, transport, service)
    }

    @Test
    fun testExperimentDefaultOffMakesZeroNetworkAndKeyStoreCalls() {
        val prefs = SyncPrefs(context)
        assertFalse("实验开关默认必须关闭", prefs.experimentSync)

        val (keyStore, transport, service) = countingEnv()
        val bridge = MemoryBridge()
        val hub = SyncHub(
            service = service,
            prefs = prefs,
            bridge = bridge,
            scope = scope,
            sdkInt = 34,
            capability = SyncCapability.FULL,
            configuredOrigin = "https://sync.example",
        )
        hub.attach()
        assertFalse(hub.uiState.value.experimentEnabled)
        // 触发各类离散触发点，关闭状态下绝不发请求、绝不读 Keystore
        hub.trigger(SyncScheduler.Trigger.LAUNCH)
        hub.trigger(SyncScheduler.Trigger.FOREGROUND)
        hub.trigger(SyncScheduler.Trigger.PANEL)
        hub.syncNow()
        assertEquals(0, transport.requests)
        assertEquals(0, keyStore.reads)
    }

    @Test
    fun testExperimentSwitchRefusesDisableWhenActiveOrStoredStateExists() = runBlocking {
        val prefs = SyncPrefs(context)
        val keyBytes = ByteArray(32) { (it + 7).toByte() }
        val derived = deriveSyncKeys(keyBytes)
        val stateStore = TestMemoryStateStore().apply {
            stored = SyncState(
                phase = SyncPhase.ACTIVE,
                origin = "https://sync.example",
                blobID = derived.blobID,
                clock = SyncClock(dev = 5L),
                epoch = "0123456789abcdef0123456789abcdef",
            )
        }
        val keyStore = TestMemoryKeyStore().apply {
            entry = SyncKeyEntry(keyBytes, 5L, derived.blobID)
        }
        val transport = TestScriptedTransport(
            gets = mutableListOf(SyncServerResponse.Transient),
        )
        val service = SyncService(
            SyncService.Environment(
                stateStore = stateStore,
                keyStore = keyStore,
                makeTransport = { _, _ -> transport },
                wallClock = { 1_800_000_000_000L },
            ),
        )
        val bridge = MemoryBridge()
        val hub = SyncHub(
            service = service,
            prefs = prefs,
            bridge = bridge,
            scope = scope,
            sdkInt = 34,
            capability = SyncCapability.FULL,
            configuredOrigin = "https://sync.example",
        )
        hub.attach()
        hub.setExperiment(true)
        assertTrue(hub.uiState.value.experimentEnabled)
        assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)

        // 同步已开启时关闭实验开关必须被拒
        hub.setExperiment(false)
        assertTrue("同步已开启时不得关闭实验开关", hub.uiState.value.experimentEnabled)
        assertNotNull(hub.uiState.value.notice)

        // 关闭同步后才允许关闭实验开关
        hub.disable()
        assertEquals(SyncUiPhase.Off, hub.uiState.value.phase)
        hub.setExperiment(false)
        assertFalse(hub.uiState.value.experimentEnabled)
        assertFalse(prefs.experimentSync)
    }

    @Test
    fun testTasteSheetHidesSyncEntryWhenUnconfiguredOrDisabledOrBelowMinSdk() {
        val prefs = SyncPrefs(context)
        val (_, _, service1) = countingEnv()
        val unconfiguredHub = SyncHub(
            service = service1,
            prefs = prefs,
            bridge = MemoryBridge(),
            scope = scope,
            sdkInt = 34,
            capability = SyncCapability.FULL,
            configuredOrigin = null,
        )
        assertFalse(unconfiguredHub.uiState.value.visible)

        val (_, _, service2) = countingEnv()
        val disabledHub = SyncHub(
            service = service2,
            prefs = prefs,
            bridge = MemoryBridge(),
            scope = scope,
            sdkInt = 34,
            capability = SyncCapability.DISABLED,
            configuredOrigin = "https://sync.example",
        )
        assertFalse(disabledHub.uiState.value.visible)

        val (_, _, service3) = countingEnv()
        val oldSdkHub = SyncHub(
            service = service3,
            prefs = prefs,
            bridge = MemoryBridge(),
            scope = scope,
            sdkInt = 25,
            capability = SyncCapability.FULL,
            configuredOrigin = "https://sync.example",
        )
        assertFalse(oldSdkHub.uiState.value.visible)

        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 800.dp)) {
                TasteSheet(
                    state = StageUiState(),
                    dispatch = {},
                    onDismiss = {},
                    syncHub = unconfiguredHub,
                )
            }
        }
        assertEquals(0, compose.onAllNodesWithTag("sync-experiment-toggle").fetchSemanticsNodes().size)
    }

    @Test
    fun testTasteSheetJoinOnlyHidesCreateAnd404CreateEntry() = runBlocking {
        val prefs = SyncPrefs(context).apply { experimentSync = true }
        val transport = TestScriptedTransport(
            gets = mutableListOf(SyncServerResponse.NotFound("0123456789abcdef0123456789abcdef", 1_800_000_000_000L)),
        )
        val service = SyncService(
            SyncService.Environment(
                stateStore = TestMemoryStateStore(),
                keyStore = TestMemoryKeyStore(),
                makeTransport = { _, _ -> transport },
                wallClock = { 1_800_000_000_000L },
            ),
        )
        val hub = SyncHub(
            service = service,
            prefs = prefs,
            bridge = MemoryBridge(),
            scope = scope,
            sdkInt = 34,
            capability = SyncCapability.JOIN_ONLY,
            configuredOrigin = "https://sync.example",
        )
        hub.attach()
        assertTrue(hub.uiState.value.visible)
        assertFalse("JOIN_ONLY 必须禁止创建", hub.uiState.value.canCreate)
        assertTrue(hub.uiState.value.canJoin)

        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 800.dp)) {
                TasteSheet(
                    state = StageUiState(),
                    dispatch = {},
                    onDismiss = {},
                    syncHub = hub,
                )
            }
        }
        compose.onNodeWithTag("sync-experiment-toggle").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sync-join-entry").performScrollTo().assertIsDisplayed()
        assertEquals(
            "JOIN_ONLY 不得显示创建入口",
            0,
            compose.onAllNodesWithTag("sync-create-entry").fetchSemanticsNodes().size,
        )

        // 404 时 JOIN_ONLY 同样不能触发 createFromJoin
        val codeText = SyncCode.format(ByteArray(32) { 9 }, "https://sync.example", ::sha256)!!
        hub.prepareJoin(codeText)
        assertTrue(hub.uiState.value.joinStage is SyncUiJoinStage.NotFound)
        hub.createFromJoin()
        assertFalse("JOIN_ONLY 下 404 不得创建空间", service.isActive)
    }

    @Test
    fun testTasteSheetFullCapabilityShowsToggleAndCreateAndJoinEntries() {
        val prefs = SyncPrefs(context)
        val (_, _, service) = countingEnv()
        val hub = SyncHub(
            service = service,
            prefs = prefs,
            bridge = MemoryBridge(),
            scope = scope,
            sdkInt = 34,
            capability = SyncCapability.FULL,
            configuredOrigin = "https://sync.example",
        )
        hub.attach()

        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 800.dp)) {
                TasteSheet(
                    state = StageUiState(),
                    dispatch = {},
                    onDismiss = {},
                    syncHub = hub,
                )
            }
        }
        // 默认关闭：显示实验开关，但不显示开启/加入按钮
        compose.onNodeWithTag("sync-experiment-toggle").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("sync-create-entry").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("sync-join-entry").fetchSemanticsNodes().size)

        // 打开实验开关后显示创建与加入入口
        compose.onNodeWithTag("sync-experiment-toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sync-create-entry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sync-join-entry").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun testStageSyncBridgeProjectionPersistAndRollbackPreservesUnsetSettings() {
        val settings = Settings(context)
        val prefs = SyncPrefs(settings.preferences)
        val stage = StageViewModel(
            settings = settings,
            http = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(200, ByteArray(0))
            },
            directory = dir,
            dispatcher = Dispatchers.Unconfined,
            context = context,
        )
        try {
            val bridge = StageSyncBridge(stage, prefs)
            // 初始未显式设置 publicOnly / infoAlwaysOn
            assertFalse(prefs.hasExplicitValue(Settings.KEY_PUBLIC_ONLY))
            assertFalse(prefs.hasExplicitValue(Settings.KEY_INFO_ALWAYS))
            assertTrue(bridge.localData().explicitSettings.isEmpty())

            // 构造文档投影：收藏 alice、不想看 t:jazz、设置 publicOnly=false
            var replica = SyncReplica(SyncDocument.empty(), SyncClock(dev = 3L))
            replica = (SyncOps.apply(SyncLocalOp.Favorite("alice"), replica, 1_800_000_000_000L) as SyncOpOutcome.Applied).replica
            replica = (SyncOps.apply(SyncLocalOp.Veto("t:jazz"), replica, 1_800_000_000_010L) as SyncOpOutcome.Applied).replica
            replica = (SyncOps.apply(SyncLocalOp.Setting("publicOnly", false), replica, 1_800_000_000_020L) as SyncOpOutcome.Applied).replica

            bridge.applyProjection(replica.doc)
            assertTrue(stage.state.value.curation.isFavorite("alice"))
            assertTrue(stage.taste.vetoedKeys.contains("t:jazz"))
            assertFalse(stage.state.value.publicOnly)
            assertTrue(bridge.persistPending())

            // 模拟快照换回：移除显式 publicOnly 后 reloadSyncedSettingsFromStore 不得把它重新写成显式键（M9）
            prefs.setExplicit(Settings.KEY_PUBLIC_ONLY, null)
            bridge.reloadSyncedSettingsFromStore()
            assertTrue(stage.state.value.publicOnly)
            assertFalse("回滚重读绝不能把未设置键写成显式值", prefs.hasExplicitValue(Settings.KEY_PUBLIC_ONLY))
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSyncClipboardMarksClipAsSensitive() {
        SyncClipboard.copySensitive(context, "vela2:test-code")
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        assertNotNull(clip)
        assertEquals("vela2:test-code", clip!!.getItemAt(0).text.toString())
        val extras = clip.description.extras
        assertNotNull(extras)
        assertTrue(extras!!.getBoolean(SyncClipboard.EXTRA_IS_SENSITIVE_KEY, false))
    }

    @Test
    fun testCreateIfSupportedReturnsNullBelowMinSdk26() {
        val settings = Settings(context)
        val stage = StageViewModel(
            settings = settings,
            http = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(200, ByteArray(0))
            },
            directory = dir,
            dispatcher = Dispatchers.Unconfined,
            context = context,
        )
        try {
            var probeCalled = false
            val hub = SyncHub.createIfSupported(
                context = context,
                stage = stage,
                syncDir = dir,
                scope = scope,
                sdkInt = 25,
                configuredOriginOverride = "https://sync.example",
                probe = {
                    probeCalled = true
                    SyncProbe.run(25)
                },
            )
            assertNull("minSdk 26 以下不得加载同步模块", hub)
            assertFalse("minSdk 26 以下甚至不应跑探针", probeCalled)
        } finally {
            stage.close()
        }
    }

    private class CountingKeyStore : SyncKeyStore {
        var reads = 0
        override fun readEntry(): SyncKeyLookup {
            reads += 1
            return SyncKeyLookup.None
        }
        override fun write(entry: SyncKeyEntry): SyncKeyWrite = SyncKeyWrite.OK
        override fun read(): vela.core.sync.SyncKeychainRead {
            reads += 1
            return vela.core.sync.SyncKeychainRead.None
        }
        override fun delete(): Boolean = true
    }

    private class CountingTransport : SyncTransport {
        var requests = 0
        override suspend fun get(): SyncServerResponse {
            requests += 1
            return SyncServerResponse.Transient
        }
        override suspend fun put(request: vela.core.sync.SyncPutRequest): SyncServerResponse {
            requests += 1
            return SyncServerResponse.Transient
        }
        override suspend fun delete(base: Long, epoch: String?): SyncServerResponse {
            requests += 1
            return SyncServerResponse.Transient
        }
    }

    private class MemoryBridge : SyncLocalBridge {
        var curation = Curation()
        var taste = Taste()
        val settings = linkedMapOf<String, Boolean>()
        var lastToast: String? = null
        override val curationLoadOutcome: PreferenceLoadOutcome = PreferenceLoadOutcome.LOADED
        override val tasteLoadOutcome: PreferenceLoadOutcome = PreferenceLoadOutcome.LOADED
        override fun localData(): SyncLocalData = SyncLocalData(curation, taste.vetoedKeys.toList(), settings.toMap())
        override fun applyProjection(doc: SyncDocument) {
            val p = vela.core.sync.SyncProjection(doc)
            curation = p.curation
            taste = p.applying(taste)
            settings.putAll(p.settings)
        }
        override fun reloadSyncedSettingsFromStore() = Unit
        override fun dropLocalOnlyVetoes(keys: List<String>) {
            var next = taste
            for (k in keys) next = next.settingSyncedVeto(k, false)
            taste = next
        }
        override fun showToast(message: String) {
            lastToast = message
        }
        override fun cancelPendingSaves() = Unit
        override fun currentTaste(): Taste = taste
        override fun saveCuration(curation: Curation): Boolean {
            this.curation = curation
            return true
        }
        override fun saveTaste(taste: Taste): Boolean {
            this.taste = taste
            return true
        }
        override fun applySettings(settings: Map<String, Boolean>) {
            this.settings.putAll(settings)
        }
        override fun persistPending(): Boolean = true
        override fun reloadAfterRollback() = Unit
    }
}
