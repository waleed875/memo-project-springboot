package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.NoteReviewConflict;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Transactional PostgreSQL verification with a reduced, test-only legacy fixture. */
@Testcontainers
class NoteReviewTransitionPostgresIT {
    @Container
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("review_transitions_it").withUsername("memo_it")
            .withPassword("test_only");

    private static final UUID ROLE_REVIEWER = UUID.fromString("cc000000-0000-4000-8000-000000000001");
    private static final UUID ROLE_ADMIN = UUID.fromString("cc000000-0000-4000-8000-000000000002");
    private static final UUID ROLE_ENTRY = UUID.fromString("cc000000-0000-4000-8000-000000000003");
    private static final UUID USER_A = UUID.fromString("cc000000-0000-4000-8000-000000000004");
    private static final UUID USER_B = UUID.fromString("cc000000-0000-4000-8000-000000000005");
    private static final UUID ADMIN = UUID.fromString("cc000000-0000-4000-8000-000000000006");
    private static final UUID ENTRY = UUID.fromString("cc000000-0000-4000-8000-000000000007");
    private static final UUID COMPANY = UUID.fromString("cc000000-0000-4000-8000-000000000008");
    private static final UUID TYPE = UUID.fromString("cc000000-0000-4000-8000-000000000009");

    private JdbcTemplate jdbc;
    private io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionRepository repository;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop schema if exists audit cascade");
        jdbc.execute("drop schema if exists core cascade");
        jdbc.execute("drop schema if exists auth cascade");
        jdbc.execute("drop schema if exists system cascade");
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql")).execute(ds);
        jdbc.update("""
            insert into auth.roles(id, code, name_ar) values
            (?, 'REVIEWER', 'Reviewer'), (?, 'ADMIN', 'Admin'), (?, 'DATA_ENTRY', 'Entry')
            """, ROLE_REVIEWER, ROLE_ADMIN, ROLE_ENTRY);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id) values
            (?, 'reviewer_a', 'test', 'Reviewer A', ?),
            (?, 'reviewer_b', 'test', 'Reviewer B', ?),
            (?, 'admin', 'test', 'Admin', ?),
            (?, 'entry', 'test', 'Entry', ?)
            """, USER_A, ROLE_REVIEWER, USER_B, ROLE_REVIEWER,
            ADMIN, ROLE_ADMIN, ENTRY, ROLE_ENTRY);
        jdbc.update("insert into core.companies(id, name) values (?, 'Company')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'Entry')", TYPE);

        // Reproduce migration 003 timestamp trigger: trigger sets updated_at on every update.
        jdbc.execute("""
            create schema if not exists common;
            create or replace function common.set_updated_at() returns trigger
            language plpgsql as $$
            begin new.updated_at = now(); return new; end;
            $$;
            create trigger trg_notes_updated before update on core.notes
              for each row execute function common.set_updated_at();
            """);
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcNoteReviewTransitionRepository(jdbc));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{
            io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionRepository.class});
        proxy.afterPropertiesSet();
        repository = (io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionRepository)
            proxy.getObject();
    }

    private UUID note(UUID creator, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into core.notes(id, company_id, note_type_id, note_number,
                                   created_by, status, arrival_at)
            values (?, ?, ?, ?, ?, ?, current_date)
            """, id, COMPANY, TYPE, id.toString(), creator, status);
        return id;
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from core.notes where id = ?", String.class, id);
    }
    private int count(String table, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where note_id = ?",
            Integer.class, id);
    }
    private int audits(UUID id) {
        return jdbc.queryForObject("select count(*) from audit.logs where record_id = ?",
            Integer.class, id);
    }
    private LocalDateTime updated(UUID id) {
        return jdbc.queryForObject("select updated_at from core.notes where id = ?",
            LocalDateTime.class, id);
    }

    @Test void successfulStartReturnThenAdminReleasePreservesAuditAndHistory() {
        UUID id = note(ENTRY, "NEEDS_REVIEW");
        LocalDateTime original = updated(id);
        repository.start(id, USER_A, original);
        assertEquals("UNDER_REVIEW", status(id));
        assertEquals(USER_A, jdbc.queryForObject("select reviewer_id from core.notes where id = ?",
            UUID.class, id));
        assertEquals(1, count("core.note_review_history", id));
        assertEquals(1, audits(id));
        assertEquals("START_REVIEW", jdbc.queryForObject(
            "select action_type from audit.logs where record_id = ?", String.class, id));
        assertEquals("NEEDS_REVIEW", jdbc.queryForObject(
            "select old_value->>'status' from audit.logs where record_id = ?", String.class, id));
        assertEquals("UNDER_REVIEW", jdbc.queryForObject(
            "select new_value->>'status' from audit.logs where record_id = ?", String.class, id));
        assertNotNull(jdbc.queryForObject("select review_started_at from core.notes where id = ?",
            LocalDateTime.class, id));

        repository.returnForCorrection(id, USER_A, " fix ", updated(id));
        assertEquals("RETURNED_FOR_CORRECTION", status(id));
        assertEquals(2, count("core.note_review_history", id));
        assertEquals(2, audits(id));
        assertNull(jdbc.queryForObject("select reviewer_id from core.notes where id = ?",
            UUID.class, id));
        assertNull(jdbc.queryForObject("select review_started_at from core.notes where id = ?",
            LocalDateTime.class, id));

        UUID again = note(ENTRY, "HAS_ERRORS");
        repository.start(again, USER_B, updated(again));
        repository.release(again, ADMIN, "handover");
        assertEquals("NEEDS_REVIEW", status(again));
        assertEquals(2, audits(again));
        assertEquals("ADMIN_RELEASE_REVIEW", jdbc.queryForObject("""
            select action_type from audit.logs where record_id = ?
            order by id desc limit 1
            """, String.class, again));
    }

    @Test void roleSelfReviewAndStaleExpectedVersionFailWithoutSideEffects() {
        UUID own = note(USER_A, "COMPLETE");
        assertThrows(NoteReviewConflict.class, () -> repository.start(own, USER_A, updated(own)));
        UUID n = note(ENTRY, "COMPLETE");
        assertThrows(MasterDataForbiddenException.class, () -> repository.start(n, ENTRY, null));
        assertThrows(NoteReviewConflict.class, () -> repository.start(
            n, USER_A, LocalDateTime.parse("2020-01-01T00:00:00")));
        assertEquals("COMPLETE", status(n));
        assertEquals(0, audits(n));
        assertEquals(0, count("core.note_review_history", n));
    }

    @Test void onlyAssignedReviewerMayReturn() {
        UUID id = note(ENTRY, "HAS_ERRORS");
        repository.start(id, USER_A, null);
        assertThrows(NoteReviewConflict.class, () -> repository.returnForCorrection(
            id, USER_B, "not my review", null));
        assertEquals("UNDER_REVIEW", status(id));
        assertEquals(1, audits(id));
    }

    @Test void auditInsertFailureRollsBackEntireReviewTransition() {
        UUID id = note(ENTRY, "COMPLETE");
        jdbc.execute("alter table audit.logs add constraint ck_reject_review_actions check (action_type <> 'START_REVIEW')");
        assertThrows(Exception.class, () -> repository.start(id, USER_A, null));
        assertEquals("COMPLETE", status(id));
        assertEquals(0, audits(id));
        assertEquals(0, count("core.note_review_history", id));
    }

    @Test void concurrentReviewClaimsOnlyOneWinner() throws Exception {
        UUID id = note(ENTRY, "NEEDS_REVIEW");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), proceed = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger(), rejected = new AtomicInteger();
        try {
            var actionA = (Callable<Void>) () -> {
                ready.countDown(); proceed.await(10, TimeUnit.SECONDS);
                try { repository.start(id, USER_A, null); accepted.incrementAndGet(); }
                catch (NoteReviewConflict ignored) { rejected.incrementAndGet(); }
                return null;
            };
            var actionB = (Callable<Void>) () -> {
                ready.countDown(); proceed.await(10, TimeUnit.SECONDS);
                try { repository.start(id, USER_B, null); accepted.incrementAndGet(); }
                catch (NoteReviewConflict ignored) { rejected.incrementAndGet(); }
                return null;
            };
            Future<Void> first = pool.submit(actionA), second = pool.submit(actionB);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            proceed.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
            assertEquals(1, accepted.get());
            assertEquals(1, rejected.get());
            assertEquals(1, audits(id));
            assertEquals(1, count("core.note_review_history", id));
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
    }
}

