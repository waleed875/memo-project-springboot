package io.github.waleed875.memo.modules.dashboard.application;

/**
 * Explicitly restricted internal diagnostics contract. Neither the name
 * nor the shape substitutes for the full legacy /api/dashboard/snapshot.
 */
public record DashboardCoreSnapshot(
    DashboardCoreSummary summary, DashboardCoreSla sla,
    DashboardSummaryCriteria effectiveFilters
) {}
