package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentDeleteRepository;
import io.github.waleed875.memo.modules.notes.application.NoteAttachmentNotFound;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import java.nio.charset.StandardCharsets;
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
class AdminNoteAttachmentDeletePostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("admin_attachment_delete_it")
            .withUsername("memo_it").withPassword("test_only");

    static final UUID ADMIN_ROLE = UUID.fromString("e5000000-0000-4000-8000-000000000001");
    static final UUID ENTRY_ROLE = UUID.fromString("e5000000-0000-4000-8000-000000000002");
    static final UUID ADMIN = UUID.fromString("e5000000-0000-4000-8000-000000000003");
    static final UUID ENTRY = UUID.fromString("e5000000-0000-4000-8000-000000000004");
    static final UUID COMPANY = UUID.fromString("e5000000-0000-4000-8000-000000000005");
    static final UUID TYPE = UUID.fromString("e5000000-0000-4000-8000-000000000006");

    private JdbcTemplate jdbc;
    private AdminNoteAttachmentDeleteRepository repository;

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
            new ClassPathResource("postgres-notes-create-attachments-links.sql")).execute(ds);
        jdbc.execute("""
            create schema if not exists common;
            create or replace function common.set_updated_at() returns trigger
            language plpgsql as $memo$
            begin new.updated_at = now(); return new; end;
            $memo$;
            create trigger trg_attachment_delete_updated
            before update on core.notes for each row
            execute function common.set_updated_at();
            """);

        jdbc.update("""
            insert into auth.roles(id, code, name_ar)
            values (?, 'ADMIN', 'Admin'), (?, 'DATA_ENTRY', 'Entry')
            """, ADMIN_ROLE, ENTRY_ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'admin', 'hash', 'Admin', ?),
                   (?, 'entry', 'hash', 'Entry', ?)
            """, ADMIN, ADMIN_ROLE, ENTRY, ENTRY_ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'Company')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'Entry')", TYPE);

        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcAdminNoteAttachmentDeleteRepository(jdbc));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{AdminNoteAttachmentDeleteRepository.class});
        proxy.afterPropertiesSet();
        repository = (AdminNoteAttachmentDeleteRepository) proxy.getObject();
    }

    private static byte[] bytes(String name) {
        return ("PDF:" + name).getBytes(StandardCharsets.US_ASCII);
    }

    private UUID note(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into core.notes(
                id, company_id, note_type_id, note_number, arrival_at,
                notes, status, created_by, reviewer_id, approved_by, approved_at,
                attachment_file_name, attachment_content_type, attachment_data
            ) values (?, ?, ?, '101', date '2026-11-24',
                'preserve-me', ?, ?, ?, ?,
                timestamp '2026-11-24 10:00:00',
                'old.pdf', 'application/pdf', ?)
            """, id, COMPANY, TYPE, status, ENTRY, ENTRY, ENTRY, bytes("old"));
        return id;
    }

    private UUID attach(UUID note, String fileName, String when) {
        UUID id = UUID.randomUUID();
        byte[] bytes = bytes(fileName);
        jdbc.update("""
            insert into core.note_attachments(
                id, note_id, file_name, content_type,
                file_data, file_size, created_at, created_by)
            values (?, ?, ?, 'application/pdf', ?, ?, cast(? as timestamptz), ?)
            """, id, note, fileName, bytes, (long) bytes.length, when, ENTRY);
        return id;
    }

    private LocalDateTime version(UUID id) {
        return jdbc.queryForObject(
            "select updated_at from core.notes where id = ?",
            LocalDateTime.class, id);
    }
    private int count(String table, String col, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + col + " = ?",
            Integer.class, id);
    }

    @Test void deleteSelectsOldestRemainingAndPreservesApprovalAndReview() {
        UUID note = note("APPROVED");
        UUID old = attach(note, "old.pdf", "2026-11-20T09:00:00Z");
        attach(note, "next.pdf", "2026-11-21T09:00:00Z");
        attach(note, "later.pdf", "2026-11-22T09:00:00Z");

        var result = repository.delete(note, old, version(note), ADMIN);
        assertEquals("old.pdf", result.fileName());
        assertEquals("next.pdf", result.nextPrimaryFileName());
        assertEquals("next.pdf", jdbc.queryForObject(
            "select attachment_file_name from core.notes where id = ?", String.class, note));
        assertArrayEquals(bytes("next.pdf"), jdbc.queryForObject(
            "select attachment_data from core.notes where id = ?", byte[].class, note));
        assertEquals(2, count("core.note_attachments", "note_id", note));
        assertEquals("APPROVED", jdbc.queryForObject(
            "select status from core.notes where id = ?", String.class, note));
        assertEquals(ENTRY, jdbc.queryForObject(
            "select reviewer_id from core.notes where id = ?", UUID.class, note));
        assertEquals(ENTRY, jdbc.queryForObject(
            "select approved_by from core.notes where id = ?", UUID.class, note));
        assertEquals("preserve-me", jdbc.queryForObject(
            "select notes from core.notes where id = ?", String.class, note));
        assertEquals(1, count("audit.logs", "record_id", old));
        assertEquals("NOTE_ATTACHMENT_DELETED", jdbc.queryForObject(
            "select action_type from audit.logs where record_id = ?", String.class, old));
        assertEquals(0, count("core.note_review_history", "note_id", note));
    }

    @Test void deletingFinalAttachmentClearsPrimaryColumns() {
        UUID note = note("UNDER_REVIEW");
        UUID only = attach(note, "old.pdf", "2026-11-20T09:00:00Z");
        var result = repository.delete(note, only, version(note), ADMIN);
        assertNull(result.nextPrimaryFileName());
        assertNull(jdbc.queryForObject(
            "select attachment_file_name from core.notes where id = ?", String.class, note));
        assertNull(jdbc.queryForObject(
            "select attachment_content_type from core.notes where id = ?", String.class, note));
        assertNull(jdbc.queryForObject(
            "select attachment_data from core.notes where id = ?", byte[].class, note));
        assertEquals(0, count("core.note_attachments", "note_id", note));
        assertEquals("UNDER_REVIEW", jdbc.queryForObject(
            "select status from core.notes where id = ?", String.class, note));
    }

    @Test void rejectsDifferentNoteAttachmentStaleVersionAndNonAdminWithoutSideEffects() {
        UUID note = note("COMPLETE"), foreign = note("DRAFT");
        UUID file = attach(foreign, "foreign.pdf", "2026-11-20T09:00:00Z");
        assertThrows(MasterDataForbiddenException.class,
            () -> repository.delete(note, file, version(note), ENTRY));
        assertThrows(NoteAttachmentNotFound.class,
            () -> repository.delete(note, file, version(note), ADMIN));
        assertThrows(NoteDraftEditConflict.class, () -> repository.delete(
            foreign, file, LocalDateTime.parse("2020-01-01T00:00:00"), ADMIN));
        assertEquals(1, count("core.note_attachments", "note_id", foreign));
        assertEquals(0, count("audit.logs", "record_id", file));
    }

    @Test void failedAuditRollsBackAttachmentDeletionAndPrimaryUpdate() {
        UUID note = note("APPROVED");
        UUID old = attach(note, "old.pdf", "2026-11-20T09:00:00Z");
        attach(note, "next.pdf", "2026-11-21T09:00:00Z");
        LocalDateTime revision = version(note);
        jdbc.execute("""
            alter table audit.logs add constraint reject_attachment_delete
            check (action_type <> 'NOTE_ATTACHMENT_DELETED')
            """);
        assertThrows(Exception.class,
            () -> repository.delete(note, old, revision, ADMIN));
        assertEquals(2, count("core.note_attachments", "note_id", note));
        assertEquals("old.pdf", jdbc.queryForObject(
            "select attachment_file_name from core.notes where id = ?", String.class, note));
        assertEquals(revision, version(note));
        assertEquals(0, count("audit.logs", "record_id", old));
    }

    @Test void equalCreationTimestampsHaveDeterministicPrimarySelection() {
        UUID note = note("COMPLETE");
        UUID first = attach(note, "removable.pdf", "2026-11-20T09:00:00Z");
        UUID a = attach(note, "a.pdf", "2026-11-21T09:00:00Z");
        UUID b = attach(note, "b.pdf", "2026-11-21T09:00:00Z");
        var deleted = repository.delete(note, first, version(note), ADMIN);
        String expected = a.compareTo(b) < 0 ? "a.pdf" : "b.pdf";
        assertEquals(expected, deleted.nextPrimaryFileName());
    }
}
