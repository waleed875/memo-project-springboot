package io.github.waleed875.memo.modules.dashboard.application;

import java.util.List;
import java.util.UUID;

/**
 * Restricted subset of original C# dashboard projections.
 * Factory/line records expose only verified timeliness aggregates; legacy
 * indicator-quality metrics are intentionally absent rather than invented.
 */
public record DashboardDistributions(
    List<MonthlyActivity> monthlyActivity,
    List<StatusDistribution> statusDistribution,
    List<CompanyTopItem> companies,
    List<TimelinessItem> timelinessByCompany,
    List<TimelinessItem> timelinessByFactory,
    List<TimelinessItem> timelinessByLine,
    DashboardSummaryCriteria effectiveFilters
) {
    public DashboardDistributions {
        monthlyActivity = List.copyOf(monthlyActivity);
        statusDistribution = List.copyOf(statusDistribution);
        companies = List.copyOf(companies);
        timelinessByCompany = List.copyOf(timelinessByCompany);
        timelinessByFactory = List.copyOf(timelinessByFactory);
        timelinessByLine = List.copyOf(timelinessByLine);
    }

    public record MonthlyActivity(String month, String monthLabel, long count) {}
    public record StatusDistribution(String status, String statusLabel, long count) {}
    public record CompanyTopItem(UUID id, String name, long noteCount) {}
    public record TimelinessItem(
        UUID dimensionId, String label,
        long totalNotes, long eligibleCount,
        long lateCount, long onTimeCount, Double lateRate
    ) {}
}
