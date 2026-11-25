package vela.app.sync

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vela.core.curation.Curation
import vela.core.curation.CurationStore
import vela.core.sync.SyncClock
import vela.core.sync.SyncFlowResult
import vela.core.sync.SyncLocalData
import vela.core.sync.SyncPhase
import vela.core.sync.SyncServerResponse
import vela.core.sync.SyncStartupAction
import vela.core.sync.SyncState
import vela.core.sync.SyncStateFileStore
import vela.core.sync.deriveSyncKeys
import vela.core.taste.Taste
import vela.core.taste.TasteStore

/**
 * PR #14 / A-SYNC-6 对照测试：
 * - `FileJoinJournal` 的 9 条回滚与删快照中断收敛、设置刷盘失败传播、列目录失败不无序删、半份/损坏快照保护；
 * - `SyncMarkedStateStore` + `SyncStateFileStore` + `SyncService` 损坏只读状态清除后同进程不重启即可重开。
 * 全部使用可注入文件操作制造故障，不依赖运行用户、文件权限位或临时目录位置。
 */
class SyncFileJournalParityTest {
    private lateinit var dir: Path
    private val origin = "https://sync.example"
    private val epoch = "0123456789abcdef0123456789abcdef"
    private val nowMs = 1_800_000_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vela-sync-journal-parity-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private data class RollbackRun(
        val steps: List<String>,
        val firstAttemptCompleted: Boolean,
        val statusAfterFault: SyncJoinJournalStatus,
        val finalStatus: SyncJoinJournalStatus,
        val finalCuration: String,
        val finalTaste: String?,
        val finalSettings: Map<String, Boolean>,
    )

    private fun runRollbackScenario(
        tasteInitiallyExists: Boolean,
        failAtStep: Int?,
        dropFirstFlush: Boolean = false,
    ): RollbackRun {
        val work = Files.createTempDirectory(dir, "step-")
        val curation = work.resolve("feed-curation.json")
        val taste = work.resolve("feed-taste.json")
        Files.writeString(curation, "old-curation")
        if (tasteInitiallyExists) Files.writeString(taste, "old-taste")

        var persisted = linkedMapOf("publicOnly" to false)
        var cached = LinkedHashMap(persisted)
        var dropFlush = false

        fun make(): FileJoinJournal = FileJoinJournal(
            dir = work.resolve("sync-join-snapshot"),
            files = listOf(curation, taste),
            settingKeys = listOf("publicOnly", "infoAlwaysOn"),
            readSettings = { LinkedHashMap(cached) },
            writeSettings = { values ->
                for ((k, v) in values) {
                    if (v == null) cached.remove(k) else cached[k] = v
                }
                if (dropFlush) {
                    false
                } else {
                    persisted = LinkedHashMap(cached)
                    true
                }
            },
        )

        val j = make()
        assertTrue(j.snapshot())
        Files.writeString(curation, "projected-curation")
        Files.writeString(taste, "projected-taste")
        persisted = linkedMapOf("publicOnly" to true, "infoAlwaysOn" to true)
        cached = LinkedHashMap(persisted)
        dropFlush = dropFirstFlush

        val steps = ArrayList<String>()
        val origRename = j.renameItem
        val origRemove = j.removeItem
        val origRead = j.readFile
        val origList = j.listDirectory
        j.renameItem = { from, to ->
            val idx = steps.size
            steps.add("rename:" + to.fileName.toString())
            if (failAtStep == idx) false else origRename(from, to)
        }
        j.removeItem = { path ->
            val idx = steps.size
            steps.add("remove:" + path.fileName.toString())
            if (failAtStep == idx) false else origRemove(path)
        }
        j.readFile = { path ->
            val idx = steps.size
            steps.add("read:" + path.fileName.toString())
            if (failAtStep == idx) null else origRead(path)
        }
        j.listDirectory = { path ->
            val idx = steps.size
            steps.add("list:" + path.fileName.toString())
            if (failAtStep == idx) null else origList(path)
        }

        val completed = j.restore() && j.discard()

        // 模拟进程被杀后重启：内存设置缓存丢失（仅保留已落盘值），新造实例按 SyncService.start() 规则收尾。
        cached = LinkedHashMap(persisted)
        dropFlush = false
        val restarted = make()
        val afterFault = restarted.status
        when (afterFault) {
            SyncJoinJournalStatus.NONE -> Unit
            SyncJoinJournalStatus.INCOMPLETE, SyncJoinJournalStatus.COMMITTED -> {
                assertTrue(restarted.discard())
            }
            SyncJoinJournalStatus.PENDING -> {
                assertTrue(restarted.restore())
                assertTrue(restarted.discard())
            }
        }

        val finalTaste = if (Files.exists(taste)) Files.readString(taste) else null
        return RollbackRun(
            steps = steps,
            firstAttemptCompleted = completed,
            statusAfterFault = afterFault,
            finalStatus = restarted.status,
            finalCuration = Files.readString(curation),
            finalTaste = finalTaste,
            finalSettings = LinkedHashMap(persisted),
        )
    }

