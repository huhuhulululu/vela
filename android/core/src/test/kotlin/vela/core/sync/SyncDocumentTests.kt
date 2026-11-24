package vela.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.taste.Taste

class SyncDocumentTests {
    private val h = "0193b2c4d5e60000a1b2c3d4"

    private fun decode(s: String) = SyncDocument.decode(s.toByteArray(Charsets.UTF_8))

    private fun wrap(rooms: String = "{}", veto: String = "{}", settings: String = "{}") =
        """{"schema":2,"rooms":$rooms,"veto":$veto,"settings":$settings}"""

    @Test
    fun testRoomKeyRules() {
        for (ok in listOf("cb:alice", "cb:a_1", "c4:bob", "abcdefgh:xx", "cb:" + "a".repeat(30))) {
            assertTrue(SyncKeys.isRoomKey(ok), ok)
        }
        for (bad in listOf("alice", "cb:a", "cb:Alice", "Cb:alice", "4c:alice", "c:alice", "abcdefghi:xx", "cb:al-ice", "cb:" + "a".repeat(31), "cb:alice:x", "cb: alice")) {
            assertFalse(SyncKeys.isRoomKey(bad), bad)
        }
    }

    @Test
    fun testLocalNameMapsToCBPrefix() {
        assertEquals("cb:alice", SyncKeys.roomKey(" Alice "))
        assertNull(SyncKeys.roomKey("a"))
        assertNull(SyncKeys.roomKey("al ice"))
        assertNull(SyncKeys.roomKey("élise"))
    }

    @Test
    fun testDisplayNameRule() {
        assertTrue(SyncKeys.isDisplayName("Alice", "cb:alice"))
        assertFalse(SyncKeys.isDisplayName("Alicia", "cb:alice"))
        assertFalse(SyncKeys.isDisplayName("\u212Aim", "cb:kim"), "开尔文符号小写后是 k，但不是 ASCII")
        assertEquals("kim", SyncKeys.displayName("\u212Aim", "cb:kim"))
        assertEquals("Kim", SyncKeys.displayName(" Kim ", "cb:kim"))
    }

    @Test
    fun testTagKeyNormalization() {
        val cases = listOf(
            "Asian" to "t:asian",
            "#Tag" to "t:#tag",
            "\u00E9" to "t:\u00E9",
            "e\u0301" to "t:\u00E9",
            "\u03A3\u0391\u03A3" to "t:\u03C3\u03B1\u03C3",
            "\u0130" to "t:i\u0307",
            "\u00DF" to "t:\u00DF",
            "\u01C5" to "t:\u01C6",
            "a".repeat(64) to "t:" + "a".repeat(64),
            "a".repeat(65) to null,
            "" to null,
            "\uD83D\uDE00" to null,
            "\u0870" to null,
            "\uE000" to null,
            "\uFFFE" to null,
            "big tits" to null,
            "a\u3000b" to null,
            "say\"hi" to null,
            "back\\slash" to null,
            "tab\u0007" to null,
            "\u023A".repeat(64) to null,
        )
        for ((raw, expected) in cases) {
            val key = SyncKeys.syncTagKey(raw)
            assertEquals(expected, key, "raw=$raw")
            if (key != null) assertTrue(SyncKeys.isSyncTagKey(key))
        }
    }

    @Test
    fun testReceiverTagKeyChecks() {
        assertFalse(SyncKeys.isSyncTagKey("t:e\u0301"), "非 NFC")
        assertFalse(SyncKeys.isSyncTagKey("t:Asian"), "非小写，幂等检查不过")
        assertFalse(SyncKeys.isSyncTagKey("t:"), "空标签")
        assertFalse(SyncKeys.isSyncTagKey("t:" + "\u00E9".repeat(65)), "超过 128 字节")
        assertTrue(SyncKeys.isSyncTagKey("t:" + "\u00E9".repeat(64)))
        assertTrue(SyncKeys.isVetoKey("g:m"))
        assertFalse(SyncKeys.isVetoKey("g:x"))
        assertFalse(SyncKeys.isVetoKey("x:m"))
    }

    @Test
    fun testLocalVetoKeyMapping() {
        assertEquals("g:f", SyncKeys.syncVetoKey("g:f"))
        assertEquals("t:asian", SyncKeys.syncVetoKey(Taste.tagKey("Asian")))
        assertNull(SyncKeys.syncVetoKey(Taste.tagKey("big tits")))
        assertNull(SyncKeys.syncVetoKey("g:z"))
    }

