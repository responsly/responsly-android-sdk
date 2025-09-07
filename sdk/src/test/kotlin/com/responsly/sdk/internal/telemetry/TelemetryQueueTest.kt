package com.responsly.sdk.internal.telemetry

import com.responsly.sdk.internal.FakeApi
import com.responsly.sdk.internal.FakeClock
import com.responsly.sdk.internal.FakeScheduler
import com.responsly.sdk.internal.InMemoryStorage
import com.responsly.sdk.internal.Logger
import com.responsly.sdk.internal.RecordingLogSink
import com.responsly.sdk.internal.fieldNames
import com.responsly.sdk.internal.net.PostResult
import com.responsly.sdk.internal.state.StateStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryQueueTest {
    private val storage = InMemoryStorage()
    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val api = FakeApi()
    private val store = StateStore(storage).apply { load() }
    private val queue = TelemetryQueue(store, api, scheduler, Logger(RecordingLogSink()))

    private fun enqueue(count: Int, type: String = "displayed") {
        repeat(count) { queue.enqueue(type, "S${it + 1}") }
        scheduler.drain()
    }

    @Test
    fun aFullBatchIsSentAtOnce() {
        enqueue(19)
        assertTrue(api.posts.isEmpty())
        queue.enqueue("completed", "S20")
        scheduler.drain()
        assertEquals(1, api.posts.size)
        assertEquals(20, api.postedEvents().size)
        assertTrue(store.telemetry.isEmpty())
        assertEquals(null, storage.getString(StateStore.KEY_TELEMETRY))
    }

    @Test
    fun theBodyHasTypeAndSurveyIdAndNothingElse() {
        queue.enqueue("displayed", "aBcD1234")
        queue.flush()
        scheduler.drain()
        val body = JSONObject(api.posts.single())
        assertEquals(setOf("events"), body.fieldNames())
        val event = body.getJSONArray("events").getJSONObject(0)
        assertEquals(setOf("type", "survey_id"), event.fieldNames())
        assertEquals("displayed", event.getString("type"))
        assertEquals("aBcD1234", event.getString("survey_id"))
    }

    @Test
    fun waitingEventsAreSentAfterThirtySeconds() {
        enqueue(3)
        scheduler.advance(29_999)
        assertTrue(api.posts.isEmpty())
        scheduler.advance(1)
        assertEquals(listOf("displayed:S1", "displayed:S2", "displayed:S3"), api.postedEvents())
        // Nothing is waiting any more, so nothing is scheduled.
        assertEquals(0, scheduler.pendingTimers)
    }

    @Test
    fun goingToTheBackgroundFlushes() {
        enqueue(2)
        queue.flush()
        scheduler.drain()
        assertEquals(2, api.postedEvents().size)
        assertEquals(0, scheduler.pendingTimers)
    }

    @Test
    fun eventsThatCannotBeSentAreKeptAndRetried() {
        api.postResult = PostResult.RETRY
        enqueue(2)
        queue.flush()
        scheduler.drain()
        assertEquals(1, api.posts.size)
        assertEquals(2, store.telemetry.size)

        // They survive a restart...
        assertEquals(2, StateStore(storage).apply { load() }.telemetry.size)

        // ...and are retried every thirty seconds while they wait, not more often.
        enqueue(1)
        assertEquals(1, api.posts.size)
        scheduler.advance(30_000)
        assertEquals(2, api.posts.size)
        api.postResult = PostResult.SENT
        scheduler.advance(30_000)
        assertEquals(3, api.posts.size)
        assertTrue(store.telemetry.isEmpty())
    }

    @Test
    fun whileTheServerIsUnreachableAFullQueueDoesNotSendOnEveryEvent() {
        api.postResult = PostResult.RETRY
        enqueue(20)
        assertEquals(1, api.posts.size)
        enqueue(5)
        assertEquals(1, api.posts.size)
        api.postResult = PostResult.SENT
        scheduler.advance(30_000)
        // Two batches: 20 and the remaining 5.
        assertEquals(3, api.posts.size)
        assertTrue(store.telemetry.isEmpty())
    }

    @Test
    fun atMostTwoHundredEventsAreKeptOldestDropped() {
        api.postResult = PostResult.RETRY
        repeat(230) { queue.enqueue("displayed", "S$it") }
        scheduler.drain()
        assertEquals(200, store.telemetry.size)
        assertEquals("S30", store.telemetry.first().surveyId)
        assertEquals("S229", store.telemetry.last().surveyId)
    }

    @Test
    fun aLongQueueIsSentInBatchesOfTwenty() {
        api.postResult = PostResult.RETRY
        enqueue(45)
        api.postResult = PostResult.SENT
        queue.flush()
        scheduler.drain()
        val sizes = api.posts.drop(1).map { JSONObject(it).getJSONArray("events").length() }
        assertEquals(listOf(20, 20, 5), sizes)
        assertTrue(store.telemetry.isEmpty())
    }

    @Test
    fun aRejectedBatchIsDropped() {
        api.postResult = PostResult.REJECTED
        enqueue(2)
        queue.flush()
        scheduler.drain()
        assertEquals(1, api.posts.size)
        assertTrue(store.telemetry.isEmpty())
    }

    @Test
    fun nothingIsRecordedOrSentWhileTrackingIsDisabled() {
        enqueue(2)
        store.setTrackingEnabled(false)
        queue.enqueue("displayed", "S9")
        queue.flush()
        scheduler.advance(60_000)
        assertTrue(api.posts.isEmpty())
        assertEquals(2, store.telemetry.size)

        queue.clear()
        assertTrue(store.telemetry.isEmpty())
        assertEquals(null, storage.getString(StateStore.KEY_TELEMETRY))
    }

    @Test
    fun eventsLeftFromAnEarlierRunAreSentAfterStart() {
        enqueue(2)
        val restartedStore = StateStore(storage).apply { load() }
        val restartedApi = FakeApi()
        val restarted = TelemetryQueue(restartedStore, restartedApi, scheduler, Logger(RecordingLogSink()))
        queue.close()
        restarted.start()
        scheduler.advance(30_000)
        assertEquals(2, restartedApi.postedEvents().size)
        assertTrue(api.posts.isEmpty())
    }
}
