package com.responsly.sdk.internal.core

import com.responsly.sdk.Decision
import com.responsly.sdk.DisplayGuard
import com.responsly.sdk.LogLevel
import com.responsly.sdk.PresentationStyle
import com.responsly.sdk.ResponslyOptions
import com.responsly.sdk.ShowOptions
import com.responsly.sdk.Survey
import com.responsly.sdk.internal.FakeApi
import com.responsly.sdk.internal.FakeClock
import com.responsly.sdk.internal.FakePlatform
import com.responsly.sdk.internal.FakeScheduler
import com.responsly.sdk.internal.InMemoryStorage
import com.responsly.sdk.internal.Logger
import com.responsly.sdk.internal.RecordingListener
import com.responsly.sdk.internal.RecordingLogSink
import com.responsly.sdk.internal.config.PresentationSize
import com.responsly.sdk.internal.fieldNames
import com.responsly.sdk.internal.net.ConfigResponse
import com.responsly.sdk.internal.net.PostResult
import com.responsly.sdk.internal.state.StateStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SDK's behaviour end to end, with a fake clock, network, storage and container. */
class ResponslyCoreTest {
    private val storage = InMemoryStorage()
    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val logs = RecordingLogSink()
    private val logger = Logger(logs)
    private val listeners = ListenerHub(scheduler, logger)
    private val listener = RecordingListener().also(listeners::add)
    private var platform = FakePlatform(storage)
    private var core = ResponslyCore(scheduler, clock, logger, listeners, "1.0.0")

    private val api get() = platform.api
    private val presenter get() = platform.presenter

    private fun survey(
        id: String,
        triggers: String = """[{"type":"event","name":"e"}]""",
        extra: String = """"frequency":{"mode":"always"}""",
    ) = """{"id":"$id","name":"Survey $id","url":"https://acme.responsly.com/f/$id","triggers":$triggers,$extra}"""

    private fun config(vararg surveys: String, ttl: Int = 600, cooldown: Int = 0) =
        """{"version":1,"ttl":$ttl,"settings":{"global_cooldown_seconds":$cooldown},"surveys":[${surveys.joinToString(",")}]}"""

    private fun initialize(
        body: String? = config(survey("S1")),
        key: String = "rsk_test",
        options: ResponslyOptions = ResponslyOptions(logLevel = LogLevel.DEBUG),
    ) {
        if (body != null) api.configResponse = ConfigResponse.Ok(body, "\"v1\"")
        core.initialize(key, options, platform)
        scheduler.drain()
    }

    /** A new process on the same device: same storage, everything else fresh. */
    private fun relaunch() {
        platform = FakePlatform(storage)
        core = ResponslyCore(scheduler, clock, logger, listeners, "1.0.0")
        listener.log.clear()
    }

    private fun track(event: String = "e", properties: Map<String, Any?> = emptyMap()) {
        core.track(event, properties)
        scheduler.drain()
    }

    private fun show(id: String, options: ShowOptions = ShowOptions()) {
        core.showSurvey(id, options)
        scheduler.drain()
    }

    private fun post(type: String, payload: String = "{}") {
        presenter.post(type, payload)
        scheduler.drain()
    }

    private fun stored() = StateStore(storage).apply { load() }

    private fun contextOf(script: String) =
        JSONObject(script.substringAfter("window.__RESPONSLY_SDK__=").removeSuffix(";})();"))

    // region Showing a survey

    @Test
    fun anEventTriggerLoadsTheSurveyHiddenAndShowsItOnReady() {
        initialize()
        core.identify("u_123", mapOf("plan" to "pro"))
        core.setHiddenFields(mapOf("order_id" to "A-77"))
        track()

        assertEquals(listOf("present:S1"), presenter.calls)
        val request = presenter.request!!
        assertEquals("https://acme.responsly.com/f/S1?sdk=1", request.url)
        assertEquals("https://acme.responsly.com/f/S1", request.surveyUrl)
        assertEquals("https://acme.responsly.com", request.origin)
        assertEquals(" ResponslySDK/1.0.0 (android)", request.userAgentSuffix)
        assertEquals(PresentationStyle.BOTTOM_SHEET, request.style)
        assertEquals(PresentationSize.MEDIUM, request.size)
        assertTrue(request.dismissible)
        val context = contextOf(request.contextScript)
        assertEquals("android", context.getString("platform"))
        assertEquals("com.acme.app", context.getString("app_id"))
        assertEquals("en-US", context.getString("locale"))
        assertEquals("S1", context.getString("survey_id"))
        assertEquals("event", context.getJSONObject("trigger").getString("type"))
        assertEquals("e", context.getJSONObject("trigger").getString("name"))
        assertEquals("u_123", context.getJSONObject("user").getString("id"))
        assertEquals("pro", context.getJSONObject("user").getJSONObject("traits").getString("plan"))
        assertEquals("A-77", context.getJSONObject("hidden").getString("order_id"))

        // Nothing is recorded while the page is loading.
        assertTrue(listener.log.isEmpty())
        assertNull(stored().state.surveys["S1"])

        post("ready", """{"survey_id":"S1","title":"NPS","background_color":"#ffffff","page_count":2}""")
        assertEquals(listOf("present:S1", "reveal"), presenter.calls)
        assertEquals(listOf("shown:S1:event"), listener.log)
        assertEquals("Survey S1", listener.shown.single().survey.name)
        assertEquals("e", listener.shown.single().trigger.name)
        val state = stored().state
        assertEquals(1, state.surveys.getValue("S1").displayCount)
        assertEquals(clock.time, state.surveys.getValue("S1").lastDisplayedAt)
        assertEquals(clock.time, state.lastAutoDisplayAt)

        core.onAppBackground()
        scheduler.drain()
        assertEquals(listOf("displayed:S1"), api.postedEvents())
    }

