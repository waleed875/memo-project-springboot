package io.github.waleed875.memo.modules.identity.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.domain.UserAccount;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-24T09:00:00Z");
    private static final LocalDateTime STAMP = LocalDateTime.parse("2026-11-01T12:00:00");
    private static final UUID ID = UUID.fromString("cc5f1230-a257-4b64-94c2-23765c009818");
    private final UserAccount account = new UserAccount(ID, "admin", "مدير النظام",
        "$2a$10$mock", MemoRole.ADMIN, false, STAMP);

    private static final class FakeAccounts implements UserAccountLookup {
        UserAccount user;
        FakeAccounts(UserAccount user) { this.user = user; }
        @Override public Optional<UserAccount> findActiveByUsername(String name) {
            return Optional.ofNullable(user).filter(u -> u.username().equalsIgnoreCase(name));
        }
        @Override public Optional<UserAccount> findActiveById(UUID id) {
            return Optional.ofNullable(user).filter(u -> u.id().equals(id));
        }
    }

    private IdentityApplicationService service(FakeAccounts accounts) {
        PasswordVerifier verifier = (raw, hash) -> "valid".equals(raw) && hash.equals("$2a$10$mock");
        PasswordHasher hasher = raw -> "$2a$10$replacement";
        UserPasswordWriter writer = (id, current, replacement) -> true;
        SecurityEventAudit audit = (id, action, description) -> {};
        return new IdentityApplicationService(accounts, verifier, hasher, writer, audit,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void successfulLoginCreatesBoundedSession() {
        FakeAccounts accounts = new FakeAccounts(account);
        IdentityApplicationService service = service(accounts);
        LoginAttempt attempt = service.login(" admin ", "valid");
        assertEquals(LoginAttempt.Status.AUTHENTICATED, attempt.status());
        assertNotNull(attempt.session());
        assertEquals(ID, attempt.session().userId());
        assertEquals(NOW, attempt.session().authenticatedAt());
        assertEquals("ADMIN", attempt.user().role());
        assertEquals(Optional.of(attempt.user()), service.current(attempt.session()));
    }

    @Test void invalidCredentialsAndMissingUserFailClosed() {
        IdentityApplicationService service = service(new FakeAccounts(account));
        assertEquals(LoginAttempt.Status.INVALID, service.login("admin", "wrong").status());
        assertEquals(LoginAttempt.Status.INVALID, service.login("", "valid").status());
        assertEquals(LoginAttempt.Status.INVALID, service.login("nobody", "valid").status());
        assertEquals(LoginAttempt.Status.INVALID, service.login("admin", " ".repeat(513)).status());
    }

    @Test void passwordChangeRequirementPreventsSession() {
        UserAccount forced = new UserAccount(ID, "admin", "Admin", "$2a$10$mock", MemoRole.ADMIN, true, STAMP);
        LoginAttempt result = service(new FakeAccounts(forced)).login("admin", "valid");
        assertEquals(LoginAttempt.Status.MUST_CHANGE_PASSWORD, result.status());
        assertNull(result.session());
    }

    @Test void sessionBecomesInvalidAfterPasswordOrRoleStateChanges() {
        FakeAccounts accounts = new FakeAccounts(account);
        IdentityApplicationService service = service(accounts);
        SessionProof proof = service.login("admin", "valid").session();
        accounts.user = new UserAccount(ID, "admin", "Admin", "$2a$10$mock",
            MemoRole.ADMIN, false, STAMP.plusSeconds(1));
        assertTrue(service.current(proof).isEmpty());
        accounts.user = new UserAccount(ID, "admin", "Admin", "$2a$10$mock",
            MemoRole.ADMIN, true, STAMP);
        assertTrue(service.current(proof).isEmpty());
        accounts.user = new UserAccount(ID, "admin", "Admin", "$2a$10$mock",
            MemoRole.VIEWER, false, STAMP);
        assertTrue(service.current(proof).isEmpty());
        accounts.user = null;
        assertTrue(service.current(proof).isEmpty());
    }

    @Test void eightHourAbsoluteExpiryAndFutureSessionAreRejected() {
        IdentityApplicationService service = service(new FakeAccounts(account));
        assertTrue(service.current(new SessionProof(ID, MemoRole.ADMIN, STAMP, NOW.minus(Duration.ofHours(8)))).isPresent());
        assertTrue(service.current(new SessionProof(ID, MemoRole.ADMIN, STAMP,
            NOW.minus(Duration.ofHours(8)).minusNanos(1))).isEmpty());
        assertTrue(service.current(new SessionProof(ID, MemoRole.ADMIN, STAMP, NOW.plusSeconds(1))).isEmpty());
        assertTrue(service.current(null).isEmpty());
    }
}
