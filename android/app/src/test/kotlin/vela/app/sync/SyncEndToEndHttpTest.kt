package vela.app.sync

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vela.core.curation.Curation
import vela.core.sync.SyncCode
import vela.core.sync.SyncCycleResult
import vela.core.sync.SyncDerived
import vela.core.sync.SyncFlowResult
import vela.core.sync.SyncHalt
import vela.core.sync.SyncHttp
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncLocalOp
import vela.core.sync.SyncScheduler
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncStateFileStore
import vela.core.sync.SyncStateRead
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256

/**
 * 验收 6：经真实 HTTP（绑定 127.0.0.1 系统分配端口的假服务端）跑通端到端同步全流程，
 * 覆盖创建、加入、推送、拉取合并、清除、关闭，以及 404、409 带体、403、410、507、429、302 与超大体。
 */
class SyncEndToEndHttpTest {
    private lateinit var tempDir: Path
    private val logicalOrigin = "https://sync.example"
    private val nowMs = 1_800_000_000_000L

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("vela-sync-e2e-")
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private class Device(
        val name: String,
        root: Path,
        server: FakeSyncServer,
        nowMs: Long,
    ) {
        val stateStore = SyncMarkedStateStore(SyncStateFileStore(root.resolve("$name-state.json")))
        val keyStore = TestMemoryKeyStore()
        val sink = TestMemorySink()
        val journal = TestMemoryJournal(sink)
        val service = SyncService(
            SyncService.Environment(
                stateStore = stateStore,
                keyStore = keyStore,
                // 逻辑 origin 用于同步码与校验，真实 HTTP 连接注入指向 127.0.0.1 假服务端。
                makeTransport = { _, keys -> SyncHttpTransport(server.baseUrl, keys) },
                journal = journal,
                wallClock = { nowMs },
            ),
        )
    }

    @Test
    fun testFullLifecycleCreateJoinPushPullMergeDisableAndClear() = runBlocking {
        FakeSyncServer(nowMs = { nowMs }).use { server ->
            val deviceA = Device("a", tempDir, server, nowMs)
            val deviceB = Device("b", tempDir, server, nowMs)
            deviceA.sink.curation = Curation(favorites = listOf("alice"), blocked = listOf("mallory"))
            deviceB.sink.curation = Curation(favorites = listOf("bob"), blocked = listOf("eve"))

            // 1. 设备 A 创建空间并首次推送
            val created = deviceA.service.create(
                logicalOrigin,
                SyncLocalData(deviceA.sink.curation, listOf("t:jazz"), mapOf("publicOnly" to true)),
            ) as SyncCreateResult.Created
            assertEquals(SyncCycleResult.Created, created.firstCycle)
            assertEquals(1L, deviceA.service.state?.lastSeenVer)

            // 2. 设备 B 准备加入 -> 预览计数 -> 提交加入（推送合并结果 ver=2）
            val prepared = deviceB.service.prepareJoin(
                created.code,
                SyncLocalData(deviceB.sink.curation, listOf("t:rock"), mapOf("infoAlwaysOn" to true)),
            ) as SyncJoinPrepared.Preview
            assertEquals(1, prepared.ticket.preview.n)
            assertEquals(1, prepared.ticket.preview.m)
            assertEquals(1, prepared.ticket.preview.v)

            val joined = deviceB.service.commitJoin(
                prepared.ticket,
                takeLocal = { SyncLocalData(deviceB.sink.curation, listOf("t:rock"), mapOf("infoAlwaysOn" to true)) },
                sink = deviceB.sink,
            )
            assertEquals(SyncJoinCommit.Joined, joined)
            assertEquals(2L, deviceB.service.state?.lastSeenVer)
            assertEquals(setOf("alice", "bob"), deviceB.sink.curation.favorites.toSet())
            assertEquals(setOf("mallory", "eve"), deviceB.sink.curation.blocked.toSet())

            // 3. 设备 A 本地修改后跑周期：拉取 ver=2 合并并推送 ver=3
            deviceA.service.perform(SyncLocalOp.Favorite("carol"))
            deviceA.service.perform(SyncLocalOp.Setting("publicOnly", false))
            assertEquals(SyncCycleResult.Pushed, deviceA.service.runCycle())
            assertEquals(3L, deviceA.service.state?.lastSeenVer)

            // 4. 设备 B 拉取合并 ver=3
            assertEquals(SyncCycleResult.UpToDate, deviceB.service.runCycle())
            assertEquals(3L, deviceB.service.state?.lastSeenVer)
            val projB = vela.core.sync.SyncProjection(deviceB.service.state!!.doc)
            assertTrue(projB.curation.isFavorite("carol"))
            assertEquals(false, projB.settings["publicOnly"])

            // 5. 设备 B 关闭同步：本机数据保留、状态与密钥删除、之后不再发请求
            val requestsBeforeDisable = server.requests.size
            assertEquals(SyncFlowResult.Done, deviceB.service.disable(deviceB.sink))
            assertFalse(deviceB.service.isActive)
            assertNull(deviceB.keyStore.entry)
            assertEquals(SyncStateRead.None, deviceB.stateStore.load())
            assertEquals(setOf("alice", "bob", "carol"), deviceB.sink.curation.favorites.toSet())
            assertNull(deviceB.service.runCycle())
            assertEquals(requestsBeforeDisable, server.requests.size)

            // 6. 设备 A 清除云端：POST /delete 成功，随后同码再探返回 410 Gone
            assertEquals(SyncFlowResult.Done, deviceA.service.clear(deviceA.sink))
            assertFalse(deviceA.service.isActive)
            assertEquals(setOf("alice", "bob", "carol"), deviceA.sink.curation.favorites.toSet())

            val afterClear = deviceB.service.prepareJoin(created.code, SyncLocalData())
            assertEquals(SyncJoinPrepared.Gone, afterClear)
        }
    }

