package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.application.NoteDeleteConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDeleteRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Integration tests on real PostgreSQL 16: optimistic version, note links,
 * soft delete and audit all belong to a single Spring-managed transaction.
 */
@Testcontainers
class NoteDeletePostgresIT {
    @Container
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_notes_delete_it")
            .withUsername("memo_it")
            .withPassword("test_only_password");

    static final UUID ROLE = UUID.fromString("d0300000-0000-4000-8000-000000000001");
    static final UUID ADMIN = UUID.fromString("d0300000-0000-4000-8000-000000000002");
    static final UUID COMPANY = UUID.fromString("d0300000-0000-4000-8000-000000000003");
    static final UUID TYPE = UUID.fromString("d0300000-0000-4000-8000-000000000004");
    static final UUID NOTE = UUID.fromString("d0300000-0000-4000-8000-000000000005");
    static final UUID RELATED = UUID.fromString("d0300000-0000-4000-8000-000000000006");
    static final LocalDateTime STAMP = LocalDateTime.of(2026, 11, 24, 11, 0);

    private static JdbcTemplate jdbc;
    private static NoteDeleteRepository repository;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql"),
            new ClassPathResource("postgres-notes-delete-schema.sql")
        ).execute(ds);
        jdbc = new JdbcTemplate(ds);
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcNoteDeleteRepository(jdbc));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{NoteDeleteRepository.class});
        proxy.afterPropertiesSet();
        repository = (NoteDeleteRepository) proxy.getObject();

        jdbc.update("insert into auth.roles(id, code, name_ar) values (?, 'ADMIN', 'مدير')", ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'admin', 'test', 'مدير النظام', ?)
            """, ADMIN, ROLE);
        jdbc.update("insert into core.companies(id, name) values (?, 'شركة')", COMPANY);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'ENTRY', 'مذكرة')", TYPE);
    }

    @BeforeEach
    void reset() {
        jdbc.update("delete from audit.logs");
        jdbc.update("delete from core.note_links");
        jdbc.update("delete from core.notes");
        jdbc.update("""
            insert into core.notes(
                id, company_id, note_type_id, note_number, status,
                created_by, updated_by, updated_at, is_deleted)
            values (?, ?, ?, 'A-2026', 'DRAFT', ?, ?, ?, false),
                   (?, ?, ?, 'B-2026', 'DRAFT', ?, ?, ?, false)
            """, NOTE, COMPANY, TYPE, ADMIN, ADMIN, STAMP,
            RELATED, COMPANY, TYPE, ADMIN, ADMIN, STAMP);
        jdbc.update("insert into core.note_links(source_note_id, related_note_id) values (?, ?)",
            NOTE, RELATED);
    }

    private long links() {
        return jdbc.queryForObject("select count(*) from core.note_links", Long.class);
    }
    private long audits() {
        return jdbc.queryForObject("select count(*) from audit.logs", Long.class);
    }

    @Test
    void validDeleteSoftDeletesAndUnlinksWithStructuredAudit() {
        repository.delete(NOTE, ADMIN, STAMP);
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
            "select is_deleted from core.notes where id = ?", Boolean.class, NOTE));
        assertEquals(ADMIN, jdbc.queryForObject(
            "select deleted_by from core.notes where id = ?", UUID.class, NOTE));
        assertNotNull(jdbc.queryForObject(
            "select deleted_at from core.notes where id = ?", LocalDateTime.class, NOTE));
        assertEquals(0L, links());
        assertEquals(2L, audits());
        assertEquals(1L, jdbc.queryForObject("""
            select count(*) from audit.logs where record_id = ? and action_type = 'DELETE_LINK'
            """, Long.class, NOTE));
        assertEquals("A-2026", jdbc.queryForObject("""
            select new_value->>'noteNumber' from audit.logs
            where record_id = ? and action_type = 'DELETE_NOTE'
            """, String.class, NOTE));
        assertEquals(ADMIN.toString(), jdbc.queryForObject("""
            select new_value->>'deletedByUserId' from audit.logs
            where record_id = ? and action_type = 'DELETE_NOTE'
            """, String.class, NOTE));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "select is_deleted from core.notes where id = ?", Boolean.class, RELATED));
    }

    @Test
    void staleVersionMustNotUnlinkOrDeleteOrWriteAudit() {
        assertThrows(NoteDeleteConflict.class,
            () -> repository.delete(NOTE, ADMIN, STAMP.minusMinutes(1)));
        assertEquals(1L, links());
        assertEquals(0L, audits());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "select is_deleted from core.notes where id = ?", Boolean.class, NOTE));
    }

    @Test
    void missingOrAlreadyDeletedNoteMustNotModifyLinks() {
        assertThrows(NoteDeleteConflict.class,
            () -> repository.delete(UUID.randomUUID(), ADMIN, STAMP));
        repository.delete(NOTE, ADMIN, STAMP);
        long before = audits();
        assertThrows(NoteDeleteConflict.class, () -> repository.delete(NOTE, ADMIN, STAMP));
        assertEquals(before, audits());
    }

    @Test
    void auditFailureRollsBackDeleteAndUnlink() {
        // Referential integrity prevents audit for a nonexistent actor, so
        // DELETE_LINK insert fails after the attempted link removal.
        assertThrows(DataIntegrityViolationException.class,
            () -> repository.delete(NOTE, UUID.randomUUID(), STAMP));
        assertEquals(1L, links());
        assertEquals(0L, audits());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "select is_deleted from core.notes where id = ?", Boolean.class, NOTE));
    }

    @Test
    void finalAuditFailureAlsoRollsBackDelete() {
        jdbc.update("delete from core.note_links");
        assertThrows(DataIntegrityViolationException.class,
            () -> repository.delete(NOTE, UUID.randomUUID(), STAMP));
        assertEquals(0L, audits());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "select is_deleted from core.notes where id = ?", Boolean.class, NOTE));
    }
}