    // parity: Tests/VelaSyncTests/SyncFileJournalTests.swift#testInterruptedDiscardMissingSettingsConvergesOnRestart
    @Test
    fun testInterruptedDiscardMissingSettingsConvergesOnRestart() {
        val work = Files.createTempDirectory(dir, "stuck-")
        val curation = work.resolve("feed-curation.json")
        Files.writeString(curation, "old-curation")
        val settings = linkedMapOf("publicOnly" to false)
        fun makeJournal(): FileJoinJournal = FileJoinJournal(
            dir = work.resolve("sync-join-snapshot"),
            files = listOf(curation),
            settingKeys = listOf("publicOnly", "infoAlwaysOn"),
            readSettings = { LinkedHashMap(settings) },
            writeSettings = { values ->
                for ((k, v) in values) {
                    if (v == null) settings.remove(k) else settings[k] = v
                }
                true
            },
        )

        val j1 = makeJournal()
        assertTrue(j1.snapshot())
        Files.writeString(curation, "projected")
        settings["publicOnly"] = true
        settings["infoAlwaysOn"] = true
        assertTrue(j1.restore())
        // 模拟删快照中途先删掉了 settings.json 后进程中断（complete 仍在）。
        Files.delete(j1.dir.resolve(FileJoinJournal.SETTINGS_FILE))

        val restarted = makeJournal()
        assertEquals(SyncJoinJournalStatus.INCOMPLETE, restarted.status)
        when (restarted.status) {
            SyncJoinJournalStatus.NONE -> Unit
            SyncJoinJournalStatus.INCOMPLETE, SyncJoinJournalStatus.COMMITTED -> assertTrue(restarted.discard())
            SyncJoinJournalStatus.PENDING -> {
                assertTrue("不应永久卡死在需要修复", restarted.restore())
                assertTrue(restarted.discard())
            }
        }
        assertEquals(SyncJoinJournalStatus.NONE, restarted.status)
        assertEquals("old-curation", Files.readString(curation))
        assertEquals(mapOf("publicOnly" to false), settings)
    }

    // parity: Tests/VelaSyncTests/SyncFileJournalTests.swift#testRollbackConvergesAfterFaultAtEveryFileStep_ExistingAndAbsent
    @Test
    fun testRollbackConvergesAfterFaultAtEveryFileStep_ExistingAndAbsent() {
        val trace = runRollbackScenario(tasteInitiallyExists = false, failAtStep = null)
        assertEquals(
            "未提交快照删除顺序：列目录 → 删 settings.json → 删 complete → 删目录",
            listOf(
                "rename:feed-curation.json",
                "remove:feed-taste.json",
                "remove:absent-feed-taste.json",
                "read:settings.json",
                "list:sync-join-snapshot",
                "remove:settings.json",
                "remove:complete",
                "remove:sync-join-snapshot",
            ),
            trace.steps,
        )
        for (step in trace.steps.indices) {
            val outcome = runRollbackScenario(tasteInitiallyExists = false, failAtStep = step)
            assertFalse("第 $step 步（${trace.steps[step]}）必须触发失败", outcome.firstAttemptCompleted)
            assertEquals("第 $step 步（${trace.steps[step]}）中断后重启须收敛到 NONE", SyncJoinJournalStatus.NONE, outcome.finalStatus)
            assertEquals("第 $step 步（${trace.steps[step]}）", "old-curation", outcome.finalCuration)
            assertNull("第 $step 步（${trace.steps[step]}）：原本没有的文件须已删除", outcome.finalTaste)
            assertEquals("第 $step 步（${trace.steps[step]}）", mapOf("publicOnly" to false), outcome.finalSettings)
        }
    }

