package com.responsly.sdk.internal.state

import com.responsly.sdk.internal.InMemoryStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateStoreTest {
    private val storage = InMemoryStorage()

    private fun open(): StateStore = StateStore(storage).apply { load() }

    @Test
    fun theInstallIdIsALowerCaseUuidV4CreatedOnce() {
        val first = open().state.installId
        assertTrue(first, Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(first))
        assertEquals(first, open().state.installId)
    }

    @Test
    fun everythingSurvivesAReload() {
        val store = open()
        store.bindSdkKey("rsk_one")
        store.identify("u1", mapOf("plan" to "pro", "seats" to 5.0, "beta" to true))
        store.setAttributes(mapOf("seats" to null, "since" to "2026-11-10T12:00:00Z"))
        store.setLocale("pt-BR")
        store.setHiddenFields(mapOf("order_id" to "A-77"))
        store.setTrackingEnabled(false)
        assertEquals(1, store.countEvent("purchase"))
        assertEquals(2, store.countEvent("purchase"))
        assertEquals(1, store.countEvent("open"))
        store.markDisplayed("S1", 1_000L, auto = true)
        store.markDisplayed("S1", 2_000L, auto = false)
        store.markDismissed("S1")
        store.markDisplayed("S2", 3_000L, auto = false)
        store.markCompleted("S2", 4_000L)
        store.saveConfig("""{"surveys":[]}""", "\"etag-1\"", 5_000L, "https://api.responsly.com")
        store.telemetry.add(TelemetryEvent("displayed", "S1"))
        store.telemetry.add(TelemetryEvent("dismissed", "S1"))
        store.saveTelemetry()

        val reloaded = open()
        val state = reloaded.state
        assertEquals("rsk_one", reloaded.sdkKey)
        assertEquals(store.state.installId, state.installId)
        assertEquals("u1", state.userId)
        assertEquals(mapOf("plan" to "pro", "beta" to true, "since" to "2026-11-10T12:00:00Z"), state.traits)
        assertEquals("pt-BR", state.locale)
        assertEquals(mapOf("order_id" to "A-77"), state.hiddenFields)
        assertFalse(state.trackingEnabled)
        assertEquals(mapOf("purchase" to 2, "open" to 1), state.eventCounts)
        assertEquals(1_000L, state.lastAutoDisplayAt)

        val first = state.surveys.getValue("S1")
        assertEquals(2, first.displayCount)
        assertEquals(2_000L, first.lastDisplayedAt)
        assertNull(first.completedAt)
        assertEquals(1, first.dismissedCount)
        val second = state.surveys.getValue("S2")
        assertEquals(1, second.displayCount)
        assertEquals(4_000L, second.completedAt)
        assertEquals(0, second.dismissedCount)

        assertEquals("""{"surveys":[]}""", reloaded.configBody)
        assertEquals("\"etag-1\"", reloaded.configEtag)
        assertEquals(5_000L, reloaded.configFetchedAt)
        assertEquals("https://api.responsly.com", reloaded.configHost)
        assertEquals(listOf("displayed:S1", "dismissed:S1"), reloaded.telemetry.map { "${it.type}:${it.surveyId}" })
    }

    @Test
    fun numbersStayNumbersAndTraitsStayTyped() {
        val store = open()
        store.setAttributes(mapOf("n" to 5.0, "f" to 5.5, "s" to "5", "b" to false))
        val traits = open().state.traits
        assertEquals(5.0, traits["n"])
        assertEquals(5.5, traits["f"])
        assertEquals("5", traits["s"])
        assertEquals(false, traits["b"])
    }

    @Test
    fun trackingIsEnabledByDefault() {
        assertTrue(open().state.trackingEnabled)
    }

    @Test
    fun resetKeepsOnlyTheInstallIdTheTrackingSwitchAndTheKey() {
        val store = open()
        store.bindSdkKey("rsk_one")
        store.identify("u1", mapOf("plan" to "pro"))
        store.setLocale("pl")
        store.setHiddenFields(mapOf("a" to "b"))
        store.setTrackingEnabled(false)
        store.countEvent("e")
        store.markDisplayed("S1", 1_000L, auto = true)
        store.saveConfig("{}", "etag", 1_000L, "https://api.responsly.com")
        store.telemetry.add(TelemetryEvent("displayed", "S1"))
        store.saveTelemetry()
        val installId = store.state.installId

        store.reset()

        for (candidate in listOf(store, open())) {
            val state = candidate.state
            assertEquals(installId, state.installId)
            assertFalse(state.trackingEnabled)
            assertEquals("rsk_one", candidate.sdkKey)
            assertNull(state.userId)
            assertTrue(state.traits.isEmpty())
            assertNull(state.locale)
            assertTrue(state.hiddenFields.isEmpty())
            assertTrue(state.eventCounts.isEmpty())
            assertTrue(state.surveys.isEmpty())
            assertNull(state.lastAutoDisplayAt)
            assertNull(candidate.configBody)
            assertNull(candidate.configEtag)
            assertNull(candidate.configFetchedAt)
            assertNull(candidate.configHost)
            assertTrue(candidate.telemetry.isEmpty())
        }
        assertEquals(
            setOf(StateStore.KEY_INSTALL_ID, StateStore.KEY_TRACKING_ENABLED, StateStore.KEY_SDK_KEY),
            storage.values.keys,
        )
    }

    @Test
    fun notModifiedOnlyRestartsTheTtl() {
        val store = open()
        store.saveConfig("{}", "etag", 1_000L, "https://api.responsly.com")
        store.touchConfig(9_000L)
        val reloaded = open()
        assertEquals("{}", reloaded.configBody)
        assertEquals("etag", reloaded.configEtag)
        assertEquals(9_000L, reloaded.configFetchedAt)
        assertEquals("https://api.responsly.com", reloaded.configHost)
    }

    @Test
    fun theCachedConfigurationRemembersItsHostAndForgetsItWhenDropped() {
        val store = open()
        store.saveConfig("{}", "etag", 1_000L, "http://10.0.2.2:8000")
        assertEquals("http://10.0.2.2:8000", open().configHost)

        store.clearConfig()
        for (candidate in listOf(store, open())) {
            assertNull(candidate.configBody)
            assertNull(candidate.configEtag)
            assertNull(candidate.configFetchedAt)
            assertNull(candidate.configHost)
        }
        assertFalse(storage.values.keys.any { it.startsWith("config") })
    }

    @Test
    fun corruptValuesAreIgnored() {
        storage.putString(StateStore.KEY_TRAITS, "{not json")
        storage.putString(StateStore.KEY_SURVEYS, "[]")
        storage.putString(StateStore.KEY_EVENT_COUNTS, """{"e":"many","ok":3}""")
        storage.putString(StateStore.KEY_TELEMETRY, "oops")
        storage.putString(StateStore.KEY_LAST_AUTO_DISPLAY_AT, "yesterday")
        val store = open()
        assertTrue(store.state.traits.isEmpty())
        assertTrue(store.state.surveys.isEmpty())
        assertEquals(mapOf("ok" to 3), store.state.eventCounts)
        assertTrue(store.telemetry.isEmpty())
        assertNull(store.state.lastAutoDisplayAt)
        assertNotNull(store.state.installId)
    }

    @Test
    fun nothingButTheDocumentedKeysIsStored() {
        val store = open()
        store.bindSdkKey("rsk_one")
        store.identify("u1", mapOf("plan" to "pro"))
        store.setLocale("pl")
        store.setHiddenFields(mapOf("a" to "b"))
        store.setTrackingEnabled(true)
        store.countEvent("e")
        store.markDisplayed("S1", 1_000L, auto = true)
        store.saveConfig("{}", "etag", 1_000L, "https://api.responsly.com")
        store.telemetry.add(TelemetryEvent("displayed", "S1"))
        store.saveTelemetry()
        assertEquals(
            setOf(
                "sdk_key", "install_id", "user_id", "traits", "locale", "hidden_fields", "tracking_enabled",
                "event_counts", "surveys", "last_auto_display_at", "config", "config_etag", "config_fetched_at",
                "config_api_host", "telemetry_queue",
            ),
            storage.values.keys,
        )
    }
}
