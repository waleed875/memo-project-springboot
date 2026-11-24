package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminNoteTextEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteTextEditRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
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
class AdminNoteTextEditPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("admin_text_edit_it").withUsername("memo_it")
            .withPassword("test_only");

    static final UUID ADMIN_ROLE = UUID.fromString("e2000000-0000-4000-8000-000000000001");
    static final UUID ENTRY_ROLE = UUID.fromString("e2000000-0000-4000-8000-000000000002");
    static final UUID ADMIN = UUID.fromString("e2000000-0000-4000-8000-000000000003");
    static final UUID ENTRY = UUID.fromString("e2000000-0000-4000-8000-000000000004");
    static final UUID COMPANY = UUID.fromString("e2000000-0000-4000-8000-000000000005");
    static final UUID TYPE = UUID.fromString("e2000000-0000-4000-8000-000000000006");
    JdbcTemplate jdbc;
    AdminNoteTextEditRepository repository;

    @BeforeEach void init() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(),
            postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop schema if exists audit cascade");
        jdbc.execute("drop schema if exists core cascade");
        jdbc.execute("drop schema if exists auth cascade");
        jdbc.execute("drop schema if exists system cascade");
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql")).execute(ds);
        jdbc.execute("""
            create schema if not exists common;
            create or replace function common.set_updated_at() returns trigger
            language plpgsql as $$
            begin new.updated_at = now(); return new; end;
            $$;
            create trigger trg_admin_note_updated
              before update on core.notes for each row
              execute function common.set_updated_at();
            """);
        jdbc.update("""
            insert into auth.roles(id, code, name_ar) values
            (?, 'ADMIN', 'Admin'), (?, 'DATA_ENTRY', 'Entry')
            """, ADMIN_ROLE, ENTRY_ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id) values
            (?, 'admin', 'test', 'Admin', ?), (?, 'entry', 'test', 'Entry', ?)
            """, ADMIN, ADMIN_ROLE, ENTRY, ENTRY_ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'Company')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'Entry')", TYPE);

        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcAdminNoteTextEditRepository(jdbc));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{AdminNoteTextEditRepository.class});
        proxy.afterPropertiesSet();
        repository = (AdminNoteTextEditRepository) proxy.getObject();
    }

    private UUID note(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into core.notes(id, company_id, note_type_id,
              note_number, status, notes, created_by, arrival_at,
              reviewer_id, review_started_at, approved_by, approved_at)
            values (?, ?, ?, '123', ?, 'before', ?, date '2026-11-20',
              ?, timestamp '2026-11-20 08:00:00', ?, timestamp '2026-11-20 09:00:00')
            """, id, COMPANY, TYPE, status, ENTRY, ENTRY, ENTRY);
        return id;
    }

    private LocalDateTime version(UUID id) {
        return jdbc.queryForObject("select updated_at from core.notes where id = ?",
            LocalDateTime.class, id);
    }
    private AdminNoteTextEditCommand command(UUID id) {
        return new AdminNoteTextEditCommand(id, version(id), "after");
    }
    private String status(UUID id) {
        return jdbc.queryForObject("select status from core.notes where id = ?", String.class, id);
    }
    private int count(UUID id) {
        return jdbc.queryForObject("select count(*) from audit.logs where record_id = ?",
            Integer.class, id);
    }

    @Test void preservesApprovalAndReviewMetadataWithStructuredAudit() {
        UUID id = note("APPROVED");
        LocalDateTime oldVersion = version(id);
        LocalDateTime next = repository.updateText(command(id), ADMIN);
        assertEquals("APPROVED", status(id));
        assertEquals("after", jdbc.queryForObject("select notes from core.notes where id = ?", String.class, id));
        assertEquals(ENTRY, jdbc.queryForObject("select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertEquals(ENTRY, jdbc.queryForObject("select approved_by from core.notes where id = ?", UUID.class, id));
        assertEquals(LocalDateTime.parse("2026-11-20T08:00:00"), jdbc.queryForObject(
            "select review_started_at from core.notes where id = ?", LocalDateTime.class, id));
        assertEquals(LocalDateTime.parse("2026-11-20T09:00:00"), jdbc.queryForObject(
            "select approved_at from core.notes where id = ?", LocalDateTime.class, id));
        assertEquals(1, count(id));
        assertEquals("ADMIN_EDIT_NOTE", jdbc.queryForObject(
            "select action_type from audit.logs where record_id = ?", String.class, id));
        assertEquals("before", jdbc.queryForObject(
            "select old_value->>'notes' from audit.logs where record_id = ?", String.class, id));
        assertEquals("after", jdbc.queryForObject(
            "select new_value->>'notes' from audit.logs where record_id = ?", String.class, id));
        assertTrue(!next.isBefore(oldVersion));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from core.note_review_history where note_id = ?",
            Integer.class, id));
    }

    @Test void preservesActiveReviewOwnerAndPreventsStaleUpdates() {
        UUID id = note("UNDER_REVIEW");
        var stale = new AdminNoteTextEditCommand(id, LocalDateTime.parse("2020-01-01T00:00:00"), "stale");
        assertThrows(NoteDraftEditConflict.class, () -> repository.updateText(stale, ADMIN));
        repository.updateText(command(id), ADMIN);
        assertEquals("UNDER_REVIEW", status(id));
        assertEquals(ENTRY, jdbc.queryForObject("select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertEquals(1, count(id));
    }

    @Test void requiresCurrentAdminRoleAndAuditFailureRollsBackText() {
        UUID id = note("DRAFT");
        assertThrows(MasterDataForbiddenException.class, () -> repository.updateText(command(id), ENTRY));
        jdbc.execute("""
            alter table audit.logs add constraint reject_admin_audit
            check (action_type <> 'ADMIN_EDIT_NOTE')
            """);
        assertThrows(Exception.class, () -> repository.updateText(command(id), ADMIN));
        assertEquals("before", jdbc.queryForObject("select notes from core.notes where id = ?", String.class, id));
        assertEquals("DRAFT", status(id));
        assertEquals(0, count(id));
    }
}
