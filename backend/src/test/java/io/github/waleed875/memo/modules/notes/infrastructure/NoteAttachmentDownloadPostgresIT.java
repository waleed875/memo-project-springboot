package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.application.DownloadedNoteAttachment;
import io.github.waleed875.memo.modules.notes.application.NoteAttachmentDownloadReader;
import java.nio.charset.StandardCharsets;
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

/**
 * PostgreSQL 16 authorization test for note attachment downloads, using
 * isolated legacy-shaped schema fixture. The SQL is tested, not mocked.
 */
@Testcontainers
class NoteAttachmentDownloadPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_download_it")
            .withUsername("memo_it").withPassword("test_only");

    private static final UUID ADMIN_ROLE = UUID.fromString("fd000000-0000-4000-8000-000000000001");
    private static final UUID ENTRY_ROLE = UUID.fromString("fd000000-0000-4000-8000-000000000002");
    private static final UUID VIEWER_ROLE = UUID.fromString("fd000000-0000-4000-8000-000000000003");
    private static final UUID REVIEW_ROLE = UUID.fromString("fd000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_ROLE = UUID.fromString("fd000000-0000-4000-8000-000000000005");
    private static final UUID OWNER = UUID.fromString("fd000000-0000-4000-8000-000000000011");
    private static final UUID FOREIGN = UUID.fromString("fd000000-0000-4000-8000-000000000012");
    private static final UUID ADMIN = UUID.fromString("fd000000-0000-4000-8000-000000000013");
    private static final UUID VIEWER = UUID.fromString("fd000000-0000-4000-8000-000000000014");
    private static final UUID REVIEWER = UUID.fromString("fd000000-0000-4000-8000-000000000015");
    private static final UUID UNKNOWN = UUID.fromString("fd000000-0000-4000-8000-000000000016");
    private static final UUID COMPANY = UUID.fromString("fd000000-0000-4000-8000-000000000021");
    private static final UUID TYPE = UUID.fromString("fd000000-0000-4000-8000-000000000022");
    private static final UUID NOTE = UUID.fromString("fd000000-0000-4000-8000-000000000031");
    private static final UUID SECOND_NOTE = UUID.fromString("fd000000-0000-4000-8000-000000000032");
    private static final UUID DELETED_NOTE = UUID.fromString("fd000000-0000-4000-8000-000000000033");
    private static final UUID ATTACHMENT = UUID.fromString("fd000000-0000-4000-8000-000000000041");
    private static final UUID SECOND_ATTACHMENT = UUID.fromString("fd000000-0000-4000-8000-000000000042");
    private static final UUID DELETED_ATTACHMENT = UUID.fromString("fd000000-0000-4000-8000-000000000043");
    private static final UUID EMPTY_ATTACHMENT = UUID.fromString("fd000000-0000-4000-8000-000000000044");
    private static final UUID OVERSIZE_ATTACHMENT = UUID.fromString("fd000000-0000-4000-8000-000000000045");

    private JdbcTemplate jdbc;
    private NoteAttachmentDownloadReader reader;

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
            new ClassPathResource("postgres-notes-create-attachments-links.sql")
        ).execute(ds);

        // Test through the same transactional proxy configuration as production.
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcNoteAttachmentDownloadReader(jdbc));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{NoteAttachmentDownloadReader.class});
        proxy.afterPropertiesSet();
        reader = (NoteAttachmentDownloadReader) proxy.getObject();

        jdbc.update("""
            insert into auth.roles(id, code, name_ar) values
            (?, 'ADMIN', 'Admin'), (?, 'DATA_ENTRY', 'Entry'),
            (?, 'VIEWER', 'Viewer'), (?, 'REVIEWER', 'Reviewer'),
            (?, 'OTHER', 'Unsupported')
            """, ADMIN_ROLE, ENTRY_ROLE, VIEWER_ROLE, REVIEW_ROLE, OTHER_ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id) values
            (?, 'owner', 'test', 'Owner', ?), (?, 'foreign', 'test', 'Foreign', ?),
            (?, 'admin', 'test', 'Admin', ?), (?, 'viewer', 'test', 'Viewer', ?),
            (?, 'reviewer', 'test', 'Reviewer', ?), (?, 'unknown', 'test', 'Unknown', ?)
            """, OWNER, ENTRY_ROLE, FOREIGN, ENTRY_ROLE, ADMIN, ADMIN_ROLE,
            VIEWER, VIEWER_ROLE, REVIEWER, REVIEW_ROLE, UNKNOWN, OTHER_ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'Test Company')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'Entry')", TYPE);
        jdbc.update("""
            insert into core.notes(
                id, company_id, note_type_id, note_number, arrival_at,
                status, created_by, is_deleted)
            values
                (?, ?, ?, '101', date '2026-11-20', 'DRAFT', ?, false),
                (?, ?, ?, '102', date '2026-11-20', 'DRAFT', ?, false),
                (?, ?, ?, '103', date '2026-11-20', 'DRAFT', ?, true)
            """, NOTE, COMPANY, TYPE, OWNER, SECOND_NOTE, COMPANY, TYPE,
            FOREIGN, DELETED_NOTE, COMPANY, TYPE, OWNER);
        addFile(ATTACHMENT, NOTE, "owned.pdf", pdf("owned"));
        addFile(SECOND_ATTACHMENT, SECOND_NOTE, "foreign.pdf", pdf("foreign"));
        addFile(DELETED_ATTACHMENT, DELETED_NOTE, "deleted.pdf", pdf("deleted"));
    }

    private static byte[] pdf(String content) {
        return ("%PDF-1.4\n" + content + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
    }

    private void addFile(UUID id, UUID note, String fileName, byte[] bytes) {
        jdbc.update("""
            insert into core.note_attachments(
                id, note_id, file_name, content_type, file_data, file_size, created_by)
            values (?, ?, ?, 'application/pdf', ?, ?, ?)
            """, id, note, fileName, bytes, (long)bytes.length, OWNER);
    }

    @Test void entryCanReadOwnFileButNotOtherEntryOrDeletedNote() {
        var own = reader.find(NOTE, ATTACHMENT, OWNER).orElseThrow();
        assertEquals("owned.pdf", own.fileName());
        assertArrayEquals(pdf("owned"), own.bytes());
        assertTrue(reader.find(SECOND_NOTE, SECOND_ATTACHMENT, OWNER).isEmpty());
        assertTrue(reader.find(DELETED_NOTE, DELETED_ATTACHMENT, OWNER).isEmpty());
    }

    @Test void downloadRequiresBothMatchingNoteIdAndAttachmentId() {
        assertTrue(reader.find(SECOND_NOTE, ATTACHMENT, ADMIN).isEmpty());
        assertTrue(reader.find(NOTE, SECOND_ATTACHMENT, ADMIN).isEmpty());
        assertTrue(reader.find(NOTE, UUID.randomUUID(), ADMIN).isEmpty());
        assertTrue(reader.find(UUID.randomUUID(), ATTACHMENT, ADMIN).isEmpty());
        assertTrue(reader.find(NOTE, ATTACHMENT, UUID.randomUUID()).isEmpty());
    }

    @Test void permittedRolesCanReadActiveNotesButUnsupportedRoleCannot() {
        for (UUID user : new UUID[]{ADMIN, VIEWER, REVIEWER}) {
            assertTrue(reader.find(NOTE, ATTACHMENT, user).isPresent());
            assertTrue(reader.find(SECOND_NOTE, SECOND_ATTACHMENT, user).isPresent());
        }
        assertTrue(reader.find(NOTE, ATTACHMENT, UNKNOWN).isEmpty());
        assertTrue(reader.find(DELETED_NOTE, DELETED_ATTACHMENT, ADMIN).isEmpty());
    }

    @Test void roleRevocationUserDeactivationAndSoftDeletionTakeEffectImmediately() {
        assertTrue(reader.find(NOTE, ATTACHMENT, OWNER).isPresent());
        jdbc.update("update auth.users set role_id = ? where id = ?", OTHER_ROLE, OWNER);
        assertTrue(reader.find(NOTE, ATTACHMENT, OWNER).isEmpty());
        jdbc.update("update auth.users set role_id = ?, is_active = false where id = ?",
            ENTRY_ROLE, OWNER);
        assertTrue(reader.find(NOTE, ATTACHMENT, OWNER).isEmpty());
        jdbc.update("update auth.users set is_active = true, is_deleted = true where id = ?", OWNER);
        assertTrue(reader.find(NOTE, ATTACHMENT, OWNER).isEmpty());
        jdbc.update("update auth.users set is_active = true, is_deleted = false where id = ?", OWNER);
        assertTrue(reader.find(NOTE, ATTACHMENT, OWNER).isPresent());
        jdbc.update("update core.notes set is_deleted = true where id = ?", NOTE);
        assertTrue(reader.find(NOTE, ATTACHMENT, OWNER).isEmpty());
    }

    @Test void inactiveRoleAndFileSizeBoundsFailClosed() {
        jdbc.update("update auth.roles set is_active = false where id = ?", VIEWER_ROLE);
        assertTrue(reader.find(NOTE, ATTACHMENT, VIEWER).isEmpty());
        addFile(EMPTY_ATTACHMENT, NOTE, "empty.pdf", new byte[0]);
        assertTrue(reader.find(NOTE, EMPTY_ATTACHMENT, ADMIN).isEmpty());
        // SQL must reject oversized legacy bytea even if data is present.
        addFile(OVERSIZE_ATTACHMENT, NOTE, "large.pdf", new byte[10 * 1024 * 1024 + 1]);
        assertTrue(reader.find(NOTE, OVERSIZE_ATTACHMENT, ADMIN).isEmpty());
    }

    @Test void returnedBytesAreImmutableCopies() {
        DownloadedNoteAttachment file = reader.find(NOTE, ATTACHMENT, OWNER).orElseThrow();
        byte[] changed = file.bytes();
        changed[0] = 0;
        assertArrayEquals(pdf("owned"), file.bytes());
        assertArrayEquals(pdf("owned"), reader.find(NOTE, ATTACHMENT, OWNER).orElseThrow().bytes());
    }
}
