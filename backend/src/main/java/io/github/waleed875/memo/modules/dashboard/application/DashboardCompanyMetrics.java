package io.github.waleed875.memo.modules.dashboard.application;

import java.math.BigDecimal;

/**
 * Exact scalar contract of the original C# CompanyMetrics and the 069 v4
 * PostgreSQL function. Nullable rates retain null when no denominator.
 */
public record DashboardCompanyMetrics(
    int totalNotes,
    int expectedItems,
    int reviewedItems,
    int unreviewedItems,
    int applicableReviewedItems,
    int correctItems,
    int errorItems,
    int notApplicableItems,
    BigDecimal reviewCoveragePercentage,
    BigDecimal itemQualityPercentage,
    BigDecimal errorsPer100Checked,
    int qualityEligibleNoteCount,
    int cleanReviewedNotes,
    int allNotApplicableNoteCount,
    int noExpectedItemsNoteCount,
    BigDecimal cleanReviewedNotePercent,
    int errorNotes,
    int incompleteReviewNotes,
    int timeEligibleNotes,
    int onTimeNotes,
    int lateNotes,
    int late6To10Notes,
    int late11To20Notes,
    int lateOver20Notes,
    int missingWritingDateNotes,
    int invalidDateOrderNotes,
    BigDecimal timeCompliancePercentage,
    BigDecimal averageLateDays,
    BigDecimal medianLateDays,
    BigDecimal p90LateDays,
    Integer maximumLateDays,
    BigDecimal averageExcessDelayDays,
    int duplicateNumberIncidents,
    int companyResendIncidents,
    int duplicateIncidents,
    int affectedNotes,
    BigDecimal incidentsPer100Notes,
    BigDecimal affectedNoteRatePercentage,
    BigDecimal duplicateFreePercentage,
    int entryNotes,
    int linkedEntryNotes,
    int unlinkedEntryNotes,
    BigDecimal linkEfficiencyPercentage
) {}
