package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.notes.application.NoteReviewConflict;
import io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionRepository;
import io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionService;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;

/**
 * Full original 001-070 PostgreSQL schema + real Spring service and JDBC adapter.
 * Assertion names and state transitions are grounded in C# DataServices.cs
 * StartReviewAsync / ReturnForCorrectionAsync / ReleaseReviewAsync / ApproveNoteAsync.
 * Audit JSONB richness is intentionally Java-specific; action names and history
 * are the portable original business contract.
 */
@EnabledIfEnvironmentVariable(named = "MEMO_FULL_LEGACY_SCHEMA", matches = "true")
class LegacyFullSchemaReviewAuditParityTest {
    private JdbcTemplate jdbc;
    private NoteReviewTransitionService service;
    private UUID creator;
    private UUID reviewer;
    private UUID reviewer2;
    private UUID admin;
    private UUID company;
    private UUID noteType;
    private UUID requiredField;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(
            "jdbc:postgresql://" + System.getenv("PGHOST") + ":" +
            System.getenv("PGPORT") + "/" + System.getenv("PGDATABASE"),
            System.getenv("PGUSER"), System.getenv("PGPASSWORD"));
        jdbc = new JdbcTemplate(ds);
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcNoteReviewTransitionRepository(jdbc));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{NoteReviewTransitionRepository.class});
        proxy.afterPropertiesSet();
        service = new NoteReviewTransitionService(
            (NoteReviewTransitionRepository) proxy.getObject(), () -> {});

        var adminRole = role("ADMIN");
        var reviewerRole = role("REVIEWER");
        var entryRole = role("DATA_ENTRY");
        admin = user(adminRole, "admin");
        creator = user(entryRole, "entry");
        reviewer = user(reviewerRole, "reviewer-one");
        reviewer2 = user(reviewerRole, "reviewer-two");

        company = UUID.randomUUID();
        jdbc.update("insert into core.companies(id, name) values (?, 'Review parity company')", company);
        noteType = UUID.randomUUID();
        jdbc.update("""
            insert into core.note_types(id, code, name_ar)
            values (?, ?, 'Review parity test type')
            """, noteType, "RV" + noteType.toString().replace("-", "").substring(0, 12));
        requiredField = UUID.randomUUID();
        jdbc.update("""
            insert into core.note_fields(id, code, name_ar)
            values (?, ?, 'Required parity field')
            """, requiredField, "rf" + requiredField.toString().replace("-", "").substring(0, 12));
        jdbc.update("""
            insert into core.note_type_fields(
                note_type_id, field_id, sort_order, is_required, is_active)
            values (?, ?, 1, true, true)
            """, noteType, requiredField);
    }

    private UUID role(String code) {
        return jdbc.queryForObject("select id from auth.roles where code = ?", UUID.class, code);
    }

    private UUID user(UUID roleId, String kind) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, ?, 'non-login-integration-test', 'Test actor', ?)
            """, id, kind + id.toString().substring(0, 8), roleId);
        return id;
    }

    private UserProfile actor(UUID id, String role) {
        return new UserProfile(id, "integration", "Test actor", role);
    }

    private UUID note(String status, short indicator) {
        UUID id = UUID.randomUUID();
        String number = Long.toString(
            100_000L + Math.floorMod(id.getMostSignificantBits(), 1_000_000_000L));
        jdbc.update("""
            insert into core.notes(id, note_number, note_number_normalized,
              note_type_id, company_id, writing_date, arrival_at,
              created_by, status, is_loot)
            values (?, ?, ?, ?, ?, current_date, current_date, ?, ?, false)
            """, id, number, number, noteType, company, creator, status);
        jdbc.update("""
            insert into core.note_field_values(
                note_id, field_id, field_value, created_by)
            values (?, ?, ?, ?)
            """, id, requiredField, indicator, creator);
        return id;
    }

    private LocalDateTime version(UUID id) {
        return jdbc.queryForObject(
            "select updated_at from core.notes where id = ?",
            LocalDateTime.class, id);
    }
    private String status(UUID id) {
        return jdbc.queryForObject(
            "select status from core.notes where id = ?", String.class, id);
    }
    private int history(UUID id) {
        return jdbc.queryForObject(
            "select count(*) from core.note_review_history where note_id = ?",
            Integer.class, id);
    }
    private int audits(UUID id) {
        return jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id = ? and table_name = 'notes'
              and action_type in (
                'START_REVIEW', 'RETURN_FOR_CORRECTION',
                'ADMIN_RELEASE_REVIEW', 'APPROVE_NOTE')
            """, Integer.class, id);
    }

    @Test
    void claimAndReturnMatchOriginalHistoryAndAuditContract() {
        UUID id = note("COMPLETE", (short) 1);
        service.start(actor(reviewer, "REVIEWER"), id, version(id));
        assertEquals("UNDER_REVIEW", status(id));
        assertEquals(reviewer, jdbc.queryForObject(
            "select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertEquals("START_REVIEW", jdbc.queryForObject("""
            select action from core.note_review_history
            where note_id = ? order by created_at, id limit 1
            """, String.class, id));
        assertEquals("COMPLETE", jdbc.queryForObject("""
            select old_value->>'status' from audit.logs
            where record_id = ? and action_type = 'START_REVIEW'
            """, String.class, id));
        assertEquals("UNDER_REVIEW", jdbc.queryForObject("""
            select new_value->>'status' from audit.logs
            where record_id = ? and action_type = 'START_REVIEW'
            """, String.class, id));
        service.returnForCorrection(actor(reviewer, "REVIEWER"), id,
            "  missing indicator  ", version(id));
        assertEquals("RETURNED_FOR_CORRECTION", status(id));
        assertEquals("missing indicator", jdbc.queryForObject(
            "select return_reason from core.notes where id = ?", String.class, id));
        assertNull(jdbc.queryForObject(
            "select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertNull(jdbc.queryForObject(
            "select review_started_at from core.notes where id = ?", LocalDateTime.class, id));
        assertEquals("RETURN_FOR_CORRECTION", jdbc.queryForObject("""
            select action from core.note_review_history
            where note_id = ? and action = 'RETURN_FOR_CORRECTION'
            """, String.class, id));
        assertEquals(2, history(id));
        assertEquals(2, audits(id));
    }

    @Test
    void approveKeepsReviewStartTimeAndWritesDistinctHistoryAndAuditActions() {
        UUID id = note("NEEDS_REVIEW", (short) 1);
        service.start(actor(reviewer, "REVIEWER"), id, version(id));
        LocalDateTime started = jdbc.queryForObject(
            "select review_started_at from core.notes where id = ?", LocalDateTime.class, id);
        assertNotNull(started);
        service.approve(actor(reviewer, "REVIEWER"), id, version(id));
        assertEquals("APPROVED", status(id));
        assertEquals(reviewer, jdbc.queryForObject(
            "select approved_by from core.notes where id = ?", UUID.class, id));
        assertNotNull(jdbc.queryForObject(
            "select approved_at from core.notes where id = ?", LocalDateTime.class, id));
        assertEquals(started, jdbc.queryForObject(
            "select review_started_at from core.notes where id = ?", LocalDateTime.class, id));
        assertNull(jdbc.queryForObject(
            "select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from core.note_review_history
            where note_id = ? and action = 'APPROVE'
            """, Integer.class, id));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id = ? and action_type = 'APPROVE_NOTE'
              and new_value->>'status' = 'APPROVED'
            """, Integer.class, id));
        assertEquals(2, history(id));
        assertEquals(2, audits(id));
    }

    @Test
    void invalidRequiredIndicatorCannotBeApprovedAndWritesNothing() {
        UUID id = note("COMPLETE", (short) 0);
        service.start(actor(reviewer, "REVIEWER"), id, version(id));
        assertThrows(NoteSaveValidationException.class,
            () -> service.approve(actor(reviewer, "REVIEWER"), id, version(id)));
        assertEquals("UNDER_REVIEW", status(id));
        assertEquals(1, history(id));
        assertEquals(1, audits(id));
        assertNull(jdbc.queryForObject(
            "select approved_by from core.notes where id = ?", UUID.class, id));
    }

    @Test
    void adminReleaseRestoresQueueWithLegacyActionsAndReason() {
        UUID id = note("NEEDS_REVIEW", (short) 1);
        service.start(actor(reviewer, "REVIEWER"), id, version(id));
        service.release(actor(admin, "ADMIN"), id, "  reassigned  ");
        assertEquals("NEEDS_REVIEW", status(id));
        assertNull(jdbc.queryForObject(
            "select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertEquals("reassigned", jdbc.queryForObject("""
            select reason from core.note_review_history
            where note_id = ? and action = 'ADMIN_RELEASE_REVIEW'
            """, String.class, id));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs where record_id = ?
              and action_type = 'ADMIN_RELEASE_REVIEW'
            """, Integer.class, id));
        assertEquals(2, history(id));
        assertEquals(2, audits(id));
    }

    @Test
    void auditFailureRollsBackStatusAndReviewHistory() {
        UUID id = note("COMPLETE", (short) 1);
        LocalDateTime before = version(id);
        jdbc.execute("""
            alter table audit.logs add constraint ck_review_fail_full_schema
            check (action_type <> 'START_REVIEW') not valid
            """);
        try {
            assertThrows(Exception.class,
                () -> service.start(actor(reviewer, "REVIEWER"), id, before));
            assertEquals("COMPLETE", status(id));
            assertEquals(0, history(id));
            assertEquals(0, audits(id));
            assertEquals(before, version(id));
        } finally {
            jdbc.execute("""
                alter table audit.logs drop constraint if exists ck_review_fail_full_schema
                """);
        }
    }

    @Test
    void unauthorizedSelfReviewAndStaleVersionHaveNoSideEffects() {
        UUID id = note("COMPLETE", (short) 1);
        assertThrows(Exception.class, () ->
            service.start(actor(creator, "DATA_ENTRY"), id, version(id)));
        assertThrows(NoteReviewConflict.class, () ->
            service.start(actor(creator, "REVIEWER"), id, version(id)));
        assertThrows(NoteReviewConflict.class, () ->
            service.start(actor(reviewer, "REVIEWER"), id,
                LocalDateTime.parse("2020-01-01T00:00:00")));
        assertEquals("COMPLETE", status(id));
        assertEquals(0, history(id));
        assertEquals(0, audits(id));
    }

    @Test
    void concurrentReviewClaimsExactlyOneReviewer() throws Exception {
        UUID id = note("COMPLETE", (short) 1);
        LocalDateTime before = version(id);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger(), conflicts = new AtomicInteger();
        try {
            Callable<Void> a = () -> {
                ready.countDown(); go.await(10, TimeUnit.SECONDS);
                try {
                    service.start(actor(reviewer, "REVIEWER"), id, before);
                    winners.incrementAndGet();
                } catch (NoteReviewConflict expected) {
                    conflicts.incrementAndGet();
                }
                return null;
            };
            Callable<Void> b = () -> {
                ready.countDown(); go.await(10, TimeUnit.SECONDS);
                try {
                    service.start(actor(reviewer2, "REVIEWER"), id, before);
                    winners.incrementAndGet();
                } catch (NoteReviewConflict expected) {
                    conflicts.incrementAndGet();
                }
                return null;
            };
            var f1 = pool.submit(a);
            var f2 = pool.submit(b);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            f1.get(20, TimeUnit.SECONDS);
            f2.get(20, TimeUnit.SECONDS);
            assertEquals(1, winners.get());
            assertEquals(1, conflicts.get());
            assertEquals(1, history(id));
            assertEquals(1, audits(id));
            assertEquals("UNDER_REVIEW", status(id));
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }
}