    // parity: Tests/VelaSyncTests/SyncFileJournalTests.swift#testRollbackConvergesAfterFaultAtEveryFileStep_TwoExistingFiles
    @Test
    fun testRollbackConvergesAfterFaultAtEveryFileStep_TwoExistingFiles() {
        val trace = runRollbackScenario(tasteInitiallyExists = true, failAtStep = null)
        assertEquals(
            listOf(
                "rename:feed-curation.json",
                "rename:feed-taste.json",
                "read:settings.json",
                "list:sync-join-snapshot",
                "remove:settings.json",
                "remove:complete",
                "remove:sync-join-snapshot",
            ),
            trace.steps,
        )
        for (step in trace.steps.indices) {
            val outcome = runRollbackScenario(tasteInitiallyExists = true, failAtStep = step)
            assertFalse("第 $step 步（${trace.steps[step]}）必须触发失败", outcome.firstAttemptCompleted)
            assertEquals("第 $step 步（${trace.steps[step]}）中断后重启须收敛到 NONE", SyncJoinJournalStatus.NONE, outcome.finalStatus)
            assertEquals("第 $step 步（${trace.steps[step]}）", "old-curation", outcome.finalCuration)
            assertEquals("第 $step 步（${trace.steps[step]}）", "old-taste", outcome.finalTaste)
            assertEquals("第 $step 步（${trace.steps[step]}）", mapOf("publicOnly" to false), outcome.finalSettings)
        }
    }

    // parity: Tests/VelaSyncTests/SyncFileJournalTests.swift#testCommittedDiscardNeverRevertsToPendingAtAnyStep
    @Test
    fun testCommittedDiscardNeverRevertsToPendingAtAnyStep() {
        data class CommittedRun(
            val steps: List<String>,
            val statusAfterFault: SyncJoinJournalStatus,
            val finalStatus: SyncJoinJournalStatus,
            val curation: String,
            val settings: Map<String, Boolean>,
        )

        fun run(failAtStep: Int?): CommittedRun {
            val work = Files.createTempDirectory(dir, "commit-")
            val curation = work.resolve("feed-curation.json")
            Files.writeString(curation, "old-curation")
            val settings = linkedMapOf("publicOnly" to false)
            fun make(): FileJoinJournal = FileJoinJournal(
                dir = work.resolve("sync-join-snapshot"),
                files = listOf(curation),
                settingKeys = listOf("publicOnly", "infoAlwaysOn"),
                readSettings = { LinkedHashMap(settings) },
                writeSettings = { values ->
                    for ((k, v) in values) {
                        if (v == null) settings.remove(k) else settings[k] = v
                    }
                    true
                },
            )

            val j = make()
            assertTrue(j.snapshot())
            assertTrue(j.markCommitted())
            Files.writeString(curation, "projected")
            settings["publicOnly"] = true
            settings["infoAlwaysOn"] = true

            val steps = ArrayList<String>()
            val origList = j.listDirectory
            val origRemove = j.removeItem
            j.listDirectory = { path ->
                val idx = steps.size
                steps.add("list:" + path.fileName.toString())
                if (failAtStep == idx) null else origList(path)
            }
            j.removeItem = { path ->
                val idx = steps.size
                steps.add("remove:" + path.fileName.toString())
                if (failAtStep == idx) false else origRemove(path)
            }
            val ok = j.discard()
            if (failAtStep != null) assertFalse(ok) else assertTrue(ok)

            val restarted = make()
            val afterFault = restarted.status
            assertNotEquals("已提交快照删到一半绝不能退回 PENDING", SyncJoinJournalStatus.PENDING, afterFault)
            if (afterFault != SyncJoinJournalStatus.NONE) assertTrue(restarted.discard())
            return CommittedRun(steps, afterFault, restarted.status, Files.readString(curation), LinkedHashMap(settings))
        }

        val base = run(failAtStep = null)
        assertEquals(
            "committed 必须在 settings.json 与 complete 之后删除，避免退回 PENDING",
            listOf(
                "list:sync-join-snapshot",
                "remove:complete",
                "remove:feed-curation.json",
                "remove:settings.json",
                "remove:committed",
                "remove:sync-join-snapshot",
            ),
            base.steps,
        )
        for (step in base.steps.indices) {
            val r = run(failAtStep = step)
            assertEquals("第 $step 步（${base.steps[step]}）", SyncJoinJournalStatus.NONE, r.finalStatus)
            assertEquals("第 $step 步（${base.steps[step]}）：不换回旧文件", "projected", r.curation)
            assertEquals("第 $step 步（${base.steps[step]}）：不换回旧设置", mapOf("publicOnly" to true, "infoAlwaysOn" to true), r.settings)
        }
    }

