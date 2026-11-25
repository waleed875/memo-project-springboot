package io.github.waleed875.memo.modules.dashboard.application;

import java.time.LocalDate;
import java.util.UUID;

/** Read-side port; JDBC verifies current live ADMIN role in its transaction. */
public interface DashboardActivityReader {
    DashboardActivitySnapshot read(
        UUID actorId, DashboardSummaryCriteria criteria, LocalDate businessDay
    );
}
