package io.github.waleed875.memo.modules.dashboard.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.Objects;
import java.util.UUID;

/**
 * Admin-only read model: the user ID comes from a database-validated session,
 * never from a query string or body. JDBC re-checks active ADMIN role.
 */
public final class DashboardQualityService {
    private static final UUID ZERO = new UUID(0L, 0L);
    private final DashboardQualityReader reader;
    public DashboardQualityService(DashboardQualityReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    public DashboardQualitySnapshot read(UserProfile actor, DashboardSummaryCriteria filters,
                                         int minSample) {
        if (actor == null || actor.id() == null || ZERO.equals(actor.id())
            || actor.role() == null) throw new UnauthenticatedMasterDataAccess();
        MemoRole role;
        try { role = MemoRole.fromDatabase(actor.role()); }
        catch (IllegalArgumentException invalid) { throw new MasterDataForbiddenException(); }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        if (filters == null) throw new InvalidDashboardSummaryRequest("معايير التصفية مطلوبة.");
        if (minSample < 1 || minSample > 10000)
            throw new InvalidDashboardSummaryRequest("حجم العينة الأدنى غير صالح.");
        return reader.read(actor.id(), filters, minSample);
    }
}
