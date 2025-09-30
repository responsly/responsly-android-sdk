package com.responsly.sdk.internal.bridge

import com.responsly.sdk.PresentationStyle
import com.responsly.sdk.SurveyTrigger
import com.responsly.sdk.internal.fieldNames
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyUrlTest {
    @Test
    fun appendsTheSdkMarkerAndTheExplicitLanguageOnly() {
        assertEquals("https://acme.responsly.com/f/aBcD1234?sdk=1", SurveyUrl.build("https://acme.responsly.com/f/aBcD1234", null))
        assertEquals(
            "https://acme.responsly.com/f/aBcD1234?sdk=1&lang=pt-BR",
            SurveyUrl.build("https://acme.responsly.com/f/aBcD1234", "pt-BR"),
        )
        assertEquals("https://a.example/f/x?sdk=1", SurveyUrl.build("https://a.example/f/x", "  "))
        assertEquals("https://a.example/f/x?sdk=1&lang=a%26b", SurveyUrl.build("https://a.example/f/x", "a&b"))
    }

    @Test
    fun keepsAnExistingQueryAndFragmentIntact() {
        assertEquals("https://a.example/f/x?theme=dark&sdk=1", SurveyUrl.build("https://a.example/f/x?theme=dark", null))
        assertEquals("https://a.example/f/x?sdk=1#top", SurveyUrl.build("https://a.example/f/x#top", null))
        assertEquals("https://a.example/f/x?a=1&sdk=1&lang=pl#top", SurveyUrl.build("https://a.example/f/x?a=1#top", "pl"))
    }

    @Test
    fun originsAreNormalisedAndLimitedToHttp() {
        assertEquals("https://acme.responsly.com", SurveyUrl.origin("https://ACME.responsly.com/f/x?y=1"))
        assertEquals("https://acme.responsly.com", SurveyUrl.origin("HTTPS://acme.responsly.com:443/f/x"))
        assertEquals("http://10.0.2.2:8080", SurveyUrl.origin("http://10.0.2.2:8080/f/x"))
        assertEquals("http://localhost", SurveyUrl.origin("http://localhost:80"))
        for (url in listOf("ftp://a.example/x", "javascript:alert(1)", "about:blank", "/f/x", "", "https://", "not a url")) {
            assertNull(url, SurveyUrl.origin(url))
        }
    }

    @Test
    fun onlyTheSurveyOriginAndPathCountAsTheSurveyPage() {
        val survey = "https://acme.responsly.com/f/aBcD1234"
        assertTrue(SurveyUrl.isSurveyPage(survey, "https://acme.responsly.com/f/aBcD1234?sdk=1&lang=pl"))
        assertTrue(SurveyUrl.isSurveyPage(survey, "https://acme.responsly.com/f/aBcD1234#thanks"))
        assertTrue(SurveyUrl.isSurveyPage(survey, "https://ACME.responsly.com:443/f/aBcD1234"))
        assertFalse(SurveyUrl.isSurveyPage(survey, "https://acme.responsly.com/f/other"))
        assertFalse(SurveyUrl.isSurveyPage(survey, "https://acme.responsly.com/f/aBcD1234/"))
        assertFalse(SurveyUrl.isSurveyPage(survey, "http://acme.responsly.com/f/aBcD1234"))
        assertFalse(SurveyUrl.isSurveyPage(survey, "https://evil.example/f/aBcD1234"))
        assertFalse(SurveyUrl.isSurveyPage(survey, "https://acme.responsly.com.evil.example/f/aBcD1234"))
        assertFalse(SurveyUrl.isSurveyPage(survey, "https://acme.responsly.com:8443/f/aBcD1234"))
        assertFalse(SurveyUrl.isSurveyPage(survey, "mailto:someone@example.com"))
        assertFalse(SurveyUrl.isSurveyPage(survey, "about:blank"))
        assertTrue(SurveyUrl.isSurveyPage("https://a.example", "https://a.example/"))
    }
}

class BridgeMessageTest {
    @Test
    fun parsesAVersionOneMessage() {
        val message = BridgeMessage.parse("""{"responsly":1,"type":"resize","payload":{"height":420}}""")!!
        assertEquals("resize", message.type)
        assertEquals(420, message.payload.getInt("height"))
    }

    @Test
    fun aMissingPayloadIsAnEmptyObject() {
        val message = BridgeMessage.parse("""{"responsly":1,"type":"ready"}""")!!
        assertEquals(0, message.payload.length())
        assertEquals(0, BridgeMessage.parse("""{"responsly":1,"type":"ready","payload":"x"}""")!!.payload.length())
    }

    @Test
    fun unknownTypesStillParseSoTheCallerCanIgnoreThem() {
        assertEquals("future_thing", BridgeMessage.parse("""{"responsly":1,"type":"future_thing","payload":{}}""")!!.type)
    }

    @Test
    fun everythingElseIsRejected() {
        val rejected = listOf(
            null,
            "",
            "ready",
            "[1]",
            """{"type":"ready","payload":{}}""",
            """{"responsly":2,"type":"ready","payload":{}}""",
            """{"responsly":"1","type":"ready","payload":{}}""",
            """{"responsly":1,"payload":{}}""",
            """{"responsly":1,"type":"","payload":{}}""",
            """{"responsly":1,"type":5,"payload":{}}""",
        )
        for (raw in rejected) assertNull(raw, BridgeMessage.parse(raw))
    }
}

