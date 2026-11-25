package io.github.waleed875.memo.modules.dashboard.application;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Internal, transparent composition of migrated dashboard slices. NOT the
 * complete original C# DashboardSnapshot wire contract (see parityGaps).
 */
public record DashboardUnifiedSnapshot(
    DashboardCoreSummary summary,
    DashboardCoreSla sla,
    DashboardDistributions distributions,
    DashboardQualitySnapshot quality,
    DashboardPerformanceBreakdown performance,
    DashboardActivitySnapshot activity,
    DashboardFlowSnapshot flow,
    DashboardCompanyComparison company,
    DashboardUnifiedViews views,
    DashboardSummaryCriteria effectiveFilters,
    OffsetDateTime generatedAt,
    int minSample,
    List<String> parityGaps
) {
    public DashboardUnifiedSnapshot { parityGaps = List.copyOf(parityGaps); }
}
