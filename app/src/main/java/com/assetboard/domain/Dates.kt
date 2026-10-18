package com.assetboard.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object Dates {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val shortFmt = DateTimeFormatter.ofPattern("MM-dd")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val monthFmt = DateTimeFormatter.ofPattern("yyyy年M月")
    private val axisFmt = DateTimeFormatter.ofPattern("yy/M/d")

    fun localDate(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    /** Records entered with a date only sit at 23:59:59, after every flow of that day. */
    fun endOfDay(date: LocalDate): Long = date.atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

    /** Material date pickers speak UTC midnight. */
    fun pickerToDate(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

    fun dateToPicker(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun atToPicker(at: Long): Long = dateToPicker(localDate(at))

    /** Today means "right now"; any other day means the end of that day. */
    fun pickedAt(date: LocalDate, now: Long = System.currentTimeMillis()): Long =
        if (date == localDate(now)) now else endOfDay(date)

    fun date(at: Long): String = dateFmt.format(localDate(at))
    fun short(at: Long): String = shortFmt.format(localDate(at))
    fun month(at: Long): String = monthFmt.format(localDate(at))
    fun axis(at: Long): String = axisFmt.format(localDate(at))
    fun time(at: Long): String = timeFmt.format(Instant.ofEpochMilli(at).atZone(zone))

    fun dateTime(at: Long): String = "${date(at)} ${time(at)}"

    fun daysBetween(from: Long, to: Long): Long = ChronoUnit.DAYS.between(localDate(from), localDate(to))

    fun relative(at: Long, now: Long = System.currentTimeMillis()): String {
        val d = daysBetween(at, now)
        return when {
            d == 0L -> "今天"
            d == 1L -> "昨天"
            d in 2..30 -> "${d} 天前"
            else -> date(at)
        }
    }
}
