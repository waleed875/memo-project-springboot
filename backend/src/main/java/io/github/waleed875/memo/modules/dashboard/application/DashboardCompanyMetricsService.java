package io.github.waleed875.memo.modules.dashboard.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Port of the C# paired-period rule: previous period exists only when
 * companyId, from and to are all supplied. Date bounds are inclusive.
 */
public final class DashboardCompanyMetricsService {
    private static final UUID ZERO=new UUID(0L,0L);
    private static final long MAX_PERIOD_DAYS=36525L; // bound expensive comparisons
    private final DashboardCompanyMetricsReader reader;

    public DashboardCompanyMetricsService(DashboardCompanyMetricsReader reader) {
        this.reader=Objects.requireNonNull(reader);
    }

    public DashboardCompanyComparison read(UserProfile actor, DashboardSummaryCriteria filters) {
        if(actor==null || actor.id()==null || ZERO.equals(actor.id()) || actor.role()==null)
            throw new UnauthenticatedMasterDataAccess();
        MemoRole role;
        try { role=MemoRole.fromDatabase(actor.role()); }
        catch(IllegalArgumentException ex) { throw new MasterDataForbiddenException(); }
        if(role!=MemoRole.ADMIN) throw new MasterDataForbiddenException();
        if(filters==null) throw new InvalidDashboardSummaryRequest("معايير التصفية مطلوبة.");

        DashboardCompanyPeriod previous=null;
        LocalDate from=filters.from(),to=filters.to();
        if(from!=null && to!=null) {
            long days=ChronoUnit.DAYS.between(from,to)+1;
            if(days>MAX_PERIOD_DAYS)
                throw new InvalidDashboardSummaryRequest("فترة التقرير طويلة جدًا.");
            if(filters.companyId()!=null) {
                try {
                    LocalDate previousTo=from.minusDays(1);
                    LocalDate previousFrom=previousTo.minusDays(days-1);
                    LocalDate previousCutoff=filters.entryCutoffDate()==null
                        ? null:filters.entryCutoffDate().minusDays(days);
                    previous=new DashboardCompanyPeriod(
                        previousFrom,previousTo,filters.dateBasis(),previousCutoff);
                } catch(java.time.DateTimeException ex) {
                    throw new InvalidDashboardSummaryRequest("الفترة السابقة خارج نطاق التاريخ.");
                }
            }
        }
        return reader.read(actor.id(),filters,previous);
    }
}
