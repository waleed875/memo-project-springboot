package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.application.NoteReviewConflict;
import io.github.waleed875.memo.modules.notes.application.NoteReviewTransitionRepository;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.LocalDateTime;
import java.util.UUID;
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

@Testcontainers
class NoteApprovalPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("note_approval_it").withUsername("memo_it")
            .withPassword("test_only_password");

    static final UUID ROLE = UUID.fromString("dd000000-0000-4000-8000-000000000001");
    static final UUID USER = UUID.fromString("dd000000-0000-4000-8000-000000000002");
    static final UUID CREATOR = UUID.fromString("dd000000-0000-4000-8000-000000000003");
    static final UUID OTHER = UUID.fromString("dd000000-0000-4000-8000-000000000004");
    static final UUID COMPANY = UUID.fromString("dd000000-0000-4000-8000-000000000005");
    static final UUID TYPE = UUID.fromString("dd000000-0000-4000-8000-000000000006");
    static final UUID FIELD = UUID.fromString("dd000000-0000-4000-8000-000000000007");
    JdbcTemplate jdbc;
    NoteReviewTransitionRepository repository;

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
        jdbc.update("insert into auth.roles(id, code, name_ar) values (?, 'REVIEWER', 'مراجع')", ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id) values
            (?, 'reviewer', 'test', 'Reviewer', ?),
            (?, 'creator', 'test', 'Creator', ?),
            (?, 'other', 'test', 'Other', ?)
            """, USER, ROLE, CREATOR, ROLE, OTHER, ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'Company')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'Entry')", TYPE);
        jdbc.update("insert into core.note_fields(id, code, name_ar) values (?, 'CHECK', 'مؤشر', ?)",
            FIELD, "مؤشر");
        jdbc.update("""
            insert into core.note_type_fields(id, note_type_id, field_id, sort_order, is_required)
            values (?, ?, ?, 1, true)
            """, UUID.randomUUID(), TYPE, FIELD);
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
        proxy.setProxyInterfaces(new Class<?>[]{NoteReviewTransitionRepository.class});
        proxy.afterPropertiesSet();
        repository = (NoteReviewTransitionRepository)proxy.getObject();
    }

    private UUID underReview(UUID creator, Integer indicator) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into core.notes(
              id, company_id, note_type_id, note_number, arrival_at, writing_date,
              created_by, reviewer_id, status, review_started_at)
            values (?, ?, ?, '12345', date '2026-11-22', date '2026-11-20',
                    ?, ?, 'UNDER_REVIEW', timestamp '2026-11-23 09:00:00')
            """, id, COMPANY, TYPE, creator, USER);
        if (indicator != null) {
            jdbc.update("""
                insert into core.note_field_values(id, note_id, field_id, field_value)
                values (?, ?, ?, ?)
                """, UUID.randomUUID(), id, FIELD, indicator);
        }
        return id;
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from core.notes where id = ?", String.class, id);
    }

    private int audits(UUID id) {
        return jdbc.queryForObject("select count(*) from audit.logs where record_id = ?",
            Integer.class, id);
    }

    private int history(UUID id) {
        return jdbc.queryForObject("select count(*) from core.note_review_history where note_id = ?",
            Integer.class, id);
    }

    @Test void approvesReviewedNoteAndRetainsReviewStartTimestamp() {
        UUID id = underReview(CREATOR, 1);
        LocalDateTime version = jdbc.queryForObject(
            "select updated_at from core.notes where id = ?", LocalDateTime.class, id);
        repository.approve(id, USER, version);
        assertEquals("APPROVED", status(id));
        assertEquals(USER, jdbc.queryForObject("select approved_by from core.notes where id = ?", UUID.class, id));
        assertNotNull(jdbc.queryForObject("select approved_at from core.notes where id = ?", LocalDateTime.class, id));
        assertNull(jdbc.queryForObject("select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertEquals(LocalDateTime.parse("2026-11-23T09:00:00"), jdbc.queryForObject(
            "select review_started_at from core.notes where id = ?", LocalDateTime.class, id));
        assertEquals("APPROVE", jdbc.queryForObject(
            "select action from core.note_review_history where note_id = ?", String.class, id));
        assertEquals("APPROVE_NOTE", jdbc.queryForObject(
            "select action_type from audit.logs where record_id = ?", String.class, id));
        assertEquals("UNDER_REVIEW", jdbc.queryForObject(
            "select old_value->>'status' from audit.logs where record_id = ?", String.class, id));
        assertEquals("APPROVED", jdbc.queryForObject(
            "select new_value->>'status' from audit.logs where record_id = ?", String.class, id));
        assertEquals(1, audits(id));
        assertEquals(1, history(id));
    }

    @Test void rejectsMissingAndUnreviewedRequiredValues() {
        UUID missing = underReview(CREATOR, null), unreviewed = underReview(CREATOR, 0);
        assertThrows(NoteSaveValidationException.class, () -> repository.approve(missing, USER, null));
        assertThrows(NoteSaveValidationException.class, () -> repository.approve(unreviewed, USER, null));
        assertEquals("UNDER_REVIEW", status(missing));
        assertEquals("UNDER_REVIEW", status(unreviewed));
        assertEquals(0, audits(missing) + audits(unreviewed));
        assertEquals(0, history(missing) + history(unreviewed));
    }

    @Test void approvedCannotBeReapprovedAndOtherReviewerCannotApprove() {
        UUID id = underReview(CREATOR, 2);
        assertThrows(NoteReviewConflict.class, () -> repository.approve(id, OTHER, null));
        repository.approve(id, USER, null);
        assertThrows(NoteReviewConflict.class, () -> repository.approve(id, USER, null));
        assertEquals(1, audits(id));
    }

    @Test void rejectsSelfApprovalAndStaleVersionWithoutAudit() {
        UUID id = underReview(USER, 1);
        assertThrows(NoteSaveValidationException.class, () -> repository.approve(id, USER, null));
        UUID other = underReview(CREATOR, 1);
        assertThrows(NoteReviewConflict.class, () -> repository.approve(
            other, USER, LocalDateTime.parse("2020-01-01T00:00:00")));
        assertEquals(0, audits(other));
    }

    @Test void auditFailureRollsBackApprovalAndReviewHistory() {
        UUID id = underReview(CREATOR, 1);
        jdbc.execute("alter table audit.logs add constraint reject_approval check (action_type <> 'APPROVE_NOTE')");
        assertThrows(Exception.class, () -> repository.approve(id, USER, null));
        assertEquals("UNDER_REVIEW", status(id));
        assertEquals(0, audits(id));
        assertEquals(0, history(id));
        assertNull(jdbc.queryForObject("select approved_by from core.notes where id = ?", UUID.class, id));
    }
}
