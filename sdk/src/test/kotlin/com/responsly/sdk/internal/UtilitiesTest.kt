package com.responsly.sdk.internal

import java.util.Date
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Fnv1aTest {
    @Test
    fun matchesThePublishedTestVectors() {
        assertEquals(0x811c9dc5L, Fnv1a.hash32(""))
        assertEquals(0xe40c292cL, Fnv1a.hash32("a"))
        assertEquals(0xbf9cf968L, Fnv1a.hash32("foobar"))
    }

    @Test
    fun samplingBucketsOfTheFixtureNames() {
        // "sampling uses a stable install bucket (install-a: S1=48, S2=5)"
        assertEquals(48, Fnv1a.samplingBucket("install-a", "S1"))
        assertEquals(5, Fnv1a.samplingBucket("install-a", "S2"))
        // "sampling hashes UTF-8 bytes (install zażółć-🙂: S1=60, S2=17)"
        assertEquals(60, Fnv1a.samplingBucket("zażółć-🙂", "S1"))
        assertEquals(17, Fnv1a.samplingBucket("zażółć-🙂", "S2"))
    }

    @Test
    fun bucketsStayInRange() {
        for (index in 0 until 1_000) {
            assertTrue(Fnv1a.samplingBucket("install-$index", "survey") in 0..99)
        }
    }
}

class IsoTimeTest {
    @Test
    fun parsesUtcTimestamps() {
        assertEquals(0L, IsoTime.parse("1970-01-01T00:00:00Z"))
        assertEquals(1_794_312_000_000L, IsoTime.parse("2026-11-10T12:00:00Z"))
        assertEquals(951_782_400_000L, IsoTime.parse("2000-02-29T00:00:00Z"))
        assertEquals(-86_400_000L, IsoTime.parse("1969-12-31T00:00:00Z"))
    }

    @Test
    fun parsesOffsetsFractionsAndShortForms() {
        val noon = IsoTime.parse("2026-11-10T12:00:00Z")!!
        assertEquals(noon, IsoTime.parse("2026-11-10T13:00:00+01:00"))
        assertEquals(noon, IsoTime.parse("2026-11-10T07:00:00-0500"))
        assertEquals(noon + 250, IsoTime.parse("2026-11-10T12:00:00.25Z"))
        assertEquals(noon + 123, IsoTime.parse("2026-11-10T12:00:00.123456Z"))
        assertEquals(noon, IsoTime.parse("2026-11-10 12:00:00"))
        assertEquals(noon, IsoTime.parse("2026-11-10T12:00Z"))
        assertEquals(noon - 12 * 3_600_000L, IsoTime.parse("2026-11-10"))
    }

    @Test
    fun rejectsMalformedTimestamps() {
        for (text in listOf("", "yesterday", "2026-13-01T00:00:00Z", "2026-02-30T00:00:00Z", "2026-11-10T25:00:00Z", "10/11/2026")) {
            assertNull(text, IsoTime.parse(text))
        }
        assertNull(IsoTime.parse(null))
    }

    @Test
    fun formatsUtcWithSecondsPrecision() {
        assertEquals("1970-01-01T00:00:00Z", IsoTime.format(0L))
        assertEquals("2026-11-10T12:00:00Z", IsoTime.format(1_794_312_000_999L))
        assertEquals("1969-12-31T23:59:59Z", IsoTime.format(-1L))
        assertEquals("2000-02-29T23:59:59Z", IsoTime.format(951_868_799_000L))
    }

    @Test
    fun formatAndParseRoundTrip() {
        var time = -2_000_000_000_000L
        while (time < 4_000_000_000_000L) {
            assertEquals(time, IsoTime.parse(IsoTime.format(time)))
            time += 86_400_000L * 97 + 3_661_000L
        }
    }
}

class ValuesTest {
    @Test
    fun normalizesHostValues() {
        assertEquals("text", Values.normalize("text"))
        assertEquals(5.0, Values.normalize(5))
        assertEquals(5.0, Values.normalize(5L))
        assertEquals(2.5, Values.normalize(2.5f))
        assertEquals(true, Values.normalize(true))
        assertEquals("2026-11-10T12:00:00Z", Values.normalize(Date(1_794_312_000_500L)))
        assertEquals("[1, 2]", Values.normalize(listOf(1, 2)))
        assertEquals("NaN", Values.normalize(Double.NaN))
        assertNull(Values.normalize(null as Any?))
    }

    @Test
    fun normalizingAMapKeepsNullsSoTheyCanRemoveTraits() {
        val normalized = Values.normalize(mapOf("plan" to "pro", "seats" to 5, "beta" to null))
        assertEquals(mapOf("plan" to "pro", "seats" to 5.0, "beta" to null), normalized)
    }

    @Test
    fun textOfValues() {
        assertEquals("abc", Values.text("abc"))
        assertEquals("true", Values.text(true))
        assertEquals("false", Values.text(false))
        assertEquals("5", Values.text(5.0))
        assertEquals("-12", Values.text(-12.0))
        assertEquals("0", Values.text(-0.0))
        assertEquals("5.5", Values.text(5.5))
        assertEquals("0.1", Values.text(0.1))
        assertEquals("0.0001", Values.text(1.0e-4))
        assertEquals("12345678.9", Values.text(1.23456789e7))
        assertEquals("100000000000000000000", Values.text(1.0e20))
    }

    @Test
    fun readsJsonScalars() {
        val json = JSONObject("""{"s":"x","i":5,"d":5.5,"b":true,"n":null,"o":{"a":1}}""")
        val map = Values.mapFromJson(json)
        assertEquals("x", map["s"])
        assertEquals(5.0, map["i"])
        assertEquals(5.5, map["d"])
        assertEquals(true, map["b"])
        assertTrue(map.containsKey("n"))
        assertNull(map["n"])
        assertTrue(map["o"] is String)
    }

    @Test
    fun convertsAnswerValuesToPlainKotlin() {
        val json = JSONObject(
            """{"text":"hi","whole":7,"big":9007199254740993,"real":7.5,"flag":false,"none":null,
               "list":[1,"two",[3]],"map":{"k":{"n":1.5}}}""",
        )
        assertEquals("hi", JsonValues.toKotlin(json.opt("text")))
        assertEquals(7L, JsonValues.toKotlin(json.opt("whole")))
        assertEquals(9007199254740993L, JsonValues.toKotlin(json.opt("big")))
        assertEquals(7.5, JsonValues.toKotlin(json.opt("real")))
        assertEquals(false, JsonValues.toKotlin(json.opt("flag")))
        assertNull(JsonValues.toKotlin(json.opt("none")))
        assertNull(JsonValues.toKotlin(json.opt("missing")))
        assertEquals(listOf(1L, "two", listOf(3L)), JsonValues.toKotlin(json.opt("list")))
        assertEquals(mapOf("k" to mapOf("n" to 1.5)), JsonValues.toKotlin(json.opt("map")))
    }
}
