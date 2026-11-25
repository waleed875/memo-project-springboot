package io.github.waleed875.memo.modules.dashboard.application;

import java.util.UUID;

/** Adapter should execute both original PostgreSQL v4 calls in one read transaction. */
public interface DashboardCompanyMetricsReader {
    DashboardCompanyComparison read(
        UUID actorId, DashboardSummaryCriteria filters, DashboardCompanyPeriod previous
    );
}
