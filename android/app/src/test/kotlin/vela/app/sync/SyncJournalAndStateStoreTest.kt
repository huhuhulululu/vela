package vela.app.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vela.core.base.io.DeferredSaver
import vela.core.curation.Curation
import vela.core.curation.CurationStore
import vela.core.sync.SyncClock
import vela.core.sync.SyncCode
import vela.core.sync.SyncDocument
import vela.core.sync.SyncFlowResult
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncPhase
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncStartupAction
import vela.core.sync.SyncState
import vela.core.sync.SyncStateFileStore
import vela.core.sync.SyncStateRead
import vela.core.sync.SyncStateStore
import vela.core.sync.SyncStartupAction.StateCorrupted
import vela.core.sync.deriveSyncKeys
import vela.core.sync.sha256
import vela.core.taste.Taste
import vela.core.taste.TasteStore

/**
 * 真实文件存储上的快照与状态损坏标记测试（对齐 sync.md 第 12.4、23 节 H1–H8）：
 * - FileJoinJournal：原子发布、完整/已提交/不完整三态、原子换回、未设置键恢复
 * - SyncMarkedStateStore：损坏标记先于改名落盘、标记写不成时不改名且完好文件仍可读、关闭先清标记再删 K
 */
class SyncJournalAndStateStoreTest {
    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vela-sync-journal-")
    }

    @After
    fun tearDown() {
        restoreWritable(dir)
        dir.toFile().deleteRecursively()
    }

    private fun restoreWritable(root: Path) {
        if (!Files.exists(root)) return
        try {
            Files.walk(root).use { stream ->
                stream.forEach { p ->
                    try {
                        Files.setPosixFilePermissions(
                            p,
                            PosixFilePermissions.fromString(if (Files.isDirectory(p)) "rwx------" else "rw-------"),
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun chmod(path: Path, mode: String) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
    }

    @Test
    fun testFileJoinJournalSnapshotRestoreAndCommittedSemantics() {
        val curation = dir.resolve("feed-curation.json")
        val taste = dir.resolve("feed-taste.json")
        Files.writeString(curation, "old-curation")
        val settings = linkedMapOf("publicOnly" to false)
        val journal = FileJoinJournal(
            dir = dir.resolve("sync-join-snapshot"),
            files = listOf(curation, taste),
            settingKeys = listOf("publicOnly", "infoAlwaysOn"),
            readSettings = { settings.toMap() },
            writeSettings = { values ->
                for ((k, v) in values) {
                    if (v == null) settings.remove(k) else settings[k] = v
                }
            },
        )
        assertEquals(SyncJoinJournalStatus.NONE, journal.status)
        assertFalse(journal.exists)
        assertTrue(journal.snapshot())
        assertEquals(SyncJoinJournalStatus.PENDING, journal.status)
        assertTrue(journal.exists)
        assertTrue(Files.exists(journal.dir.resolve(FileJoinJournal.COMPLETE_MARKER)))

        // 模拟本机投影覆盖与新增不存在的口味文件
        Files.writeString(curation, "projected-curation")
        Files.writeString(taste, "projected-taste")
        settings["publicOnly"] = true
        settings["infoAlwaysOn"] = true

        assertTrue(journal.restore())
        assertEquals("old-curation", Files.readString(curation))
        assertFalse("原本没有的文件换回后必须删除", Files.exists(taste))
        assertEquals(mapOf("publicOnly" to false), settings)
        assertTrue("换回可重复执行", journal.restore())
        assertEquals(SyncJoinJournalStatus.PENDING, journal.status)
        assertTrue(journal.discard())
        assertFalse(journal.exists)
        assertFalse("无快照时拒绝换回", journal.restore())

        // 已提交快照只能删除，不能换回
        assertTrue(journal.snapshot())
        assertTrue(journal.markCommitted())
        assertEquals(SyncJoinJournalStatus.COMMITTED, journal.status)
        Files.writeString(curation, "new-projected")
        assertFalse("已提交快照不得换回", journal.restore())
        assertEquals("new-projected", Files.readString(curation))
        assertTrue(journal.discard())
    }

    @Test
    fun testH6_HalfSnapshotAndFailedBuildLeaveNoCorruptRestore() {
        val curation = dir.resolve("feed-curation.json")
        Files.writeString(curation, "current")
        val settings = linkedMapOf("publicOnly" to false)
        val journal = FileJoinJournal(
            dir = dir.resolve("sync-join-snapshot"),
            files = listOf(curation),
            settingKeys = listOf("publicOnly", "infoAlwaysOn"),
            readSettings = { settings.toMap() },
            writeSettings = { _ -> },
        )

        // 构建中途写失败：不得留下发布目录或临时目录
        journal.writeFile = { path, data ->
            if (path.fileName.toString() == "settings.json") false
            else {
                Files.write(path, data)
                true
            }
        }
        assertFalse(journal.snapshot())
        assertEquals(SyncJoinJournalStatus.NONE, journal.status)
        val leftovers = Files.list(dir).use { s ->
            s.filter { it.fileName.toString().startsWith("sync-join-snapshot") }.toList()
        }
        assertTrue("失败构建不得留临时目录或半份目录：$leftovers", leftovers.isEmpty())

        // 手工制造没有完整性标记的半份目录：判 INCOMPLETE、不得换回、discard 删除
        Files.createDirectories(journal.dir)
        Files.writeString(journal.dir.resolve("feed-curation.json"), "stale")
        assertEquals(SyncJoinJournalStatus.INCOMPLETE, journal.status)
        assertFalse(journal.restore())
        assertEquals("current", Files.readString(curation))
        assertTrue(journal.discard())
        assertEquals(SyncJoinJournalStatus.NONE, journal.status)
    }

    @Test
    fun testH4AndH7_CorruptMarkerPersistedBeforeQuarantineAndValidReadWhenMarkerFails() = runBlocking {
        val statePath = dir.resolve("sync-state.json")
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        val derived = deriveSyncKeys(keyBytes)
        val keyStore = TestMemoryKeyStore().apply {
            entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
        }
        val sink = RealFileSink(dir)

        // H4a: 坏状态文件，损坏标记先于改名落盘；重启多次均报状态损坏且保留 K
        Files.writeString(statePath, "{not json")
        val fileWhenMarked = ArrayList<Boolean>()
        val backing = SyncMarkedStateStore(SyncStateFileStore(statePath)).apply {
            writeMarker = { marker ->
                fileWhenMarked.add(Files.exists(statePath))
                Files.write(marker, ByteArray(0))
                true
            }
        }
        assertEquals(SyncStateRead.Corrupted, backing.load())
        assertEquals(listOf(true), fileWhenMarked)
        assertFalse("坏文件已改名隔离", Files.exists(statePath))
        assertTrue("损坏标记已落盘", Files.exists(backing.corruptMarker))

        val service2 = makeService(SyncMarkedStateStore(SyncStateFileStore(statePath)), keyStore, dir)
        assertEquals(SyncStartResult.Attention(StateCorrupted), service2.start({ sink.local() }, sink))
        assertNotNull("损坏时绝不删 K", keyStore.entry)

        // H4b: 关闭时先删标记再删 K；标记删不掉不动 K
        chmod(dir, "r-x------")
        assertEquals(SyncFlowResult.StorageFailed, service2.disable(sink))
        assertNotNull("标记删不掉时必须保留 K", keyStore.entry)
        chmod(dir, "rwx------")
        assertEquals(SyncFlowResult.Done, service2.disable(sink))
        assertNull(keyStore.entry)
        assertFalse(Files.exists(backing.corruptMarker))

        // H7: 标记写不成时，完好状态文件走只读解码照常读出，不得误判损坏
        val validState = SyncState(
            phase = SyncPhase.ACTIVE,
            origin = "https://sync.example",
            blobID = derived.blobID,
            clock = SyncClock(dev = 7L),
            epoch = "0123456789abcdef0123456789abcdef",
        )
        assertTrue(SyncStateFileStore(statePath).save(validState))
        keyStore.entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
        val readOnlyBacking = SyncMarkedStateStore(SyncStateFileStore(statePath)).apply {
            writeMarker = { false }
        }
        val read = readOnlyBacking.load()
        assertTrue("完好状态在标记写不成时仍须读出", read is SyncStateRead.Present)
        val service3 = makeService(readOnlyBacking, keyStore, dir)
        assertEquals(SyncStartResult.Active, service3.start({ sink.local() }, sink))
        assertTrue(Files.exists(statePath))
        assertEquals(SyncFlowResult.Done, service3.disable(sink))
        assertNull(keyStore.entry)
    }

    @Test
    fun testH1_PendingDeferredEditBeforeJoinCommitSurvivesRollback() = runBlocking {
        val curationStore = CurationStore(CurationStore.fileIn(dir))
        assertTrue(curationStore.save(Curation(favorites = listOf("alice"))))
        val sink = RealFileSink(dir)
        // 模拟 2 秒去抖保存队列里尚未落盘的编辑
        sink.editCuration(Curation(favorites = listOf("alice", "bob")))
        assertEquals(setOf("alice"), CurationStore(CurationStore.fileIn(dir)).load().favorites.toSet())

        val keyBytes = ByteArray(32) { (it + 3).toByte() }
        val derived = deriveSyncKeys(keyBytes)
        val code = SyncCode.format(keyBytes, "https://sync.example", ::sha256)!!
        val epoch = "0123456789abcdef0123456789abcdef"
        val remoteDoc = SyncDocument.empty().also {
            val r = vela.core.sync.SyncOps.apply(
                vela.core.sync.SyncLocalOp.Favorite("zed"),
                vela.core.sync.SyncReplica(it, SyncClock(dev = 99L)),
                1_799_999_000_000L,
            ) as vela.core.sync.SyncOpOutcome.Applied
            it.rooms.putAll(r.replica.doc.rooms)
        }
        val codec = SyncCryptoCodec(derived)
        val envelope = codec.seal(remoteDoc.encoded(), 3L)!!
        val transport = TestScriptedTransport(
            gets = mutableListOf(SyncServerResponse.Blob(3L, derived.pubB64, envelope, epoch, 1_800_000_000_000L)),
            puts = mutableListOf(SyncServerResponse.Stored(4L, epoch, 1_800_000_000_000L)),
        )
        val keyStore = TestMemoryKeyStore()
        val hookedStore = HookedStateStore(SyncMarkedStateStore(SyncStateFileStore(dir.resolve("sync-state.json")))).apply {
            failSave = true
        }
        val journal = FileJoinJournal(
            dir = dir.resolve("sync-join-snapshot"),
            files = listOf(sink.curationStore.url, sink.tasteStore.url),
            settingKeys = listOf("publicOnly", "infoAlwaysOn"),
            readSettings = { sink.settings.toMap() },
            writeSettings = { vals ->
                for ((k, v) in vals) {
                    if (v == null) sink.settings.remove(k) else sink.settings[k] = v
                }
            },
        )
        val service = SyncService(
            SyncService.Environment(
                stateStore = hookedStore,
                keyStore = keyStore,
                makeTransport = { _, _ -> transport },
                journal = journal,
                wallClock = { 1_800_000_000_000L },
                random = { n -> ByteArray(n) { 1 } },
            ),
        )
        val prepared = service.prepareJoin(code, sink.local())
        assertTrue(prepared is SyncJoinPrepared.Preview)
        val ticket = (prepared as SyncJoinPrepared.Preview).ticket
        val commit = service.commitJoin(ticket, { sink.local() }, sink)
        assertTrue("状态写失败应回滚并返回 Failed，实际 $commit", commit is SyncJoinCommit.Failed)
        assertEquals(setOf("alice", "bob"), CurationStore(CurationStore.fileIn(dir)).load().favorites.toSet())
        assertEquals(setOf("alice", "bob"), sink.curation.favorites.toSet())
        sink.cancelPendingSaves()
        assertEquals(setOf("alice", "bob"), CurationStore(CurationStore.fileIn(dir)).load().favorites.toSet())
        assertNull(keyStore.entry)
        assertFalse(journal.exists)
    }

    private fun makeService(store: SyncStateStore, keyStore: SyncKeyStore, dir: Path): SyncService = SyncService(
        SyncService.Environment(
            stateStore = store,
            keyStore = keyStore,
            makeTransport = { _, _ -> TestScriptedTransport() },
            journal = FileJoinJournal(
                dir = dir.resolve("sync-join-snapshot"),
                files = listOf(CurationStore.fileIn(dir), TasteStore.defaultURL(dir)),
                settingKeys = listOf("publicOnly", "infoAlwaysOn"),
                readSettings = { emptyMap() },
                writeSettings = {},
            ),
            wallClock = { 1_800_000_000_000L },
        ),
    )

    private class HookedStateStore(private val inner: SyncStateStore) : SyncStateStore {
        var failSave = false
        override fun load(): SyncStateRead = inner.load()
        override fun save(state: SyncState): Boolean = if (failSave) false else inner.save(state)
        override fun delete(): Boolean = inner.delete()
    }

    private class RealFileSink(dir: Path) : SyncJoinSink {
        val curationStore = CurationStore(CurationStore.fileIn(dir))
        val tasteStore = TasteStore(TasteStore.defaultURL(dir))
        val curationSaver = DeferredSaver<Curation>(delay = 2.0) { curationStore.save(it) }
        val tasteSaver = DeferredSaver<Taste>(delay = 2.0) { tasteStore.save(it) }
        val settings = linkedMapOf<String, Boolean>()
        var curation: Curation = curationStore.load()
        var taste: Taste = tasteStore.load()

        fun editCuration(next: Curation) {
            curation = next
            curationSaver.schedule(next)
        }

        fun local(): SyncLocalData = SyncLocalData(curation, taste.vetoedKeys.toList(), settings.toMap())

        override fun cancelPendingSaves() {
            curationSaver.flush()
            tasteSaver.flush()
        }

        override fun currentTaste(): Taste = taste
        override fun saveCuration(curation: Curation): Boolean = curationStore.save(curation)
        override fun saveTaste(taste: Taste): Boolean = tasteStore.save(taste)
        override fun applySettings(settings: Map<String, Boolean>) {
            this.settings.putAll(settings)
        }

        override fun persistPending(): Boolean {
            cancelPendingSaves()
            return curationStore.save(curation) && tasteStore.save(taste)
        }

        override fun reloadAfterRollback() {
            curation = curationStore.load()
            taste = tasteStore.load()
        }
    }
}
