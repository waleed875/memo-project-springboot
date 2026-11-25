package io.github.waleed875.memo.modules.dashboard.application;

import java.util.UUID;

/** Read-side port; the adapter verifies the current database ADMIN role. */
public interface DashboardDistributionsReader {
    DashboardDistributions read(UUID actorId, DashboardSummaryCriteria criteria);
}
