package io.github.waleed875.memo.platform.security;

import io.github.waleed875.memo.modules.identity.application.IdentityApplicationService;
import io.github.waleed875.memo.modules.identity.application.PasswordVerifier;
import io.github.waleed875.memo.modules.identity.application.UserAccountLookup;
import java.time.Clock;
import io.github.waleed875.memo.modules.identity.application.PasswordHasher;
import io.github.waleed875.memo.modules.identity.application.UserPasswordWriter;
import io.github.waleed875.memo.modules.identity.application.SecurityEventAudit;
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
        UserAccountLookup accounts, PasswordVerifier passwords, PasswordHasher hasher,
        UserPasswordWriter writer, SecurityEventAudit audit, Clock clock
    ) {
        return new IdentityApplicationService(accounts, passwords, hasher, writer, audit, clock);
    }

    @Bean
    FixedWindowAttemptLimiter fixedWindowAttemptLimiter(Clock clock) {
        return new FixedWindowAttemptLimiter(clock);
    }
}
