package io.github.waleed875.memo.modules.masterdata.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.NoteDefinitionWriter;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldDefinition;
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
 * Real PostgreSQL verification, including transaction-proxied write/audit
 * rollback and typed JSONB before/after snapshots.
 */
@Testcontainers
class NoteDefinitionWritePostgresIT {
    @Container
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_note_definitions_write_it")
            .withUsername("memo_it")
            .withPassword("test_only_password");

    private static final UUID TYPE =
        UUID.fromString("f0a00000-0000-4000-8000-000000000001");
    private static final UUID ACTOR =
        UUID.fromString("f0a00000-0000-4000-8000-000000000002");
    private static final UUID ROLE =
        UUID.fromString("f0a00000-0000-4000-8000-000000000003");

    private static JdbcTemplate jdbc;
    private static NoteDefinitionWriter writer;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
        new ResourceDatabasePopulator(new ClassPathResource("postgres-masterdata-schema.sql"))
            .execute(ds);
        jdbc = new JdbcTemplate(ds);
        var factory = new TransactionProxyFactoryBean();
        factory.setTarget(new JdbcNoteDefinitionWriter(jdbc));
        factory.setTransactionManager(new JdbcTransactionManager(ds));
        factory.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.setProxyInterfaces(new Class<?>[]{NoteDefinitionWriter.class});
        factory.afterPropertiesSet();
        writer = (NoteDefinitionWriter) factory.getObject();

        jdbc.update("insert into auth.roles(id, code, name_ar) values (?, 'ADMIN', 'مدير')", ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'admin', 'test-only', 'مدير', ?)
            """, ACTOR, ROLE);
        jdbc.update("""
            insert into core.note_types(id, code, name_ar) values (?, 'TYPE-A', 'نوع')
            """, TYPE);
    }

    @BeforeEach
    void reset() {
        jdbc.update("delete from audit.logs");
        jdbc.update("delete from core.note_field_values");
        jdbc.update("delete from core.note_type_fields");
        jdbc.update("delete from core.note_fields");
    }

    private NoteTypeFieldDefinition definition(UUID id, UUID fieldId, String code,
                                               String name, boolean active) {
        return new NoteTypeFieldDefinition(
            id, TYPE, fieldId, code, name, "وصف", 7, true, active
        );
    }

    @Test
    void createsDefinitionAndStructuredJsonbAuditInOneTransaction() {
        var saved = writer.save(definition(null, null, "F1", "حقل ١", true), ACTOR);
        assertNotNull(saved.id());
        assertNotNull(saved.fieldId());
        assertEquals("F1", saved.fieldCode());
        assertEquals(1L, jdbc.queryForObject(
            "select count(*) from core.note_type_fields where id = ?", Long.class, saved.id()));
        assertEquals("F1", jdbc.queryForObject(
            "select code from core.note_fields where id = ?", String.class, saved.fieldId()));
        assertEquals("CREATE_NOTE_TYPE_FIELD", jdbc.queryForObject(
            "select action_type from audit.logs where record_id = ?",
            String.class, saved.id()));
        assertEquals("F1", jdbc.queryForObject("""
            select new_value ->> 'fieldCode' from audit.logs where record_id = ?
            """, String.class, saved.id()));
        assertEquals("null", jdbc.queryForObject("""
            select old_value::text from audit.logs where record_id = ?
            """, String.class, saved.id()));
    }

    @Test
    void reusesCaseInsensitiveCodeAndUpsertsInsteadOfDuplicatingDefinitions() {
        var first = writer.save(definition(null, null, "Abc", "الأول", true), ACTOR);
        var reused = writer.save(definition(null, null, "abc", "الثاني", false), ACTOR);
        assertEquals(first.id(), reused.id());
        assertEquals(first.fieldId(), reused.fieldId());
        assertEquals(1L, jdbc.queryForObject(
            "select count(*) from core.note_type_fields", Long.class));
        assertEquals(1L, jdbc.queryForObject(
            "select count(*) from core.note_fields", Long.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("""
            select is_active from core.note_type_fields where id = ?
            """, Boolean.class, first.id()));
    }

    @Test
    void updatingDefinitionAuditsBeforeAndAfterAndRejectsChangingAttachedField() {
        var first = writer.save(definition(null, null, "F1", "حقل أول", true), ACTOR);
        var updated = writer.save(definition(first.id(), first.fieldId(), "F1", "حقل معدل", false), ACTOR);
        assertEquals(first.id(), updated.id());
        assertEquals("حقل أول", jdbc.queryForObject("""
            select old_value ->> 'fieldNameAr'
            from audit.logs where record_id = ? and action_type = 'UPDATE_NOTE_TYPE_FIELD'
            """, String.class, first.id()));
        assertEquals("حقل معدل", jdbc.queryForObject("""
            select new_value ->> 'fieldNameAr'
            from audit.logs where record_id = ? and action_type = 'UPDATE_NOTE_TYPE_FIELD'
            """, String.class, first.id()));
        assertThrows(MasterDataOperationException.class,
            () -> writer.save(definition(first.id(), UUID.randomUUID(), "F1", "غير صالح", true), ACTOR));
    }

    @Test
    void deactivateKeepsHistoricalLinkAndAuditsSnapshots() {
        var first = writer.save(definition(null, null, "F1", "مؤشر", true), ACTOR);
        writer.deactivate(first.id(), ACTOR);
        assertEquals(Boolean.FALSE, jdbc.queryForObject("""
            select is_active from core.note_type_fields where id = ?
            """, Boolean.class, first.id()));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("""
            select is_deleted from core.note_type_fields where id = ?
            """, Boolean.class, first.id()));
        assertEquals("true", jdbc.queryForObject("""
            select old_value ->> 'isActive' from audit.logs
            where record_id = ? and action_type = 'DEACTIVATE_NOTE_TYPE_FIELD'
            """, String.class, first.id()));
        assertEquals("false", jdbc.queryForObject("""
            select new_value ->> 'isActive' from audit.logs
            where record_id = ? and action_type = 'DEACTIVATE_NOTE_TYPE_FIELD'
            """, String.class, first.id()));
        assertEquals(1L, jdbc.queryForObject("""
            select count(*) from core.note_type_fields where id = ?
            """, Long.class, first.id()));
    }

    @Test
    void failedAuditRollsBackFieldAndDefinitionInsert() {
        UUID invalidActor = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class,
            () -> writer.save(definition(null, null, "ROLLBACK", "لا تدقيق", true), invalidActor));
        assertEquals(0L, jdbc.queryForObject(
            "select count(*) from core.note_fields where code = 'ROLLBACK'", Long.class));
        assertEquals(0L, jdbc.queryForObject(
            "select count(*) from core.note_type_fields", Long.class));
        assertEquals(0L, jdbc.queryForObject(
            "select count(*) from audit.logs", Long.class));
    }
}