    @Test
    fun test404UnknownCodeCreateAndMissingHalt() = runBlocking {
        FakeSyncServer(nowMs = { nowMs }).use { server ->
            val device = Device("d404", tempDir, server, nowMs)
            val key = ByteArray(32) { (it + 11).toByte() }
            val codeText = SyncCode.format(key, logicalOrigin, ::sha256)!!

            // 未知空间 -> 404 NotFound -> 「仍用此码创建」
            val prepared = device.service.prepareJoin(codeText, SyncLocalData())
            assertTrue(prepared is SyncJoinPrepared.NotFound)
            val created = device.service.createWithCode(
                (prepared as SyncJoinPrepared.NotFound).code,
                SyncLocalData(Curation(favorites = listOf("alice"))),
            ) as SyncCreateResult.Created
            assertEquals(SyncCycleResult.Created, created.firstCycle)
            assertEquals(1L, device.service.state?.lastSeenVer)

            // 已有 lastSeenVer > 0 后服务端返回 404 -> 停在 SyncHalt.Missing
            server.enqueueFault(FakeSyncServer.Fault.ForceNotFound)
            assertEquals(SyncCycleResult.Halted(SyncHalt.Missing), device.service.runCycle())
            assertEquals(SyncHalt.Missing, device.service.state?.halt)
        }
    }