class CssColorTest {
    @Test
    fun parsesHexColours() {
        assertEquals(0xFFFFFFFF.toInt(), CssColor.parse("#ffffff"))
        assertEquals(0xFF1A2B3C.toInt(), CssColor.parse("#1A2B3C"))
        assertEquals(0xFFAABBCC.toInt(), CssColor.parse("#abc"))
        assertEquals(0x80112233.toInt(), CssColor.parse("#11223380"))
        assertEquals(0x00AABBCC, CssColor.parse("#abc0"))
        assertEquals(0xFF000000.toInt(), CssColor.parse(" #000 "))
    }

    @Test
    fun rejectsEverythingElse() {
        for (text in listOf(null, "", "ffffff", "#ff", "#fffff", "#gggggg", "white", "rgb(0,0,0)", "#+fffff", "#１２３")) {
            assertNull(text, CssColor.parse(text))
        }
    }

    @Test
    fun knowsLightFromDark() {
        assertTrue(CssColor.isLight(0xFFFFFFFF.toInt()))
        assertTrue(CssColor.isLight(0xFFF5F0E6.toInt()))
        assertFalse(CssColor.isLight(0xFF000000.toInt()))
        assertFalse(CssColor.isLight(0xFF1F2937.toInt()))
    }
}

class SurveyContextTest {
    private fun context(
        anonymous: Boolean = false,
        userId: String? = "u_123",
        trigger: SurveyTrigger = SurveyTrigger(SurveyTrigger.TYPE_EVENT, "purchase_completed"),
    ): JSONObject = SurveyContext.build(
        platform = "android",
        sdkVersion = "1.0.0",
        appId = "com.acme.app",
        appVersion = "2.3.0",
        osVersion = "16",
        locale = "en-US",
        surveyId = "aBcD1234",
        trigger = trigger,
        style = PresentationStyle.BOTTOM_SHEET,
        dismissible = true,
        anonymous = anonymous,
        userId = userId,
        traits = mapOf("plan" to "pro", "seats" to 5.0, "beta" to true),
        hidden = mapOf("order_id" to "A-77"),
    )

    @Test
    fun hasExactlyTheFieldsOfTheProtocol() {
        val json = context()
        assertEquals(
            setOf(
                "v", "platform", "sdk_version", "app_id", "app_version", "os_version", "locale", "survey_id",
                "trigger", "presentation", "user", "hidden",
            ),
            json.fieldNames(),
        )
        assertEquals(1, json.getInt("v"))
        assertEquals("android", json.getString("platform"))
        assertEquals("1.0.0", json.getString("sdk_version"))
        assertEquals("com.acme.app", json.getString("app_id"))
        assertEquals("2.3.0", json.getString("app_version"))
        assertEquals("16", json.getString("os_version"))
        assertEquals("en-US", json.getString("locale"))
        assertEquals("aBcD1234", json.getString("survey_id"))
        assertEquals("event", json.getJSONObject("trigger").getString("type"))
        assertEquals("purchase_completed", json.getJSONObject("trigger").getString("name"))
        assertEquals(setOf("style", "dismissible"), json.getJSONObject("presentation").fieldNames())
        assertEquals("bottom_sheet", json.getJSONObject("presentation").getString("style"))
        assertTrue(json.getJSONObject("presentation").getBoolean("dismissible"))
        val user = json.getJSONObject("user")
        assertEquals("u_123", user.getString("id"))
        assertEquals("pro", user.getJSONObject("traits").getString("plan"))
        assertEquals(5.0, user.getJSONObject("traits").getDouble("seats"), 0.0)
        assertTrue(user.getJSONObject("traits").getBoolean("beta"))
        assertEquals("A-77", json.getJSONObject("hidden").getString("order_id"))
    }

    @Test
    fun aManualTriggerHasNoName() {
        val trigger = context(trigger = SurveyTrigger(SurveyTrigger.TYPE_MANUAL, null)).getJSONObject("trigger")
        assertEquals(setOf("type"), trigger.fieldNames())
        assertEquals("manual", trigger.getString("type"))
    }

    @Test
    fun anAnonymousSurveyGetsNoUserAndNoHiddenFields() {
        val json = context(anonymous = true)
        assertTrue(json.isNull("user"))
        assertEquals(0, json.getJSONObject("hidden").length())
    }

    @Test
    fun withoutAUserIdThereIsNoUserButHiddenFieldsStay() {
        val json = context(userId = null)
        assertTrue(json.isNull("user"))
        assertEquals("A-77", json.getJSONObject("hidden").getString("order_id"))
    }

    @Test
    fun theScriptDefinesTheContextInTheMainFrameOnlyAndIsValidJavaScript() {
        val json = SurveyContext.build(
            platform = "android",
            sdkVersion = "1.0.0",
            appId = "com.acme.app",
            appVersion = "2.3.0",
            osVersion = "16",
            locale = "en-US",
            surveyId = "S1",
            trigger = SurveyTrigger(SurveyTrigger.TYPE_MANUAL, null),
            style = PresentationStyle.DIALOG,
            dismissible = false,
            anonymous = false,
            userId = "u  </script>\"'\\",
            traits = emptyMap(),
            hidden = emptyMap(),
        )
        val script = SurveyContext.script(json)
        assertTrue(script.startsWith("(function(){if(window.top!==window.self){return;}window.__RESPONSLY_SDK__={"))
        assertTrue(script.endsWith("};})();"))
        assertFalse("raw line separators would end the script", script.contains(' ') || script.contains(' '))
        // The literal is still the same JSON.
        val literal = script.substringAfter("window.__RESPONSLY_SDK__=").removeSuffix(";})();")
        assertEquals("u  </script>\"'\\", JSONObject(literal).getJSONObject("user").getString("id"))
    }
}
