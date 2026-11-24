package io.github.waleed875.memo.modules.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.IdentityApplicationService;
import io.github.waleed875.memo.modules.identity.application.LoginAttempt;
import io.github.waleed875.memo.modules.identity.application.PasswordChangeOutcome;
import io.github.waleed875.memo.modules.identity.application.SessionProof;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL 16 integration checks for the exact legacy auth tables
 * and update-audit schema. No schema modifications are made to a live DB.
 *
 * Surefire: unit tests (*Test), Failsafe: Docker-backed tests (*IT).
 */
@Testcontainers
class IdentityPostgresIT {
    @Container
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_auth_it")
            .withUsername("memo_it")
            .withPassword("memo_only_test_password");

    private static final UUID USER_ID =
        UUID.fromString("d0bbd9aa-2b51-44e4-9d97-af87d24f3224");
    private static final UUID ROLE_ID =
        UUID.fromString("8f17fa0c-8cd8-42ce-9ddd-d6a9bd56c033");
    private static final LocalDateTime INITIAL_STAMP =
        LocalDateTime.of(2026, 1, 20, 12, 15, 0);
    private static final Instant NOW =
        Instant.parse("2026-11-24T09:00:00Z");

    private static JdbcTemplate jdbc;
    private static BcryptPasswordVerifier bcrypt;
    private static JdbcUserAccountLookup users;
    private static JdbcUserPasswordWriter writer;
    private static JdbcSecurityEventAudit audit;

    @BeforeAll
    static void createLegacyAuthFixture() {
        var dataSource = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
        new ResourceDatabasePopulator(new ClassPathResource("postgres-auth-schema.sql"))
            .execute(dataSource);

        jdbc = new JdbcTemplate(dataSource);
        bcrypt = new BcryptPasswordVerifier();
        users = new JdbcUserAccountLookup(jdbc);
        writer = new JdbcUserPasswordWriter(jdbc);
        audit = new JdbcSecurityEventAudit(jdbc);
    }

    @BeforeEach
    void resetAccounts() {
        jdbc.update("delete from updates.audit_log");
        jdbc.update("delete from auth.users");
        jdbc.update("delete from auth.roles");
        jdbc.update("""
            insert into auth.roles(id, code, name_ar, is_active)
            values (?, 'ADMIN', 'مدير النظام', true)
            """, ROLE_ID);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name,
                role_id, is_active, is_deleted, must_change_password, updated_at)
            values (?, 'admin', ?, 'مدير النظام',
                ?, true, false, true, ?)
            """, USER_ID, bcrypt.hash("initial-password"), ROLE_ID, INITIAL_STAMP);
    }

    private IdentityApplicationService service() {
        return new IdentityApplicationService(
            users, bcrypt, bcrypt, writer, audit, Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void readsLegacyUsersCaseInsensitivelyAndRejectsInactiveAccounts() {
        var account = users.findActiveByUsername("  ADMIN  ").orElseThrow();
        assertEquals(USER_ID, account.id());
        assertEquals(MemoRole.ADMIN, account.role());
        assertTrue(account.mustChangePassword());
        assertEquals(INITIAL_STAMP, account.updatedAt());

        jdbc.update("update auth.users set is_deleted = true where id = ?", USER_ID);
        assertTrue(users.findActiveByUsername("admin").isEmpty());
        jdbc.update("update auth.users set is_deleted = false where id = ?", USER_ID);
        jdbc.update("update auth.roles set is_active = false where id = ?", ROLE_ID);
        assertTrue(users.findActiveById(USER_ID).isEmpty());
    }

    @Test
    void changePasswordPersistsBcryptAndInvalidatesOldSessionStamp() {
        var service = service();
        assertEquals(LoginAttempt.Status.MUST_CHANGE_PASSWORD,
            service.login("admin", "initial-password").status());

        assertEquals(PasswordChangeOutcome.SUCCESS,
            service.changePassword("admin", "initial-password", "replacement-password"));

        String updatedHash = jdbc.queryForObject(
            "select password_hash from auth.users where id = ?", String.class, USER_ID
        );
        assertNotEquals("replacement-password", updatedHash);
        assertTrue(bcrypt.matches("replacement-password", updatedHash));
        assertFalse(bcrypt.matches("initial-password", updatedHash));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "select must_change_password from auth.users where id = ?", Boolean.class, USER_ID));

        LocalDateTime currentStamp = users.findActiveById(USER_ID).orElseThrow().updatedAt();
        assertTrue(currentStamp.isAfter(INITIAL_STAMP));
        assertEquals(LoginAttempt.Status.INVALID,
            service.login("admin", "initial-password").status());
        LoginAttempt successful = service.login("ADMIN", "replacement-password");
        assertEquals(LoginAttempt.Status.AUTHENTICATED, successful.status());

        SessionProof stale = new SessionProof(USER_ID, MemoRole.ADMIN, INITIAL_STAMP, NOW);
        assertTrue(service.current(stale).isEmpty());
        assertTrue(service.current(successful.session()).isPresent());

        Long changed = jdbc.queryForObject("""
            select count(*) from updates.audit_log
            where user_id = ? and action = 'PASSWORD_CHANGED'
            """, Long.class, USER_ID);
        assertEquals(1L, changed);
    }

    @Test
    void conditionalHashUpdateCannotOverwriteConcurrentChangeOrInactiveAccount() {
        String first = users.findActiveById(USER_ID).orElseThrow().passwordHash();
        assertTrue(writer.replaceHashIfCurrent(USER_ID, first, bcrypt.hash("first-change")));
        assertFalse(writer.replaceHashIfCurrent(USER_ID, first, bcrypt.hash("stale-change")));
        assertFalse(writer.replaceHashIfCurrent(USER_ID, "not-a-stored-hash", "anything"));
        jdbc.update("update auth.users set is_active = false where id = ?", USER_ID);
        String activeHash = jdbc.queryForObject(
            "select password_hash from auth.users where id = ?", String.class, USER_ID);
        assertFalse(writer.replaceHashIfCurrent(USER_ID, activeHash, "new-hash"));
    }

    @Test
    void auditEventsEnforceLegacyForeignKeysAndNormalizedUniqueIndex() {
        audit.record(USER_ID, "LOGIN", "تسجيل دخول إلى النظام.");
        assertEquals(1L, jdbc.queryForObject("""
            select count(*) from updates.audit_log
            where user_id = ? and action = 'LOGIN'
            """, Long.class, USER_ID));

        assertThrows(DuplicateKeyException.class, () -> jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, ' ADMIN ', ?, 'Duplicate User', ?)
            """, UUID.randomUUID(), bcrypt.hash("password"), ROLE_ID));
    }
}
