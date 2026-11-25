package io.github.waleed875.memo.modules.dashboard.application;

import java.time.LocalDate;

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
    Double maxLateDays, LocalDate latestArrivalDate
) {}
