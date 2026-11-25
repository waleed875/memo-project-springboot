package io.github.waleed875.memo.modules.dashboard.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single read transaction for all eight previously migrated read-side slices.
 * JDBC adapters recheck active ADMIN roles and join the same transaction.
 * All inputs are normalized BEFORE any query runs.
 */
public class DashboardUnifiedService {
    private static final UUID ZERO=new UUID(0,0);
    private final DashboardSummaryReader summary;
    private final DashboardDistributionsReader distributions;
    private final DashboardQualityReader quality;
    private final DashboardPerformanceReader performance;
    private final DashboardActivityReader activity;
    private final DashboardFlowReader flow;
    private final DashboardCompanyMetricsReader company;
    private final DashboardCompanyMetricsService periods;
    private final Clock clock;
    private static final List<String> GAPS = List.of(
        "snapshot: preview covers JSON field names, not a verified historical-data parity run",
        "quality: five C# TopErrorField defaults are serialized as 0/0/0/0/null, not measured totals",
        "time: C# DateTime kind and business timezone conversions need cross-runtime golden checks",
        "filters: internal minSample and range bounds add migration safety limits",
        "release: React browser E2E and original public snapshot route are not yet enabled"
    );

    public DashboardUnifiedService(
        DashboardSummaryReader summary, DashboardDistributionsReader distributions,
        DashboardQualityReader quality, DashboardPerformanceReader performance,
        DashboardActivityReader activity, DashboardFlowReader flow,
        DashboardCompanyMetricsReader company, Clock clock
    ) {
        this.summary=Objects.requireNonNull(summary);
        this.distributions=Objects.requireNonNull(distributions);
        this.quality=Objects.requireNonNull(quality);
        this.performance=Objects.requireNonNull(performance);
        this.activity=Objects.requireNonNull(activity);
        this.flow=Objects.requireNonNull(flow);
        this.company=Objects.requireNonNull(company);
        this.periods=new DashboardCompanyMetricsService(company);
        this.clock=Objects.requireNonNull(clock);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=60)
    public DashboardUnifiedSnapshot read(
        UserProfile actor,DashboardSummaryCriteria filters,int minSample
    ) {
        if (actor == null || actor.id() == null || ZERO.equals(actor.id())
            || actor.role() == null) throw new UnauthenticatedMasterDataAccess();
        MemoRole role;
        try { role=MemoRole.fromDatabase(actor.role()); }
        catch (IllegalArgumentException ex) { throw new MasterDataForbiddenException(); }
        if(role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        if(filters == null) throw new InvalidDashboardSummaryRequest("معايير التصفية مطلوبة.");
        if(minSample < 1 || minSample > 10000)
            throw new InvalidDashboardSummaryRequest("حجم العينة الأدنى غير صالح.");
        if(filters.from() != null && filters.to() != null) {
            long monthSpan=ChronoUnit.MONTHS.between(
                YearMonth.from(filters.from()),YearMonth.from(filters.to()));
            if(monthSpan > 239)
                throw new InvalidDashboardSummaryRequest("الفترة المدعومة لا تتجاوز 240 شهرًا.");
        }

        UUID id=actor.id();
        LocalDate today=LocalDate.now(clock);
        var core=summary.read(id,filters,today);
        var dist=distributions.read(id,filters);
        var qual=quality.read(id,filters,minSample);
        var perf=performance.read(id,filters);
        var act=activity.read(id,filters,today);
        var fl=flow.read(id,filters);
        var previous=periods.read(actor,filters);
        var views=DashboardUnifiedViewsBuilder.build(core,dist,qual,perf,act,fl,previous);
        return new DashboardUnifiedSnapshot(
            core.summary(),core.sla(),dist,qual,perf,act,fl,previous,
            views,filters,OffsetDateTime.now(clock),minSample,GAPS);
    }
}
