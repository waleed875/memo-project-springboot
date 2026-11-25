package io.github.waleed875.memo.modules.dashboard.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure port of C# DashboardContractBuilder; null means unavailable, never 0. */
public final class DashboardUnifiedViewsBuilder {
    private static final String NO_DATA =
        "لا توجد مذكرات ضمن النطاق المحدد؛ لا تُعرض الحالة كصفر افتراضي.";
    private DashboardUnifiedViewsBuilder() {}

    public static DashboardUnifiedViews build(
        DashboardCoreSnapshot core, DashboardDistributions distributions,
        DashboardQualitySnapshot quality, DashboardPerformanceBreakdown performance,
        DashboardActivitySnapshot activity, DashboardFlowSnapshot flow,
        DashboardCompanyComparison company
    ) {
        boolean hasRows = core.summary().totalNotes() > 0;
        var availability = new DashboardUnifiedViews.Availability(
            hasRows ? "available" : "no-data", hasRows, hasRows ? null : NO_DATA);
        var executive = new DashboardUnifiedViews.ExecutiveSummary(
            availability,core.summary(),core.sla(),company.companyMetrics(),
            quality.fieldQuality(),distributions.companies(),
            performance.timelinessByCompany(),distributions.monthlyActivity(),
            distributions.statusDistribution(),quality.qualityByCompany(),
            quality.topErrorFields());
        var operations = new DashboardUnifiedViews.OperationsSummary(
            availability,core.summary(),core.sla(),company.companyMetrics(),
            flow.incomingVsApproved(),activity.backlogByAge(),
            distributions.statusDistribution(),performance.performanceByFactory(),
            performance.performanceByLine(),activity.duplicateBreakdown(),
            activity.duplicateTrend(),quality.indicatorStateDistribution(),
            flow.dataCompleteness(),quality.topErrorFields(),activity.recentNotes());

        DashboardUnifiedViews.CompanySummary companyView = null;
        if (core.effectiveFilters().companyId() != null) {
            var companyId = core.effectiveFilters().companyId();
            var chosen = distributions.companies().stream()
                .filter(item -> companyId.equals(item.id()))
                .findFirst().orElse(null);
            companyView = new DashboardUnifiedViews.CompanySummary(
                availability,chosen,company.companyMetrics(),
                company.previousCompanyMetrics(),company.previousPeriod(),
                compare(company.companyMetrics(),company.previousCompanyMetrics()),
                distributions.monthlyActivity(),distributions.statusDistribution(),
                flow.incomingVsApproved(),activity.duplicateBreakdown(),
                activity.duplicateTrend(),performance.performanceByFactory(),
                performance.performanceByLine(),quality.qualityByCompany(),
                quality.topErrorFields(),activity.recentNotes());
        }
        return new DashboardUnifiedViews(
            new DashboardUnifiedViews.Context(core.effectiveFilters()),
            executive,operations,companyView);
    }

    static DashboardUnifiedViews.Comparison compare(
        DashboardCompanyMetrics current,DashboardCompanyMetrics previous
    ) {
        if (current == null || previous == null
            || current.totalNotes() <= 0 || previous.totalNotes() <= 0) return null;
        return new DashboardUnifiedViews.Comparison(
            delta(current.itemQualityPercentage(),previous.itemQualityPercentage()),
            delta(current.timeCompliancePercentage(),previous.timeCompliancePercentage()),
            Math.subtractExact(current.totalNotes(),previous.totalNotes()));
    }

    private static Double delta(BigDecimal current, BigDecimal prior) {
        if (current == null || prior == null) return null;
        return current.subtract(prior).setScale(2,RoundingMode.HALF_EVEN).doubleValue();
    }
}
