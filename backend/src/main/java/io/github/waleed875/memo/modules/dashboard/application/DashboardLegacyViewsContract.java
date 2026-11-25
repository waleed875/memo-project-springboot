package io.github.waleed875.memo.modules.dashboard.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Exactly named C# DashboardContractDtos fields; internal preview only. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DashboardLegacyViewsContract(
    Context context,
    ExecutiveSummary executiveSummary,
    OperationsSummary operationsSummary,
    CompanySummary companySummary
) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Context(Period period, Scope scope) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Period(
        LocalDateTime from, LocalDateTime to, String dateBasis,
        LocalDateTime entryCutoffDate
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Scope(
        UUID companyId, UUID factoryId, UUID productionLineId,
        UUID noteTypeId, String status
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Availability(String state,boolean hasRows,String explanation) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ExecutiveSummary(
        Availability availability,
        DashboardLegacySnapshot.Summary summary,
        DashboardCoreSla sla,
        DashboardCompanyMetrics metrics,
        DashboardQualitySnapshot.FieldQualitySummary fieldQuality,
        List<DashboardDistributions.CompanyTopItem> companyComparison,
        List<DashboardPerformanceItem> timelinessByCompany,
        List<DashboardDistributions.MonthlyActivity> monthlyTrend,
        List<DashboardDistributions.StatusDistribution> statusDistribution,
        List<DashboardQualitySnapshot.CompanyQuality> qualityByCompany,
        List<DashboardLegacySnapshot.TopErrorField> highImpactProblems
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OperationsSummary(
        Availability availability,
        DashboardLegacySnapshot.Summary summary,
        DashboardCoreSla sla,
        DashboardCompanyMetrics metrics,
        List<DashboardFlowSnapshot.IncomingVsApprovedItem> incomingVsApproved,
        List<DashboardActivitySnapshot.BacklogAgeItem> backlogByAge,
        List<DashboardDistributions.StatusDistribution> workflowStatus,
        List<DashboardPerformanceItem> performanceByFactory,
        List<DashboardPerformanceItem> performanceByLine,
        List<DashboardActivitySnapshot.DuplicateBreakdownItem> repetitionClassification,
        List<DashboardActivitySnapshot.DuplicateTrendItem> repetitionTrend,
        List<DashboardQualitySnapshot.IndicatorState> indicatorStates,
        List<DashboardFlowSnapshot.DataCompletenessItem> dataCompleteness,
        List<DashboardLegacySnapshot.TopErrorField> topErrorFields,
        List<DashboardLegacySnapshot.RecentNote> recentNotes
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CompanySummary(
        Availability availability,
        DashboardDistributions.CompanyTopItem company,
        DashboardCompanyMetrics metrics,
        DashboardCompanyMetrics previousMetrics,
        DashboardLegacySnapshot.Period previousPeriod,
        DashboardUnifiedViews.Comparison comparison,
        List<DashboardDistributions.MonthlyActivity> monthlyTrend,
        List<DashboardDistributions.StatusDistribution> statusDistribution,
        List<DashboardFlowSnapshot.IncomingVsApprovedItem> incomingVsApproved,
        List<DashboardActivitySnapshot.DuplicateBreakdownItem> repetitionClassification,
        List<DashboardActivitySnapshot.DuplicateTrendItem> repetitionTrend,
        List<DashboardPerformanceItem> performanceByFactory,
        List<DashboardPerformanceItem> performanceByLine,
        List<DashboardQualitySnapshot.CompanyQuality> qualityByCompany,
        List<DashboardLegacySnapshot.TopErrorField> topErrorFields,
        List<DashboardLegacySnapshot.RecentNote> recentNotes
    ) {}
}