    // parity: Tests/VelaSyncTests/SyncFileJournalTests.swift#testRestorePropagatesWriteSettingsFailureAndKeepsSnapshotPending
    @Test
    fun testRestorePropagatesWriteSettingsFailureAndKeepsSnapshotPending() {
        val outcome = runRollbackScenario(tasteInitiallyExists = false, failAtStep = null, dropFirstFlush = true)
        assertFalse("设置刷盘失败时 restore() 必须返回 false，不得进入 discard()", outcome.firstAttemptCompleted)
        assertEquals("刷盘失败不得丢弃快照，状态保持可再次换回", SyncJoinJournalStatus.PENDING, outcome.statusAfterFault)
        assertEquals(SyncJoinJournalStatus.NONE, outcome.finalStatus)
        assertEquals("old-curation", outcome.finalCuration)
        assertNull(outcome.finalTaste)
        assertEquals("重启重试后设置须完整恢复", mapOf("publicOnly" to false), outcome.finalSettings)
    }

    // parity: Tests/VelaSyncTests/SyncFileJournalTests.swift#testDiscardFailsWithoutUnorderedDeleteWhenListDirectoryFails
    @Test
    fun testDiscardFailsWithoutUnorderedDeleteWhenListDirectoryFails() {
        val work = Files.createTempDirectory(dir, "listfail-")
        val curation = work.resolve("feed-curation.json")
        Files.writeString(curation, "old-curation")
        val settings = linkedMapOf("publicOnly" to false)
        val j = FileJoinJournal(
            dir = work.resolve("sync-join-snapshot"),
            files = listOf(curation),
            settingKeys = listOf("publicOnly", "infoAlwaysOn"),
            readSettings = { LinkedHashMap(settings) },
            writeSettings = { true },
        )
        assertTrue(j.snapshot())
        var removedCount = 0
        j.listDirectory = { null }
        j.removeItem = {
            removedCount += 1
            true
        }
        assertFalse("列目录失败必须直接返回 false", j.discard())
        assertEquals("不得退回无序删除", 0, removedCount)
        assertEquals("目录与关键标记完好保留", SyncJoinJournalStatus.PENDING, j.status)
    }

    // parity: Tests/VelaSyncTests/SyncFileJournalTests.swift#testHalfBuiltSnapshotWithSettingsButNoCompleteIsIncompleteAndNeverReplayed
    @Test
    fun testHalfBuiltSnapshotWithSettingsButNoCompleteIsIncompleteAndNeverReplayed() {
        val work = Files.createTempDirectory(dir, "half-")
        val curation = work.resolve("feed-curation.json")
        Files.writeString(curation, "current-curation")
        val snapDir = work.resolve("sync-join-snapshot")
        Files.createDirectories(snapDir)
        // 半份快照：已有旧收藏副本与合法 settings.json，但从未写 complete 标记。
        Files.writeString(snapDir.resolve("feed-curation.json"), "stale-curation")
        Files.writeString(snapDir.resolve(FileJoinJournal.SETTINGS_FILE), """{"publicOnly":false}""")

        val settings = linkedMapOf("publicOnly" to true, "infoAlwaysOn" to true)
        val restarted = FileJoinJournal(
            dir = snapDir,
            files = listOf(curation),
            settingKeys = listOf("publicOnly", "infoAlwaysOn"),
            readSettings = { LinkedHashMap(settings) },
            writeSettings = { values ->
                for ((k, v) in values) {
                    if (v == null) settings.remove(k) else settings[k] = v
                }
                true
            },
        )
        assertEquals("缺 complete 标记的半份快照不得判 PENDING", SyncJoinJournalStatus.INCOMPLETE, restarted.status)
        assertFalse("半份快照绝不得重放", restarted.restore())
        assertTrue(restarted.discard())
        assertEquals(SyncJoinJournalStatus.NONE, restarted.status)
        assertEquals("不得用残缺副本覆盖本机文件", "current-curation", Files.readString(curation))
        assertEquals("不得用残缺快照覆盖本机设置", mapOf("publicOnly" to true, "infoAlwaysOn" to true), settings)
    }