    @Test
    fun answersCompletionAndAutoClose() {
        initialize()
        track()
        post("ready")
        post("page_changed", """{"index":1,"count":2}""")
        post("resize", """{"height":480}""")
        post("something_new", """{"x":1}""")
        post("question_answered", """{"question_id":"q1","type":"nps","title":"How likely?","value":9}""")
        post(
            "completed",
            """{"response_id":"r_1","answers":[
               {"question_id":"q1","type":"nps","title":"How likely?","value":9},
               {"question_id":"q2","type":"multiple_choice","title":"Why?","value":["a",{"other":"text"}]},
               {"question_id":"q3","type":"text","title":"More","value":null}]}""",
        )

        assertEquals(listOf("shown:S1:event", "answered:S1:q1", "completed:S1"), listener.log)
        val answered = listener.answered.single()
        assertEquals("nps", answered.type)
        assertEquals("How likely?", answered.title)
        assertEquals(9L, answered.value)
        val completed = listener.completed.single()
        assertEquals("r_1", completed.responseId)
        assertEquals(listOf("q1", "q2", "q3"), completed.answers.map { it.questionId })
        assertEquals(listOf("a", mapOf("other" to "text")), completed.answers[1].value)
        assertNull(completed.answers[2].value)
        assertEquals(clock.time, stored().state.surveys.getValue("S1").completedAt)

        // From now on the survey can be closed by hand, and closes by itself after 3 seconds.
        assertEquals(listOf("present:S1", "reveal", "dismissible:true"), presenter.calls)
        scheduler.advance(2_999)
        assertEquals(3, presenter.calls.size)
        scheduler.advance(1)
        assertEquals("dismiss:animated", presenter.calls.last())

        // A completed survey that closes is not a dismissal.
        assertEquals(3, listener.log.size)
        assertEquals(0, stored().state.surveys.getValue("S1").dismissedCount)
        core.onAppBackground()
        scheduler.drain()
        assertEquals(listOf("displayed:S1", "completed:S1"), api.postedEvents())
    }

    @Test
    fun autoCloseZeroKeepsACompletedSurveyOpenUntilItIsClosed() {
        initialize(config(survey("S1", extra = """"frequency":{"mode":"always"},"presentation":{"dismissible":false,"auto_close_seconds":0}""")))
        track()
        assertFalse(presenter.request!!.dismissible)
        post("ready")
        post("completed", """{"response_id":"r_1","answers":[]}""")
        assertEquals("dismissible:true", presenter.calls.last())
        scheduler.advance(600_000)
        assertEquals("dismissible:true", presenter.calls.last())

        presenter.events!!.onUserClosed(presenter.token)
        scheduler.drain()
        assertEquals("dismiss:animated", presenter.calls.last())
        assertEquals(listOf("shown:S1:event", "completed:S1"), listener.log)
    }

    @Test
    fun closeRequestedAndNavigationAwayCloseACompletedSurveyEarly() {
        initialize()
        track()
        post("ready")
        // Before completion a link that leaves the survey does not close it.
        presenter.events!!.onNavigatedAway(presenter.token)
        scheduler.drain()
        assertEquals(listOf("present:S1", "reveal"), presenter.calls)

        post("completed", """{"response_id":"r_1"}""")
        presenter.events!!.onNavigatedAway(presenter.token)
        scheduler.drain()
        assertEquals("dismiss:animated", presenter.calls.last())
        // The auto-close timer is gone with the survey.
        val calls = presenter.calls.size
        scheduler.advance(10_000)
        assertEquals(calls, presenter.calls.size)

        track()
        post("ready")
        post("completed", """{"response_id":"r_2"}""")
        post("close_requested", """{"reason":"completed"}""")
        assertEquals("dismiss:animated", presenter.calls.last())
        assertFalse(listener.log.any { it.startsWith("dismissed") })
    }

    @Test
    fun closingBeforeCompletionIsADismissal() {
        initialize()
        track()
        post("ready")
        presenter.events!!.onUserClosed(presenter.token)
        scheduler.drain()

        assertEquals("dismiss:animated", presenter.calls.last())
        assertEquals(listOf("shown:S1:event", "dismissed:S1"), listener.log)
        assertEquals(1, stored().state.surveys.getValue("S1").dismissedCount)
        assertNull(stored().state.surveys.getValue("S1").completedAt)
        core.onAppBackground()
        scheduler.drain()
        assertEquals(listOf("displayed:S1", "dismissed:S1"), api.postedEvents())

        // The page asking to close and closeSurvey() count the same way.
        track()
        post("ready")
        post("close_requested", """{"reason":"user"}""")
        track()
        post("ready")
        core.closeSurvey()
        scheduler.drain()
        assertEquals(3, stored().state.surveys.getValue("S1").dismissedCount)
        assertEquals(3, listener.log.count { it == "dismissed:S1" })
    }

    @Test
    fun aDestroyedActivityDismissesTheVisibleSurvey() {
        initialize()
        track()
        post("ready")
        presenter.events!!.onHostDestroyed(presenter.token)
        scheduler.drain()
        assertEquals("dismiss", presenter.calls.last())
        assertEquals("dismissed:S1", listener.log.last())
        assertEquals(1, stored().state.surveys.getValue("S1").dismissedCount)
        // Another survey can be shown again.
        track()
        assertEquals("present:S1", presenter.calls.last())
    }

    // endregion

    // region Loading failures

    @Test
    fun aSurveyThatIsNotReadyInFifteenSecondsIsNotShownAndChangesNothing() {
        initialize()
        track()
        scheduler.advance(14_999)
        assertTrue(listener.log.isEmpty())
        scheduler.advance(1)
        assertEquals(listOf("not_shown:S1:load_failed"), listener.log)
        assertEquals("dismiss", presenter.calls.last())
        assertNull(stored().state.surveys["S1"])
        assertTrue(stored().telemetry.isEmpty())

        // A late ready from the abandoned page is ignored.
        post("ready")
        assertEquals(1, listener.log.size)
        assertFalse(presenter.calls.contains("reveal"))
    }

    @Test
    fun aLoadErrorIsLoadFailed() {
        initialize()
        track()
        presenter.events!!.onFailure(presenter.token, "load_error", "-2 net::ERR_NAME_NOT_RESOLVED")
        scheduler.drain()
        assertEquals(listOf("not_shown:S1:load_failed"), listener.log)
        assertEquals("dismiss", presenter.calls.last())
        assertNull(stored().state.surveys["S1"])
    }