    @Test
    fun test409WithBodyConcurrentRetryDuringCycleAndJoin() = runBlocking {
        FakeSyncServer(nowMs = { nowMs }).use { server ->
            val deviceA = Device("c409a", tempDir, server, nowMs)
            val deviceB = Device("c409b", tempDir, server, nowMs)
            val created = deviceA.service.create(
                logicalOrigin,
                SyncLocalData(Curation(favorites = listOf("alice"))),
            ) as SyncCreateResult.Created

            // 设备 B 加入到 ver=2
            val ticket = (deviceB.service.prepareJoin(created.code, SyncLocalData(Curation(favorites = listOf("bob"))))
                as SyncJoinPrepared.Preview).ticket
            assertEquals(SyncJoinCommit.Joined, deviceB.service.commitJoin(ticket, { SyncLocalData(Curation(favorites = listOf("bob"))) }, deviceB.sink))
            assertEquals(2L, deviceB.service.state?.lastSeenVer)

            // 构造一次真实 409 带体：拦截设备 A 的 PUT，先让设备 B 在服务端推到 ver=3，再让设备 A 用 base=2 发 PUT
            deviceA.service.perform(SyncLocalOp.Favorite("fromA"))
            var injectedConflict = false
            val rawServiceA = deviceA.service
            val realTransportA = SyncHttpTransport(server.baseUrl, rawServiceA.keys!!)
            val racingTransport = object : vela.core.sync.SyncTransport {
                override suspend fun get() = realTransportA.get()
                override suspend fun put(request: vela.core.sync.SyncPutRequest): SyncServerResponse {
                    if (!injectedConflict) {
                        injectedConflict = true
                        deviceB.service.perform(SyncLocalOp.Favorite("fromB"))
                        assertEquals(SyncCycleResult.Pushed, deviceB.service.runCycle())
                    }
                    return realTransportA.put(request)
                }
                override suspend fun delete(base: Long, epoch: String?) = realTransportA.delete(base, epoch)
            }
            val stateCopy = rawServiceA.state!!
            val coord = vela.core.sync.SyncCoordinator(
                state = stateCopy,
                store = deviceA.stateStore,
                transport = racingTransport,
                codec = SyncCryptoCodec(rawServiceA.keys!!),
                wallClock = { nowMs },
            )
            // GET 拿到 ver=2 -> PUT base=2 撞上设备 B 刚推的 ver=3 -> 服务端回 409 带体 -> 自动合并重试 PUT base=3 -> ver=4
            assertEquals(SyncCycleResult.Pushed, coord.runCycle())
            assertEquals(4L, coord.state.lastSeenVer)
            val proj = vela.core.sync.SyncProjection(coord.state.doc)
            assertTrue(proj.curation.isFavorite("fromA"))
            assertTrue(proj.curation.isFavorite("fromB"))
        }
    }

