package vela.app.infra

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicFileTest {
    @Test
    fun testWriteReplacesWholeFileUnderVelaDirectory() {
        val root = Files.createTempDirectory("vela-atomic").toFile()
        try {
            val target = AtomicFile.resolve(root, "taste.json")
            assertEquals(File(root, "Vela"), target.parentFile)
            AtomicFile.write(target, "alpha".toByteArray())
            assertArrayEquals("alpha".toByteArray(), AtomicFile.read(target))
            AtomicFile.write(target, "beta-beta".toByteArray())
            assertArrayEquals("beta-beta".toByteArray(), AtomicFile.read(target))
            assertFalse(target.parentFile!!.list()!!.any { it.contains(".tmp") || it.startsWith(".") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun testFailedReplaceKeepsPreviousBytes() {
        val root = Files.createTempDirectory("vela-atomic-fail").toFile()
        try {
            val target = AtomicFile.resolve(root, "ledger.json")
            AtomicFile.write(target, "safe".toByteArray())
            val failed = runCatching {
                AtomicFile.write(target, "damaged".toByteArray()) { _, _ ->
                    throw java.io.IOException("disk")
                }
            }
            assertTrue(failed.isFailure)
            assertArrayEquals("safe".toByteArray(), target.readBytes())
            val leftovers = target.parentFile!!.list()!!.filter { it != target.name }
            assertTrue(leftovers.toString(), leftovers.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun testOversizedReadReturnsNullAndRejectsPathEscape() {
        val root = Files.createTempDirectory("vela-atomic-limit").toFile()
        try {
            val target = AtomicFile.resolve(root, "big.json")
            AtomicFile.write(target, ByteArray(32) { 7 })
            assertNull(AtomicFile.read(target, limit = 8))
            assertEquals(32, AtomicFile.read(target, limit = 32)!!.size)
            assertTrue(runCatching { AtomicFile.resolve(root, "../outside.json") }.isFailure)
            assertTrue(runCatching { AtomicFile.resolve(root, "nested/file.json") }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
}
