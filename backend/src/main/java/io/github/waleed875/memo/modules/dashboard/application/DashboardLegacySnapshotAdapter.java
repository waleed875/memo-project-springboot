package io.github.waleed875.memo.modules.dashboard.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Pure C# DashboardSnapshot JSON-shape adapter. Does not manufacture a
 * successful public API release, and does not execute additional queries.
 */
public final class DashboardLegacySnapshotAdapter {
    private DashboardLegacySnapshotAdapter() {}

    public static DashboardLegacySnapshotPreview toPreview(DashboardUnifiedSnapshot u) {
        var legacy = toSnapshot(u);
        return new DashboardLegacySnapshotPreview(legacy, List.copyOf(u.parityGaps()), false);
    }

    public static DashboardLegacySnapshot toSnapshot(DashboardUnifiedSnapshot u) {
        var d=u.distributions();
        var q=u.quality();
        var p=u.performance();
        var a=u.activity();
        var f=u.flow();
        var c=u.company();
        var filters=effective(u.effectiveFilters());
        var previous=period(c.previousPeriod());
        var recent=a.recentNotes().stream().map(DashboardLegacySnapshotAdapter::recent).toList();
        var errors=q.topErrorFields().stream().map(DashboardLegacySnapshotAdapter::error).toList();
        var summary=summary(u.summary());
        var views=views(u,summary,previous,recent,errors);
        LocalDateTime generated=u.generatedAt().toLocalDateTime();
        return new DashboardLegacySnapshot(
            summary,d.companies(),p.timelinessByCompany(),
            d.monthlyActivity(),d.statusDistribution(),
            a.duplicateBreakdown(),recent,true,generated,generated,
            u.minSample(),null,u.sla(),a.backlogByAge(),f.incomingVsApproved(),
            q.qualityByCompany(),errors,q.fieldQuality(),
            q.indicatorStateDistribution(),p.performanceByFactory(),
            p.performanceByLine(),a.duplicateTrend(),f.dataCompleteness(),
            filters,c.companyMetrics(),c.previousCompanyMetrics(),
            previous,views);
    }

    static DashboardLegacySnapshot.Summary summary(DashboardCoreSummary s) {
        return new DashboardLegacySnapshot.Summary(
            s.totalNotes(),s.notesToday(),s.notesCurrentMonth(),
            s.activeCompaniesTotal(),s.companiesWithNotes(),
            s.approved(),s.pendingReview(),s.returnedForCorrection(),
            s.companyResend(),s.duplicateNumbers(),
            s.duplicateIncidentCount(),s.duplicateAffectedNotes(),
            s.nonDuplicateNotes(),s.lateTotal(),s.lateSameMonth(),
            s.lateCrossMonth(),s.onTime(),s.avgLateDays(),s.maxLateDays(),
            s.latestArrivalTimestamp() != null
                ? s.latestArrivalTimestamp() : midnight(s.latestArrivalDate()));
    }

    static DashboardLegacySnapshot.EffectiveFilters effective(DashboardSummaryCriteria f) {
        return new DashboardLegacySnapshot.EffectiveFilters(
            midnight(f.from()),midnight(f.to()),f.dateBasis(),
            f.companyId(),f.factoryId(),f.productionLineId(),
            f.noteTypeId(),f.status(),midnight(f.entryCutoffDate()));
    }

    static DashboardLegacySnapshot.Period period(DashboardCompanyPeriod p) {
        return p == null ? null : new DashboardLegacySnapshot.Period(
            midnight(p.from()),midnight(p.to()),p.dateBasis(),
            midnight(p.entryCutoffDate()));
    }

    static DashboardLegacySnapshot.RecentNote recent(
        DashboardActivitySnapshot.RecentNoteItem n
    ) {
        return new DashboardLegacySnapshot.RecentNote(
            n.id(),n.noteNumber(),n.companyName(),n.factoryName(),
            n.productionLineName(),n.noteTypeName(),n.status(),
            n.statusLabel(),midnight(n.writingDate()),
            n.arrivalTimestamp() != null ? n.arrivalTimestamp() : midnight(n.arrivalAt()),
            n.isLate());
    }

    static DashboardLegacySnapshot.TopErrorField error(
        DashboardQualitySnapshot.TopErrorField f
    ) {
        // The five C# properties below are NOT assigned by GetSnapshotAsync.
        // Four integer properties retain CLR default 0, nullable rate stays null.
        return new DashboardLegacySnapshot.TopErrorField(
            f.fieldId(),f.fieldName(),f.label(),f.sampleSize(),
            f.errorCount(),f.errorRate(),0,0,0,0,null);
    }

    private static LocalDateTime midnight(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private static DashboardLegacyViewsContract views(
        DashboardUnifiedSnapshot u,
        DashboardLegacySnapshot.Summary summary,
        DashboardLegacySnapshot.Period previousPeriod,
        List<DashboardLegacySnapshot.RecentNote> recent,
        List<DashboardLegacySnapshot.TopErrorField> errors
    ) {
        var filter=u.effectiveFilters();
        var context=new DashboardLegacyViewsContract.Context(
            new DashboardLegacyViewsContract.Period(
                midnight(filter.from()),midnight(filter.to()),
                filter.dateBasis(),midnight(filter.entryCutoffDate())),
            new DashboardLegacyViewsContract.Scope(
                filter.companyId(),filter.factoryId(),filter.productionLineId(),
                filter.noteTypeId(),filter.status()));
        var availability=u.views().executiveSummary().availability();
        var available=new DashboardLegacyViewsContract.Availability(
            availability.state(),availability.hasRows(),availability.explanation());
        var d=u.distributions();
        var q=u.quality();
        var p=u.performance();
        var a=u.activity();
        var f=u.flow();
        var c=u.company();

        var exec=new DashboardLegacyViewsContract.ExecutiveSummary(
            available,summary,u.sla(),c.companyMetrics(),q.fieldQuality(),
            d.companies(),p.timelinessByCompany(),d.monthlyActivity(),
            d.statusDistribution(),q.qualityByCompany(),errors);
        var operations=new DashboardLegacyViewsContract.OperationsSummary(
            available,summary,u.sla(),c.companyMetrics(),f.incomingVsApproved(),
            a.backlogByAge(),d.statusDistribution(),p.performanceByFactory(),
            p.performanceByLine(),a.duplicateBreakdown(),a.duplicateTrend(),
            q.indicatorStateDistribution(),f.dataCompleteness(),errors,recent);

        DashboardLegacyViewsContract.CompanySummary company=null;
        if (filter.companyId() != null) {
            var chosen=d.companies().stream()
                .filter(row->row.id().equals(filter.companyId()))
                .findFirst().orElse(null);
            company=new DashboardLegacyViewsContract.CompanySummary(
                available,chosen,c.companyMetrics(),c.previousCompanyMetrics(),
                previousPeriod,
                DashboardUnifiedViewsBuilder.compare(
                    c.companyMetrics(),c.previousCompanyMetrics()),
                d.monthlyActivity(),d.statusDistribution(),f.incomingVsApproved(),
                a.duplicateBreakdown(),a.duplicateTrend(),
                p.performanceByFactory(),p.performanceByLine(),
                q.qualityByCompany(),errors,recent);
        }
        return new DashboardLegacyViewsContract(context,exec,operations,company);
    }
}