    @Test
    fun anErrorMessageBeforeReadyIsSurveyUnavailableAndAfterReadyASurveyError() {
        initialize()
        track()
        post("error", """{"code":"survey_closed","message":"This survey is closed"}""")
        assertEquals(listOf("not_shown:S1:survey_unavailable"), listener.log)
        assertEquals("dismiss", presenter.calls.last())
        assertNull(stored().state.surveys["S1"])

        track()
        post("ready")
        post("error", """{"code":"submit_failed","message":"Try again"}""")
        assertEquals("error:S1:submit_failed", listener.log.last())
        // The survey stays open.
        assertEquals("reveal", presenter.calls.last())
    }

    @Test
    fun aPageThatBreaksWhileVisibleIsReportedAndClosed() {
        initialize()
        track()
        post("ready")
        presenter.events!!.onFailure(presenter.token, "webview_process_gone", "The WebView process crashed")
        scheduler.drain()
        assertEquals(listOf("shown:S1:event", "error:S1:webview_process_gone", "dismissed:S1"), listener.log)
        assertEquals("dismiss:animated", presenter.calls.last())
    }

    @Test
    fun aSurveyUrlThatIsNotHttpIsLoadFailed() {
        initialize("""{"surveys":[{"id":"S1","url":"javascript:alert(1)","triggers":[{"type":"event","name":"e"}]}]}""")
        track()
        assertEquals(listOf("not_shown:S1:load_failed"), listener.log)
        assertTrue(presenter.calls.isEmpty())
    }

    @Test
    fun aHiddenContainerThatLosesItsActivityIsLoadedAgain() {
        initialize()
        track()
        val first = presenter.token
        presenter.events!!.onHostLost(first)
        scheduler.drain()
        assertEquals(listOf("present:S1", "present:S1"), presenter.calls)
        assertNotEquals(first, presenter.token)

        // Callbacks of the abandoned attempt are ignored.
        presenter.events!!.onFailure(first, "load_error", "stale")
        scheduler.drain()
        assertTrue(listener.log.isEmpty())

        // The fifteen seconds cover both attempts.
        scheduler.advance(15_000)
        assertEquals(listOf("not_shown:S1:load_failed"), listener.log)
    }

    // endregion

    // region Targeting behaviour around a selection

    @Test
    fun theSelectedSurveyIsShownAfterItsDelayIfItIsStillEligible() {
        initialize(config(survey("S1", extra = """"frequency":{"mode":"always"},"delay_seconds":5""")))
        track()
        assertTrue(presenter.calls.isEmpty())
        scheduler.advance(4_999)
        assertTrue(presenter.calls.isEmpty())
        scheduler.advance(1)
        assertEquals(listOf("present:S1"), presenter.calls)

        // Paused during the delay: the second check fails.
        post("ready")
        core.closeSurvey()
        track()
        core.pause()
        scheduler.advance(5_000)
        assertEquals("not_shown:S1:paused", listener.log.last())
        assertEquals(1, presenter.calls.count { it == "present:S1" })
    }

    @Test
    fun aNewSelectionWhileOneIsWaitingOrVisibleIsNotShown() {
        initialize(config(survey("S1", extra = """"frequency":{"mode":"always"},"delay_seconds":5"""), survey("S2", triggers = """[{"type":"event","name":"other"}]""")))
        track()
        track("other")
        assertEquals(listOf("not_shown:S2:another_survey_visible"), listener.log)
        show("S2", ShowOptions(force = true))
        assertEquals("not_shown:S2:another_survey_visible", listener.log.last())

        scheduler.advance(5_000)
        post("ready")
        track("other")
        assertEquals("not_shown:S2:another_survey_visible", listener.log.last())
        assertEquals(1, presenter.calls.count { it.startsWith("present") })
    }

    @Test
    fun aScreenTriggerIsDroppedWhenTheScreenChangedDuringTheDelay() {
        initialize(config(survey("S1", triggers = """[{"type":"screen","name":"Checkout"}]""", extra = """"frequency":{"mode":"always"},"delay_seconds":3""")))
        core.screen("Checkout")
        core.screen("Home")
        scheduler.advance(3_000)
        assertEquals(listOf("not_shown:S1:screen_changed"), listener.log)
        assertTrue(presenter.calls.isEmpty())

        core.screen("Checkout")
        scheduler.advance(3_000)
        assertEquals(listOf("present:S1"), presenter.calls)
        assertEquals("screen", contextOf(presenter.request!!.contextScript).getJSONObject("trigger").getString("type"))
    }

    @Test
    fun everyOtherMatchedSurveyIsReportedWithItsReason() {
        initialize(
            config(
                survey("S1", extra = """"audience":{"platforms":["ios"]}"""),
                survey("S2"),
                survey("S3"),
                survey("S4", triggers = """[{"type":"event","name":"other"}]"""),
            ),
        )
        track()
        assertEquals(listOf("not_shown:S1:platform_mismatch", "not_shown:S3:another_survey_selected"), listener.log)
        assertEquals(listOf("present:S2"), presenter.calls)
    }

    @Test
    fun eventCountsAndPropertiesDriveEventTriggers() {
        initialize(
            config(survey("S1", triggers = """[{"type":"event","name":"add","min_count":2,"conditions":[{"property":"value","op":"gte","value":100}]}]""")),
        )
        track("add", mapOf("value" to 150.0))
        track("add", mapOf("value" to 50.0))
        assertTrue(presenter.calls.isEmpty())
        track("add", mapOf("value" to 100.0))
        assertEquals(listOf("present:S1"), presenter.calls)
        assertEquals(3, stored().state.eventCounts["add"])
    }

    @Test
    fun pauseStopsTargetingButNotShowSurvey() {
        initialize()
        core.pause()
        track()
        assertEquals(listOf("not_shown:S1:paused"), listener.log)
        show("S1")
        assertEquals(listOf("present:S1"), presenter.calls)
        core.closeSurvey()
        core.resume()
        track()
        assertEquals(2, presenter.calls.count { it == "present:S1" })
    }

    // endregion

    // region showSurvey

