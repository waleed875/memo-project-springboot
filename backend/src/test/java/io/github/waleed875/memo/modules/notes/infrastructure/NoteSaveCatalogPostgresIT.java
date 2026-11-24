package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Read-only preflight fixture: no actual note save or schema migration. */
@Testcontainers
class NoteSaveCatalogPostgresIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-bookworm")
        .withDatabaseName("memo_save_preflight_it")
        .withUsername("memo_it")
        .withPassword("test_only_password");

    static final UUID COMPANY = UUID.fromString("f1000000-0000-4000-8000-000000000001");
    static final UUID TYPE = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    static final UUID REQUIRED = UUID.fromString("f1000000-0000-4000-8000-000000000003");
    static final UUID DISABLED = UUID.fromString("f1000000-0000-4000-8000-000000000004");
    static final UUID NOTE = UUID.fromString("f1000000-0000-4000-8000-000000000005");
    static final UUID DELETED = UUID.fromString("f1000000-0000-4000-8000-000000000006");
    static final UUID USER = UUID.fromString("f1000000-0000-4000-8000-000000000007");
    static final UUID ROLE = UUID.fromString("f1000000-0000-4000-8000-000000000008");
    static final LocalDateTime STAMP = LocalDateTime.of(2026, 11, 24, 10, 30, 0);

    static JdbcNoteSaveCatalog reader;

    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql")
        ).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into auth.roles(id, code, name_ar) values (?, 'DATA_ENTRY', 'إدخال')", ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'owner', 'test-only', 'مدخل', ?)
            """, USER, ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'شركة')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'إدخال')", TYPE);
        jdbc.update("""
            insert into core.note_fields(id, code, name_ar, is_active, is_deleted)
            values (?, 'F1', 'إلزامي', true, false), (?, 'F2', 'معطل', false, false)
            """, REQUIRED, DISABLED);
        jdbc.update("""
            insert into core.note_type_fields(
                id, note_type_id, field_id, sort_order, is_required, is_active)
            values (?, ?, ?, 1, true, true), (?, ?, ?, 2, true, true)
            """, UUID.randomUUID(), TYPE, REQUIRED, UUID.randomUUID(), TYPE, DISABLED);
        jdbc.update("""
            insert into core.notes(id, company_id, note_type_id,
                note_number, status, created_by, updated_at, is_deleted)
            values (?, ?, ?, '123', 'RETURNED_FOR_CORRECTION', ?, ?, false),
                   (?, ?, ?, '124', 'DRAFT', ?, ?, true)
            """, NOTE, COMPANY, TYPE, USER, STAMP,
            DELETED, COMPANY, TYPE, USER, STAMP);
        reader = new JdbcNoteSaveCatalog(jdbc);
    }

    @Test void loadsActiveRequiredAndInactiveHistoricalDefinitionsSeparately() {
        var definitions = reader.activeFields(TYPE);
        assertEquals(2, definitions.size());
        assertEquals(REQUIRED, definitions.get(0).fieldId());
        assertTrue(definitions.get(0).isRequired());
        assertTrue(definitions.get(0).isActive());
        assertEquals(DISABLED, definitions.get(1).fieldId());
        assertFalse(definitions.get(1).isActive());
    }

    @Test void returnsOwnedVersionedStatusAndExcludesDeletedNotes() {
        var note = reader.existing(NOTE).orElseThrow();
        assertEquals(USER, note.createdBy());
        assertEquals(NoteStatus.RETURNED_FOR_CORRECTION, note.status());
        assertEquals(STAMP, note.updatedAt());
        assertTrue(reader.existing(DELETED).isEmpty());
        assertTrue(reader.existing(UUID.randomUUID()).isEmpty());
    }
}