    @Test
    fun test403Forbidden410Gone507QuotaFullAnd429RateLimited() = runBlocking {
        FakeSyncServer(nowMs = { nowMs }).use { server ->
            val deviceA = Device("errA", tempDir, server, nowMs)
            val deviceB = Device("errB", tempDir, server, nowMs)
            val created = deviceA.service.create(
                logicalOrigin,
                SyncLocalData(Curation(favorites = listOf("alice"))),
            ) as SyncCreateResult.Created

            // 设备 B 加入，持有有效状态
            val ticket = (deviceB.service.prepareJoin(created.code, SyncLocalData()) as SyncJoinPrepared.Preview).ticket
            assertEquals(SyncJoinCommit.Joined, deviceB.service.commitJoin(ticket, { SyncLocalData() }, deviceB.sink))

            // 507 QuotaFull + 调度层一小时硬退避
            val scheduler = SyncScheduler()
            deviceA.service.perform(SyncLocalOp.Favorite("extra1"))
            // GET 成功(ver=2)，PUT 撞 507
            val keysA = deviceA.service.keys!!
            server.enqueueFault(FakeSyncServer.Fault.QuotaFull)
            assertEquals(SyncCycleResult.QuotaFull, deviceA.service.runCycle())
            scheduler.record(SyncCycleResult.QuotaFull, nowMs.toULong())
            assertFalse(scheduler.allows(SyncScheduler.Trigger.MANUAL, (nowMs + 3_599_000L).toULong()))
            assertTrue(scheduler.allows(SyncScheduler.Trigger.MANUAL, (nowMs + 3_600_000L).toULong()))

            // 429 RateLimited（含 Retry-After 与极大值夹紧到 86400）
            server.enqueueFault(FakeSyncServer.Fault.RateLimited("120"))
            assertEquals(SyncCycleResult.RateLimited(120), deviceA.service.runCycle())
            val rateScheduler = SyncScheduler()
            rateScheduler.record(SyncCycleResult.RateLimited(120), nowMs.toULong())
            assertFalse(rateScheduler.allows(SyncScheduler.Trigger.MANUAL, (nowMs + 119_000L).toULong()))
            assertTrue(rateScheduler.allows(SyncScheduler.Trigger.MANUAL, (nowMs + 120_000L).toULong()))

            server.enqueueFault(FakeSyncServer.Fault.RateLimited("9999999"))
            assertEquals(SyncCycleResult.RateLimited(SyncHttp.maxRetryAfter), deviceA.service.runCycle())

            // 403 Forbidden -> 停止同步（持久化 halt）
            server.enqueueFault(FakeSyncServer.Fault.Forbidden)
            assertEquals(SyncCycleResult.Halted(SyncHalt.Forbidden), deviceA.service.runCycle())
            assertEquals(SyncHalt.Forbidden, deviceA.service.state?.halt)
            // 已 halt 后再次周期直接返回 Halted，不再发网络请求
            val beforeHaltedCycle = server.requests.size
            assertEquals(SyncCycleResult.Halted(SyncHalt.Forbidden), deviceA.service.runCycle())
            assertEquals(beforeHaltedCycle, server.requests.size)

            // 410 Gone：设备 B 清除云端，新建设备 C / 未停设备再 GET 收到 410
            val deviceC = Device("errC", tempDir, server, nowMs)
            val ticketC = (deviceC.service.prepareJoin(created.code, SyncLocalData()) as SyncJoinPrepared.Preview).ticket
            assertEquals(SyncJoinCommit.Joined, deviceC.service.commitJoin(ticketC, { SyncLocalData() }, deviceC.sink))
            assertEquals(SyncFlowResult.Done, deviceB.service.clear(deviceB.sink))
            val goneRes = deviceC.service.runCycle()
            assertEquals(SyncCycleResult.Halted(SyncHalt.Gone(nowMs)), goneRes)
            assertEquals(keysA.blobID, deviceC.service.state?.blobID)
        }
    }

    @Test
    fun testTransportHeadersNoCookiesNoRedirectsAndResponseSizeCap() = runBlocking {
        FakeSyncServer(nowMs = { nowMs }).use { server ->
            val keys: SyncDerived = deriveSyncKeys(ByteArray(32) { (it + 21).toByte() })
            val transport = SyncHttpTransport(server.baseUrl, keys)

            // 1. 服务端下发 Set-Cookie，下一次请求不得回传 Cookie，且 User-Agent 固定为 Vela-Sync/2
            server.enqueueFault(FakeSyncServer.Fault.SetCookieOnNext)
            val r1 = transport.get()
            assertTrue(r1 is SyncServerResponse.NotFound)
            val r2 = transport.get()
            assertTrue(r2 is SyncServerResponse.NotFound)
            assertTrue(server.requests.all { it.userAgent == SyncHttpTransport.USER_AGENT })
            assertTrue(server.requests.all { it.cookie == null })

            // 2. 302 重定向必须拒绝跟随，返回 Transient
            server.enqueueFault(FakeSyncServer.Fault.Redirect302)
            assertEquals(SyncServerResponse.Transient, transport.get())
            assertEquals(0, server.redirectTargetHits)

            // 3. 超过 maxResponseBytes（802,048）的响应体必须中止并返回 Transient
            server.enqueueFault(FakeSyncServer.Fault.OversizedBody(SyncHttp.maxResponseBytes + 1))
            assertEquals(SyncServerResponse.Transient, transport.get())
        }
    }
}
