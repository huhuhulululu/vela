package vela.core.base.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 正文形态判断：只跳过开头完整的 UTF-8 BOM，单个 BOM 字节不当空白。 */
class BOMShapeTests {
    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun testFullBOMThenJSONIsJSON() {
        assertTrue(ResponseShape.looksLikeJSON(bytes(0xEF, 0xBB, 0xBF) + """{"a":1}""".encodeToByteArray()))
        assertTrue(
            ResponseShape.looksLikeJSON(bytes(0xEF, 0xBB, 0xBF, 0x20, 0x0A) + "[1]".encodeToByteArray()),
        )
        assertTrue(
            ResponseShape.looksLikeJSON(bytes(0x20) + bytes(0xEF, 0xBB, 0xBF) + "{}".encodeToByteArray()),
            "BOM 前有空白也容忍",
        )
    }

    @Test
    fun testPartialOrStrayBOMBytesAreNotWhitespace() {
        assertFalse(ResponseShape.looksLikeJSON(bytes(0xEF) + "{}".encodeToByteArray()))
        assertFalse(ResponseShape.looksLikeJSON(bytes(0xBB) + "{}".encodeToByteArray()))
        assertFalse(ResponseShape.looksLikeJSON(bytes(0xBF) + "{}".encodeToByteArray()))
        assertFalse(ResponseShape.looksLikeJSON(bytes(0xEF, 0xBB) + "{}".encodeToByteArray()))
        assertFalse(ResponseShape.looksLikeJSON(bytes(0xBB, 0xBF, 0xEF) + "{}".encodeToByteArray()))
    }

    @Test
    fun testPlainCases() {
        assertTrue(ResponseShape.looksLikeJSON("  \n{}".encodeToByteArray()))
        assertFalse(ResponseShape.looksLikeJSON(ByteArray(0)))
        assertFalse(ResponseShape.looksLikeJSON("<html>".encodeToByteArray()))
    }
}
