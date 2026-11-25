package io.github.waleed875.memo.modules.dashboard.application;

import java.util.List;
import java.util.UUID;

/**
 * Internal quality-only projection of DashboardSnapshot. No incomplete legacy
 * /api/dashboard/snapshot DTO, and no invented time-compliance metrics.
 */
public record DashboardQualitySnapshot(
    FieldQualitySummary fieldQuality,
    List<CompanyQuality> qualityByCompany,
    List<DimensionQuality> qualityByFactory,
    List<DimensionQuality> qualityByLine,
    List<TopErrorField> topErrorFields,
    List<IndicatorState> indicatorStateDistribution,
    DashboardSummaryCriteria effectiveFilters,
    int minSample
) {
    public DashboardQualitySnapshot {
        qualityByCompany = List.copyOf(qualityByCompany);
        qualityByFactory = List.copyOf(qualityByFactory);
        qualityByLine = List.copyOf(qualityByLine);
        topErrorFields = List.copyOf(topErrorFields);
        indicatorStateDistribution = List.copyOf(indicatorStateDistribution);
    }

    public record FieldQualitySummary(
        long totalFieldCount, long correctFieldCount, long errorFieldCount,
        long unknownFieldCount, Double accuracyRate
    ) {}

    public record CompanyQuality(
        UUID companyId, String companyName, String label,
        long sampleSize, long correctCount, long errorCount,
        long qualityEligibleCount, long allNotApplicableCount,
        Double accuracyRate, Double errorRate,
        long totalFieldCount, long correctFieldCount,
        long errorFieldCount, long unknownFieldCount, Double fieldAccuracyRate
    ) {}

    /** Quality part of legacy PerformanceItem; time statistics come from phase 45. */
    public record DimensionQuality(
        UUID dimensionId, String label, long sampleCount, long correctCount,
        long errorCount, long qualityEligibleCount, Double accuracyRate
    ) {}

    public record TopErrorField(
        UUID fieldId, String fieldName, String label, long sampleSize,
        long errorCount, Double errorRate
    ) {}

    public record IndicatorState(int code, String stateLabel, long value) {}
}
