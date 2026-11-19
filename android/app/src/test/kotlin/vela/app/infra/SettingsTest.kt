package vela.app.infra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Region

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testMissingMutedIsMutedAndStoredValueKept() {
        val settings = Settings(context)
        assertFalse(settings.preferences.contains(Settings.KEY_MUTED))
        assertTrue(settings.muted)
        settings.muted = false
        assertFalse(Settings(context).muted)
        settings.muted = true
        assertTrue(Settings(context).muted)
        settings.preferences.edit().remove(Settings.KEY_MUTED).commit()
        assertTrue(Settings(context).muted)
    }

    @Test
    fun testAgeAndFeedDefaults() {
        val settings = Settings(context)
        assertFalse(settings.ageConfirmed)
        assertEquals(FeedSource.guess, settings.source)
        assertTrue(settings.publicOnly)
        assertFalse(settings.infoAlwaysOn)
        assertFalse(settings.hintSeen)
        assertEquals(0.85, settings.volume, 0.0)
        assertEquals("", settings.tag)
        assertNull(settings.gender)
        assertNull(settings.region)
        assertFalse(settings.dualWindow)
        assertTrue(settings.autoEnterPip)

        settings.ageConfirmed = true
        settings.source = FeedSource.fresh
        settings.publicOnly = false
        settings.infoAlwaysOn = true
        settings.hintSeen = true
        settings.volume = 0.2
        settings.tag = "synth"
        settings.gender = Gender.trans
        settings.region = Region.europeRussia
        settings.dualWindow = true
        settings.autoEnterPip = false

        val again = Settings(context)
        assertTrue(again.ageConfirmed)
        assertEquals(FeedSource.fresh, again.source)
        assertFalse(again.publicOnly)
        assertTrue(again.infoAlwaysOn)
        assertTrue(again.hintSeen)
        assertEquals(0.2, again.volume, 0.0)
        assertEquals("synth", again.tag)
        assertEquals(Gender.trans, again.gender)
        assertEquals(Region.europeRussia, again.region)
        assertTrue(again.dualWindow)
        assertFalse(again.autoEnterPip)
    }

    @Test
    fun testLegacySourceNamesFallBackToGuess() {
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Settings.KEY_SOURCE, "recommended").commit()
        assertEquals(FeedSource.guess, Settings(context).source)
        prefs.edit().putString(Settings.KEY_SOURCE, "following").commit()
        assertEquals(FeedSource.guess, Settings(context).source)
        prefs.edit().putString(Settings.KEY_SOURCE, "favorites").commit()
        assertEquals(FeedSource.favorites, Settings(context).source)
    }
}
