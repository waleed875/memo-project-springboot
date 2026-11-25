package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentUploadRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
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
class AdminNoteAttachmentUploadPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("admin_attachment_upload_it")
            .withUsername("memo_it").withPassword("test_only");

    static final UUID ADMIN_ROLE = UUID.fromString("e4000000-0000-4000-8000-000000000001");
    static final UUID ENTRY_ROLE = UUID.fromString("e4000000-0000-4000-8000-000000000002");
    static final UUID ADMIN = UUID.fromString("e4000000-0000-4000-8000-000000000003");
    static final UUID ENTRY = UUID.fromString("e4000000-0000-4000-8000-000000000004");
    static final UUID COMPANY = UUID.fromString("e4000000-0000-4000-8000-000000000005");
    static final UUID TYPE = UUID.fromString("e4000000-0000-4000-8000-000000000006");

    JdbcTemplate jdbc;
    AdminNoteAttachmentUploadRepository repository;

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
            new ClassPathResource("postgres-notes-detail-schema.sql"),
            new ClassPathResource("postgres-notes-create-attachments-links.sql")).execute(ds);
        jdbc.execute("""
            create schema if not exists common;
            create or replace function common.set_updated_at() returns trigger
            language plpgsql as $memo$
            begin new.updated_at = now(); return new; end;
            $memo$;
            create trigger trg_attachment_note_updated
            before update on core.notes for each row
            execute function common.set_updated_at();
            """);

        jdbc.update("""
            insert into auth.roles(id, code, name_ar) values
            (?, 'ADMIN', 'Admin'), (?, 'DATA_ENTRY', 'Entry')
            """, ADMIN_ROLE, ENTRY_ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'admin', 'hash', 'Admin', ?), (?, 'entry', 'hash', 'Entry', ?)
            """, ADMIN, ADMIN_ROLE, ENTRY, ENTRY_ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'Company')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'Entry')", TYPE);

        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcAdminNoteAttachmentUploadRepository(jdbc));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{AdminNoteAttachmentUploadRepository.class});
        proxy.afterPropertiesSet();
        repository = (AdminNoteAttachmentUploadRepository) proxy.getObject();
    }

    private static byte[] pdf(String marker) {
        return ("%PDF-1.4\n" + marker + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    private UUID note() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into core.notes(
                id, company_id, note_type_id, note_number, arrival_at,
                notes, status, created_by, reviewer_id,
                approved_by, approved_at,
                attachment_file_name, attachment_content_type, attachment_data
            ) values (?, ?, ?, '101', date '2026-11-24',
                'untouched', 'APPROVED', ?, ?, ?,
                timestamp '2026-11-24 10:00:00', 'old.pdf', 'application/pdf', ?)
            """, id, COMPANY, TYPE, ENTRY, ENTRY, ENTRY, pdf("old"));
        UUID oldAttachment = UUID.randomUUID();
        jdbc.update("""
            insert into core.note_attachments(
                id, note_id, file_name, content_type, file_data, file_size, created_by)
            values (?, ?, 'old.pdf', 'application/pdf', ?, ?, ?)
            """, oldAttachment, id, pdf("old"), (long)pdf("old").length, ENTRY);
        return id;
    }

    private LocalDateTime version(UUID id) {
        return jdbc.queryForObject("select updated_at from core.notes where id = ?",
            LocalDateTime.class, id);
    }

    private NoteAttachment newAttachment() {
        return new NoteAttachment("new.pdf", pdf("new"));
    }

    private int count(String table, String field, UUID id) {
        return jdbc.queryForObject(
            "select count(*) from " + table + " where " + field + " = ?", Integer.class, id);
    }

    @Test void appendAndReplacePrimaryWithoutDeletingOldHistoryOrWorkflow() {
        UUID id = note();
        var uploaded = repository.upload(id, version(id), newAttachment(), ADMIN);
        assertNotNull(uploaded.attachmentId());
        assertEquals("application/pdf", uploaded.contentType());
        assertEquals(2, count("core.note_attachments", "note_id", id));
        assertEquals("new.pdf", jdbc.queryForObject(
            "select attachment_file_name from core.notes where id = ?", String.class, id));
        assertArrayEquals(pdf("new"), jdbc.queryForObject(
            "select attachment_data from core.notes where id = ?", byte[].class, id));
        assertEquals("APPROVED", jdbc.queryForObject(
            "select status from core.notes where id = ?", String.class, id));
        assertEquals(ENTRY, jdbc.queryForObject(
            "select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertEquals(ENTRY, jdbc.queryForObject(
            "select approved_by from core.notes where id = ?", UUID.class, id));
        assertEquals(0, count("core.note_review_history", "note_id", id));
        assertEquals(1, count("audit.logs", "record_id", id));
        assertEquals("NOTE_ATTACHMENT_UPLOADED", jdbc.queryForObject(
            "select action_type from audit.logs where record_id = ?", String.class, id));
        assertEquals("old.pdf", jdbc.queryForObject(
            "select old_value->>'fileName' from audit.logs where record_id = ?", String.class, id));
        assertEquals(uploaded.attachmentId().toString(), jdbc.queryForObject(
            "select new_value->>'attachmentId' from audit.logs where record_id = ?", String.class, id));
    }

    @Test void staleVersionAndNonAdminDoNotCreateNewAttachments() {
        UUID id = note();
        assertThrows(MasterDataForbiddenException.class,
            () -> repository.upload(id, version(id), newAttachment(), ENTRY));
        assertThrows(NoteDraftEditConflict.class,
            () -> repository.upload(id, LocalDateTime.parse("2020-01-01T00:00:00"),
                newAttachment(), ADMIN));
        assertEquals(1, count("core.note_attachments", "note_id", id));
        assertEquals(0, count("audit.logs", "record_id", id));
    }

    @Test void auditFailureRollsBackPrimaryReplacementAndAttachmentInsert() {
        UUID id = note();
        LocalDateTime before = version(id);
        jdbc.execute("""
            alter table audit.logs add constraint reject_attachment_uploaded
            check (action_type <> 'NOTE_ATTACHMENT_UPLOADED')
            """);
        assertThrows(Exception.class,
            () -> repository.upload(id, version(id), newAttachment(), ADMIN));
        assertEquals("old.pdf", jdbc.queryForObject(
            "select attachment_file_name from core.notes where id = ?", String.class, id));
        assertArrayEquals(pdf("old"), jdbc.queryForObject(
            "select attachment_data from core.notes where id = ?", byte[].class, id));
        assertEquals(1, count("core.note_attachments", "note_id", id));
        assertEquals(0, count("audit.logs", "record_id", id));
        assertEquals(before, version(id));
    }
}
