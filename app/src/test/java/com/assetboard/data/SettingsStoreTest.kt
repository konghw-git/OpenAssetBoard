package com.assetboard.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun theAiApiKeyIsNeverWrittenInTheClear() {
        val store = SettingsStore(context)
        store.setAiApiKey("sk-secret-123")

        assertEquals("sk-secret-123", store.aiApiKey.value)
        val raw = prefs.getString("ai_api_key", null)
        // With a Keystore this is "enc:…"; without one the key stays in memory and nothing is written.
        assertFalse("the key must never sit in prefs in the clear", raw == "sk-secret-123")
        if (raw != null) assertTrue(raw.startsWith("enc:"))
    }

    @Test
    fun aLegacyPlaintextKeyStillLoadsAndIsNotLost() {
        prefs.edit().putString("ai_api_key", "sk-legacy").commit()

        val store = SettingsStore(context)

        assertEquals("sk-legacy", store.aiApiKey.value)
    }

    @Test
    fun clearingTheKeyRemovesItFromDisk() {
        val store = SettingsStore(context)
        store.setAiApiKey("sk-secret")
        store.setAiApiKey("")

        assertEquals("", store.aiApiKey.value)
        assertNull(prefs.getString("ai_api_key", null))
    }

    @Test
    fun savingWithoutAKeystoreNeverErasesTheStoredKey() {
        prefs.edit().putString("ai_api_key", "sk-legacy").commit()

        SettingsStore(context).setAiApiKey("sk-legacy")

        // With a Keystore the copy becomes "enc:…"; without one the legacy copy must survive —
        // either way the key is still readable after the save.
        assertNotNull("the stored key must not vanish", prefs.getString("ai_api_key", null))
        assertEquals("sk-legacy", SettingsStore(context).aiApiKey.value)
    }

    @Test
    fun theMigrationRulesAreEncodeDecodeRoundTrips() {
        val cipher: (String) -> String? = { "sealed($it)" }
        assertEquals("enc:sealed(sk)", encodeAiKey("sk", cipher))
        assertEquals("sk", decodeAiKey("enc:sealed(sk)") { it.removePrefix("sealed(").removeSuffix(")") })
        // Legacy plaintext reads as itself, and an empty value stores nothing.
        assertEquals("sk-legacy", decodeAiKey("sk-legacy") { null })
        assertNull(encodeAiKey("", cipher))
        // A broken Keystore writes nothing; an unreadable encrypted value decodes to empty.
        assertNull(encodeAiKey("sk") { null })
        assertEquals("", decodeAiKey("enc:gibberish") { null })
    }
}
