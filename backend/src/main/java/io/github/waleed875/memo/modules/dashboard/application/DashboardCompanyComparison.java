package io.github.waleed875.memo.modules.dashboard.application;

/**
 * Internal-only company analytics slice of the larger original snapshot.
 * Absence of previousPeriod means no comparable period was requested.
 */
public record DashboardCompanyComparison(
    DashboardCompanyMetrics companyMetrics,
    DashboardCompanyMetrics previousCompanyMetrics,
    DashboardCompanyPeriod previousPeriod,
    DashboardSummaryCriteria effectiveFilters
) {}
