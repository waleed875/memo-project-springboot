package io.github.waleed875.memo.domain;

/** Mirrors the business classifications in the legacy C# DelayCalculator. */
public enum DelayClass {
    UNKNOWN,
    INVALID_DATE_ORDER,
    ON_TIME,
    LATE_SAME_MONTH,
    LATE_CROSS_MONTH
}
