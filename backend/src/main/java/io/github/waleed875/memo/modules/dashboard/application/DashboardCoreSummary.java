package io.github.waleed875.memo.modules.dashboard.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The original C# DashboardSummary scalar fields only.
 * This is deliberately NOT the larger legacy DashboardSnapshot DTO.
 */
public record DashboardCoreSummary(
    long totalNotes, long notesToday, long notesCurrentMonth,
    long activeCompaniesTotal, long companiesWithNotes,
    long approved, long pendingReview, long returnedForCorrection,
    long companyResend, long duplicateNumbers,
    long duplicateIncidentCount, long duplicateAffectedNotes,
    long nonDuplicateNotes, long lateTotal, long lateSameMonth,
    long lateCrossMonth, long onTime, Double avgLateDays,
    Double maxLateDays, LocalDate latestArrivalDate,
    @JsonIgnore LocalDateTime latestArrivalTimestamp
) {
    // Compatibility constructor for existing read-model callers and fixtures.
    public DashboardCoreSummary(
        long totalNotes, long notesToday, long notesCurrentMonth,
        long activeCompaniesTotal, long companiesWithNotes,
        long approved, long pendingReview, long returnedForCorrection,
        long companyResend, long duplicateNumbers,
        long duplicateIncidentCount, long duplicateAffectedNotes,
        long nonDuplicateNotes, long lateTotal, long lateSameMonth,
        long lateCrossMonth, long onTime, Double avgLateDays,
        Double maxLateDays, LocalDate latestArrivalDate
    ) {
        this(totalNotes,notesToday,notesCurrentMonth,activeCompaniesTotal,
            companiesWithNotes,approved,pendingReview,returnedForCorrection,
            companyResend,duplicateNumbers,duplicateIncidentCount,
            duplicateAffectedNotes,nonDuplicateNotes,lateTotal,lateSameMonth,
            lateCrossMonth,onTime,avgLateDays,maxLateDays,latestArrivalDate,
            latestArrivalDate == null ? null : latestArrivalDate.atStartOfDay());
    }
}