    @Test
    fun showSurveyAppliesItsOptions() {
        initialize(config(survey("S1", triggers = "[]", extra = """"delay_seconds":30,"presentation":{"style":"dialog","size":"large"}""")))
        core.setHiddenFields(mapOf("a" to "global", "b" to "global"))
        show("S1", ShowOptions(hiddenFields = mapOf("b" to "local", "c" to "local"), locale = "pl", presentation = PresentationStyle.FULLSCREEN))

        // No delay for a survey that was asked for.
        val request = presenter.request!!
        assertEquals("https://acme.responsly.com/f/S1?sdk=1&lang=pl", request.url)
        assertEquals(PresentationStyle.FULLSCREEN, request.style)
        assertEquals(PresentationSize.LARGE, request.size)
        val context = contextOf(request.contextScript)
        assertEquals("pl", context.getString("locale"))
        assertEquals("manual", context.getJSONObject("trigger").getString("type"))
        assertFalse(context.getJSONObject("trigger").has("name"))
        assertEquals("fullscreen", context.getJSONObject("presentation").getString("style"))
        assertTrue(context.isNull("user"))
        val hidden = context.getJSONObject("hidden")
        assertEquals("global", hidden.getString("a"))
        assertEquals("local", hidden.getString("b"))
        assertEquals("local", hidden.getString("c"))

        post("ready")
        assertEquals(listOf("shown:S1:manual"), listener.log)
        // A manual display does not start the global cooldown.
        assertNull(stored().state.lastAutoDisplayAt)
        assertEquals(1, stored().state.surveys.getValue("S1").displayCount)
    }

    @Test
    fun theLanguageGoesIntoTheUrlOnlyWhenItWasSetExplicitly() {
        initialize()
        platform.locale = "de-DE"
        show("S1")
        assertEquals("https://acme.responsly.com/f/S1?sdk=1", presenter.request!!.url)
        assertEquals("de-DE", contextOf(presenter.request!!.contextScript).getString("locale"))
        core.closeSurvey()

        core.setLocale("pt-BR")
        show("S1")
        assertEquals("https://acme.responsly.com/f/S1?sdk=1&lang=pt-BR", presenter.request!!.url)
        assertEquals("pt-BR", contextOf(presenter.request!!.contextScript).getString("locale"))
        core.closeSurvey()

        core.setLocale(null)
        show("S1")
        assertEquals("https://acme.responsly.com/f/S1?sdk=1", presenter.request!!.url)
    }

    @Test
    fun anAnonymousSurveyGetsNeitherUserNorHiddenFields() {
        initialize(config(survey("S1", extra = """"anonymous":true""")))
        core.identify("u_123", mapOf("plan" to "pro"))
        core.setHiddenFields(mapOf("order_id" to "A-77"))
        show("S1", ShowOptions(hiddenFields = mapOf("x" to "y")))
        val context = contextOf(presenter.request!!.contextScript)
        assertTrue(context.isNull("user"))
        assertEquals(0, context.getJSONObject("hidden").length())
        assertFalse(presenter.request!!.contextScript.contains("u_123"))
        assertFalse(presenter.request!!.url.contains("u_123"))
    }

    @Test
    fun showSurveyReportsWhyItCannotShow() {
        show("S1")
        assertEquals(listOf("not_shown:S1:not_initialized"), listener.log)
        listener.log.clear()

        initialize(config(survey("S1", triggers = "[]", extra = """"audience":{"platforms":["ios"]}""")))
        show("nope")
        show("S1")
        assertEquals(listOf("not_shown:nope:survey_not_found", "not_shown:S1:platform_mismatch"), listener.log)
        assertTrue(presenter.calls.isEmpty())

        show("S1", ShowOptions(force = true))
        assertEquals(listOf("present:S1"), presenter.calls)
    }

    @Test
    fun showSurveyWithoutAConfigurationIsConfigUnavailable() {
        api.configResponse = ConfigResponse.Failed("offline")
        initialize(body = null)
        show("S1")
        assertEquals(listOf("not_shown:S1:config_unavailable"), listener.log)
    }

    @Test
    fun showSurveyWaitsForAConfigurationThatIsOnItsWay() {
        scheduler.holdIo = true
        initialize()
        show("S1")
        assertTrue(listener.log.isEmpty())
        scheduler.runIo()
        assertEquals(listOf("present:S1"), presenter.calls)

        // ...and gives up when the download fails.
        relaunch()
        storage.values.clear()
        api.configResponse = ConfigResponse.Failed("offline")
        core.initialize("rsk_test", ResponslyOptions(), platform)
        show("S1")
        assertTrue(listener.log.isEmpty())
        scheduler.runIo()
        assertEquals(listOf("not_shown:S1:config_unavailable"), listener.log)
    }

    // endregion

    // region Display guard

    private class Guard(val answer: ((Decision) -> Unit)?) : DisplayGuard {
        val asked = ArrayList<Survey>()
        var decision: Decision? = null

        override fun shouldShow(survey: Survey, decision: Decision) {
            asked.add(survey)
            this.decision = decision
            answer?.invoke(decision)
        }
    }

    @Test
    fun theGuardIsAskedWhenThePageIsReadyAndCanAllow() {
        initialize()
        val guard = Guard { it.allow() }
        core.setDisplayGuard(guard)
        track()
        assertTrue(guard.asked.isEmpty())
        post("ready")
        assertEquals("S1", guard.asked.single().id)
        assertEquals("Survey S1", guard.asked.single().name)
        assertEquals(listOf("present:S1", "reveal"), presenter.calls)
        assertEquals(listOf("shown:S1:event"), listener.log)
    }

    @Test
    fun aVetoMeansNotShownAndNothingRecorded() {
        initialize()
        core.setDisplayGuard(Guard { it.veto() })
        track()
        post("ready")
        assertEquals(listOf("present:S1", "dismiss"), presenter.calls)
        assertEquals(listOf("not_shown:S1:vetoed"), listener.log)
        assertNull(stored().state.surveys["S1"])
        assertTrue(stored().telemetry.isEmpty())

        // Without the guard the next one shows.
        core.setDisplayGuard(null)
        track()
        post("ready")
        assertEquals("reveal", presenter.calls.last())
    }

    @Test
    fun noAnswerWithinTwoSecondsCountsAsAllowAndTheFirstAnswerWins() {
        initialize()
        val guard = Guard(null)
        core.setDisplayGuard(guard)
        track()
        post("ready")
        scheduler.advance(1_999)
        assertFalse(presenter.calls.contains("reveal"))
        scheduler.advance(1)
        assertEquals("reveal", presenter.calls.last())

        // Too late: the survey is already visible.
        guard.decision!!.veto()
        scheduler.drain()
        assertEquals(listOf("shown:S1:event"), listener.log)

        // First call wins.
        core.closeSurvey()
        track()
        post("ready")
        guard.decision!!.veto()
        guard.decision!!.allow()
        scheduler.drain()
        assertEquals("not_shown:S1:vetoed", listener.log.last())
    }

