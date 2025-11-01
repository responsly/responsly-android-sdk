package com.responsly.sdk

import com.responsly.sdk.internal.resourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicApiTest {
    @Test
    fun theSdkVersionMatchesThePublishedVersion() {
        assertEquals(System.getProperty("responsly.sdk.version"), Responsly.SDK_VERSION)
    }

    @Test
    fun notShownReasonsAreExactlyThoseOfTheProtocol() {
        val protocol = resourceText("PROTOCOL.md")
        val section = protocol.substringAfter("### 4.5 Not-shown reasons").substringBefore("## 5.")
        val documented = Regex("`([a-z_]+)`").findAll(section).map { it.groupValues[1] }.toList()
        assertTrue(documented.size > 10)
        assertEquals(documented, NotShownReason.values().map { it.value })
        assertEquals(NotShownReason.VETOED, NotShownReason.fromValue("vetoed"))
        assertNull(NotShownReason.fromValue("nope"))
    }

    @Test
    fun presentationStylesUseTheProtocolStrings() {
        assertEquals(listOf("bottom_sheet", "dialog", "fullscreen"), PresentationStyle.values().map { it.value })
        assertEquals(PresentationStyle.DIALOG, PresentationStyle.fromValue("dialog"))
        assertNull(PresentationStyle.fromValue("hologram"))
    }

    @Test
    fun optionsHaveTheDocumentedDefaults() {
        val options = ResponslyOptions()
        assertEquals("https://api.responsly.com", options.apiHost)
        assertEquals(LogLevel.ERROR, options.logLevel)
        assertEquals(listOf("NONE", "ERROR", "INFO", "DEBUG"), LogLevel.values().map { it.name })

        val show = ShowOptions()
        assertFalse(show.force)
        assertTrue(show.hiddenFields.isEmpty())
        assertNull(show.locale)
        assertNull(show.presentation)
    }

    @Test
    fun showOptionsCopyTheirHiddenFields() {
        val fields = mutableMapOf("a" to "1")
        val options = ShowOptions(hiddenFields = fields)
        fields["b"] = "2"
        assertEquals(mapOf("a" to "1"), options.hiddenFields)
    }

    @Test
    fun eventsSerialiseWithSnakeCaseKeys() {
        assertEquals(
            mapOf(
                "survey" to mapOf("id" to "S1", "name" to "NPS"),
                "trigger" to mapOf("type" to "event", "name" to "purchase"),
            ),
            SurveyShownEvent(Survey("S1", "NPS"), SurveyTrigger("event", "purchase")).toMap(),
        )
        assertEquals(
            mapOf("type" to "manual", "name" to null),
            SurveyShownEvent(Survey("S1", "NPS"), SurveyTrigger("manual", null)).toMap()["trigger"],
        )
        assertEquals(
            mapOf("survey_id" to "S1", "reason" to "frequency_limit"),
            SurveyNotShownEvent("S1", NotShownReason.FREQUENCY_LIMIT).toMap(),
        )
        assertEquals(
            mapOf("survey_id" to "S1", "question_id" to "q1", "type" to "nps", "title" to "How likely?", "value" to 9L),
            QuestionAnsweredEvent("S1", "q1", "nps", "How likely?", 9L).toMap(),
        )
        assertEquals(
            mapOf(
                "survey_id" to "S1",
                "response_id" to "r_1",
                "answers" to listOf(
                    mapOf("question_id" to "q1", "type" to "nps", "title" to "How likely?", "value" to 9L),
                    mapOf("question_id" to "q2", "type" to "multiple_choice", "title" to "Why?", "value" to listOf("a", "b")),
                ),
            ),
            SurveyCompletedEvent(
                "S1",
                "r_1",
                listOf(Answer("q1", "nps", "How likely?", 9L), Answer("q2", "multiple_choice", "Why?", listOf("a", "b"))),
            ).toMap(),
        )
        assertEquals(mapOf("survey_id" to "S1"), SurveyDismissedEvent("S1").toMap())
        assertEquals(
            mapOf("survey_id" to "S1", "code" to "submit_failed", "message" to "Try again"),
            SurveyErrorEvent("S1", "submit_failed", "Try again").toMap(),
        )
    }

    @Test
    fun aListenerNeedsToImplementNothing() {
        val listener = object : ResponslyListener {}
        listener.onSurveyShown(SurveyShownEvent(Survey("S1", ""), SurveyTrigger("manual", null)))
        listener.onSurveyNotShown(SurveyNotShownEvent("S1", NotShownReason.PAUSED))
        listener.onQuestionAnswered(QuestionAnsweredEvent("S1", "q", "text", "t", null))
        listener.onSurveyCompleted(SurveyCompletedEvent("S1", "r", emptyList()))
        listener.onSurveyDismissed(SurveyDismissedEvent("S1"))
        listener.onSurveyError(SurveyErrorEvent("S1", "c", "m"))
    }
}
