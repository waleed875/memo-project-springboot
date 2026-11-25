package io.github.waleed875.memo.platform.security;

import io.github.waleed875.memo.modules.dashboard.application.DashboardLookupsReader;
import io.github.waleed875.memo.modules.dashboard.application.DashboardLookupsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HTTP, application logic and JDBC stay independently testable. */
@Configuration
public class DashboardWiring {
    @Bean DashboardLookupsService dashboardLookupsService(DashboardLookupsReader reader) {
        return new DashboardLookupsService(reader);
    }
}
