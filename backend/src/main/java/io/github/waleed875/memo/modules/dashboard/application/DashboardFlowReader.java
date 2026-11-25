package io.github.waleed875.memo.modules.dashboard.application;

import java.util.UUID;

public interface DashboardFlowReader {
    DashboardFlowSnapshot read(UUID actorId, DashboardSummaryCriteria criteria);
}
