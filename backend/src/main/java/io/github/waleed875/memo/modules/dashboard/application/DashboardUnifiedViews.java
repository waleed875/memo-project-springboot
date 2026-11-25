package io.github.waleed875.memo.modules.dashboard.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Internal views composition follows the original DashboardContractBuilder
 * rules. DTOs reference the tested component read models rather than fabricate
 * unavailable legacy fields.
 */
public record DashboardUnifiedViews(
    Context context,
    ExecutiveSummary executiveSummary,
    OperationsSummary operationsSummary,
    CompanySummary companySummary
) {
    public record Context(DashboardSummaryCriteria periodAndScope) {}
    public record Availability(String state, boolean hasRows, String explanation) {}
    public record ExecutiveSummary(
        Availability availability, DashboardCoreSummary summary,
        DashboardCoreSla sla, DashboardCompanyMetrics metrics,
        DashboardQualitySnapshot.FieldQualitySummary fieldQuality,
        List<DashboardDistributions.CompanyTopItem> companyComparison,
        List<DashboardPerformanceItem> timelinessByCompany,
        List<DashboardDistributions.MonthlyActivity> monthlyTrend,
        List<DashboardDistributions.StatusDistribution> statusDistribution,
        List<DashboardQualitySnapshot.CompanyQuality> qualityByCompany,
        List<DashboardQualitySnapshot.TopErrorField> highImpactProblems
    ) {}
    public record OperationsSummary(
        Availability availability, DashboardCoreSummary summary,
        DashboardCoreSla sla, DashboardCompanyMetrics metrics,
        List<DashboardFlowSnapshot.IncomingVsApprovedItem> incomingVsApproved,
        List<DashboardActivitySnapshot.BacklogAgeItem> backlogByAge,
        List<DashboardDistributions.StatusDistribution> workflowStatus,
        List<DashboardPerformanceItem> performanceByFactory,
        List<DashboardPerformanceItem> performanceByLine,
        List<DashboardActivitySnapshot.DuplicateBreakdownItem> repetitionClassification,
        List<DashboardActivitySnapshot.DuplicateTrendItem> repetitionTrend,
        List<DashboardQualitySnapshot.IndicatorState> indicatorStates,
        List<DashboardFlowSnapshot.DataCompletenessItem> dataCompleteness,
        List<DashboardQualitySnapshot.TopErrorField> topErrorFields,
        List<DashboardActivitySnapshot.RecentNoteItem> recentNotes
    ) {}
    public record CompanySummary(
        Availability availability,
        DashboardDistributions.CompanyTopItem company,
        DashboardCompanyMetrics metrics,
        DashboardCompanyMetrics previousMetrics,
        DashboardCompanyPeriod previousPeriod,
        Comparison comparison,
        List<DashboardDistributions.MonthlyActivity> monthlyTrend,
        List<DashboardDistributions.StatusDistribution> statusDistribution,
        List<DashboardFlowSnapshot.IncomingVsApprovedItem> incomingVsApproved,
        List<DashboardActivitySnapshot.DuplicateBreakdownItem> repetitionClassification,
        List<DashboardActivitySnapshot.DuplicateTrendItem> repetitionTrend,
        List<DashboardPerformanceItem> performanceByFactory,
        List<DashboardPerformanceItem> performanceByLine,
        List<DashboardQualitySnapshot.CompanyQuality> qualityByCompany,
        List<DashboardQualitySnapshot.TopErrorField> topErrorFields,
        List<DashboardActivitySnapshot.RecentNoteItem> recentNotes
    ) {}
    public record Comparison(
        Double itemQualityDeltaPoints,
        Double timeComplianceDeltaPoints,
        Integer totalNotesDelta
    ) {}
}
