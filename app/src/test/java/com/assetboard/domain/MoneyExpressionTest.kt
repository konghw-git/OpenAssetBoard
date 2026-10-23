package com.assetboard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyExpressionTest {
    @Test fun plainAmountsReadAsBefore() {
        assertEquals(166_024L, Money.parse("1,660.24"))
        assertEquals(-35_000L, Money.parse("-350"))
        assertNull(Money.parse("1.234"))
        assertNull(Money.parse(""))
    }

    @Test fun sumsAreWorkedOut() {
        assertEquals(1_235_000L, Money.parse("12000+350"))
        assertEquals(1_165_000L, Money.parse("12000 − 350"))
        assertEquals(599_850L, Money.parse("3×1999.5"))
        assertEquals(34_000L, Money.parse("(800-120)/2"))
        // × and ÷ before + and −.
        assertEquals(700L, Money.parse("1+2*3"))
        assertEquals(-5_000L, Money.parse("-100+50"))
        // Division rounds to the cent.
        assertEquals(3_333L, Money.parse("100/3"))
        assertEquals(1_000_00L, Money.parse("¥500+¥500"))
    }

    @Test fun brokenSumsAreNothing() {
        assertNull(Money.parse("12000+"))
        assertNull(Money.parse("1/0"))
        assertNull(Money.parse("(1+2"))
        assertNull(Money.parse("1++"))
        assertNull(Money.parse("abc"))
    }

    @Test fun whatCountsAsASum() {
        assertTrue(Money.isExpression("1+2"))
        assertTrue(Money.isExpression("3×4"))
        assertFalse(Money.isExpression("-350"))
        assertFalse(Money.isExpression("1,660.24"))
    }
}
