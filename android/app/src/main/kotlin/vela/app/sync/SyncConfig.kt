package vela.app.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import vela.core.sync.SyncCode
import vela.core.sync.SyncEncoding
import vela.core.sync.p256PublicKey
import vela.core.sync.pubcheckMessage
import vela.core.sync.signEcdsa
import vela.core.sync.verifyEcdsa

/** S0 探针能力分级（docs/plans/sync.md 第 18 节写死表格）。 */
enum class SyncCapability {
    /** 五项全部 PASS：创建、加入、全部流程可用。 */
    FULL,

    /** 仅公钥派生 FAIL、其余四项全部 PASS：隐藏创建与 404「仍用此码创建」，保留加入/推送/清除。 */
    JOIN_ONLY,

    /** 私钥导入、签名、互验、核对消息任一 FAIL，或低于 minSdk 26：不显示同步入口。 */
    DISABLED,
}

data class SyncProbeReport(
    val keyImport: Boolean,
    val signAndConvert: Boolean,
    val publicKeyMatch: Boolean,
    val crossVerify: Boolean,
    val pubcheckVerify: Boolean,
) {
    val capability: SyncCapability = SyncProbe.classify(
        keyImport = keyImport,
        signAndConvert = signAndConvert,
        publicKeyMatch = publicKeyMatch,
        crossVerify = crossVerify,
        pubcheckVerify = pubcheckVerify,
    )
}

/** minSdk 26 平台门槛：低于 26 不加载同步模块。 */
object SyncPlatformGate {
    const val MIN_SDK: Int = 26

    fun isSdkSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= MIN_SDK

    inline fun <T> loadIfSupported(
        sdkInt: Int = Build.VERSION.SDK_INT,
        factory: () -> T,
    ): T? {
        if (!isSdkSupported(sdkInt)) return null
        return factory()
    }
}

/**
 * 构建期服务端地址配置（docs/plans/sync.md 第 1 节、验收 7）。
 * 仓内不写死真实主机名：依次读取注入覆盖、BuildConfig.VELA_SYNC_BASE（若已由构建脚本注入）、
 * 清单 meta-data `vela.sync.base`。均未配置或不合规范 origin 时返回 null。
 */
object SyncConfig {
    const val BUILD_CONFIG_FIELD: String = "VELA_SYNC_BASE"
    const val MANIFEST_META_KEY: String = "vela.sync.base"

    fun parseConfiguredOrigin(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (SyncCode.isCanonicalOrigin(trimmed)) trimmed else null
    }

    fun configuredOrigin(
        rawOverride: String? = null,
        context: Context? = null,
    ): String? {
        if (rawOverride != null) return parseConfiguredOrigin(rawOverride)
        parseConfiguredOrigin(readBuildConfigSyncBase())?.let { return it }
        if (context != null) {
            parseConfiguredOrigin(readManifestMeta(context))?.let { return it }
        }
        return null
    }

    private fun readBuildConfigSyncBase(): String? = try {
        val cls = Class.forName("vela.app.BuildConfig")
        val field = cls.getDeclaredField(BUILD_CONFIG_FIELD)
        field.get(null) as? String
    } catch (_: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    private fun readManifestMeta(context: Context): String? = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }
        info.metaData?.getString(MANIFEST_META_KEY)
    } catch (_: Throwable) {
        null
    }
}

/** 同步入口与创建入口可见性裁决（验收 2、7 与 sync.md 第 18 节）。 */
object SyncEntryPolicy {
    fun showEntry(
        sdkInt: Int = Build.VERSION.SDK_INT,
        capability: SyncCapability,
        configuredOrigin: String?,
    ): Boolean {
        if (!SyncPlatformGate.isSdkSupported(sdkInt)) return false
        if (configuredOrigin == null) return false
        return capability != SyncCapability.DISABLED
    }

    fun canCreate(
        sdkInt: Int = Build.VERSION.SDK_INT,
        capability: SyncCapability,
        configuredOrigin: String?,
    ): Boolean = showEntry(sdkInt, capability, configuredOrigin) && capability == SyncCapability.FULL

    fun canJoin(
        sdkInt: Int = Build.VERSION.SDK_INT,
        capability: SyncCapability,
        configuredOrigin: String?,
    ): Boolean = showEntry(sdkInt, capability, configuredOrigin)
}

/**
 * 运行期 P-256 探针（对齐 scripts/sync/probe-android/Probe.java 与 docs/plans/sync.md 第 18 节）。
 * 内嵌金样本 Tests/Fixtures/sync/vectors.json 的单组最小向量，不把整份 5 MB 夹具打进 APK。
 */
