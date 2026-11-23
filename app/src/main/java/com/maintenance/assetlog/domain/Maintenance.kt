package com.maintenance.assetlog.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class Priority(val label: String) {
    LATE("متأخرة"), TODAY("مستحقة"), SOON("قريبة"), GOOD("جيدة"), NONE("بلا دورة")
}

data class Due(val days: Long?, val kilometers: Long?, val priority: Priority)

fun calculateDue(
    today: LocalDate,
    nextDate: LocalDate?,
    odometer: Long?,
    nextKm: Long?,
    reminderDays: Int
): Due {
    val days = nextDate?.let { ChronoUnit.DAYS.between(today, it) }
    val km = if (nextKm != null && odometer != null) nextKm - odometer else null
    val priority = when {
        listOfNotNull(days, km).any { it < 0 } -> Priority.LATE
        days == 0L || km == 0L -> Priority.TODAY
        days != null && days <= reminderDays || km != null && km <= 500 -> Priority.SOON
        days != null || km != null -> Priority.GOOD
        else -> Priority.NONE
    }
    return Due(days, km, priority)
}

fun nextDate(completed: LocalDate, months: Int?): LocalDate? =
    months?.also { require(it in 1..1200) }?.let { completed.plusMonths(it.toLong()) }

fun nextOdometer(completed: Long?, interval: Long?): Long? {
    require(completed == null || completed >= 0)
    return interval?.let {
        require(it > 0 && completed != null)
        Math.addExact(completed, it)
    }
}

fun validateOdometer(previous: Long?, current: Long?, correction: Boolean) {
    require(current == null || current >= 0)
    require(previous == null || correction || (current != null && current >= previous)) {
        "يلزم تأكيد تصحيح قراءة العداد"
    }
}
