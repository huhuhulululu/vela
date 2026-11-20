package vela.core.sync

/** 寄存器 = 24 位 HLC + 载荷。比较按 UTF-8 字节，HLC 在前。 */
data class SyncRegister(val raw: String, val hlc: Hlc) : Comparable<SyncRegister> {
    val payload: String
        get() {
            val b = raw.toByteArray(Charsets.UTF_8)
            return String(b, 24, b.size - 24, Charsets.UTF_8)
        }

    /** 载荷为 `+`。线格式固定为 24 hex + 1 个 ASCII 符号时长度为 25。 */
    val isOn: Boolean
        get() {
            val b = raw.toByteArray(Charsets.UTF_8)
            return b.size == 25 && b[24] == '+'.code.toByte()
        }

    override fun compareTo(other: SyncRegister): Int = raw.utf8Compare(other.raw)

    companion object {
        fun of(hlc: Hlc, payload: String) = SyncRegister(hlc.hex + payload, hlc)

        /** HLC 不合静态界限时返回 null。载荷合法性由调用方检查。 */
        fun parse(raw: String): SyncRegister? {
            val b = raw.toByteArray(Charsets.UTF_8)
            if (b.size <= 24) return null
            val hlc = Hlc.parse(String(b, 0, 24, Charsets.US_ASCII)) ?: return null
            if (!hlc.isStaticallyValid) return null
            return SyncRegister(raw, hlc)
        }

        fun larger(a: SyncRegister?, b: SyncRegister?): SyncRegister? {
            if (a == null) return b
            if (b == null) return a
            return if (a < b) b else a
        }
    }
}

data class SyncRoom(
    var f: SyncRegister? = null,
    var b: SyncRegister? = null,
    var n: SyncRegister? = null,
) {
    val isFavorite: Boolean
        get() {
            val fav = f ?: return false
            if (!fav.isOn) return false
            val blk = b
            if (blk == null || !blk.isOn) return true
            return fav.hlc > blk.hlc
        }

    val isBlocked: Boolean
        get() {
            val blk = b ?: return false
            if (!blk.isOn) return false
            val fav = f
            if (fav == null || !fav.isOn) return true
            return blk.hlc >= fav.hlc
        }

    val newestHlc: Hlc
        get() = listOfNotNull(f?.hlc, b?.hlc, n?.hlc).maxOrNull() ?: Hlc.ZERO

    val registers: List<SyncRegister> get() = listOfNotNull(f, b, n)
}

data class SyncDocument(
    val rooms: MutableMap<String, SyncRoom> = linkedMapOf(),
    val veto: MutableMap<String, SyncRegister> = linkedMapOf(),
    val settings: MutableMap<String, SyncRegister> = linkedMapOf(),
) {
    val allRegisters: List<SyncRegister>
        get() = rooms.values.flatMap { it.registers } + veto.values + settings.values

    val maxHlc: Hlc? get() = allRegisters.maxOfOrNull { it.hlc }

    fun encoded(): ByteArray {
        val out = ArrayList<Byte>(encodedSize.coerceAtLeast(64))
        fun add(s: String) {
            for (c in s.toByteArray(Charsets.UTF_8)) out.add(c)
        }
        add("{\"rooms\":{")
        sortedKeys(rooms.keys).forEachIndexed { i, key ->
            if (i > 0) out.add(','.code.toByte())
            appendRoom(key, rooms.getValue(key), out)
        }
        add("},\"schema\":2,\"settings\":")
        appendRegisters(settings, out)
        add(",\"veto\":")
        appendRegisters(veto, out)
        out.add('}'.code.toByte())
        return out.toByteArray()
    }

    val encodedSize: Int
        get() {
            fun separators(n: Int) = maxOf(0, n - 1)
            var total = EMPTY_BYTES + separators(rooms.size) + separators(veto.size) + separators(settings.size)
            for ((key, room) in rooms) total += roomBytes(key, room)
            for ((key, reg) in veto) total += pairBytes(key, reg)
            for ((key, reg) in settings) total += pairBytes(key, reg)
            return total
        }

    companion object {
        fun empty(): SyncDocument = SyncDocument()
        private val EMPTY_BYTES = emptyEncodedSize()

        private fun emptyEncodedSize(): Int = "{\"rooms\":{},\"schema\":2,\"settings\":{},\"veto\":{}}".toByteArray(Charsets.UTF_8).size

        fun sortedKeys(keys: Collection<String>): List<String> = keys.sortedWith { a, b -> a.utf8Compare(b) }

        fun pairBytes(key: String, reg: SyncRegister): Int = key.toByteArray(Charsets.UTF_8).size + reg.raw.toByteArray(Charsets.UTF_8).size + 5

        fun roomBytes(key: String, room: SyncRoom): Int {
            var fields = 0
            var total = key.toByteArray(Charsets.UTF_8).size + 5
            for (reg in listOf(room.b, room.f, room.n)) {
                if (reg == null) continue
                total += reg.raw.toByteArray(Charsets.UTF_8).size + 6
                fields++
            }
            return total + maxOf(0, fields - 1)
        }

        private fun appendRoom(key: String, room: SyncRoom, out: MutableList<Byte>) {
            writeString(key, out)
            out.add(':'.code.toByte())
            out.add('{'.code.toByte())
            var first = true
            for ((name, reg) in listOf("b" to room.b, "f" to room.f, "n" to room.n)) {
                if (reg == null) continue
                if (!first) out.add(','.code.toByte())
                first = false
                writeString(name, out)
                out.add(':'.code.toByte())
                writeString(reg.raw, out)
            }
            out.add('}'.code.toByte())
        }

        private fun appendRegisters(map: Map<String, SyncRegister>, out: MutableList<Byte>) {
            out.add('{'.code.toByte())
            sortedKeys(map.keys).forEachIndexed { i, key ->
                if (i > 0) out.add(','.code.toByte())
                writeString(key, out)
                out.add(':'.code.toByte())
                writeString(map.getValue(key).raw, out)
            }
            out.add('}'.code.toByte())
        }

        private fun writeString(s: String, out: MutableList<Byte>) {
            out.add('"'.code.toByte())
            for (c in s.toByteArray(Charsets.UTF_8)) out.add(c)
            out.add('"'.code.toByte())
        }
    }
}

