package vela.app.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.stage.StageViewModel
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.curation.Curation
import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncCycleResult
import vela.core.sync.SyncDocument
import vela.core.sync.SyncFlowResult
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncLocalOp
import vela.core.sync.SyncOpOutcome
import vela.core.sync.SyncOps
import vela.core.sync.SyncPhase
import vela.core.sync.SyncReplica
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncState
import vela.core.sync.SyncStateFileStore
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256

/**
 * 独立审计回归测试（A-SYNC-2）：
 * 1. 真实 SyncHub 回调链下的本地修改与远端合并不得重入 gate 死锁；
 * 2. 回滚恢复设置 commit() 失败必须向上传播，保留快照并进入 NeedsRepair；
 * 3. SyncHttpTransport 取消阻塞连接与慢速滴流响应必须在总期限内及时中止。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncAuditRegressionTest {
    private lateinit var context: Context
    private lateinit var dir: Path
    private lateinit var scope: CoroutineScope
    private val nowMs = 1_800_000_000_000L
    private val origin = "https://sync.example"
    private val epoch = "0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        dir = Files.createTempDirectory("vela-sync-audit-")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private fun makeStage(): StageViewModel = StageViewModel(
        settings = Settings(context),
        http = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(200, ByteArray(0))
        },
        directory = dir,
        dispatcher = Dispatchers.Unconfined,
        context = context,
    )

    @Test
    fun testRealSyncHubCallbackChainLocalPerformAndRemoteMergeDoNotDeadlock() = runBlocking {
        val stage = makeStage()
        try {
            val keyBytes = ByteArray(32) { (it + 13).toByte() }
            val derived = deriveSyncKeys(keyBytes)
            val codec = SyncCryptoCodec(derived)

            // 构造远端文档（ver=1 含 bob，ver=2 含 bob 与 carol，各按对应版本封 AAD）
            var remoteReplica = SyncReplica(SyncDocument.empty(), SyncClock(dev = 88L))
            remoteReplica = (SyncOps.apply(SyncLocalOp.Favorite("bob"), remoteReplica, nowMs - 1000L) as SyncOpOutcome.Applied).replica
            val remoteEnvelope1 = codec.seal(remoteReplica.doc.encoded(), 1L)!!
            var remoteReplica2 = SyncReplica(remoteReplica.doc, SyncClock(remoteReplica.clock.l, remoteReplica.clock.c, 88L))
            remoteReplica2 = (SyncOps.apply(SyncLocalOp.Favorite("carol"), remoteReplica2, nowMs - 500L) as SyncOpOutcome.Applied).replica
            val remoteEnvelope2 = codec.seal(remoteReplica2.doc.encoded(), 2L)!!

            val transport = TestScriptedTransport(
                gets = mutableListOf(
                    // 启动后的 LAUNCH 周期先返回远端 ver=1（bob），触发 mergeRemote -> onDocumentChanged
                    SyncServerResponse.Blob(1L, derived.pubB64, remoteEnvelope1, epoch, nowMs),
                    // 第二次周期返回远端 ver=2（新增 carol），验证再次合并与推送
                    SyncServerResponse.Blob(2L, derived.pubB64, remoteEnvelope2, epoch, nowMs),
                ),
                puts = mutableListOf(
                    SyncServerResponse.Stored(3L, epoch, nowMs),
                ),
            )
            val stateStore = TestMemoryStateStore().apply {
                stored = SyncState(
                    phase = SyncPhase.ACTIVE,
                    origin = origin,
                    blobID = derived.blobID,
                    clock = SyncClock(dev = 7L),
                    epoch = epoch,
                )
            }
            val keyStore = TestMemoryKeyStore().apply {
                entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
            }
            val prefs = SyncPrefs(stage.settings.preferences).apply { experimentSync = true }
            val bridge = StageSyncBridge(stage, prefs)
            val service = SyncService(
                SyncService.Environment(
                    stateStore = stateStore,
                    keyStore = keyStore,
                    makeTransport = { _, _ -> transport },
                    wallClock = { nowMs },
                ),
            )
            val hub = SyncHub(
                service = service,
                prefs = prefs,
                bridge = bridge,
                scope = scope,
                sdkInt = 34,
                capability = SyncCapability.FULL,
                configuredOrigin = origin,
                wallClock = { nowMs },
            )

            // 在带超时保护的后台线程跑，防止死锁把测试进程永久挂死
            withTimeout(3_000L) {
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    // 1. attach -> 启动激活 -> LAUNCH 周期拉取远端 bob 并触发真实 onDocumentChanged -> documentChanged -> refreshStatus -> service.state
                    hub.attach()
                    assertEquals(SyncUiPhase.Active, hub.uiState.value.phase)
                    assertTrue("远端合并结果 bob 应投影到舞台", stage.state.value.curation.isFavorite("bob"))

                    // 2. 真实 SyncHub 回调链下执行本地修改 -> perform -> onDocumentChanged -> documentChanged -> refreshStatus -> service.state
                    val outcome = hub.perform(SyncLocalOp.Favorite("alice"))
                    assertTrue("本地修改必须成功应用", outcome is SyncOpOutcome.Applied)
                    assertTrue("本地修改 alice 应投影到舞台", stage.state.value.curation.isFavorite("alice"))
                    assertTrue("本地修改后 hasPending 应刷新为 true", hub.uiState.value.hasPending)
                    assertEquals(derived.fingerprint, hub.uiState.value.fingerprint)

                    // 3. 再跑一轮真实远端合并（carol）+ 推送
                    val r2 = service.runCycle()
                    assertEquals(SyncCycleResult.Pushed, r2)
                    assertTrue(stage.state.value.curation.isFavorite("alice"))
                    assertTrue(stage.state.value.curation.isFavorite("bob"))
                    assertTrue(stage.state.value.curation.isFavorite("carol"))
                }
            }
        } finally {
            stage.close()
        }
    }

    @Test
    fun testSettingsRestoreCommitFailurePropagatesAndEntersNeedsRepair() = runBlocking {
        val backingPrefs = context.getSharedPreferences("sync-commit-fail-test", Context.MODE_PRIVATE)
        backingPrefs.edit().clear().commit()
        val flakyPrefs = FlakyCommitPreferences(backingPrefs)
        val prefs = SyncPrefs(flakyPrefs)
        prefs.setExplicit(Settings.KEY_PUBLIC_ONLY, false)

        val curationPath = dir.resolve("feed-curation.json")
        val tastePath = dir.resolve("feed-taste.json")
        Files.writeString(curationPath, "{}")

        val journal = FileJoinJournal(
            dir = dir.resolve("sync-join-snapshot"),
            files = listOf(curationPath, tastePath),
            settingKeys = SyncPrefs.SYNC_SETTING_KEYS,
            readSettings = { prefs.readSyncSettings() },
            writeSettings = { values -> prefs.writeSyncSettings(values) },
        )

        val keyBytes = ByteArray(32) { (it + 2).toByte() }
        val derived = deriveSyncKeys(keyBytes)
        val code = SyncCode.format(keyBytes, origin, ::sha256)!!
        val codec = SyncCryptoCodec(derived)
        var remoteReplica = SyncReplica(SyncDocument.empty(), SyncClock(dev = 55L))
        remoteReplica = (SyncOps.apply(SyncLocalOp.Setting("publicOnly", true), remoteReplica, nowMs - 100L) as SyncOpOutcome.Applied).replica
        val remoteEnv = codec.seal(remoteReplica.doc.encoded(), 2L)!!

        val transport = TestScriptedTransport(
            gets = mutableListOf(SyncServerResponse.Blob(2L, derived.pubB64, remoteEnv, epoch, nowMs)),
            puts = mutableListOf(SyncServerResponse.Stored(3L, epoch, nowMs)),
        )
        val stateStore = TestMemoryStateStore().apply { failSave = true }
        val keyStore = TestMemoryKeyStore()
        val sink = TestMemorySink()
        val service = SyncService(
            SyncService.Environment(
                stateStore = stateStore,
                keyStore = keyStore,
                makeTransport = { _, _ -> transport },
                journal = journal,
                wallClock = { nowMs },
            ),
        )

        val ticket = (service.prepareJoin(code, SyncLocalData(Curation())) as SyncJoinPrepared.Preview).ticket
        // 触发本机状态保存失败 -> 回滚调用 writeSettings -> 注入 commit() 返回 false
        flakyPrefs.failNextCommit = true
        val commit = service.commitJoin(ticket, { SyncLocalData(Curation()) }, sink)
        assertTrue("设置写盘失败时回滚不得成功，必须进入 NeedsRepair，实际 $commit", commit is SyncJoinCommit.NeedsRepair)
        assertTrue("快照必须保留供重试", journal.exists)
        assertTrue(service.joinRepairPending)
        assertFalse("待修复期间本机写入必须保持阻断", service.localWriteAllowed)

        // 恢复写盘能力后，点「关闭同步」应成功完成回滚并清理快照
        flakyPrefs.failNextCommit = false
        assertEquals(SyncFlowResult.Done, service.disable(sink))
        assertFalse(journal.exists)
        assertTrue(service.localWriteAllowed)
        assertEquals(false, prefs.explicitBool(Settings.KEY_PUBLIC_ONLY))
    }

    @Test
    fun testHttpTransportCancelsBlockedRequestPromptly() = runBlocking {
        val accepted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        ServerSocket().use { ss ->
            ss.bind(InetSocketAddress("127.0.0.1", 0))
            val port = ss.localPort
            val serverThread = thread(isDaemon = true) {
                try {
                    ss.accept().use { client ->
                        accepted.countDown()
                        // 接收连接后永不回写响应，模拟挂死的网络请求
                        releaseServer.await(15, TimeUnit.SECONDS)
                    }
                } catch (_: Exception) {
                }
            }
            try {
                val keys = deriveSyncKeys(ByteArray(32) { (it + 31).toByte() })
                val transport = SyncHttpTransport("http://127.0.0.1:$port", keys, timeoutMs = 20_000)
                val startNs = System.nanoTime()
                val job = launch(Dispatchers.Default) {
                    transport.get()
                }
                assertTrue("服务端应收到 TCP 连接", accepted.await(3, TimeUnit.SECONDS))
                delay(80)
                job.cancel()
                withTimeout(2_000L) {
                    job.join()
                }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                assertTrue("取消阻塞请求应在 2 秒内结束，实际 ${elapsedMs}ms", elapsedMs < 2_000L)
            } finally {
                releaseServer.countDown()
                serverThread.interrupt()
            }
        }
    }

    @Test
    fun testHttpTransportEnforcesTotalDeadlineOnTrickleResponse() = runBlocking {
        val releaseServer = CountDownLatch(1)
        ServerSocket().use { ss ->
            ss.bind(InetSocketAddress("127.0.0.1", 0))
            val port = ss.localPort
            val serverThread = thread(isDaemon = true) {
                try {
                    ss.accept().use { client ->
                        // 读完请求头后，每隔 80ms 滴流 1 字节，永不触发单次 readTimeout(5000ms)
                        val out: OutputStream = client.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 5000\r\n\r\n{".toByteArray())
                        out.flush()
                        for (i in 0 until 200) {
                            if (releaseServer.await(80, TimeUnit.MILLISECONDS)) break
                            out.write(' '.code)
                            out.flush()
                        }
                    }
                } catch (_: Exception) {
                }
            }
            try {
                val keys = deriveSyncKeys(ByteArray(32) { (it + 37).toByte() })
                val transport = SyncHttpTransport(
                    base = "http://127.0.0.1:$port",
                    keys = keys,
                    timeoutMs = 5_000,
                    totalTimeoutMs = 350,
                )
                val startNs = System.nanoTime()
                val res = withTimeout(2_000L) { transport.get() }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                assertEquals(SyncServerResponse.Transient, res)
                assertTrue("总期限应在 2 秒内中止慢速滴流响应，实际 ${elapsedMs}ms", elapsedMs < 2_000L)
            } finally {
                releaseServer.countDown()
                serverThread.interrupt()
            }
        }
    }

    private class FlakyCommitPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        @Volatile
        var failNextCommit: Boolean = false

        override fun edit(): SharedPreferences.Editor {
            val inner = delegate.edit()
            return object : SharedPreferences.Editor by inner {
                override fun commit(): Boolean {
                    if (failNextCommit) return false
                    return inner.commit()
                }
            }
        }
    }
}
