package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.application.NoteAccessDenied;
import io.github.waleed875.memo.modules.notes.application.NoteCreateConflict;
import io.github.waleed875.memo.modules.notes.application.NoteSourceStore;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceDraft;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
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

/** Migration-063 source core verification against real PostgreSQL 16. */
@Testcontainers
class NoteSourcePostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("note_source_it")
            .withUsername("memo_it").withPassword("test_only_password");

    static final UUID ROLE = UUID.fromString("b1500000-0000-4000-8000-000000000001");
    static final UUID OWNER = UUID.fromString("b1500000-0000-4000-8000-000000000002");
    static final UUID OTHER_USER = UUID.fromString("b1500000-0000-4000-8000-000000000003");
    static final UUID COMPANY = UUID.fromString("b1500000-0000-4000-8000-000000000004");
    static final UUID OTHER_COMPANY = UUID.fromString("b1500000-0000-4000-8000-000000000005");
    static final UUID ENTRY_TYPE = UUID.fromString("b1500000-0000-4000-8000-000000000006");
    static final UUID EXIT_TYPE = UUID.fromString("b1500000-0000-4000-8000-000000000007");
    static final UUID ENTRY = UUID.fromString("b1500000-0000-4000-8000-000000000008");
    static final UUID EXIT1 = UUID.fromString("b1500000-0000-4000-8000-000000000009");
    static final UUID EXIT2 = UUID.fromString("b1500000-0000-4000-8000-000000000010");
    static final UUID FOREIGN_EXIT = UUID.fromString("b1500000-0000-4000-8000-000000000011");

    static JdbcTemplate jdbc;
    static NoteSourceStore store;

    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql"),
            new ClassPathResource("postgres-notes-source-schema.sql")
        ).execute(ds);
        var triggers = new ResourceDatabasePopulator(
            new ClassPathResource("postgres-notes-source-trigger.sql"));
        triggers.setSeparator("@@");
        triggers.execute(ds);
        jdbc = new JdbcTemplate(ds);
        var factory = new TransactionProxyFactoryBean();
        factory.setTarget(new JdbcNoteSourceStore(jdbc, new NamedParameterJdbcTemplate(ds)));
        factory.setTransactionManager(new JdbcTransactionManager(ds));
        factory.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.setProxyInterfaces(new Class<?>[]{NoteSourceStore.class});
        factory.afterPropertiesSet();
        store = (NoteSourceStore) factory.getObject();
        jdbc.update("insert into auth.roles(id,code,name_ar) values (?,'ADMIN','مدير')", ROLE);
        jdbc.update("""
            insert into auth.users(id,username,password_hash,full_name,role_id)
            values (?,'owner','test-only','مالك',?),
                   (?,'other','test-only','آخر',?)
            """, OWNER,ROLE,OTHER_USER,ROLE);
        jdbc.update("insert into core.companies(id,name) values (?,'شركة'),(?,'شركة ثانية')",
            COMPANY,OTHER_COMPANY);
        jdbc.update("""
            insert into core.note_types(id,code,name_ar)
            values (?,'ENTRY','إدخال'), (?,'EXIT','إخراج')
            """, ENTRY_TYPE,EXIT_TYPE);
        jdbc.update("""
            insert into core.notes(
                id,company_id,note_type_id,note_number,created_by,is_deleted)
            values
                (?, ?, ?, '1', ?, false),
                (?, ?, ?, '2', ?, false),
                (?, ?, ?, '3', ?, false),
                (?, ?, ?, '4', ?, false)
            """, ENTRY,COMPANY,ENTRY_TYPE,OWNER,
                EXIT1,COMPANY,EXIT_TYPE,OWNER,
                EXIT2,COMPANY,EXIT_TYPE,OWNER,
                FOREIGN_EXIT,OTHER_COMPANY,EXIT_TYPE,OTHER_USER);
    }

    @BeforeEach void clear() {
        jdbc.update("delete from audit.logs");
        jdbc.update("delete from core.note_source_exit_refs");
        jdbc.update("delete from core.note_source_invoices");
        jdbc.update("delete from core.note_source_loot");
        jdbc.update("delete from core.note_sources");
        jdbc.update("update core.notes set is_loot = false");
    }

    static NoteSourceDraft exits() {
        return new NoteSourceDraft(NoteSourceType.Exit,null,null,List.of(
            new NoteSourceDraft.ExitReference(EXIT1,1),
            new NoteSourceDraft.ExitReference(EXIT2,2)),List.of());
    }
    static NoteSourceDraft loot() {
        return new NoteSourceDraft(NoteSourceType.Loot,null,"غنائم",List.of(),List.of());
    }
    static NoteSourceDraft invoice() {
        return new NoteSourceDraft(NoteSourceType.Invoice,null,null,List.of(),
            List.of(new NoteSourceDraft.Invoice("INV-7",
                LocalDate.of(2026,11,24),"جهة",
                new NoteAttachment("proof.pdf", "%PDF-1.4\n%%EOF\n"
                    .getBytes(StandardCharsets.US_ASCII)))));
    }
    long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table,Long.class);
    }

    @Test void storesTwoExitReferencesAndReadsMetadataInOrder() {
        store.save(ENTRY,OWNER,true,exits());
        var result = store.read(ENTRY,OWNER,true).orElseThrow();
        assertEquals(NoteSourceType.Exit,result.sourceType());
        assertEquals(2,result.exitReferences().size());
        assertEquals(EXIT1,result.exitReferences().get(0).exitNoteId());
        assertEquals(EXIT2,result.exitReferences().get(1).exitNoteId());
        assertEquals("2",result.exitReferences().get(0).noteNumber());
        assertEquals(1L,count("audit.logs"));
        assertEquals(2L,count("core.note_source_exit_refs"));
        assertEquals("EXIT",jdbc.queryForObject("""
            select new_value->>'sourceType' from audit.logs
            where action_type='UPDATE_NOTE_SOURCE'
            """,String.class));
    }

    @Test void transitionsExitToLootToInvoiceThenOtherWithAuditedSnapshots() {
        store.save(ENTRY,OWNER,true,exits());
        store.save(ENTRY,OWNER,true,loot());
        assertEquals(0L,count("core.note_source_exit_refs"));
        assertEquals(1L,count("core.note_source_loot"));
        assertEquals(Boolean.TRUE,jdbc.queryForObject(
            "select is_loot from core.notes where id=?",Boolean.class,ENTRY));
        assertEquals(NoteSourceType.Loot,store.read(ENTRY,OWNER,true).orElseThrow().sourceType());
        assertEquals("EXIT",jdbc.queryForObject("""
            select old_value->>'sourceType' from audit.logs
            where action_type='UPDATE_NOTE_SOURCE'
            order by id desc limit 1
            """,String.class));

        store.save(ENTRY,OWNER,true,invoice());
        var source = store.read(ENTRY,OWNER,true).orElseThrow();
        assertEquals(NoteSourceType.Invoice,source.sourceType());
        assertEquals(1,source.invoices().size());
        assertEquals("INV-7",source.invoices().get(0).invoiceNumber());
        assertTrue(source.invoices().get(0).hasAttachment());
        assertEquals(Boolean.FALSE,jdbc.queryForObject(
            "select is_loot from core.notes where id=?",Boolean.class,ENTRY));
        assertEquals(1L,count("core.note_source_invoices"));
        assertEquals("application/pdf",jdbc.queryForObject(
            "select attachment_content_type from core.note_source_invoices",
            String.class));

        store.save(ENTRY,OWNER,true,
            new NoteSourceDraft(NoteSourceType.Other,"مصدر آخر",null,List.of(),List.of()));
        assertEquals(0L,count("core.note_source_invoices"));
        assertEquals("مصدر آخر",store.read(ENTRY,OWNER,true).orElseThrow().otherDescription());
        assertEquals(4L,count("audit.logs"));
    }

    @Test void deniesForeignOwnershipAndInvalidCompanyBeforeMutation() {
        assertThrows(NoteAccessDenied.class,
            () -> store.read(ENTRY,OTHER_USER,true));
        assertThrows(NoteAccessDenied.class,
            () -> store.save(ENTRY,OTHER_USER,true,loot()));
        assertThrows(NoteCreateConflict.class,
            () -> store.save(ENTRY,OWNER,true,new NoteSourceDraft(
                NoteSourceType.Exit,null,null,
                List.of(new NoteSourceDraft.ExitReference(FOREIGN_EXIT,1)),List.of())));
        assertThrows(NoteCreateConflict.class,
            () -> store.save(EXIT1,OWNER,true,loot()));
        assertEquals(0L,count("core.note_sources"));
        assertEquals(0L,count("audit.logs"));
    }

    @Test void rollbackSourceAndLootFlagIfAuditInsertFails() {
        // Set action_type limit to force the last audit insert to fail.
        jdbc.execute("""
            alter table audit.logs add constraint reject_source_audit_test
            check(action_type <> 'UPDATE_NOTE_SOURCE')
            """);
        try {
            assertThrows(DataIntegrityViolationException.class,
                () -> store.save(ENTRY,OWNER,true,loot()));
            assertEquals(0L,count("core.note_sources"));
            assertEquals(0L,count("core.note_source_loot"));
            assertEquals(Boolean.FALSE,jdbc.queryForObject(
                "select is_loot from core.notes where id=?",Boolean.class,ENTRY));
        } finally {
            jdbc.execute("alter table audit.logs drop constraint reject_source_audit_test");
        }
    }

    @Test void removalDeletesPayloadAndAuditsOldSnapshot() {
        store.save(ENTRY,OWNER,true,invoice());
        store.remove(ENTRY,OWNER,true);
        assertEquals(0L,count("core.note_sources"));
        assertEquals(0L,count("core.note_source_invoices"));
        assertEquals(2L,count("audit.logs"));
        assertEquals("INVOICE",jdbc.queryForObject("""
            select old_value->>'sourceType' from audit.logs
            where action_type='REMOVE_NOTE_SOURCE'
            """,String.class));
        assertThrows(NoteCreateConflict.class,()->store.remove(ENTRY,OWNER,true));
    }
}
