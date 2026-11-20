package vela.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncPrimitivesTests {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun parse(s: String, limit: Int = SyncLimits.plaintextBytes) = SyncJson.parse(bytes(s), limit)

    @Test
    fun testParsesCompactAndWhitespace() {
        val v = parse(" {\"a\" : {\"b\":\"x\"},\n\"n\":2}\t") as SyncJson.Value.Obj
        assertEquals(listOf("a", "n"), v.members.map { it.key })
        assertEquals("2", (v.members[1].value as SyncJson.Value.Num).text)
    }

    @Test
    fun testRejectsDuplicateKeysByBytes() {
        val dup = assertFailsWith<SyncJson.ParseException> { parse("""{"a":"1","a":"2"}""") }
        assertEquals("duplicateKey", dup.reason)
        parse("{\"\u00E9\":\"1\",\"e\u0301\":\"2\"}")
    }

    @Test
    fun testRejectsBOMEscapesControlAndBadUTF8() {
        val cases = listOf(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes("{}") to "bom",
            bytes("""{"a":"\u0041"}""") to "escape",
            bytes("""{"a":"\n"}""") to "escape",
            bytes("""{"a":"\/"}""") to "escape",
            bytes("{\"a\":\"x\ty\"}") to "controlCharacter",
            bytes("{\"a\":\"") + byteArrayOf(0xC0.toByte(), 0x80.toByte()) + bytes("\"}") to "invalidUTF8",
            bytes("{\"a\":\"") + byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) + bytes("\"}") to "invalidUTF8",
            bytes("{\"a\":\"") + byteArrayOf(0x80.toByte()) + bytes("\"}") to "invalidUTF8",
            bytes("{\"a\":\"") + byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()) + bytes("\"}") to "invalidUTF8",
        )
        for ((input, error) in cases) {
            val got = assertFailsWith<SyncJson.ParseException> { SyncJson.parse(input) }
            assertEquals(error, got.reason)
        }
    }

    @Test
    fun testRejectsSyntaxDepthAndSize() {
        for (text in listOf("", "{", "{\"a\":}", "{\"a\":1,}", "{\"a\" \"b\"}", "{} {}", "{\"a\":01}", "{\"a\":1.}", "{'a':1}", "[1,]", "tru")) {
            assertFailsWith<SyncJson.ParseException>(text) { parse(text) }
        }
        val deep = assertFailsWith<SyncJson.ParseException> { parse("""{"a":{"b":{"c":{}}}}""") }
        assertEquals("tooDeep", deep.reason)
        parse("""{"a":{"b":{"c":"d"}}}""")
        val big = assertFailsWith<SyncJson.ParseException> { parse("""{"a":"xx"}""", limit = 8) }
        assertEquals("tooLarge", big.reason)
    }

    @Test
    fun testParsesLiteralsAndNumbers() {
        val v = parse("""{"a":[true,false,null,-1.5e+3,0]}""") as SyncJson.Value.Obj
        val items = (v.members[0].value as SyncJson.Value.Arr).items
        assertTrue(items[0] is SyncJson.Value.Bool && (items[0] as SyncJson.Value.Bool).value)
        assertTrue(items[1] is SyncJson.Value.Bool && !(items[1] as SyncJson.Value.Bool).value)
        assertTrue(items[2] is SyncJson.Value.Null)
        assertEquals("-1.5e+3", (items[3] as SyncJson.Value.Num).text)
        assertEquals("0", (items[4] as SyncJson.Value.Num).text)
    }

    @Test
    fun testBase64URLStrict() {
        assertEquals("-_8", SyncEncoding.base64url(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
        assertTrue(SyncEncoding.decodeBase64url("-_8")!!.contentEquals(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
        assertTrue(SyncEncoding.decodeBase64url("")!!.isEmpty())
        assertTrue(SyncEncoding.decodeBase64url("AAAA")!!.contentEquals(byteArrayOf(0, 0, 0)))
        for (bad in listOf("-_8=", "-_ 8", "+/8", "-_9", "A", "AB==")) assertNull(SyncEncoding.decodeBase64url(bad), bad)
        val all = ByteArray(256) { it.toByte() }
        assertTrue(SyncEncoding.decodeBase64url(SyncEncoding.base64url(all))!!.contentEquals(all))
    }

    @Test
    fun testHexAndDecimal() {
        assertEquals("0aff", SyncEncoding.hex(byteArrayOf(0x0a, 0xff.toByte())))
        assertTrue(SyncEncoding.decodeHex("0aff")!!.contentEquals(byteArrayOf(0x0a, 0xff.toByte())))
        assertNull(SyncEncoding.decodeHex("0AFF"))
        assertNull(SyncEncoding.decodeHex("abc"))
        assertEquals(0L, SyncEncoding.parseDecimal("0"))
        assertEquals(9_007_199_254_740_991L, SyncEncoding.parseDecimal("9007199254740991"))
        for (bad in listOf("", "00", "01", "+1", "-1", "1.0", "1e3", "9007199254740992", " 1", "99999999999999999999")) {
            assertNull(SyncEncoding.parseDecimal(bad), bad)
        }
    }

    @Test
    fun testHLCWireFormatOrdersLikeTuple() {
        val a = Hlc(0x0193b2c4d5e6, 0, 0xa1b2c3d4)
        assertEquals("0193b2c4d5e60000a1b2c3d4", a.hex)
        assertEquals(a, Hlc.parse(a.hex))
        assertNull(Hlc.parse("0193B2C4D5E60000A1B2C3D4"))
        assertNull(Hlc.parse("0193b2c4d5e60000a1b2c3d"))
        val samples = listOf(Hlc(1, 0, 9), Hlc(1, 1, 1), Hlc(2, 0, 1), Hlc(2, 0, 2))
        for (x in samples) for (y in samples) {
            assertEquals(x.hex.utf8Precedes(y.hex), x < y)
        }
    }

    @Test
    fun testHLCStaticBounds() {
        assertTrue(Hlc(1, 0, 1).isStaticallyValid)
        assertTrue(Hlc(0xFFFF00000000, 0xFFFF, 1).isStaticallyValid)
        assertFalse(Hlc(0, 5, 1).isStaticallyValid)
        assertFalse(Hlc(0xFFFF00000001, 0, 1).isStaticallyValid)
        assertFalse(Hlc(5, 0, 0).isStaticallyValid)
    }

    @Test
    fun testTickAdvancesWithWallClock() {
        val clock = SyncClock(0, 0, 7)
        assertEquals(Hlc(100, 0, 7), clock.tick(100))
        assertEquals(Hlc(100, 1, 7), clock.tick(100))
        assertEquals(Hlc(100, 2, 7), clock.tick(50))
        assertEquals(Hlc(101, 0, 7), clock.tick(101))
    }

    @Test
    fun testTickCounterOverflowCarriesIntoL() {
        val clock = SyncClock(100, 0xFFFF, 7)
        assertEquals(Hlc(101, 0, 7), clock.tick(100))
    }

    @Test
    fun testReceiveTakesLargerWithoutTicking() {
        val clock = SyncClock(100, 3, 7)
        clock.receive(Hlc(100, 2, 9))
        assertEquals(SyncClock(100, 3, 7), clock)
        clock.receive(Hlc(200, 4, 9))
        assertEquals(SyncClock(200, 4, 7), clock)
        assertEquals(Hlc(200, 5, 7), clock.tick(150))
    }
}
