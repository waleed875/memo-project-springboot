package io.github.waleed875.memo.modules.dashboard.application;

import java.time.LocalDate;

/** Inclusive comparison window, matching the original DashboardPeriod fields. */
public record DashboardCompanyPeriod(
    LocalDate from, LocalDate to, String dateBasis, LocalDate entryCutoffDate
) {}
