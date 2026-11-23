package com.maintenance.assetlog

import com.maintenance.assetlog.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MaintenanceTest {
    private val today = LocalDate.of(2024, 1, 31)

    @Test fun monthsRespectLeapDays() {
        assertEquals(LocalDate.of(2024, 2, 29), nextDate(today, 1))
        assertEquals(LocalDate.of(2025, 2, 28), nextDate(LocalDate.of(2025, 1, 31), 1))
        assertNull(nextDate(today, null))
    }

    @Test fun mileageOnlyHasNoDate() {
        val target = nextOdometer(120_000, 5_000)
        assertEquals(125_000L, target)
        val due = calculateDue(today, null, 124_900, target, 7)
        assertNull(due.days)
        assertEquals(100L, due.kilometers)
        assertEquals(Priority.SOON, due.priority)
    }

    @Test fun earliestConditionDeterminesPriority() {
        assertEquals(Priority.TODAY, calculateDue(today, today.plusMonths(2), 125_000, 125_000, 7).priority)
        assertEquals(Priority.LATE, calculateDue(today, today.minusDays(1), 120_000, 125_000, 7).priority)
        assertEquals(Priority.LATE, calculateDue(today, today.plusMonths(1), 125_001, 125_000, 7).priority)
        assertEquals(Priority.GOOD, calculateDue(today, today.plusDays(8), null, null, 7).priority)
        assertEquals(Priority.NONE, calculateDue(today, null, null, 125_000, 7).priority)
    }

    @Test fun rejectsInvalidInputsAndRequiresExplicitCorrection() {
        assertThrows(IllegalArgumentException::class.java) { nextOdometer(-1, 100) }
        assertThrows(IllegalArgumentException::class.java) { nextOdometer(null, 100) }
        assertThrows(ArithmeticException::class.java) { nextOdometer(Long.MAX_VALUE, 100) }
        assertThrows(IllegalArgumentException::class.java) { nextDate(today, 0) }
        assertThrows(IllegalArgumentException::class.java) { validateOdometer(100, 99, false) }
        assertThrows(IllegalArgumentException::class.java) { validateOdometer(100, null, false) }
        validateOdometer(100, 99, true)
        validateOdometer(100, 101, false)
    }
}
