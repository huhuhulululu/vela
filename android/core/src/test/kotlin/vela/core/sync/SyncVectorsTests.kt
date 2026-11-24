package vela.core.sync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import vela.core.curation.Curation

/**
 * 金样本（docs/plans/sync.md 第 17、18 节）。加密段在 JVM 上比对；
 * `sign` / `signInvalid` 只比对验签结果（ECDSA 签名非确定）。
 */
class SyncVectorsTests {
    @Test
    fun testGoldenVectors() {
        val path = vectorsPath()
        assertTrue(Files.exists(path), "找不到金样本 $path")
        val report = Vectors.run(path)
        val required = listOf(
            "json", "decimal", "decimalInvalid", "base64url", "base64urlInvalid", "code", "codeInvalid",
            "kdf", "pubkey", "pubcheck", "tagKey", "hlc", "merge", "size", "projection", "ops", "migration",
            "simulation", "payload", "payloadInvalid", "envelope", "envelopeInvalid", "sign", "signInvalid",
        )
        for (section in required) {
            val c = report.counts[section] ?: (0 to 0)
            println("金样本 $section：通过 ${c.first}，失败 ${c.second}")
            assertTrue(
                c.first > 0,
                "金样本段 $section 通过 ${c.first} 失败 ${c.second} " +
                    report.failures.filter { it.startsWith(section) }.take(2).joinToString(" | "),
            )
        }
        assertEquals(emptyList<String>(), report.failures.take(40), report.failures.take(8).joinToString("\n"))
    }

    @Test
    fun testLoaderAcceptsGoodAndCatchesBadSamples() {
        val dir = Files.createTempDirectory("sync-vectors")
        val good = """
            {"json":[{"text":"{\"rooms\":{},\"schema\":2,\"settings\":{},\"veto\":{}}","valid":true},{"text":"{\"schema\":3}","valid":false,"readonly":true}],
             "base64url":[{"text":"-_8","bytes_hex":"fbff"}],
             "tagKey":[{"raw":"Asian","key":"t:asian"},{"raw":"a b","local_only":true},{"cps":[55296],"local_only":true}],
             "merge":[{"a":{"schema":2,"rooms":{"cb:alice":{"f":"000000000005000000000001+"}},"veto":{},"settings":{}},"b":{"schema":2,"rooms":{"cb:alice":{"b":"000000000006000000000002+"}},"veto":{},"settings":{}},"expected":{"schema":2,"rooms":{"cb:alice":{"f":"000000000005000000000001+","b":"000000000006000000000002+"}},"veto":{},"settings":{}}}],
             "projection":[{"doc":{"schema":2,"rooms":{"cb:alice":{"f":"000000000005000000000001+","b":"000000000006000000000002+"}},"veto":{},"settings":{}},"expected":{"favorites":[],"blocked":[{"room":"cb:alice","name":"alice","local":true}],"vetoed":[],"settings":{}}}],
             "hlc":[{"state":{"l":5,"c":0,"dev":1},"events":[{"type":"local","pt":5,"expected":{"l":5,"c":1,"dev":1}}]}]}
        """.trimIndent()
        val goodURL = dir.resolve("good.json")
        Files.writeString(goodURL, good)
        val ok = Vectors.run(goodURL)
        assertEquals(emptyList<String>(), ok.failures)
        assertEquals(9, ok.counts.values.sumOf { it.first })
        val bad = good
            .replace("\"b\":\"000000000006000000000002+\"", "\"f\":\"000000000005000000000001+\"", )
            .replace("\"key\":\"t:asian\"", "\"key\":\"t:Asian\"")
            .replace("\"c\":1", "\"c\":0")
            .replace("\"hlc\":[", "\"ops\":[{\"unexpected\":true}],\"hlc\":[")
        // 上面的替换不稳定。直接写坏样本。
        val badText = """
            {"json":[{"text":"{\"rooms\":{},\"schema\":2,\"settings\":{},\"veto\":{}}","valid":true},{"text":"{\"schema\":3}","valid":false,"readonly":true}],
             "base64url":[{"text":"-_8","bytes_hex":"fbff"}],
             "tagKey":[{"raw":"Asian","key":"t:Asian"}],
             "merge":[{"a":{"schema":2,"rooms":{"cb:alice":{"f":"000000000005000000000001+"}},"veto":{},"settings":{}},"b":{"schema":2,"rooms":{"cb:alice":{"b":"000000000006000000000002+"}},"veto":{},"settings":{}},"expected":{"schema":2,"rooms":{"cb:alice":{"f":"000000000005000000000001+"}},"veto":{},"settings":{}}}],
             "projection":[{"doc":{"schema":2,"rooms":{"cb:alice":{"f":"000000000005000000000001+","b":"000000000006000000000002+"}},"veto":{},"settings":{}},"expected":{"favorites":[],"blocked":[{"room":"cb:alice","name":"alice","local":true}],"vetoed":[],"settings":{}}}],
             "hlc":[{"state":{"l":5,"c":0,"dev":1},"events":[{"type":"local","pt":5,"expected":{"l":5,"c":0,"dev":1}}]}],
             "ops":[{"unexpected":true}]}
        """.trimIndent()
        val badURL = dir.resolve("bad.json")
        Files.writeString(badURL, badText)
        assertEquals(4, Vectors.run(badURL).failures.size, "负样本必须失败")
    }
}