    @Test
    fun theGuardIsNotAskedForForce() {
        initialize()
        val guard = Guard { it.veto() }
        core.setDisplayGuard(guard)
        show("S1", ShowOptions(force = true))
        post("ready")
        assertTrue(guard.asked.isEmpty())
        assertEquals("reveal", presenter.calls.last())

        // It is asked for a showSurvey without force.
        core.closeSurvey()
        show("S1")
        post("ready")
        assertEquals(1, guard.asked.size)
        assertEquals("not_shown:S1:vetoed", listener.log.last())
    }

    @Test
    fun aGuardThatThrowsDoesNotBlockTheSurvey() {
        initialize()
        core.setDisplayGuard { _, _ -> throw IllegalStateException("boom") }
        track()
        post("ready")
        assertEquals("reveal", presenter.calls.last())
    }

    // endregion

    // region Before initialize, before the configuration

    @Test
    fun callsBeforeInitializeAreRememberedAndApplied() {
        core.identify("u_1", mapOf("plan" to "pro"))
        core.setAttributes(mapOf("seats" to 5.0))
        core.setLocale("pl")
        core.setHiddenFields(mapOf("k" to "v"))
        core.track("e", emptyMap())
        core.screen("Home")
        scheduler.drain()
        assertTrue(storage.values.isEmpty())

        initialize(config(survey("S0", triggers = """[{"type":"app_launch"}]""", extra = """"frequency":{"mode":"always"},"audience":{"identified":true}"""), survey("S1")))
        val state = stored().state
        assertEquals("u_1", state.userId)
        assertEquals(mapOf("plan" to "pro", "seats" to 5.0), state.traits)
        assertEquals("pl", state.locale)
        assertEquals(mapOf("k" to "v"), state.hiddenFields)
        assertEquals(1, state.eventCounts["e"])

        // app_launch is evaluated first (and already sees the remembered identify), then the queued track.
        assertEquals(listOf("present:S0"), presenter.calls)
        assertEquals(listOf("not_shown:S1:another_survey_visible"), listener.log)
    }

    @Test
    fun triggersBeforeTheConfigurationAreQueuedAndEvaluatedInOrder() {
        scheduler.holdIo = true
        initialize(config(survey("S1", extra = """"frequency":{"mode":"once"}"""), survey("S2", triggers = """[{"type":"event","name":"second"}]""")))
        track("second")
        track("e")
        assertTrue(presenter.calls.isEmpty())
        assertTrue(listener.log.isEmpty())
        // Counted at call time.
        assertEquals(1, stored().state.eventCounts["e"])

        scheduler.runIo()
        assertEquals(listOf("present:S2"), presenter.calls)
        assertEquals(listOf("not_shown:S1:another_survey_visible"), listener.log)
    }

    @Test
    fun atMostFiftyCallsAreQueuedOldestDropped() {
        scheduler.holdIo = true
        initialize(
            config(
                survey("FIRST", triggers = """[{"type":"event","name":"first"}]"""),
                survey("LAST", triggers = """[{"type":"event","name":"last"}]"""),
            ),
        )
        track("first")
        repeat(49) { track("filler") }
        track("last")
        scheduler.runIo()
        assertEquals(listOf("present:LAST"), presenter.calls)
        assertEquals(49, stored().state.eventCounts["filler"])
    }

    @Test
    fun appLaunchIsEvaluatedOncePerProcessWhenTheConfigurationFirstBecomesAvailable() {
        val body = config(survey("S0", triggers = """[{"type":"app_launch"}]"""))
        initialize(body)
        assertEquals(listOf("present:S0"), presenter.calls)
        core.closeSurvey()
        scheduler.drain()

        // A refreshed configuration is not a launch.
        scheduler.advance(601_000)
        core.onAppForeground()
        scheduler.drain()
        assertEquals(2, api.configFetches)
        assertEquals(1, presenter.calls.count { it == "present:S0" })

        // The next process evaluates it again, straight from the cache.
        clock.time += 1_000
        relaunch()
        scheduler.holdIo = true
        initialize(body)
        assertEquals(listOf("present:S0"), presenter.calls)
    }

    // endregion

    // region Configuration

    @Test
    fun aFreshCachedConfigurationIsUsedWithoutANetworkCall() {
        initialize()
        assertEquals(1, api.configFetches)
        assertNull(api.etags.single())

        clock.time += 599_000
        relaunch()
        initialize(body = null)
        assertEquals(0, api.configFetches)
        track()
        assertEquals(listOf("present:S1"), presenter.calls)
    }

    @Test
    fun aStaleCacheIsRefreshedWithItsEtagAndNotModifiedRestartsTheTtl() {
        initialize()
        clock.time += 600_000
        relaunch()
        api.configResponse = ConfigResponse.NotModified
        initialize(body = null)
        assertEquals(listOf<String?>("\"v1\""), api.etags)
        assertEquals(clock.time, stored().configFetchedAt)

        // Fresh again: the next foreground does not fetch.
        clock.time += 599_000
        core.onAppForeground()
        scheduler.drain()
        assertEquals(1, api.configFetches)
        clock.time += 1_000
        core.onAppForeground()
        scheduler.drain()
        assertEquals(2, api.configFetches)
    }

    @Test
    fun aNewConfigurationReplacesTheOldOne() {
        initialize()
        api.configResponse = ConfigResponse.Ok(config(survey("S9")), "\"v2\"")
        scheduler.advance(600_000)
        core.onAppForeground()
        scheduler.drain()
        assertEquals("\"v2\"", stored().configEtag)
        track()
        assertEquals(listOf("present:S9"), presenter.calls)
    }

    @Test
    fun aFailedFetchKeepsTheCacheAndIsRetriedOnTheNextForeground() {
        initialize()
        api.configResponse = ConfigResponse.Failed("HTTP 500")
        scheduler.advance(600_000)
        core.onAppForeground()
        scheduler.drain()
        assertEquals(2, api.configFetches)
        track()
        assertEquals(listOf("present:S1"), presenter.calls)

        core.onAppForeground()
        scheduler.drain()
        assertEquals(3, api.configFetches)
    }

