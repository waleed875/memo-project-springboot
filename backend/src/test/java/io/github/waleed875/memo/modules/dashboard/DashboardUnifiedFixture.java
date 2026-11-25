package io.github.waleed875.memo.modules.dashboard;

import io.github.waleed875.memo.modules.dashboard.application.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Deterministic, framework-free fully populated read-model fixture. */
public final class DashboardUnifiedFixture {
    private DashboardUnifiedFixture() {}

    public static DashboardUnifiedSnapshot build(UUID companyId) {
        UUID id=companyId==null?UUID.fromString("e1000000-0000-4000-8000-000000000001"):companyId;
        LocalDate d=LocalDate.of(2026,11,25);
        var filters=new DashboardSummaryCriteria(
            LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),
            "ArrivalDate",companyId,null,null,null,null,null);
        var summary=new DashboardCoreSummary(
            3,1,3,1,1,1,1,0,0,0,0,0,3,1,1,0,2,6.0,6.0,d);
        var sla=new DashboardCoreSla(2,1,1,50.0,0);
        var dists=new DashboardDistributions(
            List.of(new DashboardDistributions.MonthlyActivity("2026-11","نوفمبر 2026",3)),
            List.of(new DashboardDistributions.StatusDistribution("APPROVED","معتمدة",1)),
            List.of(new DashboardDistributions.CompanyTopItem(id,"شركة",3)),
            List.of(),List.of(),List.of(),filters);
        var quality=new DashboardQualitySnapshot(
            new DashboardQualitySnapshot.FieldQualitySummary(4,3,1,0,75.0),
            List.of(new DashboardQualitySnapshot.CompanyQuality(
                id,"شركة","شركة",3,1,1,2,0,50.0,50.0,4,3,1,0,75.0)),
            List.of(),List.of(),
            List.of(new DashboardQualitySnapshot.TopErrorField(
                UUID.fromString("e1000000-0000-4000-8000-000000000003"),"حقل","حقل",4,1,25.0)),
            List.of(new DashboardQualitySnapshot.IndicatorState(1,"صحيح",3)),
            filters,10);
        var performance=new DashboardPerformanceBreakdown(
            List.of(new DashboardPerformanceItem(
                id,"شركة",3,2,1,1,3,1,1,2,50.0,50.0)),
            List.of(),List.of(),filters);
        var activity=new DashboardActivitySnapshot(
            List.of(new DashboardActivitySnapshot.DuplicateTrendItem(
                "2026-11","نوفمبر 2026","نوفمبر 2026",1,0)),
            List.of(new DashboardActivitySnapshot.DuplicateBreakdownItem(
                "COMPANY_RESEND","إعادة إرسال من الشركة",1)),
            List.of(new DashboardActivitySnapshot.RecentNoteItem(
                UUID.fromString("e1000000-0000-4000-8000-000000000002"),"N-1","شركة",null,null,"نوع","APPROVED",
                "معتمدة",LocalDate.of(2026,11,19),d,true)),
            List.of(new DashboardActivitySnapshot.BacklogAgeItem(
                "1-3-days","1-3 أيام",1)),filters);
        var flow=new DashboardFlowSnapshot(
            List.of(new DashboardFlowSnapshot.IncomingVsApprovedItem(
                "2026-11","نوفمبر 2026","نوفمبر 2026",3,1)),
            List.of(new DashboardFlowSnapshot.DataCompletenessItem(
                "missing-factory","دون مصنع",1)),filters);
        var company=new DashboardCompanyComparison(null,null,null,filters);
        var views=DashboardUnifiedViewsBuilder.build(
            new DashboardCoreSnapshot(summary,sla,filters),
            dists,quality,performance,activity,flow,company);
        return new DashboardUnifiedSnapshot(summary,sla,dists,quality,performance,
            activity,flow,company,views,filters,
            OffsetDateTime.parse("2026-11-25T12:00:00+03:00"),10,
            List.of("browser parity pending"));
    }
}
