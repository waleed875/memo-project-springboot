package io.github.waleed875.memo.modules.dashboard.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.Objects;
import java.util.UUID;

/** Authenticated admin-only read use case with explicit dependencies. */
public final class DashboardPerformanceService {
    private static final UUID ZERO = new UUID(0L, 0L);
    private final DashboardPerformanceReader reader;
    public DashboardPerformanceService(DashboardPerformanceReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }
    public DashboardPerformanceBreakdown read(UserProfile actor, DashboardSummaryCriteria criteria) {
        if (actor == null || actor.id() == null || ZERO.equals(actor.id())
            || actor.role() == null) throw new UnauthenticatedMasterDataAccess();
        MemoRole role;
        try { role = MemoRole.fromDatabase(actor.role()); }
        catch (IllegalArgumentException ex) { throw new MasterDataForbiddenException(); }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        if (criteria == null)
            throw new InvalidDashboardSummaryRequest("معايير تصفية الأداء مطلوبة.");
        return reader.read(actor.id(), criteria);
    }
}
