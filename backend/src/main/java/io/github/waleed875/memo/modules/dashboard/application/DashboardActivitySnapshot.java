package io.github.waleed875.memo.modules.dashboard.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Restricted, read-only slices of the original C# DashboardSnapshot. */
public record DashboardActivitySnapshot(
    List<DuplicateTrendItem> duplicateTrend,
    List<DuplicateBreakdownItem> duplicateBreakdown,
    List<RecentNoteItem> recentNotes,
    List<BacklogAgeItem> backlogByAge,
    DashboardSummaryCriteria effectiveFilters
) {
    public DashboardActivitySnapshot {
        duplicateTrend = List.copyOf(duplicateTrend);
        duplicateBreakdown = List.copyOf(duplicateBreakdown);
        recentNotes = List.copyOf(recentNotes);
        backlogByAge = List.copyOf(backlogByAge);
    }

    public record DuplicateTrendItem(
        String periodKey, String periodLabel, String label,
        long companyResend, long duplicateNumber
    ) {}
    public record DuplicateBreakdownItem(
        String classification, String label, long count
    ) {}
    public record RecentNoteItem(
        UUID id, String noteNumber, String companyName, String factoryName,
        String productionLineName, String noteTypeName,
        String status, String statusLabel, LocalDate writingDate,
        LocalDate arrivalAt, boolean isLate
    ) {}
    public record BacklogAgeItem(String code, String label, long value) {}
}
