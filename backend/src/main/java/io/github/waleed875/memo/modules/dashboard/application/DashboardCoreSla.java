package io.github.waleed875.memo.modules.dashboard.application;

/** Four scalar SLA facts and the legacy percentage (null if no eligible notes). */
public record DashboardCoreSla(
    long eligibleNotes, long onTimeNotes, long lateNotes,
    Double onTimeRate, long invalidDateOrderNotes
) {}
