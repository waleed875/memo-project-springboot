package io.github.waleed875.memo.platform.security;

import io.github.waleed875.memo.modules.dashboard.application.DashboardLookupsReader;
import io.github.waleed875.memo.modules.dashboard.application.DashboardLookupsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HTTP, application logic and JDBC stay independently testable. */
@Configuration
public class DashboardWiring {
    // The original server evaluates 'today' in the Damascus business time zone.
    @Bean
    io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryService
        dashboardSummaryService(
            io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryReader reader
        ) {
        return new io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryService(
            reader, java.time.Clock.system(java.time.ZoneId.of("Asia/Damascus")));
    }

    @Bean
    io.github.waleed875.memo.modules.dashboard.application.DashboardDistributionsService
        dashboardDistributionsService(
            io.github.waleed875.memo.modules.dashboard.application.DashboardDistributionsReader reader
        ) {
        return new io.github.waleed875.memo.modules.dashboard.application.DashboardDistributionsService(reader);
    }

    @Bean
    io.github.waleed875.memo.modules.dashboard.application.DashboardQualityService
        dashboardQualityService(
            io.github.waleed875.memo.modules.dashboard.application.DashboardQualityReader reader
        ) {
        return new io.github.waleed875.memo.modules.dashboard.application.DashboardQualityService(reader);
    }

    @Bean
    io.github.waleed875.memo.modules.dashboard.application.DashboardPerformanceService
        dashboardPerformanceService(
            io.github.waleed875.memo.modules.dashboard.application.DashboardPerformanceReader reader
        ) {
        return new io.github.waleed875.memo.modules.dashboard.application.DashboardPerformanceService(reader);
    }

    @Bean
    io.github.waleed875.memo.modules.dashboard.application.DashboardActivityService
        dashboardActivityService(
            io.github.waleed875.memo.modules.dashboard.application.DashboardActivityReader reader
        ) {
        return new io.github.waleed875.memo.modules.dashboard.application.DashboardActivityService(
            reader, java.time.Clock.system(java.time.ZoneId.of("Asia/Damascus")));
    }

    @Bean
    io.github.waleed875.memo.modules.dashboard.application.DashboardFlowService
        dashboardFlowService(
            io.github.waleed875.memo.modules.dashboard.application.DashboardFlowReader reader
        ) {
        return new io.github.waleed875.memo.modules.dashboard.application.DashboardFlowService(reader);
    }

    @Bean
    io.github.waleed875.memo.modules.dashboard.application.DashboardCompanyMetricsService
        dashboardCompanyMetricsService(
            io.github.waleed875.memo.modules.dashboard.application.DashboardCompanyMetricsReader reader
        ) {
        return new io.github.waleed875.memo.modules.dashboard.application.DashboardCompanyMetricsService(reader);
    }

    @Bean
    io.github.waleed875.memo.modules.dashboard.application.DashboardUnifiedService
        dashboardUnifiedService(
            io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryReader summary,
            io.github.waleed875.memo.modules.dashboard.application.DashboardDistributionsReader distributions,
            io.github.waleed875.memo.modules.dashboard.application.DashboardQualityReader quality,
            io.github.waleed875.memo.modules.dashboard.application.DashboardPerformanceReader performance,
            io.github.waleed875.memo.modules.dashboard.application.DashboardActivityReader activity,
            io.github.waleed875.memo.modules.dashboard.application.DashboardFlowReader flow,
            io.github.waleed875.memo.modules.dashboard.application.DashboardCompanyMetricsReader company
        ) {
        return new io.github.waleed875.memo.modules.dashboard.application.DashboardUnifiedService(
            summary,distributions,quality,performance,activity,flow,company,
            java.time.Clock.system(java.time.ZoneId.of("Asia/Damascus")));
    }

    @Bean DashboardLookupsService dashboardLookupsService(DashboardLookupsReader reader) {
        return new DashboardLookupsService(reader);
    }
}