    @Test
    fun anUnreadableBodyKeepsTheCache() {
        initialize()
        api.configResponse = ConfigResponse.Ok("<html>", null)
        scheduler.advance(600_000)
        core.onAppForeground()
        scheduler.drain()
        assertTrue(stored().configBody!!.contains("S1"))
        assertEquals("\"v1\"", stored().configEtag)
    }

    @Test
    fun anInvalidKeyDropsTheCacheAndIsNotRetriedInThisProcess() {
        initialize()
        api.configResponse = ConfigResponse.InvalidKey
        scheduler.advance(600_000)
        core.onAppForeground()
        scheduler.drain()
        assertNull(stored().configBody)
        assertNull(stored().configEtag)

        track()
        show("S1")
        assertTrue(presenter.calls.isEmpty())
        assertEquals(listOf("not_shown:S1:config_unavailable"), listener.log)

        scheduler.advance(3_600_000)
        core.onAppForeground()
        scheduler.drain()
        assertEquals(2, api.configFetches)

        // The next launch tries again.
        relaunch()
        initialize()
        assertEquals(1, api.configFetches)
    }

    @Test
    fun theApiHostOptionIsUsed() {
        initialize(options = ResponslyOptions(apiHost = "http://10.0.2.2:8000"))
        assertEquals(listOf("http://10.0.2.2:8000"), platform.apiHosts)
    }

    // endregion

    // region Tracking switch, reset, key change

    @Test
    fun disabledTrackingMeansNoNetworkNoTargetingAndNothingShown() {
        core.setTrackingEnabled(false)
        initialize()
        assertEquals(0, api.configFetches)
        show("S1")
        assertEquals(listOf("not_shown:S1:disabled"), listener.log)

        // Persisted across launches.
        relaunch()
        initialize()
        assertEquals(0, api.configFetches)

        core.setTrackingEnabled(true)
        scheduler.drain()
        assertEquals(1, api.configFetches)
        track()
        assertEquals(listOf("present:S1"), presenter.calls)
    }

    @Test
    fun whatHappensWhileTrackingIsDisabledIsNotEvaluatedLater() {
        core.setTrackingEnabled(false)
        initialize()
        track()
        core.screen("Checkout")
        core.setTrackingEnabled(true)
        scheduler.drain()
        assertEquals(1, api.configFetches)
        assertTrue(presenter.calls.isEmpty())
        assertTrue(listener.log.isEmpty())
        // The event was still counted.
        assertEquals(1, stored().state.eventCounts["e"])
    }

    @Test
    fun disablingTrackingClosesTheSurveyAndDropsTelemetry() {
        initialize()
        track()
        post("ready")
        core.setTrackingEnabled(false)
        scheduler.drain()
        assertEquals("dismiss:animated", presenter.calls.last())
        assertEquals("dismissed:S1", listener.log.last())
        assertTrue(stored().telemetry.isEmpty())

        track()
        assertEquals("not_shown:S1:disabled", listener.log.last())
        core.onAppBackground()
        scheduler.advance(120_000)
        assertTrue(api.posts.isEmpty())
    }

    @Test
    fun aSurveyThatIsNotVisibleYetIsCancelledSilentlyWhenTrackingIsDisabled() {
        // Waiting for its delay.
        initialize(config(survey("S1", extra = """"frequency":{"mode":"always"},"delay_seconds":5""")))
        track()
        core.setTrackingEnabled(false)
        scheduler.drain()
        scheduler.advance(10_000)
        assertTrue(presenter.calls.isEmpty())
        assertTrue(listener.log.isEmpty())

        // Loading in the hidden container.
        core.setTrackingEnabled(true)
        track()
        scheduler.advance(5_000)
        assertEquals(listOf("present:S1"), presenter.calls)
        core.setTrackingEnabled(false)
        scheduler.drain()
        assertEquals(listOf("present:S1", "dismiss"), presenter.calls)
        scheduler.advance(60_000)

        // Never shown: no callback, no state change, no telemetry.
        assertTrue(listener.log.isEmpty())
        assertNull(stored().state.surveys["S1"])
        assertTrue(stored().telemetry.isEmpty())
        assertTrue(api.posts.isEmpty())
    }

    @Test
    fun resetForgetsEverythingButTheInstallIdAndTheTrackingSwitch() {
        initialize(
            config(
                survey("S0", triggers = """[{"type":"app_launch"}]"""),
                survey("S1"),
                """{"id":"S2","url":"http://10.0.2.2:8080/f/S2"}""",
            ),
        )
        core.closeSurvey()
        core.identify("u_1", mapOf("plan" to "pro"))
        core.setLocale("pl")
        core.setHiddenFields(mapOf("k" to "v"))
        track()
        post("ready")
        val installId = stored().state.installId
        listener.log.clear()
        presenter.calls.clear()

        core.reset()
        scheduler.drain()

        // The visible survey is closed...
        assertEquals(listOf("dismiss"), presenter.calls)
        assertEquals(listOf("dismissed:S1"), listener.log)
        // ...the website data of the survey origins is cleared...
        assertEquals(listOf(listOf("https://acme.responsly.com", "http://10.0.2.2:8080")), presenter.cleared)
        // ...and the state is gone, except for what must stay.
        val after = stored()
        assertEquals(installId, after.state.installId)
        assertTrue(after.state.trackingEnabled)
        assertNull(after.state.userId)
        assertTrue(after.state.traits.isEmpty())
        assertNull(after.state.locale)
        assertTrue(after.state.hiddenFields.isEmpty())
        assertTrue(after.state.eventCounts.isEmpty())
        assertTrue(after.state.surveys.isEmpty())
        assertTrue(after.telemetry.isEmpty())

        // The SDK stays initialised: the configuration was downloaded again (without an ETag)
        // and targeting works. app_launch is not evaluated a second time.
        assertEquals(listOf<String?>(null, null), api.etags)
        assertFalse(presenter.calls.contains("present:S0"))
        track()
        assertEquals("present:S1", presenter.calls.last())
        assertTrue(contextOf(presenter.request!!.contextScript).isNull("user"))
    }

