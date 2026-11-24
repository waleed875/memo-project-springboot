package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.application.NoteDetailsReader;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
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

/** Real PostgreSQL 16 tests of full note retrieval and SQL owner predicate. */
@Testcontainers
class NoteDetailsPostgresIT {
    @Container
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_notes_read_it")
            .withUsername("memo_it")
            .withPassword("test_only_password");

    private static final UUID ROLE = UUID.fromString("a0b00000-0000-4000-8000-000000000001");
    private static final UUID OWNER = UUID.fromString("a0b00000-0000-4000-8000-000000000002");
    private static final UUID FOREIGN = UUID.fromString("a0b00000-0000-4000-8000-000000000003");
    private static final UUID COMPANY = UUID.fromString("a0b00000-0000-4000-8000-000000000004");
    private static final UUID TYPE = UUID.fromString("a0b00000-0000-4000-8000-000000000005");
    private static final UUID FIELD = UUID.fromString("a0b00000-0000-4000-8000-000000000006");
    private static final UUID NOTE = UUID.fromString("a0b00000-0000-4000-8000-000000000007");
    private static final UUID RELATED = UUID.fromString("a0b00000-0000-4000-8000-000000000008");
    private static final UUID DELETED = UUID.fromString("a0b00000-0000-4000-8000-000000000009");

    private static JdbcTemplate jdbc;
    private static NoteDetailsReader reader;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql")
        ).execute(ds);
        jdbc = new JdbcTemplate(ds);
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcNoteDetailsReader(jdbc));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{NoteDetailsReader.class});
        proxy.afterPropertiesSet();
        reader = (NoteDetailsReader) proxy.getObject();

        jdbc.update("insert into auth.roles(id, code, name_ar) values (?, 'ADMIN', 'مدير')", ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'owner', 'test-only', 'مُدخل', ?),
                   (?, 'foreign', 'test-only', 'مراجع', ?)
            """, OWNER, ROLE, FOREIGN, ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'شركة تجريبية')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'مذكرة إدخال')", TYPE);
        jdbc.update("insert into core.note_fields(id, code, name_ar, default_sort_order) values (?, 'F1', 'اختبار', 1)", FIELD);
        jdbc.update("""
            insert into core.notes(
                id, company_id, note_type_id, note_number, writing_date, arrival_at,
                attachment_file_name, attachment_content_type, attachment_data, notes,
                status, created_by, updated_by, is_loot, is_invoice, is_deleted)
            values (?, ?, ?, '23/2026', date '2026-11-15', date '2026-11-20',
                    'attachment.pdf', 'application/pdf', ?, 'تجربة',
                    'NEEDS_REVIEW', ?, ?, false, false, false),
                   (?, ?, ?, '24/2026', null, date '2026-11-21',
                    null, null, null, null, 'DRAFT', ?, ?, false, true, false),
                   (?, ?, ?, '25/2026', null, date '2026-11-22',
                    null, null, null, null, 'DRAFT', ?, ?, false, false, true)
            """, NOTE, COMPANY, TYPE, "original-pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            OWNER, OWNER, RELATED, COMPANY, TYPE, OWNER, OWNER,
            DELETED, COMPANY, TYPE, OWNER, OWNER);
        jdbc.update("""
            insert into core.note_field_values(id, note_id, field_id, field_value, inactive_reason)
            values (?, ?, ?, 2, 'غير مطابق')
            """, UUID.randomUUID(), NOTE, FIELD);
        jdbc.update("""
            insert into core.note_review_history(note_id, action, reason, user_id)
            values (?, 'START_REVIEW', 'فحص', ?)
            """, NOTE, FOREIGN);
        jdbc.update("insert into core.note_sources(note_id, source_type) values (?, 'LOOT')", NOTE);
        jdbc.update("insert into core.note_links(source_note_id, related_note_id) values (?, ?)", NOTE, RELATED);
    }

    @Test
    void ownerAndForeignOwnershipMetadataAreSeparatedFromFullData() {
        assertEquals(OWNER, reader.ownership(NOTE).orElseThrow().createdBy());
        assertTrue(reader.ownership(DELETED).isEmpty());
        assertTrue(reader.ownership(UUID.randomUUID()).isEmpty());
        assertTrue(reader.find(NOTE, FOREIGN, true).isEmpty());
        assertTrue(reader.find(NOTE, OWNER, true).isPresent());
    }

    @Test
    void legacyNoteDetailPreservesFieldsHistoryLinksAndAttachment() {
        var note = reader.find(NOTE, FOREIGN, false).orElseThrow();
        assertEquals("23/2026", note.noteNumber());
        assertEquals("مذكرة إدخال", note.noteTypeName());
        assertEquals(LocalDate.of(2026, 11, 15), note.writingDate());
        assertEquals(LocalDate.of(2026, 11, 20), note.arrivalAt());
        assertEquals(NoteStatus.NEEDS_REVIEW, note.status());
        assertEquals("attachment.pdf", note.attachmentFileName());
        assertArrayEquals("original-pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8), note.attachmentData());
        assertEquals(1, note.fieldValues().size());
        assertEquals(IndicatorValue.Incorrect, note.fieldValues().get(0).fieldValue());
        assertEquals("غير مطابق", note.fieldValues().get(0).inactiveReason());
        assertEquals(1, note.reviewHistory().size());
        assertEquals("START_REVIEW", note.reviewHistory().get(0).action());
        assertEquals("مراجع", note.reviewHistory().get(0).userName());
        assertEquals(1, note.relatedNoteIds().size());
        assertEquals(RELATED, note.relatedNoteIds().get(0));
        assertTrue(note.isLoot());
        assertFalse(note.isInvoice());
    }

    @Test
    void legacyDetailIncludesNullableDatesAndInvoiceFlagAndExcludesSoftDeleted() {
        var note = reader.find(RELATED, OWNER, true).orElseThrow();
        assertNull(note.writingDate());
        assertTrue(note.isInvoice());
        assertEquals(0, note.fieldValues().size());
        assertTrue(reader.find(DELETED, OWNER, false).isEmpty());
    }
}
