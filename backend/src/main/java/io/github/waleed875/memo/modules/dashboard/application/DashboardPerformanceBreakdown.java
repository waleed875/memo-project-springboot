package io.github.waleed875.memo.modules.dashboard.application;

import java.util.List;

/**
 * Internal performance slice. Names mirror original DashboardSnapshot members,
 * but this is not a replacement for the full legacy snapshot response.
 */
public record DashboardPerformanceBreakdown(
    List<DashboardPerformanceItem> timelinessByCompany,
    List<DashboardPerformanceItem> performanceByFactory,
    List<DashboardPerformanceItem> performanceByLine,
    DashboardSummaryCriteria effectiveFilters
) {
    public DashboardPerformanceBreakdown {
        timelinessByCompany = List.copyOf(timelinessByCompany);
        performanceByFactory = List.copyOf(performanceByFactory);
        performanceByLine = List.copyOf(performanceByLine);
    }
}
