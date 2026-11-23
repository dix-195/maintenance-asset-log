package com.maintenance.assetlog

import com.maintenance.assetlog.data.*
import com.maintenance.assetlog.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class FinanceTest {
    private val bill = Operation(sectionId = HOUSE, title = "الكهرباء", amount = 7500, currency = "SAR",
        state = PaymentState.DUE, date = "2026-01-30", dueOn = "2026-01-31", recurrence = "M")

    @Test fun reportsCountOnlyPaidTransactionsInTheActualPaymentMonth() {
        val payment = bill.copy(state = PaymentState.PAID, paidOn = "2026-02-02")
        val legacy = Maintenance(id = 1, assetId = 1, type = "الزيت", date = "2026-02-02", cost = 10000, cycle = "oil")
        val input = listOf(payment, bill.copy(id = "pending"), bill.copy(id = "cancelled", state = PaymentState.CANCELLED),
            checkNotNull(payment.followUp()))
        val paid = paidItems(input, listOf(legacy, legacy.copy(id = 2, status = "قيد التنفيذ")))
        assertTrue(paid.inMonth(YearMonth.of(2026, 1)).isEmpty())
        assertEquals(17500L, paid.inMonth(YearMonth.of(2026, 2)).sumOf { it.amount })
        assertEquals(2, paid.map { it.id }.distinct().size)
    }

    @Test fun monthlyRecurrenceKeepsTheOriginalDayAcrossShortMonths() {
        assertNull(bill.followUp())
        val february = checkNotNull(bill.copy(state = PaymentState.PAID, paidOn = "2026-02-02").followUp())
        assertEquals(PaymentState.EXPECTED, february.state)
        assertEquals("2026-02-28", february.dueOn)
        assertEquals("", february.paidOn)
        assertEquals(bill.id, february.previousId)
        assertEquals("2026-03-31", february.copy(state = PaymentState.PAID, paidOn = "2026-02-28").followUp()?.dueOn)
    }

    @Test fun recurrenceSupportsLeapYearsWeeksAndCustomDays() {
        val leap = LocalDate.of(2024, 2, 29)
        assertEquals(LocalDate.of(2025, 2, 28), nextOccurrence(leap, "Y"))
        assertEquals(LocalDate.of(2028, 2, 29), nextOccurrence(LocalDate.of(2027, 2, 28), "Y", 29))
        assertEquals(LocalDate.of(2024, 3, 7), nextOccurrence(leap, "W"))
        assertEquals(LocalDate.of(2024, 3, 2), nextOccurrence(leap, "D:2"))
        assertThrows(IllegalArgumentException::class.java) { nextOccurrence(leap, "D:0") }
    }

    @Test fun mileageTargetsNeverBecomeRecordedOdometerReadings() {
        val vehicle = bill.copy(sectionId = CAR, state = PaymentState.PAID, paidOn = "2026-02-01",
            vehicleId = 1, recurrence = "", odometer = 5000, nextKm = 8000)
        val next = checkNotNull(vehicle.followUp())
        assertNull(next.odometer)
        assertEquals(8000L, next.nextKm)
        assertNull(next.copy(state = PaymentState.PAID, paidOn = "2026-02-04").followUp())
        assertEquals(11200L, next.copy(state = PaymentState.PAID, paidOn = "2026-02-04", odometer = 8200).followUp()?.nextKm)
    }
}
