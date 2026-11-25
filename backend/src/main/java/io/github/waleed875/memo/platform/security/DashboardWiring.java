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

    @Bean DashboardLookupsService dashboardLookupsService(DashboardLookupsReader reader) {
        return new DashboardLookupsService(reader);
    }
}
