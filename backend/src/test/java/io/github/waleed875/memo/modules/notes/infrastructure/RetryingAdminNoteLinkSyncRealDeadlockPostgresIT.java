package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Actual PostgreSQL deadlock and lock timeout, not constructed SQLExceptions. */
@Testcontainers
class RetryingAdminNoteLinkSyncRealDeadlockPostgresIT {
    @Container
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("real_retry_deadlock_it")
            .withUsername("memo_it").withPassword("test_only");

    private JdbcTemplate jdbc;
    private JdbcTransactionManager manager;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        manager = new JdbcTransactionManager(dataSource);
        jdbc.execute("drop table if exists retry_real_attempts");
        jdbc.execute("drop table if exists retry_real_locks");
        jdbc.execute("""
            create table retry_real_locks(
                id integer primary key, counter integer not null default 0)
            """);
        jdbc.update("insert into retry_real_locks(id) values (1), (2)");
        jdbc.execute("""
            create table retry_real_attempts(
                note_id uuid primary key, attempt integer not null)
            """);
    }

    private AdminNoteLinkSyncCommand command() {
        return new AdminNoteLinkSyncCommand(UUID.randomUUID(),
            LocalDateTime.parse("2026-11-25T09:00:00"),
            List.of(), List.of(UUID.randomUUID()));
    }

    private AdminNoteLinkSyncRepository.SyncResult done(AdminNoteLinkSyncCommand cmd) {
        return new AdminNoteLinkSyncRepository.SyncResult(
            cmd.noteId(), cmd.relatedNoteIds(), 1, 0, cmd.expectedUpdatedAt());
    }

    @Test
    void realDeadlockVictimRollsBackAndFreshTransactionCommitsOnce() throws Exception {
        CountDownLatch otherHasRowOne = new CountDownLatch(1);
        CountDownLatch retryHasRowTwo = new CountDownLatch(1);
        CountDownLatch otherIsRequestingRowTwo = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<NoteTransactionRetryPolicy.FailureKind> firstFailure =
            new AtomicReference<>();
        UUID actor = UUID.randomUUID();
        var command = command();
        var executor = Executors.newSingleThreadExecutor();

        try {
            var other = executor.submit(() -> {
                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    jdbc.execute("set local deadlock_timeout = '5s'");
                    jdbc.execute("set local lock_timeout = '8s'");
                    jdbc.update("update retry_real_locks set counter = counter + 1 where id = 1");
                    otherHasRowOne.countDown();
                    try {
                        assertTrue(retryHasRowTwo.await(8, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    otherIsRequestingRowTwo.countDown();
                    jdbc.update("update retry_real_locks set counter = counter + 1 where id = 2");
                });
                return null;
            });

            assertTrue(otherHasRowOne.await(8, TimeUnit.SECONDS));

            AdminNoteLinkSyncRepository delegate = (cmd, user) -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(actor, user);
                int attempt = attempts.incrementAndGet();
                // Testcontainers creates memo_it as the database owner/superuser.
                // The shorter detector delay deterministically makes this
                // transaction detect the cycle and become the deadlock victim.
                jdbc.execute("set local deadlock_timeout = '125ms'");
                jdbc.execute("set local lock_timeout = '4s'");
                assertEquals(0, jdbc.queryForObject(
                    "select count(*) from retry_real_attempts", Integer.class),
                    "No failed attempt may leave behind committed data");
                jdbc.update(
                    "insert into retry_real_attempts(note_id, attempt) values (?, ?)",
                    cmd.noteId(), attempt);
                jdbc.update("update retry_real_locks set counter = counter + 1 where id = 2");

                if (attempt == 1) {
                    retryHasRowTwo.countDown();
                    try {
                        assertTrue(otherIsRequestingRowTwo.await(8, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                try {
                    jdbc.update("update retry_real_locks set counter = counter + 1 where id = 1");
                } catch (DataAccessException ex) {
                    firstFailure.set(NoteTransactionRetryPolicy.classify(ex));
                    throw ex;
                }
                return done(cmd);
            };

            var retry = new RetryingAdminNoteLinkSyncRepository(
                delegate, manager, millis -> {});
            assertEquals(command.noteId(), retry.sync(command, actor).noteId());
            other.get(15, TimeUnit.SECONDS);

            assertEquals(NoteTransactionRetryPolicy.FailureKind.DEADLOCK, firstFailure.get());
            assertEquals(2, attempts.get(), "Deadlock must cause one fresh retry");
            assertEquals(1, jdbc.queryForObject(
                "select count(*) from retry_real_attempts", Integer.class));
            assertEquals(2, jdbc.queryForObject(
                "select attempt from retry_real_attempts where note_id = ?",
                Integer.class, command.noteId()));
            assertEquals(2, jdbc.queryForObject(
                "select counter from retry_real_locks where id = 1", Integer.class));
            assertEquals(2, jdbc.queryForObject(
                "select counter from retry_real_locks where id = 2", Integer.class));
        } finally {
            retryHasRowTwo.countDown();
            otherIsRequestingRowTwo.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void realLockTimeoutStopsAfterThreeRolledBackAttempts() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<NoteTransactionRetryPolicy.FailureKind> failureKind =
            new AtomicReference<>();
        var executor = Executors.newSingleThreadExecutor();
        var command = command();
        try {
            var locker = executor.submit(() -> {
                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    jdbc.update("update retry_real_locks set counter = counter + 1 where id = 1");
                    locked.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
                return null;
            });
            assertTrue(locked.await(8, TimeUnit.SECONDS));
            AdminNoteLinkSyncRepository delegate = (cmd, actor) -> {
                jdbc.execute("set local lock_timeout = '120ms'");
                int attempt = attempts.incrementAndGet();
                jdbc.update(
                    "insert into retry_real_attempts(note_id, attempt) values (?, ?)",
                    cmd.noteId(), attempt);
                try {
                    jdbc.update("update retry_real_locks set counter = counter + 1 where id = 1");
                } catch (DataAccessException ex) {
                    failureKind.set(NoteTransactionRetryPolicy.classify(ex));
                    throw ex;
                }
                return done(cmd);
            };
            var retry = new RetryingAdminNoteLinkSyncRepository(
                delegate, manager, millis -> {});
            assertThrows(DataAccessException.class,
                () -> retry.sync(command, UUID.randomUUID()));
            assertEquals(NoteTransactionRetryPolicy.FailureKind.LOCK_TIMEOUT, failureKind.get());
            assertEquals(3, attempts.get());
            assertEquals(0, jdbc.queryForObject(
                "select count(*) from retry_real_attempts", Integer.class));
            release.countDown();
            locker.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
