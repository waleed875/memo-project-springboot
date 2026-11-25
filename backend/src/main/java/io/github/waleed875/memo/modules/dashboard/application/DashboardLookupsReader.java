package io.github.waleed875.memo.modules.dashboard.application;

import java.util.UUID;

/** Read-only port; implementation must confirm active DB role before loading dashboard data. */
public interface DashboardLookupsReader {
    DashboardLookups read(UUID actorId);
}