object Vectors {
    class Report {
        val counts = HashMap<String, Pair<Int, Int>>()
        val failures = ArrayList<String>()
    }

    fun run(path: Path): Report {
        val root = Json.parseToJsonElement(Files.readString(path)) as JsonObject
        val report = Report()
        fun check(section: String, body: (JsonObject) -> String?) {
            val items = root[section] as? JsonArray ?: return
            var pass = 0
            var fail = 0
            items.forEachIndexed { i, item ->
                val d = item as? JsonObject
                val name = d?.strOr("name", "")?.ifEmpty { null } ?: d?.strOr("reason", "")?.ifEmpty { null } ?: d?.strOr("note", "") ?: ""
                try {
                    val problem = if (d == null) "不是对象" else body(d)
                    if (problem != null) {
                        fail += 1
                        report.failures.add("$section[$i] $name：$problem")
                    } else {
                        pass += 1
                    }
                } catch (e: Exception) {
                    fail += 1
                    report.failures.add("$section[$i] $name 格式与加载器不符：${e.message}")
                }
            }
            report.counts[section] = pass to fail
        }

        check("json") { d ->
            val valid = d.bool("valid")
            val readonly = d.boolOr("readonly", false)
            when (val got = SyncDocument.decode(bytes(d))) {
                is SyncDecodeResult.Valid -> if (valid) null else "期望不合法，实际合法"
                SyncDecodeResult.NewerSchema -> if (!valid && readonly) null else "实际只读（schema 更大）"
                is SyncDecodeResult.Invalid -> if (!valid && !readonly) null else "期望${if (valid) "合法" else "只读"}，实际不合法（${got.reason}）"
            }
        }
        check("decimal") { d ->
            val want = d.long("value")
            val got = SyncEncoding.parseDecimal(d.str("text"))
            if (got == want) null else "期望 $want，实际 $got"
        }
        check("decimalInvalid") { d -> if (SyncEncoding.parseDecimal(d.str("text")) == null) null else "应拒绝" }
        check("base64url") { d ->
            val text = d.str("text")
            val want = SyncEncoding.decodeHex(d.str("bytes_hex")) ?: return@check "bytes_hex"
            if (SyncEncoding.decodeBase64url(text)?.contentEquals(want) == true && SyncEncoding.base64url(want) == text) null
            else "编解码不一致"
        }
        check("base64urlInvalid") { d -> if (SyncEncoding.decodeBase64url(d.str("text")) == null) null else "应拒绝" }
        check("code") { d ->
            val text = d.str("code")
            val parsed = SyncCode.parse(text, ::sha256) ?: return@check "解析失败"
            val key = SyncEncoding.decodeHex(d.str("K_hex"))
            val again = SyncCode.format(parsed.key, parsed.origin, ::sha256)
            if (parsed.key.contentEquals(key) && parsed.origin == d.str("origin") && again == text) null else "K、origin 或重新写出不一致"
        }
        check("codeInvalid") { d -> if (SyncCode.parse(d.str("code"), ::sha256) == null) null else "应拒绝" }
        check("kdf") { d ->
            val derived = deriveSyncKeys(SyncEncoding.decodeHex(d.str("K_hex"))!!)
            val problems = ArrayList<String>()
            if (derived.blobID != d.str("blobID")) problems.add("blobID")
            if (derived.fingerprint != d.str("fingerprint")) problems.add("fingerprint")
            if (!derived.encKey.contentEquals(SyncEncoding.decodeHex(d.str("encKey_hex")))) problems.add("enc")
            if (!derived.d.contentEquals(SyncEncoding.decodeHex(d.str("d_hex")))) problems.add("d")
            if (derived.signCounter != d.long("signCounter").toInt()) problems.add("counter")
            if (derived.pubB64 != d.str("pub_b64url")) problems.add("pub")
            val msg = pubcheckMessage(d.str("blobID"))
            if (!msg.contentEquals(SyncEncoding.decodeHex(d.str("pubcheck_message_hex")))) problems.add("pubcheck")
            if (problems.isEmpty()) null else problems.joinToString(",")
        }
        check("pubkey") { d ->
            val pub = p256PublicKey(SyncEncoding.decodeHex(d.str("d_hex"))!!)
            if (SyncEncoding.base64url(pub) == d.str("pub_b64url")) null else "公钥 ${SyncEncoding.base64url(pub)}"
        }
        check("pubcheck") { d ->
            val msg = SyncEncoding.decodeHex(d.str("message_hex"))!!
            if (!pubcheckMessage(d.str("blobID")).contentEquals(msg)) return@check "消息不一致"
            val ok = verifyEcdsa(SyncEncoding.decodeBase64url(d.str("pub_b64url"))!!, msg, SyncEncoding.decodeBase64url(d.str("sig_b64url"))!!)
            if (ok == d.bool("valid")) null else "验签 $ok"
        }
        check("payload") { d ->
            val json = d.str("json_utf8").toByteArray(Charsets.UTF_8)
            val body = SyncEncoding.decodeBase64url(d.str("body_b64url"))!!
            val flag = d.long("flag").toInt()
            val decoded = decodePayload(flag, body)
            if (decoded !is PayloadDecode.Ok || !decoded.bytes.contentEquals(json)) return@check "解出不一致"
            // Go `compress/flate` level 6 与 `java.util.zip.Deflater` 不是逐位相同。
            // 样本负责证明「能解开生成器的 raw DEFLATE」；本端封包只要求按同一规则回环。
            val (f, b) = encodePayload(json)
            val again = decodePayload(f, b)
            if (again !is PayloadDecode.Ok || !again.bytes.contentEquals(json)) return@check "回环失败"
            val oursSmaller = deflateRaw(json).size < json.size
            if ((f == 0x01) != oursSmaller) return@check "flag 选择不符合更小才压缩"
            null
        }
        check("payloadInvalid") { d ->
            val body = SyncEncoding.decodeBase64url(d.str("body_b64url"))!!
            val got = decodePayload(d.long("flag").toInt(), body)
            val reason = (got as? PayloadDecode.Bad)?.reason
            if (reason == d.str("reason")) null else "期望 ${d.str("reason")}，实际 $reason"
        }
        check("envelope") { d ->
            val key = SyncEncoding.decodeHex(d.str("encKey_hex"))!!
            val aad = aadFor(d.str("blobID"), d.long("ver"))
            if (!aad.contentEquals(SyncEncoding.decodeHex(d.str("aad_hex")))) return@check "aad"
            val payload = SyncEncoding.decodeHex(d.str("payload_hex"))!!
            val opened = openEnvelope(key, d.str("envelope"), aad) ?: return@check "解不开"
            if (!opened.contentEquals(payload)) return@check "明文不一致"
            val sealed = sealEnvelope(key, SyncEncoding.decodeHex(d.str("nonce_hex"))!!, payload, aad)
            if (sealed == d.str("envelope")) null else "封包不一致"
        }
        check("envelopeInvalid") { d ->
            val key = d.hexOr("encKey_hex") ?: ByteArray(32)
            val aad = d.hexOr("aad_hex") ?: ByteArray(0)
            if (openEnvelope(key, d.str("envelope"), aad) == null) null else "应拒绝 ${d.str("reason")}"
        }
        check("sign") { d -> verifyCase(d) }
        check("signInvalid") { d -> verifyCase(d) }
        check("tagKey") { d ->
            val localOnly = d.boolOr("local_only", false)
            val raw = if (d["raw"] is JsonPrimitive) {
                d.str("raw")
            } else {
                val cps = (d["cps"] as JsonArray).map { (it as JsonPrimitive).content.toInt() }
                if (cps.any { it > 0x10FFFF || it in 0xD800..0xDFFF }) return@check if (localOnly) null else "不可表示的码点应为本机专用"
                String(cps.map { Character.toChars(it) }.flatMap { it.asIterable() }.toCharArray())
            }
            val got = SyncKeys.syncTagKey(raw)
            if (localOnly) return@check if (got == null) null else "期望本机专用，实际 $got"
            val want = d.str("key")
            if (got?.utf8Equals(want) == true) null else "期望 $want，实际 ${got ?: "nil"}"
        }
        check("hlc") { d ->
            for (b in d.arr("bounds")) {
                val bd = b as JsonObject
                val valid = bd.bool("valid")
                val got = Hlc.parse(bd.str("wire"))?.isStaticallyValid ?: false
                if (got != valid) return@check "界限 ${bd.str("wire")} 期望 $valid"
            }
            val replica = SyncReplica(SyncDocument.empty(), clock(d.obj("state")))
            for (e in d.arr("events")) {
                val ev = e as JsonObject
                when (ev.str("type")) {
                    "local" -> replica.clock.tick(ev.long("pt"))
                    "receive" -> replica.clock.receive(docValue(ev["doc"]!!).maxHlc)
                    "presend" -> {
                        val n = ev.long("N")
                        replica.doc = docValue(ev["doc"]!!)
                        val result = SyncRestamp.presend(replica, n, ev.long("maxSentL"), n)
                        val blocked = result is SyncRestamp.Result.BlockedBySent
                        if (blocked != ev.bool("diagnostic")) return@check "诊断标记不一致：$result"
                        if (replica.doc != docValue(ev["expected_doc"]!!)) return@check "重新盖章后的文档不一致：${SyncFixture.text(replica.doc).take(200)}"
                    }
                    else -> error("未知事件")
                }
                val want = clock((ev["expected"] ?: ev["expected_state"]) as JsonObject)
                if (replica.clock != want) return@check "时钟期望 $want，实际 ${replica.clock}"
            }
            null
        }
        check("merge") { d ->
            val got = SyncMerge.fitSize(SyncMerge.union(docValue(d["a"]!!), docValue(d["b"]!!))).doc
            if (got == docValue(d["expected"]!!)) null else "合并结果 ${SyncFixture.text(got).take(200)}"
        }
        check("size") { d ->
            val p = d.long("p").toInt()
            val docEl = d["doc"]
            if (docEl == null || docEl is JsonNull) {
                val docs = (root["size"] as JsonArray).map { it as JsonObject }.filter { it["doc"] !is JsonNull && it["doc"] != null }
                val sizes = docs.map { docValue(it["doc"]!!).encodedSize }
                val with = d.long("with_tombstones").toInt()
                val live = d.long("live_only").toInt()
                if (sizes.contains(with) && sizes.contains(live) && p == SyncLimits.plaintextBytes) null else "汇总 $sizes"
            } else {
                val doc = docValue(docEl)
                val bytes = d.long("bytes").toInt()
                if (doc.encodedSize != bytes || doc.encoded().size != bytes) return@check "实测 ${doc.encodedSize}，期望 $bytes"
                val fitted = SyncMerge.fitSize(doc, p)
                val removed = doc.rooms.size + doc.veto.size - fitted.doc.rooms.size - fitted.doc.veto.size
                if (removed != d.long("tombstones_removed").toInt()) return@check "删除墓碑数 $removed"
                if (d.str("result") == "reject") return@check if (!fitted.fits) null else "应拒绝"
                if (fitted.fits && fitted.doc == docValue(d["expected"]!!)) null else "体积处理结果不一致"
            }
        }
        check("projection") { d ->
            val p = SyncProjection(docValue(d["doc"]!!))
            val e = d.obj("expected")
            fun entries(name: String, got: List<SyncProjection.Entry>): String? {
                val want = e.arr(name).map { it as JsonObject }
                val ok = want.map { it.str("room") } == got.map { it.key } &&
                    want.map { it.str("name") } == got.map { it.display } &&
                    want.map { it.bool("local") } == got.map { SyncKeys.isProjected(it.key) }
                return if (ok) null else "$name 不一致：$got"
            }
            entries("favorites", p.favorites)?.let { return@check it }
            entries("blocked", p.blocked)?.let { return@check it }
            val vetoed = e.arr("vetoed").map { (it as JsonPrimitive).content }.toSet()
            if (vetoed != p.veto.filterValues { it }.keys) return@check "vetoed 不一致"
            val settings = e.obj("settings").mapValues { (it.value as JsonPrimitive).content == "1" }
            if (settings == p.settings) null else "settings 不一致"
        }
        check("ops") { d ->
            val replica = SyncReplica(docValue(d["doc"]!!), clock(d.obj("hlc")))
            val pt = d.long("pt")
            d.arr("ops").forEachIndexed { i, o ->
                val od = o as JsonObject
                val wrote = apply(op(od), replica, pt)
                if (wrote != od.bool("wrote")) return@check "第 $i 步 ${od.str("fn")} 写入标记不一致"
                if (replica.doc != docValue(od["doc"]!!)) return@check "第 $i 步 ${od.str("fn")} 文档不一致：${SyncFixture.text(replica.doc).take(300)}"
                if (replica.clock != clock(od.obj("hlc"))) return@check "第 $i 步时钟不一致"
            }
            if (replica.clock != clock(d.obj("expected_hlc"))) return@check "最终时钟不一致"
            if (replica.doc == docValue(d["expected"]!!)) null else "最终文档不一致"
        }
        check("migration") { d ->
            val settings = (d["settings"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content == "1" } ?: emptyMap()
            val favorites = d.strList("favorites")
            val blocked = d.strList("blocked")
            val local = SyncLocalData(
                Curation(favorites, blocked),
                d.strList("vetoed"),
                settings,
            )
            val c = SyncClock(0, 0, d.long("dev"))
            val pt = d.long("pt")
            val counts = d.obj("counts")
            val remoteEl = d["remote"]
            val gotDoc: SyncDocument
            val preview: SyncJoinPreview
            if (remoteEl != null && remoteEl !is JsonNull) {
                val r = SyncMigration.stampJoin(local, docValue(remoteEl), SyncClock(c.l, c.c, c.dev), pt)
                gotDoc = r.replica.doc
                preview = r.preview
            } else {
                val r = SyncMigration.stampJoin(local, SyncDocument.empty(), SyncClock(c.l, c.c, c.dev), pt)
                val created = SyncMigration.stampCreator(local, SyncClock(c.l, c.c, c.dev), pt)
                if (created.first.doc != r.replica.doc) return@check "创建方盖章与空远端加入不一致"
                gotDoc = created.first.doc
                preview = r.preview
            }
            val have = mapOf("N" to preview.n, "M" to preview.m, "V" to preview.v, "X" to preview.x, "Y" to preview.y, "Z" to preview.z)
            for ((k, v) in have) if (counts.long(k).toInt() != v) return@check "计数 $k 期望 ${counts.long(k)}，实际 $v"
            if (gotDoc == docValue(d["expected"]!!)) null else "盖章结果不一致：${SyncFixture.text(gotDoc).take(300)}"
        }
        check("simulation") { d ->
            val n = d.long("replicas").toInt()
            val reps = (0 until n).map { SyncReplica(SyncDocument.empty(), SyncClock(0, 0, (it + 1).toLong())) }.toMutableList()
            val sent = LongArray(n)
            fun maxL(doc: SyncDocument) = doc.allRegisters.maxOfOrNull { it.hlc.l } ?: 0L
            d.arr("steps").forEachIndexed { k, s ->
                val st = s as JsonObject
                when (st.str("kind")) {
                    "op" -> {
                        val i = st.long("replica").toInt()
                        apply(op(st), reps[i], st.long("pt"))
                    }
                    "merge" -> {
                        val i = st.long("i").toInt()
                        val j = st.long("j").toInt()
                        val write = st.str("write")
                        val now = st.long("now")
                        val leaving = when (write) {
                            "both" -> listOf(i, j)
                            "i" -> listOf(j)
                            else -> listOf(i)
                        }
                        for (sIdx in leaving) {
                            SyncRestamp.presend(reps[sIdx], now, sent[sIdx], now)
                            sent[sIdx] = maxOf(sent[sIdx], maxL(reps[sIdx].doc))
                        }
                        val di = reps[i].doc
                        val dj = reps[j].doc
                        val merged = SyncMerge.fitSize(SyncMerge.union(di, dj)).doc
                        if (write != "j") {
                            reps[i].clock.receive(dj.maxHlc)
                            reps[i].doc = merged
                        }
                        if (write != "i") {
                            reps[j].clock.receive(di.maxHlc)
                            reps[j].doc = merged
                        }
                    }
                    else -> error("未知步骤")
                }
                val docs = st.arr("docs").map { docValue(it) }
                for ((r, want) in docs.withIndex()) {
                    if (reps[r].doc != want) return@check "第 $k 步后副本 $r 不一致：${SyncFixture.text(reps[r].doc).take(300)}"
                }
            }
            val final = docValue(d["final"]!!)
            if (reps.all { it.doc == final }) null else "最终文档不一致"
        }
        return report
    }

    private fun verifyCase(d: JsonObject): String? {
        val msg = signMessage(
            d.str("op"), d.str("blobID"), d.str("epoch"), d.long("base"), d.long("next"), d.str("envelope_sha256_hex"),
        )
        // 只要求验签结果与样本一致；消息字节仅在样本给出且验签为真时顺带核对。
        val pub = SyncEncoding.decodeBase64url(d.str("pub_b64url")) ?: return if (d.bool("valid")) "公钥" else null
        val sig = SyncEncoding.decodeBase64url(d.str("sig_b64url")) ?: return if (d.bool("valid")) "签名" else null
        val ok = verifyEcdsa(pub, msg, sig)
        if (ok && !msg.contentEquals(SyncEncoding.decodeHex(d.str("message_hex")))) return "消息不一致"
        return if (ok == d.bool("valid")) null else "验签 $ok 期望 ${d.bool("valid")}"
    }

    private fun apply(op: SyncLocalOp?, replica: SyncReplica, pt: Long): Boolean {
        val outcome = if (op == null) SyncOpOutcome.Unchanged else SyncOps.apply(op, replica, pt)
        val applied = outcome as? SyncOpOutcome.Applied ?: return false
        replica.doc = applied.replica.doc
        replica.clock = applied.replica.clock
        return true
    }

    private fun op(d: JsonObject): SyncLocalOp? {
        val name = d.strOr("name", "")
        val key = d.strOr("key", "")
        return when (val fn = d.str("fn")) {
            "Curation.favoriting" -> SyncLocalOp.Favorite(name)
            "Curation.restoringFavorite" -> SyncLocalOp.RestoreFavorite(name)
            "Curation.unfavoriting" -> SyncLocalOp.Unfavorite(name)
            "Curation.blocking" -> SyncLocalOp.Block(name)
            "Curation.unblocking" -> SyncLocalOp.Unblock(name)
            "Curation.importing" -> SyncLocalOp.Importing((d["names"] as JsonArray).map { (it as JsonPrimitive).content })
            "Taste.vetoing" -> SyncLocalOp.Veto(key)
            "Taste.restoring", "Taste.boosting", "Taste.removing" -> SyncLocalOp.Unveto(key)
            "Taste.resettingProfile", "Taste.erased" -> SyncLocalOp.ClearVetoes
            "settings" -> SyncLocalOp.Setting(key, d.str("value") == "1")
            "marking", "rejecting", "following", "recording", "settingPaused" -> null
            else -> error("未知操作 $fn")
        }
    }

    private fun bytes(d: JsonObject): ByteArray {
        val text = d["text"]
        if (text is JsonPrimitive && text.isString) return text.content.toByteArray(Charsets.UTF_8)
        val hex = d["bytes_hex"]
        if (hex is JsonPrimitive) return SyncEncoding.decodeHex(hex.content) ?: error("bytes_hex")
        error("缺字段 text / bytes_hex")
    }

    private fun docValue(v: JsonElement): SyncDocument {
        val raw = when (v) {
            is JsonPrimitive -> v.content.toByteArray(Charsets.UTF_8)
            is JsonObject -> Json.encodeToString(JsonElement.serializer(), v).toByteArray(Charsets.UTF_8)
            else -> error("缺文档")
        }
        return when (val decoded = SyncDocument.decode(raw)) {
            is SyncDecodeResult.Valid -> decoded.doc
            SyncDecodeResult.NewerSchema -> error("文档 schema 更大")
            is SyncDecodeResult.Invalid -> error("文档不合法（${decoded.reason}）")
        }
    }

    private fun clock(d: JsonObject) = SyncClock(d.long("l"), d.long("c").toInt(), d.long("dev"))

    private fun JsonObject.hexOr(k: String): ByteArray? {
        val v = get(k) as? JsonPrimitive ?: return null
        return SyncEncoding.decodeHex(v.content)
    }

    private fun JsonObject.str(k: String) = (get(k) as JsonPrimitive).content
    private fun JsonObject.strOr(k: String, default: String): String {
        val v = get(k) ?: return default
        if (v is JsonNull) return default
        return (v as? JsonPrimitive)?.contentOrNull ?: default
    }
    private fun JsonObject.bool(k: String) = (get(k) as JsonPrimitive).booleanOrNull ?: (get(k) as JsonPrimitive).content.toBoolean()
    private fun JsonObject.boolOr(k: String, default: Boolean): Boolean {
        val v = get(k) ?: return default
        if (v is JsonNull) return default
        return (v as JsonPrimitive).booleanOrNull ?: default
    }
    private fun JsonObject.long(k: String): Long {
        val p = get(k) as JsonPrimitive
        return p.longOrNull ?: p.content.toLong()
    }
    private fun JsonObject.obj(k: String) = get(k) as JsonObject
    private fun JsonObject.arr(k: String): List<JsonElement> = (get(k) as? JsonArray)?.toList() ?: emptyList()
    private fun JsonObject.strList(k: String): List<String> {
        val v = get(k) ?: return emptyList()
        if (v is JsonNull) return emptyList()
        return (v as JsonArray).map { (it as JsonPrimitive).content }
    }
}

fun vectorsPath(): Path {
    val prop = System.getProperty("vela.sync.vectors")
    if (!prop.isNullOrEmpty()) return Path.of(prop)
    var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    repeat(6) {
        val candidate = dir.resolve("Tests/Fixtures/sync/vectors.json")
        if (Files.exists(candidate)) return candidate
        dir = dir.parent ?: return candidate
    }
    return Path.of("Tests/Fixtures/sync/vectors.json")
}
