package com.responsly.sdk.internal

import com.responsly.sdk.LogLevel
import com.responsly.sdk.QuestionAnsweredEvent
import com.responsly.sdk.ResponslyListener
import com.responsly.sdk.SurveyCompletedEvent
import com.responsly.sdk.SurveyDismissedEvent
import com.responsly.sdk.SurveyErrorEvent
import com.responsly.sdk.SurveyNotShownEvent
import com.responsly.sdk.SurveyShownEvent
import com.responsly.sdk.internal.bridge.BridgeMessage
import com.responsly.sdk.internal.core.Environment
import com.responsly.sdk.internal.core.Platform
import com.responsly.sdk.internal.core.PresentationRequest
import com.responsly.sdk.internal.core.PresenterEvents
import com.responsly.sdk.internal.core.SurveyPresenter
import com.responsly.sdk.internal.net.ConfigResponse
import com.responsly.sdk.internal.net.PostResult
import com.responsly.sdk.internal.net.SdkApi
import com.responsly.sdk.internal.state.KeyValueStorage
import org.json.JSONObject

internal class FakeClock(var time: Long = IsoTime.parse("2026-11-10T12:00:00Z")!!) : Clock {
    override fun now(): Long = time
}

internal class InMemoryStorage : KeyValueStorage {
    val values = LinkedHashMap<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/**
 * Runs everything on the calling thread, in submission order, when [drain] is called. Timers fire
 * when [advance] moves the clock past them.
 */
internal class FakeScheduler(private val clock: FakeClock) : Scheduler {
    private class Timer(val at: Long, val order: Int, val task: () -> Unit) {
        var cancelled = false
    }

    private val queue = ArrayDeque<() -> Unit>()
    private val timers = ArrayList<Timer>()
    private var orders = 0

    /** Network tasks are held back until [runIo] when this is set. */
    var holdIo = false
    private val heldIo = ArrayDeque<() -> Unit>()

    override fun execute(task: () -> Unit) {
        queue.addLast(task)
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val timer = Timer(clock.time + delayMillis, orders++, task)
        timers.add(timer)
        return Cancellable { timer.cancelled = true }
    }

    override fun io(task: () -> Unit) {
        if (holdIo) heldIo.addLast(task) else queue.addLast(task)
    }

    override fun main(task: () -> Unit) {
        queue.addLast(task)
    }

    fun drain() {
        while (true) {
            val task = queue.removeFirstOrNull() ?: break
            task()
        }
    }

    fun runIo() {
        while (true) {
            val task = heldIo.removeFirstOrNull() ?: break
            task()
        }
        drain()
    }

    fun advance(millis: Long) {
        drain()
        val target = clock.time + millis
        while (true) {
            val next = timers.filter { !it.cancelled && it.at <= target }.minWithOrNull(compareBy({ it.at }, { it.order }))
                ?: break
            timers.remove(next)
            clock.time = next.at
            next.task()
            drain()
        }
        clock.time = target
    }

    val pendingTimers: Int get() = timers.count { !it.cancelled }
}

internal class FakeApi : SdkApi {
    var configResponse: ConfigResponse = ConfigResponse.Failed("offline")
    var postResult: PostResult = PostResult.SENT
    val etags = ArrayList<String?>()
    val posts = ArrayList<String>()

    val configFetches: Int get() = etags.size

    override fun fetchConfig(etag: String?): ConfigResponse {
        etags.add(etag)
        return configResponse
    }

    override fun postEvents(body: String): PostResult {
        posts.add(body)
        return postResult
    }

    /** `type:survey_id` of every event that was posted, in order. */
    fun postedEvents(): List<String> = posts.flatMap { body ->
        val events = JSONObject(body).getJSONArray("events")
        (0 until events.length()).map { index ->
            val event = events.getJSONObject(index)
            "${event.getString("type")}:${event.getString("survey_id")}"
        }
    }
}

internal class FakePresenter : SurveyPresenter {
    val calls = ArrayList<String>()
    var request: PresentationRequest? = null
    var events: PresenterEvents? = null
    val cleared = ArrayList<List<String>>()

    override fun present(request: PresentationRequest, events: PresenterEvents) {
        this.request = request
        this.events = events
        calls.add("present:${request.surveyId}")
    }

    override fun reveal(token: Long) {
        calls.add("reveal")
    }

    override fun setDismissible(token: Long, dismissible: Boolean) {
        calls.add("dismissible:$dismissible")
    }

    override fun dismiss(token: Long, animated: Boolean) {
        calls.add(if (animated) "dismiss:animated" else "dismiss")
    }

    override fun clearWebsiteData(origins: List<String>) {
        cleared.add(origins)
    }

    /** The page posts a bridge message. */
    fun post(type: String, payload: String = "{}") {
        val message = BridgeMessage.parse("""{"responsly":1,"type":"$type","payload":$payload}""")!!
        events!!.onBridgeMessage(request!!.token, message)
    }

    val token: Long get() = request!!.token
}

internal class FakePlatform(
    override val storage: KeyValueStorage = InMemoryStorage(),
    val api: FakeApi = FakeApi(),
    override val presenter: FakePresenter = FakePresenter(),
    var locale: String = "en-US",
) : Platform {
    val apiHosts = ArrayList<String>()

    /** APIs for specific hosts; every other host gets [api]. */
    val perHost = HashMap<String, FakeApi>()

    override val environment = Environment(
        appId = "com.acme.app",
        appVersion = "2.3.0",
        osVersion = "16",
        deviceLocale = { locale },
    )

    override fun createApi(apiHost: String, sdkKey: String): SdkApi {
        apiHosts.add(apiHost)
        return perHost[apiHost] ?: api
    }
}

internal class RecordingListener : ResponslyListener {
    val log = ArrayList<String>()
    val completed = ArrayList<SurveyCompletedEvent>()
    val answered = ArrayList<QuestionAnsweredEvent>()
    val shown = ArrayList<SurveyShownEvent>()

    override fun onSurveyShown(event: SurveyShownEvent) {
        shown.add(event)
        log.add("shown:${event.survey.id}:${event.trigger.type}")
    }

    override fun onSurveyNotShown(event: SurveyNotShownEvent) {
        log.add("not_shown:${event.surveyId}:${event.reason.value}")
    }

    override fun onQuestionAnswered(event: QuestionAnsweredEvent) {
        answered.add(event)
        log.add("answered:${event.surveyId}:${event.questionId}")
    }

    override fun onSurveyCompleted(event: SurveyCompletedEvent) {
        completed.add(event)
        log.add("completed:${event.surveyId}")
    }

    override fun onSurveyDismissed(event: SurveyDismissedEvent) {
        log.add("dismissed:${event.surveyId}")
    }

    override fun onSurveyError(event: SurveyErrorEvent) {
        log.add("error:${event.surveyId}:${event.code}")
    }
}

internal class RecordingLogSink : LogSink {
    val lines = ArrayList<String>()

    override fun write(level: LogLevel, message: String, error: Throwable?) {
        lines.add("$level $message")
    }
}

internal fun resourceText(name: String): String =
    checkNotNull(TestSupport::class.java.classLoader!!.getResourceAsStream(name)) { "missing test resource $name" }
        .use { it.readBytes().toString(Charsets.UTF_8) }

internal object TestSupport

/** Tests compile against Android's org.json, which has no `keySet()`. */
internal fun JSONObject.fieldNames(): Set<String> = keys().asSequence().toSet()
