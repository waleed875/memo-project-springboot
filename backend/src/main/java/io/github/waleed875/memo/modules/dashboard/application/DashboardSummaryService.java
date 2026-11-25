package io.github.waleed875.memo.modules.dashboard.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Only the administrator may read the internal summary; server session owns actor ID. */
public final class DashboardSummaryService {
    private static final UUID ZERO = new UUID(0L, 0L);
    private final DashboardSummaryReader reader;
    private final Clock businessClock;

    public DashboardSummaryService(DashboardSummaryReader reader, Clock businessClock) {
        this.reader = Objects.requireNonNull(reader);
        this.businessClock = Objects.requireNonNull(businessClock);
    }

    public DashboardCoreSnapshot read(UserProfile actor, DashboardSummaryCriteria criteria) {
        if (actor == null || actor.id() == null || ZERO.equals(actor.id())
            || actor.role() == null) throw new UnauthenticatedMasterDataAccess();
        MemoRole role;
        try { role = MemoRole.fromDatabase(actor.role()); }
        catch (IllegalArgumentException invalid) { throw new MasterDataForbiddenException(); }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        if (criteria == null) throw new InvalidDashboardSummaryRequest("معايير لوحة المعلومات مطلوبة.");
        // Original C# GetBusinessNow uses Asia/Damascus and a UTC+03 fallback.
        return reader.read(actor.id(), criteria, LocalDate.now(businessClock));
    }
}
