package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Golden-rule duplicate key checks on real PostgreSQL 16. */
@Testcontainers
class NoteDuplicatePostgresIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-bookworm")
        .withDatabaseName("memo_note_duplicates_it")
        .withUsername("memo_it")
        .withPassword("test_only_password");

    static final UUID TYPE = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    static final UUID COMPANY = UUID.fromString("e1000000-0000-4000-8000-000000000002");
    static final UUID OTHER_COMPANY = UUID.fromString("e1000000-0000-4000-8000-000000000003");
    static final UUID NOTE_2026 = UUID.fromString("e1000000-0000-4000-8000-000000000004");
    static final UUID NOTE_2025 = UUID.fromString("e1000000-0000-4000-8000-000000000005");
    static final UUID OLD_TEXT = UUID.fromString("e1000000-0000-4000-8000-000000000006");
    static final UUID DELETED = UUID.fromString("e1000000-0000-4000-8000-000000000007");
    static final UUID OTHER = UUID.fromString("e1000000-0000-4000-8000-000000000008");
    static final UUID INVOICE = UUID.fromString("e1000000-0000-4000-8000-000000000009");
    static final UUID ROLE = UUID.fromString("e1000000-0000-4000-8000-000000000010");
    static final UUID ACTOR = UUID.fromString("e1000000-0000-4000-8000-000000000011");

    private static JdbcNoteDuplicateReader reader;

    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql"),
            new ClassPathResource("postgres-notes-duplicate-schema.sql")
        ).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into auth.roles(id,code,name_ar) values (?, 'ADMIN', 'مدير')",ROLE);
        jdbc.update("""
            insert into auth.users(id,username,password_hash,full_name,role_id)
            values (?, 'actor','test-only','المستخدم',?)
            """,ACTOR,ROLE);
        jdbc.update("""
            insert into core.companies(id,name) values (?, 'شركة أولى'), (?, 'شركة أخرى')
            """,COMPANY,OTHER_COMPANY);
        jdbc.update("insert into core.note_types(id,code,name_ar) values (?, 'ENTRY','مذكرة')",TYPE);
        jdbc.update("""
            insert into core.notes(id,company_id,note_type_id,note_number,note_number_normalized,
                note_number_value,note_year,writing_date,arrival_at,created_by,is_deleted)
            values
              (?, ?, ?, '١٢٣', '١٢٣', 123, 2026, date '2026-11-15',date '2026-11-17',?,false),
              (?, ?, ?, '123', '123', 123, 2025, date '2025-11-15',date '2025-11-17',?,false),
              (?, ?, ?, 'AB/123', 'AB123', null, 2026, date '2026-11-15',date '2026-11-17',?,false),
              (?, ?, ?, '123', '123', 123, 2026, date '2026-11-15',date '2026-11-17',?,true),
              (?, ?, ?, '123', '123', 123, 2026, date '2026-11-15',date '2026-11-17',?,false),
              (?, ?, ?, '123', '123', 123, 2026, date '2026-11-20',date '2026-11-22',?,false)
            """,
            NOTE_2026, COMPANY,TYPE,ACTOR,
            NOTE_2025, COMPANY,TYPE,ACTOR,
            OLD_TEXT, COMPANY,TYPE,ACTOR,
            DELETED, COMPANY,TYPE,ACTOR,
            OTHER, OTHER_COMPANY,TYPE,ACTOR,
            INVOICE, COMPANY,TYPE,ACTOR);
        reader = new JdbcNoteDuplicateReader(new NamedParameterJdbcTemplate(ds));
    }

    @Test void numericDuplicateIncludesArabicAndLatinKeysInSameYear() {
        var matches = reader.find(null, COMPANY, TYPE,"123",123L,2026,
            LocalDate.of(2026,11,15),false);
        assertEquals(2, matches.size());
        assertTrue(matches.stream().anyMatch(x -> x.noteId().equals(NOTE_2026)));
        assertTrue(matches.stream().anyMatch(x -> x.noteId().equals(INVOICE)));
        assertFalse(matches.stream().anyMatch(x -> x.noteId().equals(NOTE_2025)));
        assertFalse(matches.stream().anyMatch(x -> x.noteId().equals(DELETED)));
        assertFalse(matches.stream().anyMatch(x -> x.noteId().equals(OTHER)));
    }

    @Test void invoiceRequiresExactWritingDateAndExcludeCurrentId() {
        var matches=reader.find(NOTE_2026,COMPANY,TYPE,"123",123L,2026,
            LocalDate.of(2026,11,15),true);
        assertEquals(0,matches.size());
        var invoice=reader.find(null,COMPANY,TYPE,"123",123L,2026,
            LocalDate.of(2026,11,20),true);
        assertEquals(1,invoice.size());
        assertEquals(INVOICE,invoice.get(0).noteId());
    }

    @Test void legacyAlphanumericMatchUsesNormalizedTextWhenNumericMissing() {
        var matches=reader.find(null,COMPANY,TYPE,"AB123",null,2026,
            LocalDate.of(2026,11,15),false);
        assertEquals(1,matches.size());
        assertEquals(OLD_TEXT,matches.get(0).noteId());
    }

    @Test void nullYearMatchesAcrossYearsAndMissingCompanyReturnsEmpty() {
        var matches=reader.find(null,COMPANY,TYPE,"123",123L,null,null,false);
        assertEquals(3,matches.size());
        assertTrue(reader.find(null,UUID.randomUUID(),TYPE,"123",123L,null,null,false).isEmpty());
    }
}
