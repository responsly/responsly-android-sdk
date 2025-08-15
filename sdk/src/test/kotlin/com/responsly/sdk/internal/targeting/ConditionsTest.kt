package com.responsly.sdk.internal.targeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionsTest {
    private fun eval(op: String, actual: Any?, expected: Any?) = Conditions.evaluate(op, actual, expected)

    @Test
    fun existsAndNotExists() {
        assertTrue(eval("exists", "x", null))
        assertTrue(eval("exists", false, null))
        assertFalse(eval("exists", null, null))
        assertTrue(eval("not_exists", null, null))
        assertFalse(eval("not_exists", 0.0, null))
    }

    @Test
    fun equalityComparesNumbersNumericallyAndBooleansAsBooleans() {
        assertTrue(eval("eq", 5.0, 5.0))
        assertFalse(eval("eq", 5.0, 5.5))
        assertTrue(eval("eq", true, true))
        assertFalse(eval("eq", true, false))
        assertTrue(eval("neq", true, false))
    }

    @Test
    fun equalityFallsBackToCaseInsensitiveText() {
        assertTrue(eval("eq", "pro", "PRO"))
        assertTrue(eval("eq", 5.0, "5"))
        assertTrue(eval("eq", "5", 5.0))
        assertTrue(eval("eq", 5.5, "5.5"))
        assertTrue(eval("eq", true, "TRUE"))
        assertTrue(eval("eq", "false", false))
        assertFalse(eval("eq", 5.0, "5.0"))
        assertFalse(eval("eq", "a", "b"))
    }

    @Test
    fun lowerCasingDoesNotDependOnTheDefaultLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertTrue(eval("eq", "TITLE", "title"))
            assertTrue(eval("contains", "INFINITY", "fin"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun absentValues() {
        assertFalse(eval("eq", null, "x"))
        assertTrue(eval("neq", null, "x"))
        assertFalse(eval("contains", null, "x"))
        assertTrue(eval("not_contains", null, "x"))
        for (op in listOf("gt", "gte", "lt", "lte")) assertFalse(op, eval(op, null, 1.0))
    }

    @Test
    fun containsIsCaseInsensitiveAndWorksOnTheTextOfAnyValue() {
        assertTrue(eval("contains", "Poland", "land"))
        assertTrue(eval("contains", "Zoë", "zo"))
        assertTrue(eval("contains", 1234.0, "23"))
        assertTrue(eval("contains", true, "ru"))
        assertFalse(eval("contains", "abc", "d"))
        assertTrue(eval("not_contains", "abc", "d"))
        assertFalse(eval("not_contains", "abc", "B"))
    }

    @Test
    fun orderingIsNumericWhenBothSidesAreNumbersOrDecimalStrings() {
        assertTrue(eval("gt", 6.0, 5.0))
        assertTrue(eval("gte", 5.0, 5.0))
        assertTrue(eval("lt", 5.0, 5.5))
        assertTrue(eval("lte", "5", "5"))
        assertTrue(eval("gte", "250", 100.0))
        assertTrue(eval("lt", "9", "10"))
        assertTrue(eval("gt", "1e3", 999.0))
        assertFalse(eval("gte", "4", 5.0))
    }

    @Test
    fun orderingIsOrdinalForOtherStringPairs() {
        assertTrue(eval("gt", "2026-11-10T12:00:00Z", "2026-01-01T00:00:00Z"))
        assertTrue(eval("lt", "B", "a"))
        assertTrue(eval("gt", "b", "B"))
        assertTrue(eval("lte", "abc", "abc"))
        assertTrue(eval("lt", "ab", "abc"))
        // Ordinal by code point: a supplementary character sorts after every BMP character.
        assertTrue(eval("gt", "🙂", "�"))
    }

    @Test
    fun orderingIsFalseForEverythingElse() {
        assertFalse(eval("gt", 5.0, "abc"))
        assertFalse(eval("lt", 5.0, "abc"))
        assertFalse(eval("gt", true, false))
        assertFalse(eval("lte", true, true))
        assertFalse(eval("gt", "abc", 5.0))
    }

    @Test
    fun unknownOperatorsAreFalse() {
        assertFalse(eval("regex", "abc", ".*"))
        assertFalse(eval("", "abc", "abc"))
        assertFalse(eval("EQ", "abc", "abc"))
    }

    @Test
    fun onlyStrictDecimalsParseAsNumbers() {
        assertEquals(5.0, Conditions.parseDecimal("5")!!, 0.0)
        assertEquals(-5.5, Conditions.parseDecimal("-5.5")!!, 0.0)
        assertEquals(0.5, Conditions.parseDecimal(".5")!!, 0.0)
        assertEquals(5.0, Conditions.parseDecimal("5.")!!, 0.0)
        assertEquals(1200.0, Conditions.parseDecimal("+1.2E3")!!, 0.0)
        for (text in listOf("", " 5", "5 ", "5f", "5d", "NaN", "Infinity", "0x1p3", "0x10", "1e", ".", "1,5", "1e999", "٥", "1_000")) {
            assertNull("\"$text\" is not a number", Conditions.parseDecimal(text))
        }
    }
}
