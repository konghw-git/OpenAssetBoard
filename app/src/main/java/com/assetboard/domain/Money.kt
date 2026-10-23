package com.assetboard.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.absoluteValue

/** Money is Long cents everywhere below the UI. These are the only conversions to and from text. */
object Money {
    private fun formatter() = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

    fun format(cents: Long, signed: Boolean = false): String {
        val body = formatter().format(BigDecimal.valueOf(cents, 2).abs())
        val sign = when {
            cents < 0 -> "-"
            signed && cents > 0 -> "+"
            else -> ""
        }
        return sign + body
    }

    /** "1.86万" style for chart axes. */
    fun compact(cents: Long): String = scaled(cents, strip = true) ?: run {
        val sign = if (cents < 0) "-" else ""
        sign + BigDecimal.valueOf(cents, 2).abs().setScale(0, RoundingMode.HALF_UP).toPlainString()
    }

    /** [compact] for reading rather than axes: always two decimals ("1.80万"), and below 1万 the full amount. */
    fun short(cents: Long): String = scaled(cents, strip = false) ?: format(cents)

    /** In 万 or 亿 with two decimals, or null below 1万. */
    private fun scaled(cents: Long, strip: Boolean): String? {
        val abs = BigDecimal.valueOf(cents, 2).abs()
        val sign = if (cents < 0) "-" else ""
        val (unit, name) = when {
            abs >= BigDecimal(100_000_000) -> BigDecimal(100_000_000) to "亿"
            abs >= BigDecimal(10_000) -> BigDecimal(10_000) to "万"
            else -> return null
        }
        val v = abs.divide(unit).setScale(2, RoundingMode.HALF_UP)
        return sign + (if (strip) v.stripTrailingZeros() else v).toPlainString() + name
    }

    /**
     * Accepts "1,660.24", "1660.2", "¥12", and a sum worked out on the spot: "12000+350",
     * "3×1999.5", "(800-120)/2" (+ − × ÷ or + - * /, × and ÷ first, the result rounded to the
     * cent). Returns null for anything else: a plain amount with more than two decimals, a
     * division by zero, a stray sign.
     */
    fun parse(input: String): Long? {
        val t = input.trim().replace(",", "").replace("，", "").replace("¥", "").replace(" ", "")
        if (t.isEmpty()) return null
        return try {
            val bd = BigDecimal(t)
            if (bd.scale() > 2 && bd.stripTrailingZeros().scale() > 2) return null
            bd.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            expression(t)
        }
    }

    /** Whether [input] is a sum to work out rather than a plain amount, so the field can show the result. */
    fun isExpression(input: String): Boolean = OPERATORS.containsMatchIn(input.trim().removePrefix("-").removePrefix("−"))

    private val OPERATORS = Regex("[+\\-*/×÷xX＋－−]")

    private fun expression(t: String): Long? {
        val s = t.replace('×', '*').replace('x', '*').replace('X', '*').replace('÷', '/')
            .replace('＋', '+').replace('－', '-').replace('−', '-').replace('（', '(').replace('）', ')')
        if (!s.any { it in "+-*/" }) return null
        return try {
            Expression(s).value().setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** + and − of × and ÷ of numbers, a minus sign or a bracket; anything left over is an error. */
    private class Expression(private val s: String) {
        private var i = 0

        fun value(): BigDecimal = sum().also { require(i == s.length) }

        private fun sum(): BigDecimal {
            var v = product()
            while (i < s.length && s[i] in "+-") {
                val op = s[i++]
                val r = product()
                v = if (op == '+') v.add(r) else v.subtract(r)
            }
            return v
        }

        private fun product(): BigDecimal {
            var v = factor()
            while (i < s.length && s[i] in "*/") {
                val op = s[i++]
                val r = factor()
                v = if (op == '*') v.multiply(r) else v.divide(r, 10, RoundingMode.HALF_UP)
            }
            return v
        }

        private fun factor(): BigDecimal {
            require(i < s.length)
            if (s[i] == '-') {
                i++
                return factor().negate()
            }
            if (s[i] == '(') {
                i++
                val v = sum()
                require(i < s.length && s[i] == ')')
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start)
            return BigDecimal(s.substring(start, i))
        }
    }

    /** Plain editable text, e.g. 166024 -> "1660.24". */
    fun toInput(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /** Always two decimals, "12.50%", so shares and ratios line up and small ones still show. */
    fun percent(part: Long, whole: Long): String {
        if (whole == 0L) return "0.00%"
        val p = BigDecimal(part).multiply(BigDecimal(100)).divide(BigDecimal(whole.absoluteValue), 2, RoundingMode.HALF_UP)
        return p.toPlainString() + "%"
    }
}