    @Test
    fun resetKeepsTheTrackingSwitchOff() {
        initialize()
        core.setTrackingEnabled(false)
        core.reset()
        scheduler.drain()
        assertFalse(stored().state.trackingEnabled)
        assertEquals(1, api.configFetches)
    }

    @Test
    fun initializeIsIdempotentForTheSameKey() {
        initialize()
        core.identify("u_1", emptyMap())
        initialize()
        initialize()
        assertEquals(1, api.configFetches)
        assertEquals("u_1", stored().state.userId)
    }

    @Test
    fun aDifferentKeyResetsTheStateFirst() {
        initialize(key = "rsk_one")
        core.identify("u_1", mapOf("plan" to "pro"))
        track()
        post("ready")
        val installId = stored().state.installId

        // In the same process.
        initialize(config(survey("S2")), key = "rsk_two")
        assertEquals("rsk_two", stored().sdkKey)
        assertEquals(installId, stored().state.installId)
        assertNull(stored().state.userId)
        assertTrue(stored().state.surveys.isEmpty())
        assertEquals(2, api.configFetches)
        assertNull(api.etags.last())
        track()
        assertEquals("present:S2", presenter.calls.last())

        // And at the next launch.
        core.identify("u_2", emptyMap())
        scheduler.drain()
        relaunch()
        core.identify("u_3", emptyMap())
        initialize(key = "rsk_three")
        assertEquals("rsk_three", stored().sdkKey)
        assertEquals(installId, stored().state.installId)
        // Calls remembered before initialize are applied after the reset.
        assertEquals("u_3", stored().state.userId)
        assertNull(api.etags.single())
    }

    // region The same key with another API host

    private val hostA = "http://10.0.2.2:8000"
    private val hostB = "http://10.0.2.2:9000"

    private fun apiWith(vararg surveys: String, etag: String): FakeApi =
        FakeApi().apply { configResponse = ConfigResponse.Ok(config(*surveys), etag) }

    private fun initializeOn(host: String) = initialize(body = null, options = ResponslyOptions(apiHost = host))

    @Test
    fun theSameKeyAndHostStaysIdempotentWhateverTheTrailingSlash() {
        initialize(options = ResponslyOptions(apiHost = hostA))
        core.identify("u_1", emptyMap())
        initialize(options = ResponslyOptions(apiHost = "$hostA/"))
        initialize(options = ResponslyOptions(apiHost = " $hostA "))
        assertEquals(1, api.configFetches)
        assertEquals(listOf(hostA), platform.apiHosts)
        assertEquals(hostA, stored().configHost)
        assertEquals("u_1", stored().state.userId)
    }

    @Test
    fun theSameKeyWithAnotherApiHostSwitchesToItDropsTheCacheAndFetchesAgain() {
        val apiA = apiWith(survey("SA"), etag = "\"a1\"")
        val apiB = apiWith(survey("SB"), etag = "\"b1\"")
        platform.perHost[hostA] = apiA
        platform.perHost[hostB] = apiB

        initializeOn(hostA)
        core.identify("u_1", mapOf("plan" to "pro"))
        track()
        assertEquals(listOf("present:SA"), presenter.calls)
        post("ready")
        core.closeSurvey()
        scheduler.drain()
        val installId = stored().state.installId
        assertEquals(hostA, stored().configHost)
        assertEquals("\"a1\"", stored().configEtag)

        initializeOn(hostB)

        // The new host was asked, and not with the ETag of the old host's copy.
        assertEquals(listOf(hostA, hostB), platform.apiHosts)
        assertEquals(1, apiA.configFetches)
        assertEquals(listOf<String?>(null), apiB.etags)
        val after = stored()
        assertEquals(hostB, after.configHost)
        assertEquals("\"b1\"", after.configEtag)
        assertTrue(after.configBody!!.contains("SB"))
        assertFalse(after.configBody!!.contains("SA"))

        // Nothing else was reset.
        assertEquals("rsk_test", after.sdkKey)
        assertEquals(installId, after.state.installId)
        assertEquals("u_1", after.state.userId)
        assertEquals(mapOf<String, Any?>("plan" to "pro"), after.state.traits)
        assertEquals(1, after.state.eventCounts["e"])
        assertEquals(1, after.state.surveys.getValue("SA").displayCount)

        // Targeting runs on the new configuration, and telemetry goes to the new host.
        track()
        assertEquals("present:SB", presenter.calls.last())
        post("ready")
        core.onAppBackground()
        scheduler.drain()
        assertTrue(apiB.postedEvents().contains("displayed:SB"))

        // Initialising with the new host again changes nothing.
        initializeOn(hostB)
        assertEquals(1, apiB.configFetches)
    }

    @Test
    fun theOldHostsCacheIsNotUsedEvenWhenTheNewHostCannotBeReached() {
        val apiA = apiWith(survey("SA"), etag = "\"a1\"")
        val apiB = FakeApi().apply { configResponse = ConfigResponse.Failed("offline") }
        platform.perHost[hostA] = apiA
        platform.perHost[hostB] = apiB
        initializeOn(hostA)

        initializeOn(hostB)
        assertEquals(1, apiB.configFetches)
        assertNull(stored().configBody)
        assertNull(stored().configEtag)
        assertNull(stored().configHost)
        show("SA")
        assertEquals(listOf("not_shown:SA:config_unavailable"), listener.log)
        assertTrue(presenter.calls.isEmpty())

        // Going back to the first host downloads its configuration again, from scratch.
        initializeOn(hostA)
        assertEquals(listOf<String?>(null, null), apiA.etags)
        assertEquals(hostA, stored().configHost)
        show("SA")
        assertEquals(listOf("present:SA"), presenter.calls)
    }

    @Test
    fun atTheNextLaunchACacheFromAnotherHostIsNotUsed() {
        initialize(options = ResponslyOptions(apiHost = hostA))
        assertEquals(hostA, stored().configHost)

        clock.time += 1_000
        relaunch()
        api.configResponse = ConfigResponse.Failed("offline")
        initializeOn(hostB)
        // The copy was fresh, but it is another host's: fetched anyway, without its ETag.
        assertEquals(listOf<String?>(null), api.etags)
        assertNull(stored().configBody)
        show("S1")
        assertEquals(listOf("not_shown:S1:config_unavailable"), listener.log)
        assertTrue(presenter.calls.isEmpty())
    }

