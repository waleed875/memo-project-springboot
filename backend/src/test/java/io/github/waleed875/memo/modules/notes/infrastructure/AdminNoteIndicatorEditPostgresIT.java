package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminNoteIndicatorEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteIndicatorEditRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AdminNoteIndicatorEditPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("admin_indicators_it")
            .withUsername("memo_it").withPassword("test_only");

    static final UUID ADMIN_ROLE = UUID.fromString("e3000000-0000-4000-8000-000000000001");
    static final UUID ENTRY_ROLE = UUID.fromString("e3000000-0000-4000-8000-000000000002");
    static final UUID ADMIN = UUID.fromString("e3000000-0000-4000-8000-000000000003");
    static final UUID ENTRY = UUID.fromString("e3000000-0000-4000-8000-000000000004");
    static final UUID COMPANY = UUID.fromString("e3000000-0000-4000-8000-000000000005");
    static final UUID TYPE = UUID.fromString("e3000000-0000-4000-8000-000000000006");
    static final UUID ACTIVE = UUID.fromString("e3000000-0000-4000-8000-000000000007");
    static final UUID HISTORIC = UUID.fromString("e3000000-0000-4000-8000-000000000008");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    JdbcTemplate jdbc;
    AdminNoteIndicatorEditRepository repository;

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
            new ClassPathResource("postgres-notes-detail-schema.sql"),
            new ClassPathResource("postgres-notes-draft-edit-schema.sql")).execute(ds);

        // Fixture is deliberately reduced: execute PL/pgSQL as a whole
        // instead of splitting the function body on semicolons.
        jdbc.execute("""
            create schema if not exists common;
            create or replace function common.set_updated_at() returns trigger
            language plpgsql as $memo$
            begin new.updated_at = now(); return new; end;
            $memo$;
            create trigger trg_admin_indicator_updated
            before update on core.notes for each row
            execute function common.set_updated_at();
            """);
        jdbc.update("""
            insert into auth.roles(id, code, name_ar)
            values (?, 'ADMIN', 'Admin'), (?, 'DATA_ENTRY', 'Entry')
            """, ADMIN_ROLE, ENTRY_ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'admin', 'test', 'Admin', ?), (?, 'entry', 'test', 'Entry', ?)
            """, ADMIN, ADMIN_ROLE, ENTRY, ENTRY_ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'Company')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'Entry')", TYPE);
        jdbc.update("""
            insert into core.note_fields(id, code, name_ar, is_active)
            values (?, 'ACTIVE', 'Required active', true),
                   (?, 'HISTORIC', 'Historic inactive', false)
            """, ACTIVE, HISTORIC);
        jdbc.update("""
            insert into core.note_type_fields(id, note_type_id, field_id, sort_order, is_required)
            values (?, ?, ?, 1, true), (?, ?, ?, 2, true)
            """, UUID.randomUUID(), TYPE, ACTIVE, UUID.randomUUID(), TYPE, HISTORIC);

        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcAdminNoteIndicatorEditRepository(jdbc));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{AdminNoteIndicatorEditRepository.class});
        proxy.afterPropertiesSet();
        repository = (AdminNoteIndicatorEditRepository) proxy.getObject();
    }

    UUID memo(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into core.notes(
                id, company_id, note_type_id, note_number, writing_date,
                arrival_at, status, notes, created_by, reviewer_id,
                review_started_at, approved_by, approved_at)
            values (?, ?, ?, '100', date '2026-11-20', date '2026-11-21',
                ?, 'preserved', ?, ?, timestamp '2026-11-22 08:00:00',
                ?, timestamp '2026-11-22 10:00:00')
            """, id, COMPANY, TYPE, status, ENTRY, ENTRY, ENTRY);
        jdbc.update("""
            insert into core.note_field_values(id, note_id, field_id, field_value, inactive_reason)
            values (?, ?, ?, 1, 'old active'), (?, ?, ?, 2, 'historic')
            """, UUID.randomUUID(), id, ACTIVE, UUID.randomUUID(), id, HISTORIC);
        return id;
    }

    LocalDateTime revision(UUID id) {
        return jdbc.queryForObject(
            "select updated_at from core.notes where id = ?", LocalDateTime.class, id);
    }

    AdminNoteIndicatorEditCommand command(UUID id, IndicatorValue newValue) {
        return new AdminNoteIndicatorEditCommand(id, revision(id), false,
            List.of(new AdminNoteIndicatorEditCommand.IndicatorInput(
                ACTIVE, newValue, "updated")));
    }

    int count(String table, UUID id, String field) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + field + " = ?",
            Integer.class, id);
    }

    short value(UUID note, UUID field) {
        return jdbc.queryForObject("""
            select field_value from core.note_field_values
            where note_id = ? and field_id = ?
            """, Short.class, note, field);
    }

    @Test void adminEditPreservesHistoricValueAndApprovedStateWithAudits() {
        UUID id = memo("APPROVED");
        LocalDateTime before = revision(id);
        LocalDateTime after = repository.updateIndicators(
            command(id, IndicatorValue.Incorrect), ADMIN, TODAY);
        assertEquals((short) IndicatorValue.Incorrect.ordinal(), value(id, ACTIVE));
        assertEquals((short) 2, value(id, HISTORIC));
        assertEquals("APPROVED", jdbc.queryForObject(
            "select status from core.notes where id = ?", String.class, id));
        assertEquals(ENTRY, jdbc.queryForObject(
            "select approved_by from core.notes where id = ?", UUID.class, id));
        assertEquals(ENTRY, jdbc.queryForObject(
            "select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertEquals("preserved", jdbc.queryForObject(
            "select notes from core.notes where id = ?", String.class, id));
        assertEquals(0, count("core.note_review_history", id, "note_id"));
        assertEquals(2, count("audit.logs", id, "record_id"));
        assertEquals("ADMIN_EDIT_NOTE", jdbc.queryForObject("""
            select action_type from audit.logs where record_id = ? order by id limit 1
            """, String.class, id));
        assertTrue(!after.isBefore(before));
        assertEquals("APPROVED", jdbc.queryForObject("""
            select new_value->>'status' from audit.logs
            where record_id = ? and action_type = 'ADMIN_EDIT_NOTE'
            """, String.class, id));
    }

    @Test void underReviewStatusAndAssignedReviewerArePreserved() {
        UUID id = memo("UNDER_REVIEW");
        repository.updateIndicators(command(id, IndicatorValue.Correct), ADMIN, TODAY);
        assertEquals("UNDER_REVIEW", jdbc.queryForObject(
            "select status from core.notes where id = ?", String.class, id));
        assertEquals(ENTRY, jdbc.queryForObject(
            "select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertEquals((short) 2, value(id, HISTORIC));
    }

    @Test void staleOrNonAdminAreRejectedWithoutChanges() {
        UUID id = memo("COMPLETE");
        assertThrows(MasterDataForbiddenException.class, () ->
            repository.updateIndicators(command(id, IndicatorValue.Correct), ENTRY, TODAY));
        var stale = new AdminNoteIndicatorEditCommand(
            id, LocalDateTime.parse("2020-01-01T00:00:00"), false,
            List.of(new AdminNoteIndicatorEditCommand.IndicatorInput(
                ACTIVE, IndicatorValue.Correct, null)));
        assertThrows(NoteDraftEditConflict.class, () ->
            repository.updateIndicators(stale, ADMIN, TODAY));
        assertEquals((short) 1, value(id, ACTIVE));
        assertEquals(0, count("audit.logs", id, "record_id"));
    }

    @Test void missingRequiredAndMarkAllMismatchDoNotWriteAnything() {
        UUID id = memo("HAS_ERRORS");
        var missing = new AdminNoteIndicatorEditCommand(
            id, revision(id), false, List.of());
        assertThrows(NoteSaveValidationException.class, () ->
            repository.updateIndicators(missing, ADMIN, TODAY));
        var invalid = new AdminNoteIndicatorEditCommand(
            id, revision(id), true,
            List.of(new AdminNoteIndicatorEditCommand.IndicatorInput(
                ACTIVE, IndicatorValue.Incorrect, "wrong")));
        assertThrows(NoteSaveValidationException.class, () ->
            repository.updateIndicators(invalid, ADMIN, TODAY));
        assertEquals(0, count("audit.logs", id, "record_id"));
        assertEquals((short) 1, value(id, ACTIVE));
    }

    @Test void failedAuditRollsBackIndicatorChangesAndNoteRevision() {
        UUID id = memo("COMPLETE");
        LocalDateTime before = revision(id);
        jdbc.execute("""
            alter table audit.logs add constraint reject_admin_indicator_audit
            check (action_type <> 'SAVE_INDICATORS')
            """);
        assertThrows(Exception.class, () -> repository.updateIndicators(
            command(id, IndicatorValue.Incorrect), ADMIN, TODAY));
        assertEquals((short) 1, value(id, ACTIVE));
        assertEquals((short) 2, value(id, HISTORIC));
        assertEquals(before, revision(id));
        assertEquals(0, count("audit.logs", id, "record_id"));
    }
}
