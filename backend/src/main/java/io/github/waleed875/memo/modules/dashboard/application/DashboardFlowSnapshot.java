package io.github.waleed875.memo.modules.dashboard.application;

import java.util.List;

/**
 * Restricted legacy component projection; not the complete DashboardSnapshot.
 * Incoming uses arrival_at; approved uses approved_at, as in original C# SQL.
 */
public record DashboardFlowSnapshot(
    List<IncomingVsApprovedItem> incomingVsApproved,
    List<DataCompletenessItem> dataCompleteness,
    DashboardSummaryCriteria effectiveFilters
) {
    public DashboardFlowSnapshot {
        incomingVsApproved = List.copyOf(incomingVsApproved);
        dataCompleteness = List.copyOf(dataCompleteness);
    }
    public record IncomingVsApprovedItem(
        String periodKey, String periodLabel, String label, long incoming, long approved
    ) {}
    public record DataCompletenessItem(String code, String label, long value) {}
}
