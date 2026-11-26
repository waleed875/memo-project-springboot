package io.github.waleed875.memo.platform.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.waleed875.memo.modules.identity.application.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

final class PreviewAdminBootstrapRunnerTest {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PasswordHasher passwords = mock(PasswordHasher.class);
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    PreviewAdminBootstrapRunner runner;

    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(transaction);
        runner = new PreviewAdminBootstrapRunner(jdbc, passwords, manager, true);
    }

    private void eligible() {
        when(jdbc.queryForObject("select current_database()", String.class))
            .thenReturn("memodb_preview");
        when(jdbc.queryForObject("select current_user", String.class))
            .thenReturn("memo_preview");
        when(jdbc.queryForObject(
            "select database_version from system.database_settings where singleton = true",
            String.class)).thenReturn("070");
        when(jdbc.queryForObject("select count(*) from auth.users", Long.class)).thenReturn(1L);
        when(jdbc.queryForObject("select count(*) from core.notes", Long.class)).thenReturn(0L);
        when(jdbc.queryForObject(argThat(sql -> sql != null &&
            sql.contains("BOOTSTRAP_BCRYPT_ADMIN123")), eq(Long.class))).thenReturn(1L);
    }

    @Test void freshDatabaseIsAccepted() {
        eligible();
        assertDoesNotThrow(() -> runner.validatePreviewTarget());
    }

    @Test void refusesOtherDatabaseOrRoleBeforeHashing() {
        eligible();
        when(jdbc.queryForObject("select current_database()", String.class))
            .thenReturn("production");
        assertThrows(IllegalStateException.class, runner::validatePreviewTarget);
        verifyNoInteractions(passwords);
    }

    @Test void refusesExistingDataOrActivatedAccount() {
        eligible();
        when(jdbc.queryForObject("select count(*) from core.notes", Long.class)).thenReturn(1L);
        assertThrows(IllegalStateException.class, runner::validatePreviewTarget);
        when(jdbc.queryForObject("select count(*) from core.notes", Long.class)).thenReturn(0L);
        when(jdbc.queryForObject(argThat(sql -> sql != null &&
            sql.contains("BOOTSTRAP_BCRYPT_ADMIN123")), eq(Long.class))).thenReturn(0L);
        assertThrows(IllegalStateException.class, runner::validatePreviewTarget);
    }

    @Test void rejectsWeakAndOversizedBcryptPasswordsWithoutDatabaseWrite() {
        assertThrows(IllegalArgumentException.class,
            () -> runner.activate("short".toCharArray()));
        assertThrows(IllegalArgumentException.class,
            () -> runner.activate("أ".repeat(40).toCharArray()));
        verify(jdbc, never()).update(contains("update auth.users"), anyString());
    }

    @Test void atomicallyUpdatesOnlySeedHashAndPersistsAudit() {
        char[] candidate = "test-preview-password-44".toCharArray();
        when(passwords.hash(new String(candidate)))
            .thenReturn("$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        when(jdbc.update(argThat(sql -> sql != null &&
            sql.contains("BOOTSTRAP_BCRYPT_ADMIN123") &&
            sql.contains("select count(*) from core.notes")), anyString())).thenReturn(1);
        runner.activate(candidate);
        verify(jdbc).update(argThat(sql -> sql != null &&
            sql.contains("BOOTSTRAP_BCRYPT_ADMIN123")), startsWith("$2a$"));
        verify(jdbc).update(contains("insert into audit.logs"));
        verify(manager).commit(transaction);
    }

    @Test void alreadyActivatedSeedFailsClosedWithoutCommitting() {
        char[] candidate = "test-preview-password-44".toCharArray();
        when(passwords.hash(anyString()))
            .thenReturn("$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        assertThrows(IllegalStateException.class, () -> runner.activate(candidate));
        verify(manager).rollback(transaction);
        verify(jdbc, never()).update(contains("insert into audit.logs"));
    }

    @Test void invalidEncoderResultFailsClosed() {
        when(passwords.hash(anyString())).thenReturn("not-a-bcrypt-hash");
        assertThrows(IllegalStateException.class,
            () -> runner.activate("another-test-password-444".toCharArray()));
        verify(manager, never()).getTransaction(any());
    }
}