    @Test
    fun aCacheThatDoesNotSayWhichHostItCameFromIsNotUsed() {
        initialize()
        storage.values.remove(StateStore.KEY_CONFIG_HOST)

        relaunch()
        api.configResponse = ConfigResponse.Failed("offline")
        initialize(body = null)
        assertEquals(listOf<String?>(null), api.etags)
        assertNull(stored().configBody)
        track()
        assertTrue(presenter.calls.isEmpty())
    }

    @Test
    fun anAnswerStillOnItsWayFromTheOldHostIsIgnored() {
        val apiA = apiWith(survey("SA"), etag = "\"a1\"")
        val apiB = apiWith(survey("SB"), etag = "\"b1\"")
        platform.perHost[hostA] = apiA
        platform.perHost[hostB] = apiB
        scheduler.holdIo = true
        initializeOn(hostA)
        initializeOn(hostB)
        show("SB")
        scheduler.runIo()

        assertEquals(1, apiA.configFetches)
        assertEquals(1, apiB.configFetches)
        assertEquals(hostB, stored().configHost)
        assertTrue(stored().configBody!!.contains("SB"))
        assertFalse(stored().configBody!!.contains("SA"))
        // The showSurvey that was waiting for a configuration got the new host's.
        assertEquals(listOf("present:SB"), presenter.calls)
        assertTrue(listener.log.isEmpty())
    }

    @Test
    fun switchingTheHostCancelsASurveyThatIsNotVisibleYetAndLeavesAVisibleOneAlone() {
        platform.perHost[hostA] = apiWith(survey("SA", extra = """"frequency":{"mode":"always"},"delay_seconds":5"""), etag = "\"a1\"")
        platform.perHost[hostB] = apiWith(survey("SB"), etag = "\"b1\"")
        initializeOn(hostA)
        track()
        initializeOn(hostB)
        scheduler.advance(10_000)
        assertTrue(presenter.calls.isEmpty())
        assertTrue(listener.log.isEmpty())

        track()
        post("ready")
        assertEquals(listOf("present:SB", "reveal"), presenter.calls)
        initializeOn(hostA)
        assertEquals(listOf("present:SB", "reveal"), presenter.calls)
        assertEquals(listOf("shown:SB:event"), listener.log)

        // The respondent can still finish it.
        post("completed", """{"response_id":"r_1"}""")
        assertEquals("completed:SB", listener.log.last())
    }

    @Test
    fun aHostThatRejectedTheKeyDoesNotBlockAnotherHost() {
        platform.perHost[hostA] = FakeApi().apply { configResponse = ConfigResponse.InvalidKey }
        val apiB = apiWith(survey("SB"), etag = "\"b1\"")
        platform.perHost[hostB] = apiB
        initializeOn(hostA)
        show("SB")
        assertEquals(listOf("not_shown:SB:config_unavailable"), listener.log)

        initializeOn(hostB)
        assertEquals(1, apiB.configFetches)
        track()
        assertEquals(listOf("present:SB"), presenter.calls)
    }

    @Test
    fun appLaunchIsEvaluatedAgainstTheNewHostsConfiguration() {
        platform.perHost[hostA] = apiWith(survey("SA"), etag = "\"a1\"")
        platform.perHost[hostB] = apiWith(survey("SL", triggers = """[{"type":"app_launch"}]"""), etag = "\"b1\"")
        initializeOn(hostA)
        assertTrue(presenter.calls.isEmpty())
        initializeOn(hostB)
        assertEquals(listOf("present:SL"), presenter.calls)
    }

    // endregion

    @Test
    fun anEmptyKeyIsIgnored() {
        initialize(key = "  ")
        assertEquals(0, api.configFetches)
        show("S1")
        assertEquals(listOf("not_shown:S1:not_initialized"), listener.log)
    }

    // endregion

    // region Telemetry and logging

    @Test
    fun telemetryIsFlushedInTheBackgroundAndRetriedLater() {
        initialize()
        api.postResult = PostResult.RETRY
        track()
        post("ready")
        core.onAppBackground()
        scheduler.drain()
        assertEquals(1, api.posts.size)
        assertEquals(1, stored().telemetry.size)

        api.postResult = PostResult.SENT
        scheduler.advance(30_000)
        assertEquals(2, api.posts.size)
        assertTrue(stored().telemetry.isEmpty())
        // Only the type and the survey id leave the device.
        val body = JSONObject(api.posts.last())
        assertEquals(setOf("events"), body.fieldNames())
        val event = body.getJSONArray("events").getJSONObject(0)
        assertEquals(1, body.getJSONArray("events").length())
        assertEquals(setOf("type", "survey_id"), event.fieldNames())
        assertEquals("displayed", event.getString("type"))
        assertEquals("S1", event.getString("survey_id"))
    }

    @Test
    fun debugLoggingCoversDecisionsFetchesAndBridgeMessages() {
        initialize(config(survey("S1"), survey("S2")))
        track()
        post("ready")
        assertTrue(logs.lines.all { it.contains("[Responsly] ") })
        assertTrue(logs.lines.any { it.contains("config: fetching") })
        assertTrue(logs.lines.any { it.contains("config: fetched") })
        assertTrue(logs.lines.any { it.contains("survey S1 selected") })
        assertTrue(logs.lines.any { it.contains("survey S2 not shown: another_survey_selected") })
        assertTrue(logs.lines.any { it.contains("bridge: ready") })
    }

    @Test
    fun theDefaultLogLevelOnlyLogsErrors() {
        initialize(options = ResponslyOptions())
        track()
        post("ready")
        assertTrue(logs.lines.toString(), logs.lines.isEmpty())
    }

    @Test
    fun aListenerThatThrowsDoesNotStopTheOthers() {
        val second = RecordingListener()
        listeners.remove(listener)
        listeners.add(object : com.responsly.sdk.ResponslyListener {
            override fun onSurveyNotShown(event: com.responsly.sdk.SurveyNotShownEvent) = throw IllegalStateException("boom")
        })
        listeners.add(second)
        show("S1")
        assertEquals(listOf("not_shown:S1:not_initialized"), second.log)
    }

    // endregion
}
