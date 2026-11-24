package io.github.waleed875.memo.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DelayCalculatorTest {
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 28, 14, 30);

    @Test void missingWritingDateIsUnknown() {
        assertEquals(DelayClass.UNKNOWN, DelayCalculator.classify(null, BASE));
        assertFalse(DelayCalculator.isLate(null, BASE));
        assertFalse(DelayCalculator.isOnTime(null, BASE));
        assertEquals(0, DelayCalculator.lateDays(null, BASE));
    }

    @Test void negativeDateOrderIsInvalid() {
        assertEquals(DelayClass.INVALID_DATE_ORDER, DelayCalculator.classify(BASE, BASE.minusSeconds(1)));
        assertTrue(DelayCalculator.isInvalidDateOrder(BASE, BASE.minusSeconds(1)));
        assertEquals(0, DelayCalculator.lateDays(BASE, BASE.minusSeconds(1)));
    }

    @Test void exactlyFiveFullDaysIsOnTime() {
        LocalDateTime arrival = BASE.plusDays(5);
        assertEquals(DelayClass.ON_TIME, DelayCalculator.classify(BASE, arrival));
        assertTrue(DelayCalculator.isOnTime(BASE, arrival));
        assertFalse(DelayCalculator.isLate(BASE, arrival));
    }

    @Test void lateWithinMonthAndAcrossMonth() {
        LocalDateTime jan = LocalDateTime.of(2026, 1, 2, 9, 0);
        assertEquals(DelayClass.LATE_SAME_MONTH, DelayCalculator.classify(jan, jan.plusDays(6)));
        assertEquals(DelayClass.LATE_CROSS_MONTH, DelayCalculator.classify(BASE, BASE.plusDays(6)));
    }

    @Test void thresholdUsesHoursAndMinutesAndHalfEvenRounding() {
        LocalDateTime arrival = BASE.plusDays(5).plusMinutes(1);
        assertTrue(DelayCalculator.isLate(BASE, arrival));
        assertEquals(5.0, DelayCalculator.lateDays(BASE, arrival));
        assertEquals(6.0, DelayCalculator.lateDays(BASE, BASE.plusDays(6)));
    }

    @Test void monthStartUsesFirstDay() {
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), DelayCalculator.monthStart(BASE));
    }
}
