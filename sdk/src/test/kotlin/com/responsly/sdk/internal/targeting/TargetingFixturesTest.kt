package com.responsly.sdk.internal.targeting

import com.responsly.sdk.internal.IsoTime
import com.responsly.sdk.internal.Values
import com.responsly.sdk.internal.config.ConfigParser
import com.responsly.sdk.internal.resourceText
import com.responsly.sdk.internal.state.SdkState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Runs every case of the shared conformance fixtures (`targeting-fixtures.json`, protocol
 * section 9) through the targeting engine, one test per case. The file is a verbatim copy of the
 * specification's and must never be edited here.
 */
@RunWith(Parameterized::class)
class TargetingFixturesTest(private val caseName: String, private val case: JSONObject) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val cases = JSONObject(resourceText("targeting-fixtures.json")).getJSONArray("cases")
            check(cases.length() > 0) { "the fixture file has no cases" }
            return (0 until cases.length()).map { index ->
                val case = cases.getJSONObject(index)
                arrayOf<Any>(case.getString("name"), case)
            }
        }
    }

    @Test
    fun passes() {
        assertTrue("\"$caseName\" checks at least one expectation", runCase(case) > 0)
    }

    /** Returns the number of steps with an expectation. */
    private fun runCase(case: JSONObject): Int {
        val name = case.getString("name")
        val config = ConfigParser.parse(case.getJSONObject("config"))
        val context = case.getJSONObject("context")
        val state = SdkState().apply { installId = context.getString("install_id") }
        var paused = false
        var visible = false
        var checked = 0

        val steps = case.getJSONArray("steps")
        for (index in 0 until steps.length()) {
            val step = steps.getJSONObject(index)
            val now = checkNotNull(IsoTime.parse(step.getString("at"))) { "bad time in \"$name\" step $index" }
            val action = step.getJSONObject("action")
            val targeting = TargetingContext(
                platform = context.getString("platform"),
                appId = context.getString("app_id"),
                deviceLocale = context.getString("locale"),
                paused = paused,
                surveyActive = visible,
            )
            var decision: TargetingDecision? = null
            when (val type = action.getString("type")) {
                "track" -> {
                    val event = action.getString("name")
                    val properties = Values.mapFromJson(action.optJSONObject("properties")).filterValues { it != null }
                    val call = TriggerCall.Event(event, properties, state.countEvent(event))
                    decision = TargetingEngine.onTrigger(config, state, targeting, call, now)
                }
                "screen" -> {
                    val call = TriggerCall.Screen(action.getString("name"))
                    decision = TargetingEngine.onTrigger(config, state, targeting, call, now)
                }
                "app_launch" -> decision = TargetingEngine.onTrigger(config, state, targeting, TriggerCall.AppLaunch, now)
                "show" -> decision = TargetingEngine.onShow(
                    config,
                    state,
                    targeting,
                    action.getString("survey"),
                    action.optBoolean("force", false),
                    now,
                )
                "displayed" -> state.markDisplayed(action.getString("survey"), now, action.optBoolean("auto", false))
                "completed" -> state.markCompleted(action.getString("survey"), now)
                "dismissed" -> state.markDismissed(action.getString("survey"))
                "identify" -> {
                    state.userId = action.getString("user_id")
                    state.mergeTraits(Values.mapFromJson(action.optJSONObject("traits")))
                }
                "set_attributes" -> state.mergeTraits(Values.mapFromJson(action.optJSONObject("traits")))
                "set_locale" -> state.locale = if (action.isNull("locale")) null else action.getString("locale")
                "pause" -> paused = true
                "resume" -> paused = false
                "set_tracking" -> state.trackingEnabled = action.getBoolean("enabled")
                "visible" -> visible = action.getBoolean("value")
                else -> throw AssertionError("unknown action \"$type\" in \"$name\" step $index")
            }

            val expect = step.optJSONObject("expect") ?: continue
            val label = "\"$name\" step $index (${action})"
            checkNotNull(decision) { "$label has an expectation but is not a trigger or show" }
            val expectedShow = if (expect.isNull("show")) null else expect.getString("show")
            assertEquals("$label: show", expectedShow, decision.selected?.id)
            expect.optJSONObject("not_shown")?.let { expected ->
                val expectedReasons = expected.keys().asSequence().associateWith { expected.getString(it) }
                val actualReasons = decision.notShown.mapValues { it.value.value }
                assertEquals("$label: not_shown", expectedReasons, actualReasons)
            }
            checked++
        }
        return checked
    }
}
