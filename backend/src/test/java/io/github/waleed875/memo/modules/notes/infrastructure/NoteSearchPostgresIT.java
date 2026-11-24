package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.domain.NoteSearchCriteria;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Test actual C# reporting view projection/filter semantics on PostgreSQL 16.
 * The isolated fixture is a subset of legacy migrations, not a production DDL.
 */
@Testcontainers
class NoteSearchPostgresIT {
    @Container
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_notes_search_it")
            .withUsername("memo_it")
            .withPassword("test_only_password");

    static final UUID ROLE = UUID.fromString("ac000000-0000-4000-8000-000000000001");
    static final UUID USER_A = UUID.fromString("ac000000-0000-4000-8000-000000000002");
    static final UUID USER_B = UUID.fromString("ac000000-0000-4000-8000-000000000003");
    static final UUID COMPANY = UUID.fromString("ac000000-0000-4000-8000-000000000004");
    static final UUID TYPE_ENTRY = UUID.fromString("ac000000-0000-4000-8000-000000000005");
    static final UUID TYPE_EXIT = UUID.fromString("ac000000-0000-4000-8000-000000000006");
    static final UUID FIELD = UUID.fromString("ac000000-0000-4000-8000-000000000007");
    static final UUID ENTRY_UNLINKED = UUID.fromString("ac000000-0000-4000-8000-000000000008");
    static final UUID ENTRY_LOOT = UUID.fromString("ac000000-0000-4000-8000-000000000009");
    static final UUID EXIT_LINKED = UUID.fromString("ac000000-0000-4000-8000-000000000010");
    static final UUID ENTRY_LINKED = UUID.fromString("ac000000-0000-4000-8000-000000000011");
    static final UUID DELETED = UUID.fromString("ac000000-0000-4000-8000-000000000012");


    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql"),
            new ClassPathResource("postgres-notes-search-view.sql")
        ).execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        jdbc.update("insert into auth.roles(id, code, name_ar) values (?, 'ADMIN', 'مدير')", ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'usera', 'test', 'الأول', ?), (?, 'userb', 'test', 'الثاني', ?)
            """, USER_A, ROLE, USER_B, ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'الشركة')", COMPANY);
        jdbc.update("""
            insert into core.note_types(id, code, name_ar)
            values (?, 'ENTRY', 'إدخال'), (?, 'EXIT', 'إخراج')
            """, TYPE_ENTRY, TYPE_EXIT);
        jdbc.update("insert into core.note_fields(id, code, name_ar) values (?, 'F1', 'حقل')", FIELD);
        jdbc.update("""
            insert into core.note_type_fields(
                id, note_type_id, field_id, sort_order, is_required)
            values (?, ?, ?, 1, true), (?, ?, ?, 1, true)
            """, UUID.randomUUID(), TYPE_ENTRY, FIELD, UUID.randomUUID(), TYPE_EXIT, FIELD);
        jdbc.update("""
            insert into core.notes(
                id, company_id, note_type_id, note_number, writing_date, arrival_at,
                status, created_by, is_loot, is_invoice, is_deleted)
            values
                (?, ?, ?, '2026-A', date '2026-11-10', date '2026-11-15', 'DRAFT', ?, false, false, false),
                (?, ?, ?, '2026-B', null, date '2026-11-16', 'NEEDS_REVIEW', ?, false, false, false),
                (?, ?, ?, '2026-C', date '2026-11-14', date '2026-11-17', 'APPROVED', ?, false, true, false),
                (?, ?, ?, '2026-D', date '2026-11-12', date '2026-11-18', 'DRAFT', ?, false, false, false),
                (?, ?, ?, '2026-Z', date '2026-11-13', date '2026-11-19', 'DRAFT', ?, false, false, true)
            """,
            ENTRY_UNLINKED, COMPANY, TYPE_ENTRY, USER_A,
            ENTRY_LOOT, COMPANY, TYPE_ENTRY, USER_B,
            EXIT_LINKED, COMPANY, TYPE_EXIT, USER_B,
            ENTRY_LINKED, COMPANY, TYPE_ENTRY, USER_A,
            DELETED, COMPANY, TYPE_ENTRY, USER_A);
        jdbc.update("""
            insert into core.note_field_values(id, note_id, field_id, field_value)
            values (?, ?, ?, 1), (?, ?, ?, 2), (?, ?, ?, 0), (?, ?, ?, 1)
            """, UUID.randomUUID(), ENTRY_UNLINKED, FIELD,
            UUID.randomUUID(), ENTRY_LOOT, FIELD,
            UUID.randomUUID(), EXIT_LINKED, FIELD,
            UUID.randomUUID(), ENTRY_LINKED, FIELD);
        jdbc.update("insert into core.note_sources(note_id, source_type) values (?, 'LOOT')", ENTRY_LOOT);
        jdbc.update("insert into core.note_links(source_note_id, related_note_id) values (?, ?)",
            ENTRY_LINKED, EXIT_LINKED);

        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcNoteSearchReader(new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{io.github.waleed875.memo.modules.notes.application.NoteSearchReader.class});
        proxy.afterPropertiesSet();
        // TransactionProxyFactoryBean exposes the NoteSearchReader port.
        repository = (io.github.waleed875.memo.modules.notes.application.NoteSearchReader) proxy.getObject();
    }

    private static io.github.waleed875.memo.modules.notes.application.NoteSearchReader repository;

    static NoteSearchCriteria criteria(
        UUID creator, NoteStatus status, Boolean unlinked, Boolean loot,
        Boolean invoice, String basis, Boolean hasWritingDate,
        LocalDate from, LocalDate to, int page, int size
    ) {
        return new NoteSearchCriteria(
            null, COMPANY, null, null, null, creator, status,
            from, to, basis, hasWritingDate, unlinked, loot, invoice,
            null, page, size);
    }

    @Test void excludesDeletedAndClampsPagePastEnd() {
        var page = repository.search(criteria(null, null, null, null,
            null, "ArrivalDate", null, LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 30), 100, 2));
        assertEquals(4, page.totalCount());
        assertEquals(2, page.totalPages());
        assertEquals(2, page.pageNumber());
        assertEquals(2, page.items().size());
        assertEquals(ENTRY_LOOT, page.items().get(0).noteId());
        assertEquals(ENTRY_UNLINKED, page.items().get(1).noteId());
        assertNull(page.items().get(0).imagePath());
    }

    @Test void creatorAndStatusFiltersAreEnforcedInSqlCountAndItems() {
        var page = repository.search(criteria(USER_A, NoteStatus.DRAFT, null,
            null, null, "ArrivalDate", null, LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 30), 1, 20));
        assertEquals(2, page.totalCount());
        assertTrue(page.items().stream().allMatch(n -> USER_A.equals(n.createdBy())));
        assertTrue(page.items().stream().allMatch(n -> n.status() == NoteStatus.DRAFT));
        assertEquals(1, page.items().get(0).activeCount());
    }

    @Test void sourceLootFilterUsesLegacyFlagOrSourceAndInvoiceIsSeparate() {
        var loot = repository.search(criteria(null, null, null, true,
            null, null, null, LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 30), 1, 50));
        assertEquals(1, loot.totalCount());
        assertEquals(ENTRY_LOOT, loot.items().get(0).noteId());
        // Search projection returns n.is_loot, not the source-derived effective value.
        assertFalse(loot.items().get(0).isLoot());
        var invoice = repository.search(criteria(null, null, null, null,
            true, null, null, LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 30), 1, 50));
        assertEquals(1, invoice.totalCount());
        assertEquals(EXIT_LINKED, invoice.items().get(0).noteId());
    }

    @Test void unlinkedEntryOnlyExcludesLootAndExistingNoteLinks() {
        var page = repository.search(criteria(null, null, true, null,
            null, null, null, LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 30), 1, 50));
        assertEquals(1, page.totalCount());
        assertEquals(ENTRY_UNLINKED, page.items().get(0).noteId());
    }

    @Test void writingDateBasisFallsBackOnlyIfHasWritingDateIsUnset() {
        var fallback = repository.search(criteria(null, null, null, null,
            null, "WritingDate", null, LocalDate.of(2026, 11, 16),
            LocalDate.of(2026, 11, 16), 1, 25));
        assertEquals(1, fallback.totalCount());
        assertEquals(ENTRY_LOOT, fallback.items().get(0).noteId());

        var required = repository.search(criteria(null, null, null, null,
            null, "WritingDate", true, LocalDate.of(2026, 11, 16),
            LocalDate.of(2026, 11, 16), 1, 25));
        assertEquals(0, required.totalCount());
        assertEquals(0, required.totalPages());
        assertTrue(required.items().isEmpty());
    }
}
