package io.github.waleed875.memo.modules.dashboard.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Admin-only dashboard activity, with bounded month filling and a business clock. */
public final class DashboardActivityService {
    private static final UUID ZERO = new UUID(0L, 0L);
    private final DashboardActivityReader reader;
    private final Clock businessClock;

    public DashboardActivityService(DashboardActivityReader reader, Clock businessClock) {
        this.reader = Objects.requireNonNull(reader);
        this.businessClock = Objects.requireNonNull(businessClock);
    }

    public DashboardActivitySnapshot read(UserProfile actor, DashboardSummaryCriteria criteria) {
        if (actor == null || actor.id() == null || ZERO.equals(actor.id())
            || actor.role() == null) throw new UnauthenticatedMasterDataAccess();
        MemoRole role;
        try { role = MemoRole.fromDatabase(actor.role()); }
        catch (IllegalArgumentException ex) { throw new MasterDataForbiddenException(); }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        if (criteria == null) throw new InvalidDashboardSummaryRequest("معايير التصفية مطلوبة.");
        if (criteria.from() != null && criteria.to() != null
            && ChronoUnit.MONTHS.between(
                YearMonth.from(criteria.from()), YearMonth.from(criteria.to())) > 239) {
            throw new InvalidDashboardSummaryRequest("الفترة المدعومة لا تتجاوز 240 شهرًا.");
        }
        return reader.read(actor.id(), criteria, LocalDate.now(businessClock));
    }
}
