package com.responsly.sdk.internal.config

import com.responsly.sdk.PresentationStyle
import com.responsly.sdk.internal.IsoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigParserTest {
    @Test
    fun aSurveyWithOnlyIdAndUrlGetsEveryDefault() {
        val config = ConfigParser.parse("""{"surveys":[{"id":"S1","url":"https://acme.responsly.com/f/S1"}]}""")!!
        assertEquals(1, config.version)
        assertEquals(600_000L, config.ttlMillis)
        assertEquals(0L, config.globalCooldownMillis)

        val survey = config.surveys.single()
        assertEquals("S1", survey.id)
        assertEquals("", survey.name)
        assertEquals("https://acme.responsly.com/f/S1", survey.url)
        assertTrue(survey.triggers.isEmpty())
        assertEquals(0L, survey.delayMillis)
        assertTrue(survey.audience.platforms.isEmpty())
        assertTrue(survey.audience.appIds.isEmpty())
        assertTrue(survey.audience.locales.isEmpty())
        assertNull(survey.audience.identified)
        assertFalse(survey.audience.matchAny)
        assertTrue(survey.audience.conditions.isEmpty())
        assertEquals(100.0, survey.samplingPercent, 0.0)
        assertEquals(FrequencyMode.ONCE, survey.frequency.mode)
        assertEquals(0L, survey.frequency.recontactMillis)
        assertNull(survey.frequency.maxDisplays)
        assertNull(survey.schedule.startsAt)
        assertNull(survey.schedule.endsAt)
        assertEquals(PresentationStyle.BOTTOM_SHEET, survey.presentation.style)
        assertEquals(PresentationSize.MEDIUM, survey.presentation.size)
        assertTrue(survey.presentation.dismissible)
        assertEquals(3_000L, survey.presentation.autoCloseMillis)
        assertFalse(survey.anonymous)
    }

    @Test
    fun readsTheExampleOfTheProtocol() {
        val config = ConfigParser.parse(
            """
            {
              "version": 1, "ttl": 900, "settings": { "global_cooldown_seconds": 120 },
              "surveys": [ {
                "id": "aBcD1234", "name": "NPS after purchase", "url": "https://acme.responsly.com/f/aBcD1234",
                "updated_at": "2026-11-10T12:00:00Z",
                "triggers": [
                  { "type": "event", "name": "purchase_completed", "min_count": 2,
                    "conditions": [ { "property": "value", "op": "gte", "value": 100 } ] },
                  { "type": "screen", "name": "Checkout" },
                  { "type": "app_launch" }
                ],
                "delay_seconds": 2.5,
                "audience": { "platforms": ["android"], "app_ids": ["com.acme.app"], "locales": ["pl", "en"],
                  "identified": true, "match": "any",
                  "conditions": [ { "attribute": "plan", "op": "eq", "value": "pro" } ] },
                "sampling_percent": 25,
                "frequency": { "mode": "until_completed", "recontact_seconds": 3600, "max_displays": 3 },
                "schedule": { "starts_at": "2026-11-01T00:00:00Z", "ends_at": "2026-12-01T00:00:00Z" },
                "presentation": { "style": "dialog", "size": "large", "dismissible": false, "auto_close_seconds": 0 },
                "anonymous": true
              } ]
            }
            """,
        )!!
        assertEquals(900_000L, config.ttlMillis)
        assertEquals(120_000L, config.globalCooldownMillis)
        val survey = config.find("aBcD1234")!!
        assertEquals("NPS after purchase", survey.name)
        assertEquals(listOf(TriggerType.EVENT, TriggerType.SCREEN, TriggerType.APP_LAUNCH), survey.triggers.map { it.type })
        val event = survey.triggers[0]
        assertEquals("purchase_completed", event.name)
        assertEquals(2.0, event.minCount, 0.0)
        assertEquals("value", event.conditions.single().name)
        assertEquals("gte", event.conditions.single().op)
        assertEquals(100.0, event.conditions.single().value)
        assertEquals(1.0, survey.triggers[1].minCount, 0.0)
        assertEquals(2_500L, survey.delayMillis)
        assertEquals(listOf("android"), survey.audience.platforms)
        assertEquals(listOf("com.acme.app"), survey.audience.appIds)
        assertEquals(listOf("pl", "en"), survey.audience.locales)
        assertEquals(true, survey.audience.identified)
        assertTrue(survey.audience.matchAny)
        assertEquals("plan", survey.audience.conditions.single().name)
        assertEquals("pro", survey.audience.conditions.single().value)
        assertEquals(25.0, survey.samplingPercent, 0.0)
        assertEquals(FrequencyMode.UNTIL_COMPLETED, survey.frequency.mode)
        assertEquals(3_600_000L, survey.frequency.recontactMillis)
        assertEquals(3.0, survey.frequency.maxDisplays!!, 0.0)
        assertEquals(IsoTime.parse("2026-11-01T00:00:00Z"), survey.schedule.startsAt)
        assertEquals(IsoTime.parse("2026-12-01T00:00:00Z"), survey.schedule.endsAt)
        assertEquals(PresentationStyle.DIALOG, survey.presentation.style)
        assertEquals(PresentationSize.LARGE, survey.presentation.size)
        assertFalse(survey.presentation.dismissible)
        assertEquals(0L, survey.presentation.autoCloseMillis)
        assertTrue(survey.anonymous)
    }

    @Test
    fun unknownEnumValuesFallBack() {
        val config = ConfigParser.parse(
            """
            {"version": 7, "future_field": true, "surveys": [ {
              "id": "S2", "url": "https://acme.responsly.com/f/S2", "future": {"x": 1},
              "triggers": [ { "type": "shake" }, { "type": "event", "name": "e" } ],
              "frequency": { "mode": "sometimes" },
              "presentation": { "style": "hologram", "size": "huge" },
              "audience": { "match": "most" }
            } ] }
            """,
        )!!
        assertEquals(7, config.version)
        val survey = config.surveys.single()
        assertEquals(listOf(TriggerType.UNKNOWN, TriggerType.EVENT), survey.triggers.map { it.type })
        assertEquals(FrequencyMode.ONCE, survey.frequency.mode)
        assertEquals(PresentationStyle.BOTTOM_SHEET, survey.presentation.style)
        assertEquals(PresentationSize.MEDIUM, survey.presentation.size)
        assertFalse(survey.audience.matchAny)
    }

    @Test
    fun everyKnownEnumValueIsRead() {
        fun survey(extra: String) =
            ConfigParser.parse("""{"surveys":[{"id":"S","url":"https://a.b/c",$extra}]}""")!!.surveys.single()
        assertEquals(FrequencyMode.RECURRING, survey(""""frequency":{"mode":"recurring"}""").frequency.mode)
        assertEquals(FrequencyMode.ALWAYS, survey(""""frequency":{"mode":"always"}""").frequency.mode)
        assertEquals(FrequencyMode.ONCE, survey(""""frequency":{"mode":"once"}""").frequency.mode)
        assertEquals(PresentationStyle.FULLSCREEN, survey(""""presentation":{"style":"fullscreen"}""").presentation.style)
        assertEquals(PresentationStyle.BOTTOM_SHEET, survey(""""presentation":{"style":"bottom_sheet"}""").presentation.style)
        assertEquals(PresentationSize.SMALL, survey(""""presentation":{"size":"small"}""").presentation.size)
        assertEquals(PresentationSize.MEDIUM, survey(""""presentation":{"size":"medium"}""").presentation.size)
        assertEquals(7_500L, survey(""""presentation":{"auto_close_seconds":7.5}""").presentation.autoCloseMillis)
    }

    @Test
    fun wrongTypesFallBackToDefaults() {
        val config = ConfigParser.parse(
            """
            {"ttl": "soon", "settings": [], "surveys": [ {
              "id": "S1", "url": "https://acme.responsly.com/f/S1", "name": 5,
              "triggers": "none", "delay_seconds": "2", "audience": "everyone", "sampling_percent": null,
              "frequency": 3, "schedule": { "starts_at": "tomorrow", "ends_at": 5 },
              "presentation": { "dismissible": "yes", "auto_close_seconds": "3" }, "anonymous": 1
            } ] }
            """,
        )!!
        assertEquals(600_000L, config.ttlMillis)
        assertEquals(0L, config.globalCooldownMillis)
        val survey = config.surveys.single()
        assertEquals("", survey.name)
        assertTrue(survey.triggers.isEmpty())
        assertEquals(0L, survey.delayMillis)
        assertEquals(100.0, survey.samplingPercent, 0.0)
        assertEquals(FrequencyMode.ONCE, survey.frequency.mode)
        assertNull(survey.schedule.startsAt)
        assertNull(survey.schedule.endsAt)
        assertTrue(survey.presentation.dismissible)
        assertEquals(3_000L, survey.presentation.autoCloseMillis)
        assertFalse(survey.anonymous)
    }

    @Test
    fun surveysWithoutIdOrUrlAndDuplicatesAreSkipped() {
        val config = ConfigParser.parse(
            """
            {"surveys": [
              {"id": "A", "url": "https://a.example/f/A", "name": "first"},
              {"id": "", "url": "https://a.example/f/empty"},
              {"url": "https://a.example/f/no-id"},
              {"id": "no-url"},
              "not an object",
              {"id": "A", "url": "https://a.example/f/A2", "name": "duplicate"},
              {"id": "B", "url": "https://a.example/f/B"}
            ]}
            """,
        )!!
        assertEquals(listOf("A", "B"), config.surveys.map { it.id })
        assertEquals("first", config.find("A")!!.name)
        assertNull(config.find("missing"))
    }

    @Test
    fun theEffectiveTtlIsAtLeastOneMinute() {
        assertEquals(60_000L, ConfigParser.parse("""{"ttl":5}""")!!.ttlMillis)
        assertEquals(60_000L, ConfigParser.parse("""{"ttl":0}""")!!.ttlMillis)
        assertEquals(60_000L, ConfigParser.parse("""{"ttl":-10}""")!!.ttlMillis)
        assertEquals(61_000L, ConfigParser.parse("""{"ttl":61}""")!!.ttlMillis)
    }

    @Test
    fun negativeDurationsCountAsZero() {
        val survey = ConfigParser.parse(
            """{"settings":{"global_cooldown_seconds":-5},"surveys":[{"id":"S","url":"https://a.b/c","delay_seconds":-1,
               "frequency":{"recontact_seconds":-1},"presentation":{"auto_close_seconds":-1}}]}""",
        )!!
        assertEquals(0L, survey.globalCooldownMillis)
        assertEquals(0L, survey.surveys.single().delayMillis)
        assertEquals(0L, survey.surveys.single().frequency.recontactMillis)
        assertEquals(0L, survey.surveys.single().presentation.autoCloseMillis)
    }

    @Test
    fun conditionValuesKeepTheirJsonType() {
        val conditions = ConfigParser.parse(
            """{"surveys":[{"id":"S","url":"https://a.b/c","audience":{"conditions":[
               {"attribute":"s","op":"eq","value":"5"},{"attribute":"n","op":"eq","value":5},
               {"attribute":"b","op":"eq","value":true},{"attribute":"z","op":"exists"},
               {"attribute":"nil","op":"eq","value":null},{"op":"eq","value":1}]}}]}""",
        )!!.surveys.single().audience.conditions
        assertEquals("5", conditions[0].value)
        assertEquals(5.0, conditions[1].value)
        assertEquals(true, conditions[2].value)
        assertNull(conditions[3].value)
        assertNull(conditions[4].value)
        assertNull(conditions[5].name)
    }

    @Test
    fun aBodyThatIsNotAJsonObjectIsRejected() {
        assertNull(ConfigParser.parse("<html>captive portal</html>"))
        assertNull(ConfigParser.parse(""))
        assertNull(ConfigParser.parse("[1,2]"))
        assertNotNull(ConfigParser.parse("{}"))
        assertTrue(ConfigParser.parse("{}")!!.surveys.isEmpty())
    }
}