    // parity: Tests/VelaSyncTests/SyncFileJournalTests.swift#testCompleteSnapshotMidDiscardRemainsPendingBeforeSettingsRemovedThenConverges
    @Test
    fun testCompleteSnapshotMidDiscardRemainsPendingBeforeSettingsRemovedThenConverges() {
        val trace = runRollbackScenario(tasteInitiallyExists = false, failAtStep = null)
        val settingsRemoveStep = trace.steps.indexOf("remove:settings.json")
        val completeRemoveStep = trace.steps.indexOf("remove:complete")
        assertTrue("未提交快照删除追踪必须包含 remove:settings.json", settingsRemoveStep >= 0)
        assertTrue("未提交快照删除追踪必须包含 remove:complete", completeRemoveStep >= 0)
        assertTrue("未提交快照须先删 settings.json 再删 complete", settingsRemoveStep < completeRemoveStep)

        // 删 settings.json 之前中断：complete 与 settings.json 都在，保持 PENDING 可再次换回。
        val beforeSettings = runRollbackScenario(tasteInitiallyExists = false, failAtStep = settingsRemoveStep)
        assertEquals(SyncJoinJournalStatus.PENDING, beforeSettings.statusAfterFault)
        assertEquals(SyncJoinJournalStatus.NONE, beforeSettings.finalStatus)
        assertEquals(mapOf("publicOnly" to false), beforeSettings.finalSettings)

        // 删 settings.json 之后、删 complete 之前中断：设置已刷盘，转为 INCOMPLETE，重启安全删干净。
        val afterSettings = runRollbackScenario(tasteInitiallyExists = false, failAtStep = completeRemoveStep)
        assertEquals(SyncJoinJournalStatus.INCOMPLETE, afterSettings.statusAfterFault)
        assertEquals(SyncJoinJournalStatus.NONE, afterSettings.finalStatus)
        assertEquals(mapOf("publicOnly" to false), afterSettings.finalSettings)
    }

    // parity: Tests/VelaSyncTests/SyncFileJournalTests.swift#testCorruptSettingsJsonIsIncompleteAndConverges
    @Test
    fun testCorruptSettingsJsonIsIncompleteAndConverges() {
        val work = Files.createTempDirectory(dir, "corrupt-")
        val curation = work.resolve("feed-curation.json")
        Files.writeString(curation, "old-curation")
        val settings = linkedMapOf("publicOnly" to false)
        fun make(): FileJoinJournal = FileJoinJournal(
            dir = work.resolve("sync-join-snapshot"),
            files = listOf(curation),
            settingKeys = listOf("publicOnly", "infoAlwaysOn"),
            readSettings = { LinkedHashMap(settings) },
            writeSettings = { values ->
                for ((k, v) in values) {
                    if (v == null) settings.remove(k) else settings[k] = v
                }
                true
            },
        )

        val j = make()
        assertTrue(j.snapshot())
        // 快照后本机已被新数据改写，随后快照里的 settings.json 损坏。
        Files.writeString(curation, "projected")
        settings["publicOnly"] = true
        settings["infoAlwaysOn"] = true
        Files.writeString(j.dir.resolve(FileJoinJournal.SETTINGS_FILE), "{corrupt")

        val restarted = make()
        assertEquals(SyncJoinJournalStatus.INCOMPLETE, restarted.status)
        assertFalse("损坏快照不得重放", restarted.restore())
        assertTrue(restarted.discard())
        assertEquals(SyncJoinJournalStatus.NONE, restarted.status)
        assertEquals("只删不换回", "projected", Files.readString(curation))
        assertEquals("只删不换回", mapOf("publicOnly" to true, "infoAlwaysOn" to true), settings)
    }

