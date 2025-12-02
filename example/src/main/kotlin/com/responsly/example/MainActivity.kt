package com.responsly.example

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.responsly.sdk.LogLevel
import com.responsly.sdk.QuestionAnsweredEvent
import com.responsly.sdk.Responsly
import com.responsly.sdk.ResponslyListener
import com.responsly.sdk.ResponslyOptions
import com.responsly.sdk.ShowOptions
import com.responsly.sdk.SurveyCompletedEvent
import com.responsly.sdk.SurveyDismissedEvent
import com.responsly.sdk.SurveyErrorEvent
import com.responsly.sdk.SurveyNotShownEvent
import com.responsly.sdk.SurveyShownEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A control panel for the SDK: every button is one SDK call, and every listener callback is
 * printed below the buttons.
 */
class MainActivity : Activity(), ResponslyListener {
    private lateinit var sdkKey: EditText
    private lateinit var apiHost: EditText
    private lateinit var debugLogging: Switch
    private lateinit var surveyId: EditText
    private lateinit var force: Switch
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sdkKey = findViewById(R.id.sdk_key)
        apiHost = findViewById(R.id.api_host)
        debugLogging = findViewById(R.id.debug_logging)
        surveyId = findViewById(R.id.survey_id)
        force = findViewById(R.id.force)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.log_scroll)

        val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        sdkKey.setText(preferences.getString(KEY_SDK_KEY, DEFAULT_SDK_KEY))
        apiHost.setText(preferences.getString(KEY_API_HOST, ResponslyOptions.DEFAULT_API_HOST))
        debugLogging.isChecked = preferences.getBoolean(KEY_DEBUG, true)
        surveyId.setText(preferences.getString(KEY_SURVEY_ID, ""))
        savedInstanceState?.getCharSequence(STATE_LOG)?.let { log.text = it }

        Responsly.addListener(this)

        onClick(R.id.initialize) { initializeSdk() }
        onClick(R.id.identify) {
            Responsly.identify(
                DEMO_USER_ID,
                mapOf("plan" to "pro", "seats" to 5, "beta" to true, "signed_up" to Date()),
            )
            print("identify(\"$DEMO_USER_ID\", plan=pro, seats=5, beta=true, signed_up=now)")
        }
        onClick(R.id.track) {
            Responsly.track("purchase_completed", mapOf("value" to 149.0, "currency" to "USD"))
            print("track(\"purchase_completed\", value=149.0, currency=USD)")
        }
        onClick(R.id.screen) {
            Responsly.screen("Checkout")
            print("screen(\"Checkout\")")
        }
        onClick(R.id.show) {
            val id = surveyId.text.toString().trim()
            if (id.isEmpty()) {
                surveyId.error = getString(R.string.survey_id)
                return@onClick
            }
            preferences.edit().putString(KEY_SURVEY_ID, id).apply()
            Responsly.showSurvey(id, ShowOptions(force = force.isChecked))
            print("showSurvey(\"$id\", force=${force.isChecked})")
        }
        onClick(R.id.pause) {
            Responsly.pause()
            print("pause()")
        }
        onClick(R.id.resume) {
            Responsly.resume()
            print("resume()")
        }
        onClick(R.id.close) {
            Responsly.closeSurvey()
            print("closeSurvey()")
        }
        onClick(R.id.reset) {
            Responsly.reset()
            print("reset()")
        }
        onClick(R.id.clear) { log.text = "" }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence(STATE_LOG, log.text.toString())
    }

    override fun onDestroy() {
        Responsly.removeListener(this)
        super.onDestroy()
    }

    private fun initializeSdk() {
        val key = sdkKey.text.toString().trim().ifEmpty { DEFAULT_SDK_KEY }
        val host = apiHost.text.toString().trim().ifEmpty { ResponslyOptions.DEFAULT_API_HOST }
        val level = if (debugLogging.isChecked) LogLevel.DEBUG else LogLevel.ERROR
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY_SDK_KEY, key)
            .putString(KEY_API_HOST, host)
            .putBoolean(KEY_DEBUG, debugLogging.isChecked)
            .apply()
        Responsly.initialize(applicationContext, key, ResponslyOptions(apiHost = host, logLevel = level))
        print("initialize(\"$key\", apiHost=$host, logLevel=$level)")

        // initialize is idempotent for the same key, so a new API host needs a new process.
        val previous = initializedWith
        if (previous != null && previous.first == key && previous.second != host) {
            print("note: already initialised with this key; restart the app to use another API host")
        } else {
            initializedWith = key to host
        }
    }

    // region ResponslyListener - called on the main thread

    override fun onSurveyShown(event: SurveyShownEvent) = print("onSurveyShown ${event.toMap()}")

    override fun onSurveyNotShown(event: SurveyNotShownEvent) = print("onSurveyNotShown ${event.toMap()}")

    override fun onQuestionAnswered(event: QuestionAnsweredEvent) = print("onQuestionAnswered ${event.toMap()}")

    override fun onSurveyCompleted(event: SurveyCompletedEvent) = print("onSurveyCompleted ${event.toMap()}")

    override fun onSurveyDismissed(event: SurveyDismissedEvent) = print("onSurveyDismissed ${event.toMap()}")

    override fun onSurveyError(event: SurveyErrorEvent) = print("onSurveyError ${event.toMap()}")

    // endregion

    private fun onClick(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener { action() }
    }

    private fun print(line: String) {
        log.append("${clock.format(Date())}  $line\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private companion object {
        /** SDK key and API host of the first initialize in this process. */
        var initializedWith: Pair<String, String>? = null

        const val PREFERENCES = "example"
        const val KEY_SDK_KEY = "sdk_key"
        const val KEY_API_HOST = "api_host"
        const val KEY_DEBUG = "debug"
        const val KEY_SURVEY_ID = "survey_id"
        const val STATE_LOG = "log"
        const val DEFAULT_SDK_KEY = "rsk_demo"
        const val DEMO_USER_ID = "demo-user-1"
    }
}
