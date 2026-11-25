package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentBatchUploadCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentBatchUploadRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.nio.charset.StandardCharsets;
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
class AdminNoteAttachmentBatchUploadPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("admin_attachment_batch_it")
            .withUsername("memo_it").withPassword("test_only");

    static final UUID ADMIN_ROLE = UUID.fromString("e6000000-0000-4000-8000-000000000001");
    static final UUID ENTRY_ROLE = UUID.fromString("e6000000-0000-4000-8000-000000000002");
    static final UUID ADMIN = UUID.fromString("e6000000-0000-4000-8000-000000000003");
    static final UUID ENTRY = UUID.fromString("e6000000-0000-4000-8000-000000000004");
    static final UUID COMPANY = UUID.fromString("e6000000-0000-4000-8000-000000000005");
    static final UUID TYPE = UUID.fromString("e6000000-0000-4000-8000-000000000006");

    JdbcTemplate jdbc;
    AdminNoteAttachmentBatchUploadRepository repository;

    @BeforeEach void init() {
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
            create trigger trg_attachment_batch_updated
              before update on core.notes for each row
              execute function common.set_updated_at();
            """);
        jdbc.update("""
            insert into auth.roles(id, code, name_ar)
            values (?, 'ADMIN', 'Admin'), (?, 'DATA_ENTRY', 'Entry')
            """, ADMIN_ROLE, ENTRY_ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'admin', 'hash', 'Admin', ?), (?, 'entry', 'hash', 'Entry', ?)
            """, ADMIN, ADMIN_ROLE, ENTRY, ENTRY_ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'Company')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'Entry')", TYPE);

        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcAdminNoteAttachmentBatchUploadRepository(jdbc));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{AdminNoteAttachmentBatchUploadRepository.class});
        proxy.afterPropertiesSet();
        repository = (AdminNoteAttachmentBatchUploadRepository) proxy.getObject();
    }

    static byte[] pdfBytes(String key) {
        return ("%PDF-1.4\n" + key + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
    }
    static NoteAttachment pdf(String file) {
        return new NoteAttachment(file, pdfBytes(file));
    }

    UUID note(boolean primary) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into core.notes(
                id, company_id, note_type_id, note_number, arrival_at,
                status, notes, created_by, reviewer_id, approved_by, approved_at,
                attachment_file_name, attachment_content_type, attachment_data
            ) values (?, ?, ?, '100', date '2026-11-24',
                'APPROVED', 'unchanged', ?, ?, ?,
                timestamp '2026-11-24 09:00:00', ?, ?, ?)
            """, id, COMPANY, TYPE, ENTRY, ENTRY, ENTRY,
            primary ? "existing.pdf" : null,
            primary ? "application/pdf" : null,
            primary ? pdfBytes("existing.pdf") : null);
        return id;
    }

    LocalDateTime version(UUID noteId) {
        return jdbc.queryForObject("select updated_at from core.notes where id = ?",
            LocalDateTime.class, noteId);
    }
    AdminNoteAttachmentBatchUploadCommand command(UUID id, NoteAttachment... files) {
        return new AdminNoteAttachmentBatchUploadCommand(id, version(id), List.of(files));
    }
    int count(String table, String field, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + field + " = ?",
            Integer.class, id);
    }
    String primaryName(UUID id) {
        return jdbc.queryForObject("select attachment_file_name from core.notes where id = ?",
            String.class, id);
    }

    @Test void appendsThreeFilesKeepsExistingPrimaryAndWorkflowUnchanged() {
        UUID id = note(true);
        var uploaded = repository.upload(command(id,
            pdf("one.pdf"), pdf("two.pdf"), pdf("three.pdf")), ADMIN);
        assertEquals(3, uploaded.uploaded().size());
        assertEquals("existing.pdf", primaryName(id));
        assertArrayEquals(pdfBytes("existing.pdf"), jdbc.queryForObject(
            "select attachment_data from core.notes where id = ?", byte[].class, id));
        assertEquals(3, count("core.note_attachments", "note_id", id));
        assertEquals(3, jdbc.queryForObject("""
            select count(*) from audit.logs
            where action_type = 'NOTE_ATTACHMENT_UPLOADED'
              and new_value->>'noteId' = ?
            """, Integer.class, id.toString()));
        assertEquals("APPROVED", jdbc.queryForObject(
            "select status from core.notes where id = ?", String.class, id));
        assertEquals(ENTRY, jdbc.queryForObject(
            "select reviewer_id from core.notes where id = ?", UUID.class, id));
        assertEquals(ENTRY, jdbc.queryForObject(
            "select approved_by from core.notes where id = ?", UUID.class, id));
        assertEquals(0, count("core.note_review_history", "note_id", id));
        assertEquals(3, uploaded.uploaded().stream().map(f -> f.id()).distinct().count());
    }

    @Test void emptyPrimaryIsPopulatedFromFirstFileOnly() {
        UUID id = note(false);
        repository.upload(command(id, pdf("first.pdf"), pdf("second.pdf")), ADMIN);
        assertEquals("first.pdf", primaryName(id));
        assertArrayEquals(pdfBytes("first.pdf"), jdbc.queryForObject(
            "select attachment_data from core.notes where id = ?", byte[].class, id));
        assertEquals(2, count("core.note_attachments", "note_id", id));
    }

    @Test void nonAdminAndStaleVersionDoNotInsertOrAuditAnything() {
        UUID id = note(false);
        assertThrows(MasterDataForbiddenException.class,
            () -> repository.upload(command(id, pdf("first.pdf")), ENTRY));
        var stale = new AdminNoteAttachmentBatchUploadCommand(
            id, LocalDateTime.parse("2020-01-01T00:00:00"), List.of(pdf("first.pdf")));
        assertThrows(NoteDraftEditConflict.class, () -> repository.upload(stale, ADMIN));
        assertEquals(0, count("core.note_attachments", "note_id", id));
        assertEquals(0, count("audit.logs", "record_id", id));
    }

    @Test void invalidLaterFileRollsBackEarlierInsert() {
        UUID id = note(false);
        var broken = new NoteAttachment("invalid.pdf", new byte[]{1, 2, 3});
        assertThrows(NoteSaveValidationException.class,
            () -> repository.upload(command(id, pdf("one.pdf"), broken), ADMIN));
        assertNull(primaryName(id));
        assertEquals(0, count("core.note_attachments", "note_id", id));
    }

    @Test void auditFailureRollsBackAllFilesAndPrimaryColumns() {
        UUID id = note(false);
        LocalDateTime before = version(id);
        jdbc.execute("""
            alter table audit.logs add constraint reject_multi_upload
            check (action_type <> 'NOTE_ATTACHMENT_UPLOADED')
            """);
        assertThrows(Exception.class, () -> repository.upload(
            command(id, pdf("one.pdf"), pdf("two.pdf")), ADMIN));
        assertEquals(0, count("core.note_attachments", "note_id", id));
        assertNull(primaryName(id));
        assertEquals(before, version(id));
    }
}
