package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditCommand;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditRepository;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class NoteDraftEditPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("draft_edit_it").withUsername("memo_it")
            .withPassword("test_only");

    static final UUID ROLE = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    static final UUID USER = UUID.fromString("e1000000-0000-4000-8000-000000000002");
    static final UUID OTHER = UUID.fromString("e1000000-0000-4000-8000-000000000003");
    static final UUID COMPANY = UUID.fromString("e1000000-0000-4000-8000-000000000004");
    static final UUID TYPE = UUID.fromString("e1000000-0000-4000-8000-000000000005");
    static final UUID FIELD = UUID.fromString("e1000000-0000-4000-8000-000000000006");
    static final UUID OBSOLETE = UUID.fromString("e1000000-0000-4000-8000-000000000007");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    JdbcTemplate jdbc;
    NoteDraftEditRepository repository;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(),
            postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop schema if exists audit cascade");
        jdbc.execute("drop schema if exists core cascade");
        jdbc.execute("drop schema if exists auth cascade");
        jdbc.execute("drop schema if exists system cascade");
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql"),
            new ClassPathResource("postgres-notes-draft-edit-schema.sql")).execute(ds);

        jdbc.update("insert into auth.roles(id, code, name_ar) values (?, 'DATA_ENTRY', 'إدخال')", ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'first', 'hash', 'First', ?), (?, 'other', 'hash', 'Other', ?)
            """, USER, ROLE, OTHER, ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'Company')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'Entry')", TYPE);
        jdbc.update("""
            insert into core.note_fields(id, code, name_ar, is_active)
            values (?, 'ACTIVE', 'مؤشر أساسي', true), (?, 'INACTIVE', 'قديم', false)
            """, FIELD, OBSOLETE);
        jdbc.update("""
            insert into core.note_type_fields(id, note_type_id, field_id, sort_order, is_required)
            values (?, ?, ?, 1, true), (?, ?, ?, 2, true)
            """, UUID.randomUUID(), TYPE, FIELD, UUID.randomUUID(), TYPE, OBSOLETE);

        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcNoteDraftEditRepository(jdbc, new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{NoteDraftEditRepository.class});
        proxy.afterPropertiesSet();
        repository = (NoteDraftEditRepository) proxy.getObject();
    }

    UUID note(UUID creator, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into core.notes(
              id, company_id, note_type_id, note_number,
              writing_date, arrival_at, created_by, status, notes)
            values (?, ?, ?, '123', date '2026-11-20', date '2026-11-21',
                    ?, ?, 'before')
            """, id, COMPANY, TYPE, creator, status);
        jdbc.update("""
            insert into core.note_field_values(id, note_id, field_id, field_value)
            values (?, ?, ?, 0), (?, ?, ?, 2)
            """, UUID.randomUUID(), id, FIELD, UUID.randomUUID(), id, OBSOLETE);
        return id;
    }

    LocalDateTime version(UUID id) {
        return jdbc.queryForObject("select updated_at from core.notes where id = ?",
            LocalDateTime.class, id);
    }
    NoteDraftEditCommand cmd(UUID id, boolean draft, IndicatorValue value) {
        return new NoteDraftEditCommand(id, version(id), "after", draft, false,
            List.of(new NoteDraftEditCommand.IndicatorInput(FIELD, value, null)));
    }
    String status(UUID id) {
        return jdbc.queryForObject("select status from core.notes where id = ?", String.class, id);
    }
    int count(String table, UUID id, String column) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?",
            Integer.class, id);
    }

    @Test void savesDraftWithoutChangingMediaOrCatalogAndDeletesInactiveField() {
        UUID id = note(USER, "DRAFT");
        var result = repository.edit(cmd(id, true, IndicatorValue.Unreviewed), USER, TODAY);
        assertEquals("DRAFT", status(id));
        assertEquals("after", jdbc.queryForObject("select notes from core.notes where id = ?", String.class, id));
        assertEquals(1, count("core.note_field_values", id, "note_id"));
        assertEquals("SAVE_DRAFT", jdbc.queryForObject(
            "select action from core.note_review_history where note_id = ?", String.class, id));
        assertEquals(2, count("audit.logs", id, "record_id"));
        assertEquals("UPDATE_NOTE", jdbc.queryForObject("""
            select action_type from audit.logs where record_id = ? order by id limit 1
            """, String.class, id));
        assertEquals(id, result.noteId());
        assertNotNull(result.updatedAt());
    }

    @Test void resubmissionClearsReturnStateButDoesNotTouchLinksOrAttachmentBytes() {
        UUID id = note(USER, "RETURNED_FOR_CORRECTION");
        jdbc.update("""
            update core.notes
            set reviewer_id = ?, review_started_at = now(), return_reason = 'old', attachment_data = ?
            where id = ?
            """, OTHER, new byte[]{1,2,3}, id);
        var result = repository.edit(cmd(id, false, IndicatorValue.Correct), USER, TODAY);
        assertEquals("COMPLETE", status(id));
        assertEquals("RESUBMIT_FOR_REVIEW", jdbc.queryForObject(
            "select action from core.note_review_history where note_id = ?", String.class, id));
        assertNull(jdbc.queryForObject("select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertNull(jdbc.queryForObject("select return_reason from core.notes where id = ?", String.class, id));
        assertArrayEquals(new byte[]{1,2,3}, jdbc.queryForObject(
            "select attachment_data from core.notes where id = ?", byte[].class, id));
        assertEquals("COMPLETE", result.status().name());
    }

    @Test void rejectsForeignOwnerAndNonDraftStatusWithNoAudit() {
        UUID foreign = note(OTHER, "DRAFT");
        assertThrows(NoteAccessDenied.class,
            () -> repository.edit(cmd(foreign, true, IndicatorValue.Correct), USER, TODAY));
        UUID approved = note(USER, "APPROVED");
        assertThrows(NoteDraftEditConflict.class,
            () -> repository.edit(cmd(approved, true, IndicatorValue.Correct), USER, TODAY));
        assertEquals(0, count("audit.logs", foreign, "record_id"));
        assertEquals(0, count("audit.logs", approved, "record_id"));
    }

    @Test void staleRevisionAndIncompleteResubmissionFailWithoutMutation() {
        UUID id = note(USER, "RETURNED_FOR_CORRECTION");
        var stale = new NoteDraftEditCommand(id, LocalDateTime.parse("2020-01-01T00:00:00"),
            "after", false, false, List.of());
        assertThrows(NoteDraftEditConflict.class, () -> repository.edit(stale, USER, TODAY));
        assertThrows(NoteSaveValidationException.class,
            () -> repository.edit(cmd(id, false, IndicatorValue.Unreviewed), USER, TODAY));
        assertEquals("before", jdbc.queryForObject("select notes from core.notes where id = ?", String.class, id));
        assertEquals(0, count("audit.logs", id, "record_id"));
    }

    @Test void auditInsertFailureRollsBackNoteValuesHistoryAndAudit() {
        UUID id = note(USER, "DRAFT");
        jdbc.execute("""
            alter table audit.logs
            add constraint reject_draft_test
            check (action_type <> 'UPDATE_NOTE')
            """);
        assertThrows(Exception.class,
            () -> repository.edit(cmd(id, false, IndicatorValue.Correct), USER, TODAY));
        assertEquals("DRAFT", status(id));
        assertEquals("before", jdbc.queryForObject("select notes from core.notes where id = ?", String.class, id));
        assertEquals(2, count("core.note_field_values", id, "note_id"));
        assertEquals(0, count("core.note_review_history", id, "note_id"));
        assertEquals(0, count("audit.logs", id, "record_id"));
    }
}
