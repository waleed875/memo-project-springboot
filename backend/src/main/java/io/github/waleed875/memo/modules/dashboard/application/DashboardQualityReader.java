package io.github.waleed875.memo.modules.dashboard.application;

import java.util.UUID;

/** Fresh active DB-role validation is mandatory in the JDBC adapter. */
public interface DashboardQualityReader {
    DashboardQualitySnapshot read(UUID actorId, DashboardSummaryCriteria filters, int minSample);
}
