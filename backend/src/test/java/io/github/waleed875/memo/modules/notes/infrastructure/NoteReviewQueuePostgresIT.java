package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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

/** Only a PostgreSQL fixture: full legacy migrations are a separate release gate. */
@Testcontainers
class NoteReviewQueuePostgresIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-bookworm")
        .withDatabaseName("memo_review_queue").withUsername("memo_it").withPassword("test_only");

    static final UUID ROLE = UUID.fromString("bb000000-0000-4000-8000-000000000001");
    static final UUID REVIEWER = UUID.fromString("bb000000-0000-4000-8000-000000000002");
    static final UUID CREATOR = UUID.fromString("bb000000-0000-4000-8000-000000000003");
    static final UUID OTHER_REVIEWER = UUID.fromString("bb000000-0000-4000-8000-000000000004");
    static final UUID COMPANY = UUID.fromString("bb000000-0000-4000-8000-000000000005");
    static final UUID TYPE = UUID.fromString("bb000000-0000-4000-8000-000000000006");
    static final UUID QUEUED = UUID.fromString("bb000000-0000-4000-8000-000000000007");
    static final UUID CLAIMED = UUID.fromString("bb000000-0000-4000-8000-000000000008");
    static final UUID OWN = UUID.fromString("bb000000-0000-4000-8000-000000000009");
    static final UUID OTHER_CLAIMED = UUID.fromString("bb000000-0000-4000-8000-000000000010");
    static final UUID DRAFT = UUID.fromString("bb000000-0000-4000-8000-000000000011");
    static final UUID DELETED = UUID.fromString("bb000000-0000-4000-8000-000000000012");
    static final UUID COMPLETE = UUID.fromString("bb000000-0000-4000-8000-000000000013");
    static final UUID INVALID_CLAIMED = UUID.fromString("bb000000-0000-4000-8000-000000000014");

    private static JdbcNoteReviewQueueReader repository;

    @BeforeAll static void setup() {
        var dataSource = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into auth.roles(id, code, name_ar) values (?, 'REVIEWER', 'مدقق')", ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'reviewer', 'test', 'Reviewer', ?),
                   (?, 'creator', 'test', 'Creator', ?),
                   (?, 'other', 'test', 'Other', ?)
            """, REVIEWER, ROLE, CREATOR, ROLE, OTHER_REVIEWER, ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'Company')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'Entry')", TYPE);
        insert(jdbc, QUEUED, CREATOR, null, "NEEDS_REVIEW", false, "2026-11-11");
        insert(jdbc, CLAIMED, CREATOR, REVIEWER, "UNDER_REVIEW", false, "2026-11-21");
        insert(jdbc, OWN, REVIEWER, null, "HAS_ERRORS", false, "2026-11-01");
        insert(jdbc, OTHER_CLAIMED, CREATOR, OTHER_REVIEWER, "UNDER_REVIEW", false, "2026-11-02");
        insert(jdbc, DRAFT, CREATOR, null, "DRAFT", false, "2026-11-03");
        insert(jdbc, DELETED, CREATOR, null, "NEEDS_REVIEW", true, "2026-11-04");
        insert(jdbc, COMPLETE, CREATOR, null, "COMPLETE", false, "2026-11-09");
        insert(jdbc, INVALID_CLAIMED, CREATOR, OTHER_REVIEWER, "NEEDS_REVIEW", false, "2026-11-05");
        repository = new JdbcNoteReviewQueueReader(new NamedParameterJdbcTemplate(dataSource));
    }

    private static void insert(JdbcTemplate jdbc, UUID id, UUID creator, UUID reviewer,
                               String status, boolean deleted, String arrival) {
        jdbc.update("""
            insert into core.notes(id,company_id,note_type_id,note_number,arrival_at,status,
                                   created_by,reviewer_id,is_deleted)
            values (?, ?, ?, ?, ?::date, ?, ?, ?, ?)
            """, id, COMPANY, TYPE, id.toString(), arrival, status, creator, reviewer, deleted);
    }

    @Test void includesAvailableAndOwnClaimedFirstThenOldestAvailable() {
        var items = repository.findForReviewer(REVIEWER);
        assertEquals(List.of(CLAIMED, COMPLETE, QUEUED), items.stream().map(n -> n.noteId()).toList());
        assertEquals("UNDER_REVIEW", items.getFirst().status().name());
        assertEquals(REVIEWER, items.getFirst().reviewerId());
    }

    @Test void separateReviewersHaveIsolatedClaimedQueues() {
        var items = repository.findForReviewer(OTHER_REVIEWER);
        assertTrue(items.stream().anyMatch(n -> n.noteId().equals(OTHER_CLAIMED)));
        assertFalse(items.stream().anyMatch(n -> n.noteId().equals(CLAIMED)));
    }

    @Test void filtersSoftDeletedDraftOwnAndInconsistentAssignments() {
        var ids = repository.findForReviewer(REVIEWER).stream().map(n -> n.noteId()).toList();
        assertFalse(ids.contains(OWN));
        assertFalse(ids.contains(DELETED));
        assertFalse(ids.contains(DRAFT));
        assertFalse(ids.contains(OTHER_CLAIMED));
        assertFalse(ids.contains(INVALID_CLAIMED));
    }
}