    // parity: Tests/VelaSyncTests/SyncJoinRealFilesTests.swift#testReadOnlyCorruptedStateReenablesAfterDisableWithoutRestart
    @Test
    fun testReadOnlyCorruptedStateReenablesAfterDisableWithoutRestart() = runBlocking {
        val work = Files.createTempDirectory(dir, "readonly-reset-")
        val statePath = work.resolve("sync-state.json")
        Files.writeString(statePath, "{not json")

        val keyBytes = ByteArray(32) { (it + 19).toByte() }
        val derived = deriveSyncKeys(keyBytes)
        val keyStore = TestMemoryKeyStore().apply {
            entry = SyncKeyEntry(keyBytes, 7L, derived.blobID)
        }
        val inner = SyncStateFileStore(statePath).apply {
            // 注入改名隔离失败（不依赖运行用户与目录权限位），直接置上底层只读标记。
            moveItem = { _, _ -> throw IOException("no permission") }
        }
        val markedStore = SyncMarkedStateStore(inner)
        val transport = TestScriptedTransport()
        val curationStore = CurationStore(CurationStore.fileIn(work))
        val tasteStore = TasteStore(TasteStore.defaultURL(work))
        val localSettings = linkedMapOf<String, Boolean>()
        val sink = object : SyncJoinSink {
            var curation = Curation()
            var taste = Taste()
            fun local() = SyncLocalData(curation, taste.vetoedKeys.toList(), LinkedHashMap(localSettings))
            override fun cancelPendingSaves() = Unit
            override fun currentTaste(): Taste = taste
            override fun saveCuration(curation: Curation): Boolean = curationStore.save(curation)
            override fun saveTaste(taste: Taste): Boolean = tasteStore.save(taste)
            override fun applySettings(settings: Map<String, Boolean>) {
                localSettings.putAll(settings)
            }
            override fun persistPending(): Boolean = saveCuration(curation) && saveTaste(taste)
            override fun reloadAfterRollback() {
                curation = curationStore.load()
                taste = tasteStore.load()
            }
        }
        val journal = FileJoinJournal(
            dir = work.resolve("sync-join-snapshot"),
            files = listOf(curationStore.url, tasteStore.url),
            settingKeys = listOf("publicOnly", "infoAlwaysOn"),
            readSettings = { LinkedHashMap(localSettings) },
            writeSettings = { values ->
                for ((k, v) in values) {
                    if (v == null) localSettings.remove(k) else localSettings[k] = v
                }
                true
            },
        )
        val service = SyncService(
            SyncService.Environment(
                stateStore = markedStore,
                keyStore = keyStore,
                makeTransport = { _, _ -> transport },
                journal = journal,
                wallClock = { nowMs },
            ),
        )

        val started = service.start({ sink.local() }, sink)
        assertEquals(SyncStartResult.Attention(SyncStartupAction.StateCorrupted), started)
        assertTrue("前提：改名隔离失败，坏文件留在原处", Files.exists(statePath))

        val probe = SyncState(
            phase = SyncPhase.CREATING,
            origin = origin,
            blobID = derived.blobID,
            clock = SyncClock(dev = 7L),
        )
        assertFalse("前提：底层只读标记已置上", markedStore.save(probe))

        // 注入删除失败：关闭失败时只读标记不得复位。
        val realRemove = inner.removeItem
        inner.removeItem = { throw IOException("no permission") }
        assertEquals(SyncFlowResult.StorageFailed, service.disable(sink))
        inner.removeItem = realRemove
        assertFalse("删除未成功，只读标记不得复位", markedStore.save(probe))

        // 不重启（沿用同一 SyncService 与 SyncMarkedStateStore 实例）：关闭成功后直接重新开启。
        assertEquals(SyncFlowResult.Done, service.disable(sink))
        assertNull(keyStore.entry)
        assertFalse(Files.exists(statePath))
        assertFalse(Files.exists(markedStore.corruptMarker))

        transport.gets.add(SyncServerResponse.NotFound(epoch, nowMs))
        transport.puts.add(SyncServerResponse.Stored(1L, epoch, nowMs))
        val created = service.create(origin, sink.local())
        assertTrue("清除损坏状态后不重启应能重新开启，实际 $created", created is SyncCreateResult.Created)
        assertTrue(service.isActive)
    }
}
