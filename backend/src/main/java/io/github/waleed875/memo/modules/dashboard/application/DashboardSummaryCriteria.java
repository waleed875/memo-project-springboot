package io.github.waleed875.memo.modules.dashboard.application;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Filter rules used by original /api/dashboard/snapshot, not a public snapshot DTO. */
public record DashboardSummaryCriteria(
    LocalDate from, LocalDate to, String dateBasis,
    UUID companyId, UUID factoryId, UUID productionLineId,
    UUID noteTypeId, String status, LocalDate entryCutoffDate
) {
    private static final Set<String> STATUSES = Set.of(
        "DRAFT", "NEEDS_REVIEW", "UNDER_REVIEW", "HAS_ERRORS",
        "COMPLETE", "APPROVED", "RETURNED_FOR_CORRECTION");

    public DashboardSummaryCriteria {
        if (from != null && to != null && from.isAfter(to))
            throw new InvalidDashboardSummaryRequest("يجب ألا يكون تاريخ البداية بعد تاريخ النهاية.");
        dateBasis = "ArrivalDate".equalsIgnoreCase(dateBasis) ? "ArrivalDate" : "WritingDate";
        status = status == null || status.isBlank()
            ? null : status.trim().toUpperCase(Locale.ROOT);
        if (status != null && !STATUSES.contains(status))
            throw new InvalidDashboardSummaryRequest("حالة المذكرة غير صالحة.");
    }
}