    @Test
    fun testMinimalAndExampleDocumentsAreValid() {
        assertEquals(SyncDecodeResult.Valid(SyncDocument.empty()), decode(wrap()))
        val example = wrap(
            rooms = """{"cb:alice":{"f":"$h+","b":"0193b2c4d5e60000a1b2c3d4-","n":"${h}Alice"},"c4:bob_x":{"b":"0193b2c4d5e70001a1b2c3d4+"}}""",
            veto = """{"t:asian":"0193b2c4d5e80000a1b2c3d4+","g:m":"0193b2c4d5e90000a1b2c3d4-"}""",
            settings = """{"publicOnly":"0193b2c4d5ea0000a1b2c3d41"}""",
        )
        val doc = (decode(example) as SyncDecodeResult.Valid).doc
        assertEquals(2, doc.rooms.size)
        assertEquals("Alice", doc.rooms["cb:alice"]?.n?.payload)
        assertEquals(SyncDecodeResult.Valid(doc), decode(SyncFixture.text(doc)), "写出再读回不变")
    }

    @Test
    fun testEncodingIsCompactAndSorted() {
        val d = SyncDocument.empty()
        d.veto["t:b"] = SyncFixture.reg(2, "+")
        d.veto["g:m"] = SyncFixture.reg(1, "-")
        d.rooms["cb:zed"] = SyncRoom(f = SyncFixture.reg(3, "+"), n = SyncFixture.reg(3, "Zed"))
        fun x(l: Long) = Hlc(l, 0, 1).hex
        assertEquals("000000000003000000000001", x(3))
        assertEquals(
            """{"rooms":{"cb:zed":{"f":"${x(3)}+","n":"${x(3)}Zed"}},"schema":2,"settings":{},"veto":{"g:m":"${x(1)}-","t:b":"${x(2)}+"}}""",
            SyncFixture.text(d),
        )
    }

    @Test
    fun testSchemaVersions() {
        assertEquals(SyncDecodeResult.NewerSchema, decode("""{"schema":3,"whatever":[]}"""))
        assertEquals(SyncDecodeResult.NewerSchema, decode(wrap().replace(":2", ":12")))
        for (bad in listOf(
            wrap().replace(":2", ":1"),
            wrap().replace(":2", ":\"2\""),
            wrap().replace(":2", ":2.0"),
            wrap().replace(":2", ":02"),
            wrap().replace(":2", ":-2"),
        )) {
            assertTrue(decode(bad) is SyncDecodeResult.Invalid, bad)
        }
    }

    @Test
    fun testShapeViolationsAreInvalid() {
        val bad = listOf(
            """{"schema":2,"rooms":{},"veto":{}}""",
            """{"schema":2,"rooms":{},"veto":{},"settings":{},"x":{}}""",
            "[]",
            wrap(rooms = "[]"),
            wrap(rooms = """{"cb:alice":{}}"""),
            wrap(rooms = """{"cb:alice":{"x":"$h+"}}"""),
            wrap(rooms = """{"cb:alice":{"f":"${h}x"}}"""),
            wrap(rooms = """{"cb:alice":{"f":"$h"}}"""),
            wrap(rooms = """{"cb:alice":{"f":"${h.uppercase()}+"}}"""),
            wrap(rooms = """{"cb:alice":{"f":"000000000000000000000001+"}}"""),
            wrap(rooms = """{"cb:alice":{"f":"000000000001000000000000+"}}"""),
            wrap(rooms = """{"cb:alice":{"f":"ffff00000001000000000001+"}}"""),
            wrap(rooms = """{"cb:alice":{"n":"${h}Alicia"}}"""),
            wrap(rooms = """{"cb:alice":{"f":1}}"""),
            wrap(rooms = """{"alice":{"f":"$h+"}}"""),
            wrap(veto = """{"t:Asian":"$h+"}}"""),
            wrap(veto = """{"t:e\u0301":"$h+"}}"""),
            wrap(veto = """{"t:asian":"${h}1"}}"""),
            wrap(veto = """{"t:asian":2}"""),
            wrap(settings = """{"muted":"${h}1"}}"""),
            wrap(settings = """{"publicOnly":"$h+"}}"""),
            wrap(settings = """{"publicOnly":true}"""),
        )
        for (text in bad) assertTrue(decode(text) is SyncDecodeResult.Invalid, text)
    }

    @Test
    fun testCountsOverLimitAreInvalid() {
        val d = SyncDocument.empty()
        for (i in 0 until 301) d.rooms["cb:u%03d".format(i)] = SyncRoom(f = SyncFixture.reg((i + 1).toLong(), "+"))
        assertTrue(decode(SyncFixture.text(d)) is SyncDecodeResult.Invalid)
        val trimmed = SyncMerge.trim(d)
        assertEquals(SyncDecodeResult.Valid(trimmed), decode(SyncFixture.text(trimmed)))
    }

    @Test
    fun testOversizePlaintextIsInvalid() {
        val pad = " ".repeat(SyncLimits.plaintextBytes)
        assertTrue(decode(wrap() + pad) is SyncDecodeResult.Invalid)
    }
}
