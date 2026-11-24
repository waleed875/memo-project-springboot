package io.github.waleed875.memo.platform.security;

import io.github.waleed875.memo.modules.identity.application.IdentityApplicationService;
import io.github.waleed875.memo.modules.identity.application.PasswordVerifier;
import io.github.waleed875.memo.modules.identity.application.UserAccountLookup;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root for the framework-independent identity use case. */
@Configuration
public class IdentityWiring {
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    IdentityApplicationService identityApplicationService(
        UserAccountLookup accounts, PasswordVerifier passwords, Clock clock
    ) {
        return new IdentityApplicationService(accounts, passwords, clock);
    }
}
