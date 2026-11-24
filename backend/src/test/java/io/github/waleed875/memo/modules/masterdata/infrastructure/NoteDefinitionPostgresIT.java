package io.github.waleed875.memo.modules.masterdata.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

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

/**
 * Real PostgreSQL 16 tests of the legacy query and impact-preview semantics.
 * A deliberately minimal schema is used: this is NOT migration 001-070 parity.
 */
@Testcontainers
class NoteDefinitionPostgresIT {
    @Container
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_note_definitions_it")
            .withUsername("memo_it")
            .withPassword("test_only_password");

    private static final UUID COMPANY =
        UUID.fromString("1d000000-0000-4000-8000-000000000001");
    private static final UUID TYPE =
        UUID.fromString("1d000000-0000-4000-8000-000000000002");
    private static final UUID HIDDEN_TYPE =
        UUID.fromString("1d000000-0000-4000-8000-000000000003");
    private static final UUID FIELD =
        UUID.fromString("1d000000-0000-4000-8000-000000000004");
    private static final UUID SECOND_FIELD =
        UUID.fromString("1d000000-0000-4000-8000-000000000005");
    private static final UUID DEFINITION =
        UUID.fromString("1d000000-0000-4000-8000-000000000006");
    private static final UUID SECOND_DEFINITION =
        UUID.fromString("1d000000-0000-4000-8000-000000000007");
    private static final UUID REVIEWED_NOTE =
        UUID.fromString("1d000000-0000-4000-8000-000000000008");
    private static final UUID UNREVIEWED_NOTE =
        UUID.fromString("1d000000-0000-4000-8000-000000000009");
    private static final UUID MISSING_NOTE =
        UUID.fromString("1d000000-0000-4000-8000-000000000010");
    private static final UUID DELETED_NOTE =
        UUID.fromString("1d000000-0000-4000-8000-000000000011");

    private static JdbcNoteDefinitionReader reader;

    @BeforeAll
    static void setup() {
        var datasource = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("postgres-masterdata-schema.sql"))
            .execute(datasource);
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        jdbc.update("insert into core.companies(id, name) values (?, 'شركة')", COMPANY);
        jdbc.update("""
            insert into core.note_types(id, code, name_ar, is_active, is_deleted)
            values (?, 'A', 'مراسلة', true, false), (?, 'Z', 'محذوف', true, true)
            """, TYPE, HIDDEN_TYPE);
        jdbc.update("""
            insert into core.note_fields(id, code, name_ar, is_active, is_deleted)
            values (?, 'F1', 'حقل أول', false, false),
                   (?, 'F2', 'حقل ثان', true, true)
            """, FIELD, SECOND_FIELD);
        jdbc.update("""
            insert into core.note_type_fields(
                id, note_type_id, field_id, sort_order, is_required, is_active, is_deleted)
            values (?, ?, ?, 9, true, true, false),
                   (?, ?, ?, 10, false, true, false)
            """, DEFINITION, TYPE, FIELD, SECOND_DEFINITION, TYPE, SECOND_FIELD);
        jdbc.update("""
            insert into core.notes(id, company_id, note_type_id, is_deleted)
            values (?, ?, ?, false), (?, ?, ?, false),
                   (?, ?, ?, false), (?, ?, ?, true)
            """, REVIEWED_NOTE, COMPANY, TYPE, UNREVIEWED_NOTE, COMPANY, TYPE,
            MISSING_NOTE, COMPANY, TYPE, DELETED_NOTE, COMPANY, TYPE);
        jdbc.update("""
            insert into core.note_field_values(id, note_id, field_id, field_value)
            values (?, ?, ?, 1), (?, ?, ?, 0), (?, ?, ?, 0)
            """, UUID.randomUUID(), REVIEWED_NOTE, FIELD,
            UUID.randomUUID(), UNREVIEWED_NOTE, FIELD,
            UUID.randomUUID(), DELETED_NOTE, FIELD);
        reader = new JdbcNoteDefinitionReader(jdbc);
    }

    @Test
    void typesExcludeSoftDeletedAndPreserveLegacyFieldNames() {
        var result = reader.noteTypes();
        assertEquals(1, result.size());
        assertEquals(TYPE, result.get(0).id());
        assertEquals("A", result.get(0).code());
        assertEquals("مراسلة", result.get(0).nameAr());
        assertTrue(result.get(0).isActive());
    }

    @Test
    void fieldsIncludeInactiveDefinitionAndExcludeSoftDeletedFields() {
        var fields = reader.fieldsForType(TYPE);
        assertEquals(1, fields.size());
        assertEquals(DEFINITION, fields.get(0).id());
        assertEquals(FIELD, fields.get(0).fieldId());
        assertEquals("F1", fields.get(0).fieldCode());
        assertEquals(9, fields.get(0).sortOrder());
        assertTrue(fields.get(0).isRequired());
        assertFalse(fields.get(0).isActive());
        assertTrue(reader.fieldsForType(UUID.randomUUID()).isEmpty());
    }

    @Test
    void previewCountsOnlyActiveNotesAndKeepsOriginalAffectedCountMeaning() {
        var result = reader.preview(TYPE, FIELD);
        assertEquals(2, result.affectedNoteCount());
        assertEquals(2, result.missingOrUnreviewedCount());
        assertEquals(2, result.existingValueCount());
        assertFalse(result.isNewField());
    }

    @Test
    void previewOfNewFieldReportsMissingForAllNonDeletedNotes() {
        var result = reader.preview(TYPE, null);
        assertEquals(3, result.affectedNoteCount());
        assertEquals(3, result.missingOrUnreviewedCount());
        assertEquals(0, result.existingValueCount());
        assertTrue(result.isNewField());
        var absentType = reader.preview(UUID.randomUUID(), null);
        assertEquals(0, absentType.affectedNoteCount());
        assertEquals(0, absentType.existingValueCount());
    }
}
