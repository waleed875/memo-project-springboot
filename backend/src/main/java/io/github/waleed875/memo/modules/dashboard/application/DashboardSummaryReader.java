package io.github.waleed875.memo.modules.dashboard.application;

import java.time.LocalDate;
import java.util.UUID;

/** DB port; implementation must revalidate current ADMIN role in the read transaction. */
public interface DashboardSummaryReader {
    DashboardCoreSnapshot read(UUID actorId, DashboardSummaryCriteria filters, LocalDate businessDay);
}
