package io.github.waleed875.memo.modules.dashboard.application;

import java.util.UUID;

/** Repository adapter must re-check the live ADMIN role in the same read TX. */
public interface DashboardPerformanceReader {
    DashboardPerformanceBreakdown read(UUID actorId, DashboardSummaryCriteria criteria);
}
