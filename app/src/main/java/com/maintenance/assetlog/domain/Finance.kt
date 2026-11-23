package com.maintenance.assetlog.domain

import com.maintenance.assetlog.data.*
import java.time.LocalDate
import java.time.YearMonth

val recurrenceChoices = linkedMapOf("" to "بدون تكرار", "W" to "أسبوعي", "M" to "شهري", "Y" to "سنوي", "D" to "كل عدد من الأيام")

fun nextOccurrence(date: LocalDate, recurrence: String, anchorDay: Int = date.dayOfMonth): LocalDate? = when (recurrence) {
    "" -> null
    "W" -> date.plusWeeks(1)
    "M" -> date.plusMonths(1).let { it.withDayOfMonth(anchorDay.coerceAtMost(it.lengthOfMonth())) }
    "Y" -> date.plusYears(1).let { it.withDayOfMonth(anchorDay.coerceAtMost(it.lengthOfMonth())) }
    else -> {
        require(recurrence.startsWith("D:"))
        date.plusDays(recurrence.substringAfter(':').toLong().also { require(it in 1..3650) })
    }
}

fun Operation.outstanding() = state == PaymentState.EXPECTED || state == PaymentState.DUE

fun Operation.followUp(): Operation? {
    if (state != PaymentState.PAID) return null
    val scheduled = LocalDate.parse(dueOn.ifBlank { endsOn.ifBlank { date } })
    val anchor = anchorDay ?: scheduled.dayOfMonth
    val next = if (sectionId == CAR && previousId == null && dueOn.isNotBlank()) scheduled
        else nextOccurrence(scheduled, recurrence, anchor)
    val interval = mileageInterval ?: if (odometer != null && nextKm != null) {
        (nextKm - odometer).takeIf { it > 0 }
    } else null
    val mileageTarget = when {
        odometer != null && interval != null -> Math.addExact(odometer, interval)
        previousId == null -> nextKm
        else -> null
    }
    if (next == null && mileageTarget == null) return null
    return copy(id = java.util.UUID.randomUUID().toString(), state = PaymentState.EXPECTED,
        date = (next ?: LocalDate.parse(paidOn)).toString(), dueOn = next?.toString().orEmpty(),
        paidOn = "", endsOn = "", attachment = null, odometer = null,
        nextKm = mileageTarget, previousId = id, anchorDay = anchor, mileageInterval = interval)
}

data class PaidItem(
    val id: String, val sectionId: String, val title: String, val date: LocalDate,
    val amount: Long, val currency: String, val legacyId: Long? = null
)

fun paidItems(operations: List<Operation>, history: List<Maintenance>, assets: List<AssetItem> = emptyList()): List<PaidItem> {
    val categories = assets.associate { it.id to it.category }
    return operations.filter { it.state == PaymentState.PAID }.map {
        PaidItem(it.id, it.sectionId, it.title, LocalDate.parse(it.paidOn), it.amount, it.currency)
    } + history.filter { it.status == "مكتملة" }.map {
        val category = categories[it.assetId].orEmpty().trim().lowercase()
        val section = if (category in setOf("car", "vehicle", "سيارة", "مركبة", "السيارة", "المركبة") ||
            category.startsWith("سيارة ") || category.startsWith("مركبة ")) CAR else LEGACY
        PaidItem("legacy-${it.id}", section, it.type, LocalDate.parse(it.date), it.cost, it.currency, it.id)
    }
}

fun List<PaidItem>.inMonth(month: YearMonth) = filter { YearMonth.from(it.date) == month }

val defaultSections = listOf(
    Section(CAR, "السيارة", "سيارة", 0xFF5A7D6C),
    Section(HOUSE, "المنزل", "منزل", 0xFF78644F),
    Section(MOBILE, "الجوال والاشتراكات", "جوال", 0xFF536F77),
    Section(PERSONAL, "مصروفات الشهر", "مصروفات", 0xFF7B647C),
    Section(LEGACY, "السجلات السابقة", archived = true)
)

val defaultServices = mapOf(
    CAR to listOf("تغيير زيت المحرك", "تغيير فلتر الزيت", "تغيير فلتر الهواء", "تغيير فلتر المكيف",
        "تغيير الإطارات", "تغيير البطارية", "تغيير الفرامل", "تغيير قطع الغيار", "صيانة المكيف",
        "تعبئة الوقود", "غسيل السيارة", "تغيير سائل التبريد", "تغيير زيت ناقل الحركة", "فحص السيارة الدوري",
        "تجديد الاستمارة", "تجديد التأمين", "إصلاح الأعطال", "صيانة أخرى"),
    HOUSE to listOf("فاتورة الكهرباء", "فاتورة المياه", "فاتورة الإنترنت", "الإيجار", "الغاز",
        "الصيانة المنزلية", "الأجهزة المنزلية", "مواد التنظيف", "المشتريات المنزلية", "الأثاث", "الخدمات الأخرى"),
    MOBILE to listOf("باقات الجوال", "شحن الرصيد", "باقات الإنترنت", "اشتراكات التطبيقات",
        "خدمات التخزين السحابي", "اشتراكات المشاهدة", "اشتراكات البرامج", "الخدمات الرقمية الأخرى")
).flatMap { (section, names) ->
    names.mapIndexed { index, name -> Service("preset-$section-$index", section, name,
        type = when (section) { CAR -> "خدمة سيارة"; HOUSE -> "فاتورة أو خدمة"; else -> "اشتراك أو باقة" }) }
}
