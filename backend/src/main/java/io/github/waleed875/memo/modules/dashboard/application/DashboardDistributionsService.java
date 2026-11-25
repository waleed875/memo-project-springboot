package io.github.waleed875.memo.modules.dashboard.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Preserve admin-only dashboard access; protect internal zero-fill ranges. */
public final class DashboardDistributionsService {
    private static final UUID ZERO = new UUID(0L, 0L);
    private final DashboardDistributionsReader reader;
    public DashboardDistributionsService(DashboardDistributionsReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    public DashboardDistributions read(UserProfile actor, DashboardSummaryCriteria criteria) {
        if (actor == null || actor.id() == null || ZERO.equals(actor.id()) || actor.role() == null)
            throw new UnauthenticatedMasterDataAccess();
        MemoRole role;
        try { role = MemoRole.fromDatabase(actor.role()); }
        catch (IllegalArgumentException ex) { throw new MasterDataForbiddenException(); }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        if (criteria == null) throw new InvalidDashboardSummaryRequest("معايير التصفية مطلوبة.");

        // Internal query safety limit. Public C# snapshot stays disabled and unchanged.
        if (criteria.from() != null && criteria.to() != null
            && ChronoUnit.MONTHS.between(
                YearMonth.from(criteria.from()), YearMonth.from(criteria.to())) > 239) {
            throw new InvalidDashboardSummaryRequest("الفترة المدعومة لا تتجاوز 240 شهرًا.");
        }
        return reader.read(actor.id(), criteria);
    }
}
