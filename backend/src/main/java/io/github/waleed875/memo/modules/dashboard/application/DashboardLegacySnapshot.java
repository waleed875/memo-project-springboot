package io.github.waleed875.memo.modules.dashboard.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Flat original C# DashboardSnapshot wire property manifest.
 * An INTERNAL preview only until end-to-end historical and browser parity.
 * The envelope with parityGaps is separate from this exact DTO shape.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DashboardLegacySnapshot(
    Summary summary,
    List<DashboardDistributions.CompanyTopItem> companies,
    List<DashboardPerformanceItem> timelinessByCompany,
    List<DashboardDistributions.MonthlyActivity> monthlyActivity,
    List<DashboardDistributions.StatusDistribution> statusDistribution,
    List<DashboardActivitySnapshot.DuplicateBreakdownItem> duplicateBreakdown,
    List<RecentNote> recentNotes,
    boolean isHealthy,
    LocalDateTime serverTime,
    LocalDateTime generatedAt,
    int minSample,
    String errorMessage,
    DashboardCoreSla sla,
    List<DashboardActivitySnapshot.BacklogAgeItem> backlogByAge,
    List<DashboardFlowSnapshot.IncomingVsApprovedItem> incomingVsApproved,
    List<DashboardQualitySnapshot.CompanyQuality> qualityByCompany,
    List<TopErrorField> topErrorFields,
    DashboardQualitySnapshot.FieldQualitySummary fieldQuality,
    List<DashboardQualitySnapshot.IndicatorState> indicatorStateDistribution,
    List<DashboardPerformanceItem> performanceByFactory,
    List<DashboardPerformanceItem> performanceByLine,
    List<DashboardActivitySnapshot.DuplicateTrendItem> duplicateTrend,
    List<DashboardFlowSnapshot.DataCompletenessItem> dataCompleteness,
    EffectiveFilters effectiveFilters,
    DashboardCompanyMetrics companyMetrics,
    DashboardCompanyMetrics previousCompanyMetrics,
    Period previousPeriod,
    DashboardLegacyViewsContract views
) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(
        long totalNotes,
        long notesToday,
        long notesCurrentMonth,
        long activeCompaniesTotal,
        long companiesWithNotes,
        long approved,
        long pendingReview,
        long returnedForCorrection,
        long companyResend,
        long duplicateNumbers,
        long duplicateIncidentCount,
        long duplicateAffectedNotes,
        long nonDuplicateNotes,
        long lateTotal,
        long lateSameMonth,
        long lateCrossMonth,
        long onTime,
        Double avgLateDays,
        Double maxLateDays,
        LocalDateTime latestArrivalDate
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecentNote(
        UUID id,
        String noteNumber,
        String companyName,
        String factoryName,
        String productionLineName,
        String noteTypeName,
        String status,
        String statusLabel,
        LocalDateTime writingDate,
        LocalDateTime arrivalAt,
        boolean isLate
    ) {}

    /**
     * The C# GetSnapshotAsync projection sets only the first six fields.
     * Remaining four long fields are CLR defaults (0); nullable rate is null.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TopErrorField(
        UUID fieldId,
        String fieldName,
        String label,
        long sampleSize,
        long errorCount,
        Double errorRate,
        long totalFieldCount,
        long correctFieldCount,
        long errorFieldCount,
        long unknownFieldCount,
        Double fieldAccuracyRate
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EffectiveFilters(
        LocalDateTime from,
        LocalDateTime to,
        String dateBasis,
        UUID companyId,
        UUID factoryId,
        UUID productionLineId,
        UUID noteTypeId,
        String status,
        LocalDateTime entryCutoffDate
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Period(
        LocalDateTime from,
        LocalDateTime to,
        String dateBasis,
        LocalDateTime entryCutoffDate
    ) {}
}