sealed class SyncDecodeResult {
    data class Valid(val doc: SyncDocument) : SyncDecodeResult()
    data object NewerSchema : SyncDecodeResult()
    data class Invalid(val reason: String) : SyncDecodeResult()
}

fun SyncDocument.Companion.decode(bytes: ByteArray): SyncDecodeResult {
    val value = try {
        SyncJson.parse(bytes)
    } catch (e: SyncJson.ParseException) {
        return SyncDecodeResult.Invalid("json:${e.reason}")
    }
    val top = value as? SyncJson.Value.Obj ?: return SyncDecodeResult.Invalid("top-not-object")
    val schemaMember = top.members.firstOrNull { it.key == "schema" } ?: return SyncDecodeResult.Invalid("missing-schema")
    val schemaText = (schemaMember.value as? SyncJson.Value.Num)?.text ?: return SyncDecodeResult.Invalid("schema-not-integer")
    val schema = SyncEncoding.parseDecimal(schemaText) ?: return SyncDecodeResult.Invalid("schema-not-integer")
    if (schema > SyncLimits.schema) return SyncDecodeResult.NewerSchema
    if (schema != SyncLimits.schema.toLong()) return SyncDecodeResult.Invalid("schema-old")
    val keys = top.members.map { it.key }.toSet()
    if (top.members.size != 4 || keys != setOf("schema", "rooms", "veto", "settings")) {
        return SyncDecodeResult.Invalid("top-keys")
    }
    val doc = SyncDocument()
    for (member in top.members) {
        when (member.key) {
            "rooms" -> {
                val rooms = member.value as? SyncJson.Value.Obj ?: return SyncDecodeResult.Invalid("rooms-not-object")
                for (entry in rooms.members) {
                    if (!SyncKeys.isRoomKey(entry.key)) return SyncDecodeResult.Invalid("room-key")
                    val room = decodeRoom(entry.key, entry.value) ?: return SyncDecodeResult.Invalid("room:${entry.key}")
                    doc.rooms[entry.key] = room
                }
            }
            "veto" -> {
                val veto = member.value as? SyncJson.Value.Obj ?: return SyncDecodeResult.Invalid("veto-not-object")
                for (entry in veto.members) {
                    if (!SyncKeys.isVetoKey(entry.key)) return SyncDecodeResult.Invalid("veto-key")
                    val reg = register(entry.value)
                    if (reg == null || (reg.payload != "+" && reg.payload != "-")) return SyncDecodeResult.Invalid("veto-register")
                    doc.veto[entry.key] = reg
                }
            }
            "settings" -> {
                val settings = member.value as? SyncJson.Value.Obj ?: return SyncDecodeResult.Invalid("settings-not-object")
                for (entry in settings.members) {
                    if (entry.key !in SyncKeys.settingKeys) return SyncDecodeResult.Invalid("settings-key")
                    val reg = register(entry.value)
                    if (reg == null || (reg.payload != "1" && reg.payload != "0")) return SyncDecodeResult.Invalid("settings-register")
                    doc.settings[entry.key] = reg
                }
            }
        }
    }
    if (!SyncMerge.withinCounts(doc)) return SyncDecodeResult.Invalid("over-limit")
    return SyncDecodeResult.Valid(doc)
}

private fun register(value: SyncJson.Value): SyncRegister? {
    val text = (value as? SyncJson.Value.Str)?.text ?: return null
    return SyncRegister.parse(text)
}

private fun decodeRoom(key: String, value: SyncJson.Value): SyncRoom? {
    val fields = value as? SyncJson.Value.Obj ?: return null
    if (fields.members.isEmpty()) return null
    val room = SyncRoom()
    for (field in fields.members) {
        val reg = register(field.value) ?: return null
        when (field.key) {
            "f" -> {
                if (reg.payload != "+" && reg.payload != "-") return null
                room.f = reg
            }
            "b" -> {
                if (reg.payload != "+" && reg.payload != "-") return null
                room.b = reg
            }
            "n" -> {
                if (!SyncKeys.isDisplayName(reg.payload, key)) return null
                room.n = reg
            }
            else -> return null
        }
    }
    return room
}
