package vela.app.infra

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import androidx.test.core.app.ApplicationProvider
import vela.app.R

/**
 * release 的网络安全配置不能再为 WebView 放行回环明文。
 * 调试用的 testnet 配置是另一份资源，不在这里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReleaseNetworkPolicyTest {
    @Test
    fun testReleaseConfigDoesNotPermitLoopbackCleartext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = context.resources.getXml(R.xml.network_security_config)
        val problems = ArrayList<String>()
        var domainCleartext = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "base-config" -> {
                        if (cleartextPermitted(parser)) problems.add("base-config")
                    }
                    "domain-config" -> domainCleartext = cleartextPermitted(parser)
                    "domain" -> if (domainCleartext) {
                        val host = parser.nextText()
                        if (loopbackDomain(host)) problems.add(host.trim())
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "domain-config") {
                domainCleartext = false
            }
            event = parser.next()
        }
        assertTrue("release 仍放行回环明文：$problems", problems.isEmpty())
    }

    private fun cleartextPermitted(parser: XmlPullParser): Boolean =
        parser.getAttributeValue(null, "cleartextTrafficPermitted")
            ?.equals("true", ignoreCase = true) == true

    private fun loopbackDomain(host: String): Boolean {
        val name = host.trim().lowercase()
        return name == "127.0.0.1" ||
            name == "localhost" ||
            name == "::1" ||
            name.endsWith(".localhost")
    }
}
