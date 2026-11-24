package io.github.waleed875.memo.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * Port of the legacy DelayCalculator from MemoDashboard.Backend.Services.
 *
 * Treats timestamps as naive LocalDateTime, matching the arithmetic
 * of the original DateTime values without applying a time zone.
 * Threshold: 5 complete 24-hour periods, not five calendar dates.
 */
public final class DelayCalculator {
    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);
    private static final BigDecimal NANO_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);
    private static final BigDecimal FIVE_DAYS = BigDecimal.valueOf(5);
    private DelayCalculator() {}

    public static LocalDateTime monthStart(LocalDateTime date) {
        return date.withDayOfMonth(1).toLocalDate().atStartOfDay();
    }

    public static boolean isLate(LocalDateTime writingDate, LocalDateTime arrivalAt) {
        return writingDate != null && differenceInDays(writingDate, arrivalAt).compareTo(FIVE_DAYS) > 0;
    }

    public static boolean isOnTime(LocalDateTime writingDate, LocalDateTime arrivalAt) {
        if (writingDate == null) return false;
        BigDecimal days = differenceInDays(writingDate, arrivalAt);
        return days.signum() >= 0 && days.compareTo(FIVE_DAYS) <= 0;
    }

    public static boolean isSameMonth(LocalDateTime writingDate, LocalDateTime arrivalAt) {
        return writingDate != null && YearMonth.from(writingDate).equals(YearMonth.from(arrivalAt));
    }

    public static DelayClass classify(LocalDateTime writingDate, LocalDateTime arrivalAt) {
        if (writingDate == null) return DelayClass.UNKNOWN;
        if (arrivalAt.isBefore(writingDate)) return DelayClass.INVALID_DATE_ORDER;
        if (!isLate(writingDate, arrivalAt)) return DelayClass.ON_TIME;
        return isSameMonth(writingDate, arrivalAt)
            ? DelayClass.LATE_SAME_MONTH : DelayClass.LATE_CROSS_MONTH;
    }

    public static boolean isInvalidDateOrder(LocalDateTime writingDate, LocalDateTime arrivalAt) {
        return writingDate != null && arrivalAt.isBefore(writingDate);
    }

    public static double lateDays(LocalDateTime writingDate, LocalDateTime arrivalAt) {
        if (!isLate(writingDate, arrivalAt)) return 0;
        return differenceInDays(writingDate, arrivalAt)
            .setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static BigDecimal differenceInDays(LocalDateTime start, LocalDateTime end) {
        Duration duration = Duration.between(start, end);
        BigDecimal seconds = BigDecimal.valueOf(duration.getSeconds())
            .add(BigDecimal.valueOf(duration.getNano()).divide(NANO_PER_SECOND));
        return seconds.divide(SECONDS_PER_DAY, 18, RoundingMode.HALF_EVEN);
    }
}
