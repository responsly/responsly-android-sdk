package com.responsly.sdk.internal.targeting

import com.responsly.sdk.NotShownReason
import com.responsly.sdk.internal.config.ConfigParser
import com.responsly.sdk.internal.state.SdkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MalformedConditionsTest {
    private val context = TargetingContext(
        platform = "android",
        appId = "com.acme.app",
        deviceLocale = "en-US",
        paused = false,
        surveyActive = false,
    )
    private val state = SdkState().apply { installId = "test-install" }
    private val negativeOperators = listOf("not_exists", "neq", "not_contains")

    private fun config(audience: String = "{}", triggerConditions: String = "[]") =
        ConfigParser.parse(
            """{"surveys":[{"id":"S1","url":"https://acme.responsly.com/f/S1",
               "audience":$audience,"triggers":[{"type":"event","name":"purchase",
               "conditions":$triggerConditions}]}]}""",
        )!!

    @Test
    fun aNamelessAudienceConditionCannotAdmitASurvey() {
        for (op in negativeOperators) {
            val config = config(audience = """{"conditions":[{"op":"$op","value":"pro"}]}""")
            val decision = TargetingEngine.onShow(config, state, context, "S1", false, 0L)

            assertNull(op, decision.selected)
            assertEquals(op, mapOf("S1" to NotShownReason.AUDIENCE_MISMATCH), decision.notShown)
        }
    }

    @Test
    fun aNamelessEventConditionCannotMatchATrigger() {
        for (op in negativeOperators) {
            val config = config(triggerConditions = """[{"op":"$op","value":"pro"}]""")
            val decision = TargetingEngine.onTrigger(
                config,
                state,
                context,
                TriggerCall.Event("purchase", emptyMap(), 1),
                0L,
            )

            assertNull(op, decision.selected)
            assertTrue(op, decision.notShown.isEmpty())
        }
    }

    @Test
    fun aNamedAbsentFieldStillMatchesNegativeConditions() {
        for (op in negativeOperators) {
            val audience = config(
                audience = """{"conditions":[{"attribute":"plan","op":"$op","value":"pro"}]}""",
            )
            assertEquals(op, "S1", TargetingEngine.onShow(audience, state, context, "S1", false, 0L).selected?.id)

            val trigger = config(
                triggerConditions = """[{"property":"coupon","op":"$op","value":"pro"}]""",
            )
            assertEquals(
                op,
                "S1",
                TargetingEngine.onTrigger(
                    trigger,
                    state,
                    context,
                    TriggerCall.Event("purchase", emptyMap(), 1),
                    0L,
                ).selected?.id,
            )
        }
    }
}