object SyncProbe {
    // 来自 Tests/Fixtures/sync/vectors.json kdf[0] / pubkey[0] / sign[0] / signInvalid[0] / pubcheck[0]
    private const val SAMPLE_D_HEX = "5f29b5ea117fdc29a695237548e0b52627c01e5cb220c0afe6ce7ec41bd7281f"
    private const val SAMPLE_BLOB_ID = "e89fab0772b4dd0becc6198ca404654c2e8c1c6f7c9673ffe0911c0ee293461c"
    private const val SAMPLE_PUB_B64 = "BAO5M39hLcAbhHlk4bNWV4OeKxGO2lzdb0bX_jR4NFe5fYbfODXZBKt-BwSIdpOSFjgmM13B5AfyVrKxeaGUPVI"
    private const val SAMPLE_G_PUB_B64 = "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
    private const val SAMPLE_SIGN_MSG_HEX =
        "76656c612d73796e632f76322f7075740a653839666162303737326234646430626563633631393863613430343635346332653863316336663763393637336666653039313163306565323933343631630a30313233343536373839616263646566666564636261393837363534333231300a300a310a38616466373734333861353030336332636463646534663530356566336430383933383838396134373866663534343330313136303333383265323861633433"
    private const val SAMPLE_SIGN_SIG_B64 =
        "7xvB1vbTbRQLjd3YrK3pZw2XwqGIkSdi3nmFxixn-mm6ZAZYvNF8LXZbycVUELEAsTAQEKoSkllO_8ncCY1gOA"
    private const val SAMPLE_BAD_LEN63_SIG_B64 =
        "Yxb81uInRdP-S3D5Y7vQb9BSrbd8jh7Qr1ferAZ0mPsXdYZUkvlXayNOTzKD5XUAMU2FwoVBPqnj9lqsgWMe"
    private const val SAMPLE_PUBCHECK_SIG_B64 =
        "vGNlGiLz2CRPqVyR7OkRKNzZ0pdzN74kbzwMeQoiyrT3ae39Kyk1GdFenWzMQrB69nxQHb1w7Rwxl4-BqmhNxg"
    private const val SCALAR_NM1_HEX = "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550"

    fun classify(
        keyImport: Boolean,
        signAndConvert: Boolean,
        publicKeyMatch: Boolean,
        crossVerify: Boolean,
        pubcheckVerify: Boolean,
    ): SyncCapability {
        if (!keyImport || !signAndConvert || !crossVerify || !pubcheckVerify) {
            return SyncCapability.DISABLED
        }
        return if (publicKeyMatch) SyncCapability.FULL else SyncCapability.JOIN_ONLY
    }

    fun run(sdkInt: Int = Build.VERSION.SDK_INT): SyncProbeReport {
        if (!SyncPlatformGate.isSdkSupported(sdkInt)) {
            return SyncProbeReport(
                keyImport = false,
                signAndConvert = false,
                publicKeyMatch = false,
                crossVerify = false,
                pubcheckVerify = false,
            )
        }
        val d = SyncEncoding.decodeHex(SAMPLE_D_HEX)!!
        val scalarOne = ByteArray(32).apply { this[31] = 1 }
        val scalarNm1 = SyncEncoding.decodeHex(SCALAR_NM1_HEX)!!
        val expectPub = SyncEncoding.decodeBase64url(SAMPLE_PUB_B64)!!
        val expectG = SyncEncoding.decodeBase64url(SAMPLE_G_PUB_B64)!!
        val signMsg = SyncEncoding.decodeHex(SAMPLE_SIGN_MSG_HEX)!!
        val goSig = SyncEncoding.decodeBase64url(SAMPLE_SIGN_SIG_B64)!!
        val badLenSig = SyncEncoding.decodeBase64url(SAMPLE_BAD_LEN63_SIG_B64)!!
        val goPubcheckSig = SyncEncoding.decodeBase64url(SAMPLE_PUBCHECK_SIG_B64)!!
        val checkMsg = pubcheckMessage(SAMPLE_BLOB_ID)

        val keyImport = try {
            val params = AlgorithmParameters.getInstance("EC")
            params.init(ECGenParameterSpec("secp256r1"))
            val spec = params.getParameterSpec(ECParameterSpec::class.java)
            val kf = KeyFactory.getInstance("EC")
            kf.generatePrivate(ECPrivateKeySpec(BigInteger(1, d), spec))
            kf.generatePrivate(ECPrivateKeySpec(BigInteger(1, scalarOne), spec))
            kf.generatePrivate(ECPrivateKeySpec(BigInteger(1, scalarNm1), spec))
            true
        } catch (_: Throwable) {
            false
        }

        val publicKeyMatch = try {
            p256PublicKey(d).contentEquals(expectPub) && p256PublicKey(scalarOne).contentEquals(expectG)
        } catch (_: Throwable) {
            false
        }

        val signAndConvert = if (!keyImport) {
            false
        } else {
            try {
                val sig = signEcdsa(d, signMsg)
                sig.size == 64 && verifyEcdsa(expectPub, signMsg, sig)
            } catch (_: Throwable) {
                false
            }
        }

        val crossVerify = try {
            val zeroSig = ByteArray(64)
            verifyEcdsa(expectPub, signMsg, goSig) &&
                !verifyEcdsa(expectPub, signMsg, badLenSig) &&
                !verifyEcdsa(expectPub, signMsg, zeroSig)
        } catch (_: Throwable) {
            false
        }

        val pubcheckVerify = if (!keyImport) {
            false
        } else {
            try {
                val selfSig = signEcdsa(d, checkMsg)
                verifyEcdsa(expectPub, checkMsg, goPubcheckSig) &&
                    verifyEcdsa(expectPub, checkMsg, selfSig)
            } catch (_: Throwable) {
                false
            }
        }

        return SyncProbeReport(
            keyImport = keyImport,
            signAndConvert = signAndConvert,
            publicKeyMatch = publicKeyMatch,
            crossVerify = crossVerify,
            pubcheckVerify = pubcheckVerify,
        )
    }
}
