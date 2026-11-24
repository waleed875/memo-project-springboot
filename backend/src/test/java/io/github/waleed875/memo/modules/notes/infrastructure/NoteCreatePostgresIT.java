package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.application.NoteCreateCommand;
import io.github.waleed875.memo.modules.notes.application.NoteCreateConflict;
import io.github.waleed875.memo.modules.notes.application.NoteCreator;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Core create transaction acceptance tests. Only small self-contained notes
 * are supported; no public POST /api/notes is exposed by this implementation.
 */
@Testcontainers
class NoteCreatePostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_note_create_it")
            .withUsername("memo_it").withPassword("test_only_password");

    static final UUID ROLE = UUID.fromString("a1500000-0000-4000-8000-000000000001");
    static final UUID ACTOR = UUID.fromString("a1500000-0000-4000-8000-000000000002");
    static final UUID SECOND = UUID.fromString("a1500000-0000-4000-8000-000000000003");
    static final UUID COMPANY = UUID.fromString("a1500000-0000-4000-8000-000000000004");
    static final UUID TYPE = UUID.fromString("a1500000-0000-4000-8000-000000000005");
    static final UUID EXIT_TYPE = UUID.fromString("a1500000-0000-4000-8000-000000000008");
    static final UUID LINK_TARGET = UUID.fromString("a1500000-0000-4000-8000-000000000009");
    static final UUID FIELD = UUID.fromString("a1500000-0000-4000-8000-000000000006");
    static final UUID INACTIVE = UUID.fromString("a1500000-0000-4000-8000-000000000007");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    static JdbcTemplate jdbc;
    static NoteCreator creator;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql"),
            new ClassPathResource("postgres-notes-duplicate-schema.sql"),
            new ClassPathResource("postgres-notes-create-schema.sql"),
            new ClassPathResource("postgres-notes-create-attachments-links.sql")
        ).execute(ds);
        jdbc = new JdbcTemplate(ds);
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcNoteCreator(jdbc,
            new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{NoteCreator.class});
        proxy.afterPropertiesSet();
        creator = (NoteCreator) proxy.getObject();
        jdbc.update("insert into auth.roles(id,code,name_ar) values (?,'DATA_ENTRY','إدخال')", ROLE);
        jdbc.update("""
            insert into auth.users(id,username,password_hash,full_name,role_id)
            values (?,'first','test','الأول',?), (?,'second','test','الثاني',?)
            """, ACTOR,ROLE,SECOND,ROLE);
        jdbc.update("insert into core.companies(id,name) values (?,'شركة')", COMPANY);
        jdbc.update("""
            insert into core.note_types(id,code,name_ar)
            values (?,'ENTRY','إدخال'), (?,'EXIT','إخراج')
            """, TYPE, EXIT_TYPE);
        jdbc.update("""
            insert into core.note_fields(id,code,name_ar,is_active)
            values (?,'REQ','إلزامي',true), (?,'OLD','معطل',false)
            """, FIELD,INACTIVE);
        jdbc.update("""
            insert into core.note_type_fields(
                id,note_type_id,field_id,sort_order,is_required,is_active)
            values (?,?,?,1,true,true), (?,?,?,2,true,true)
            """, UUID.randomUUID(),TYPE,FIELD,UUID.randomUUID(),TYPE,INACTIVE);
        jdbc.execute("""
            alter table audit.logs add constraint reject_test_create_audit
            check(action_type <> 'CREATE_NOTE'
                or new_value->>'noteNumber' is distinct from '999')
            """);
    }

    @BeforeEach
    void reset() {
        jdbc.update("delete from core.note_create_operations");
        jdbc.update("delete from audit.logs");
        jdbc.update("delete from core.note_attachments");
        jdbc.update("delete from core.note_links");
        jdbc.update("delete from core.note_review_history");
        jdbc.update("delete from core.note_field_values");
        jdbc.update("delete from core.notes");
    }

    private void seedLinkTarget() {
        jdbc.update("""
            insert into core.notes(
                id, company_id, note_type_id, note_number,
                note_number_normalized, note_number_value,
                writing_date, arrival_at, created_by, updated_by)
            values (?, ?, ?, '90000', '90000', 90000,
                    date '2026-11-20', date '2026-11-24', ?, ?)
            """, LINK_TARGET, COMPANY, EXIT_TYPE, ACTOR, ACTOR);
    }

    private NoteCreateCommand command(String number, boolean draft) {
        return new NoteCreateCommand(UUID.randomUUID(), UUID.randomUUID(),
            new NoteSaveCandidate(number,COMPANY,TYPE,null,null,
                TODAY.minusDays(4),TODAY,draft,false,
                List.of(new NoteSaveCandidate.IndicatorInput(FIELD,IndicatorValue.Correct))),
            "ملاحظة");
    }

    private NoteCreateCommand draftCommand(String number) {
        return new NoteCreateCommand(UUID.randomUUID(), UUID.randomUUID(),
            new NoteSaveCandidate(number,COMPANY,TYPE,null,null,
                null,TODAY,true,false,List.of()),"مسودة");
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void createsFullAtomicNoteWithIndicatorsReviewAndAudit() {
        var c=command("١٢٣",false);
        UUID noteId=creator.create(c,ACTOR,TODAY);
        assertEquals(c.noteId(),noteId);
        assertEquals("COMPLETE",jdbc.queryForObject(
            "select status from core.notes where id = ?",String.class,noteId));
        assertEquals(123L,jdbc.queryForObject(
            "select note_number_value from core.notes where id = ?",Long.class,noteId));
        assertEquals(2026,jdbc.queryForObject(
            "select note_year from core.notes where id = ?",Integer.class,noteId));
        assertEquals("SUBMIT_FOR_REVIEW",jdbc.queryForObject(
            "select action from core.note_review_history where note_id = ?",String.class,noteId));
        assertEquals(1L,count("select count(*) from core.note_field_values"));
        assertEquals(2L,count("select count(*) from audit.logs"));
        assertEquals("١٢٣",jdbc.queryForObject("""
            select new_value->>'noteNumber' from audit.logs
            where action_type = 'CREATE_NOTE'
            """,String.class));
        assertEquals(1L,count("select count(*) from core.note_create_operations"));
    }

    @Test
    void aCompletedOperationReplaysWithoutNewAuditOrDuplicates() {
        var c=command("124",false);
        UUID first=creator.create(c,ACTOR,TODAY);
        assertEquals(first,creator.create(c,ACTOR,TODAY));
        assertEquals(1L,count("select count(*) from core.notes"));
        assertEquals(1L,count("select count(*) from core.note_create_operations"));
        assertEquals(2L,count("select count(*) from audit.logs"));
        assertThrows(NoteCreateConflict.class,()->creator.create(c,SECOND,TODAY));
        var changed=new NoteCreateCommand(c.operationId(),c.noteId(),c.candidate(),"تغيير");
        assertThrows(NoteCreateConflict.class,()->creator.create(changed,ACTOR,TODAY));
    }

    @Test
    void sameNoteIdCannotBeReusedWithNewOperation() {
        var c=command("125",false);
        creator.create(c,ACTOR,TODAY);
        var other=new NoteCreateCommand(UUID.randomUUID(),c.noteId(),c.candidate(),"ملاحظة");
        assertThrows(NoteCreateConflict.class,()->creator.create(other,ACTOR,TODAY));
        assertEquals(1L,count("select count(*) from core.notes"));
    }

    @Test
    void numericDuplicatesInArabicAndLatinAreRejectedAndReservationRollsBack() {
        creator.create(command("١٢٦",false),ACTOR,TODAY);
        assertThrows(NoteCreateConflict.class,
            ()->creator.create(command("126",false),ACTOR,TODAY));
        assertEquals(1L,count("select count(*) from core.notes"));
        assertEquals(1L,count("select count(*) from core.note_create_operations"));
        assertEquals(2L,count("select count(*) from audit.logs"));
    }

    @Test
    void validatesRequiredFieldsAndRollsBackReservation() {
        var invalid=new NoteCreateCommand(UUID.randomUUID(),UUID.randomUUID(),
            new NoteSaveCandidate("127",COMPANY,TYPE,null,null,
                null,TODAY,false,false,List.of()),"خطأ");
        assertThrows(NoteSaveValidationException.class,
            ()->creator.create(invalid,ACTOR,TODAY));
        assertEquals(0L,count("select count(*) from core.note_create_operations"));
        assertEquals(0L,count("select count(*) from core.notes"));
    }

    @Test
    void draftCanBeIncompleteButStillRecordsReviewHistory() {
        var c=draftCommand("128");
        UUID id=creator.create(c,ACTOR,TODAY);
        assertEquals("DRAFT",jdbc.queryForObject(
            "select status from core.notes where id=?",String.class,id));
        assertEquals("CREATE_DRAFT",jdbc.queryForObject(
            "select action from core.note_review_history where note_id=?",String.class,id));
        assertEquals(0L,count("select count(*) from core.note_field_values"));
    }

    @Test
    void failedAuditRollsBackNoteIndicatorsHistoryAndOperationKey() {
        var c=command("999",false);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            ()->creator.create(c,ACTOR,TODAY));
        assertEquals(0L,count("select count(*) from core.note_create_operations"));
        assertEquals(0L,count("select count(*) from core.notes"));
        assertEquals(0L,count("select count(*) from core.note_field_values"));
        assertEquals(0L,count("select count(*) from core.note_review_history"));
        assertEquals(0L,count("select count(*) from audit.logs"));
    }

    @Test
    void twoConcurrentIdenticalCreatesResolveToSingleNote() throws Exception {
        var c=command("129",false);
        var pool=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);
        var go=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{ready.countDown();go.await();return creator.create(c,ACTOR,TODAY);});
            var b=pool.submit(()->{ready.countDown();go.await();return creator.create(c,ACTOR,TODAY);});
            assertTrue(ready.await(5,TimeUnit.SECONDS));
            go.countDown();
            assertEquals(c.noteId(),a.get(15,TimeUnit.SECONDS));
            assertEquals(c.noteId(),b.get(15,TimeUnit.SECONDS));
            assertEquals(1L,count("select count(*) from core.notes"));
            assertEquals(1L,count("select count(*) from core.note_create_operations"));
            assertEquals(2L,count("select count(*) from audit.logs"));
        } finally {
            pool.shutdownNow();
        }
    }
    @Test
    void differentOperationsWithSameNumberCannotCreateDuplicateConcurrently() throws Exception {
        var first = command("١٣٠", false);
        var second = command("130", false);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    creator.create(first, ACTOR, TODAY);
                    return true;
                } catch (NoteCreateConflict duplicate) {
                    return false;
                }
            });
            var b = pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    creator.create(second, ACTOR, TODAY);
                    return true;
                } catch (NoteCreateConflict duplicate) {
                    return false;
                }
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            boolean wonA = a.get(15, TimeUnit.SECONDS);
            boolean wonB = b.get(15, TimeUnit.SECONDS);
            assertNotEquals(wonA, wonB);
            assertEquals(1L, count("select count(*) from core.notes"));
            assertEquals(1L, count("select count(*) from core.note_create_operations"));
            assertEquals(2L, count("select count(*) from audit.logs"));
        } finally {
            pool.shutdownNow();
        }
    }

    private byte[] samplePdf() {
        return "%PDF-1.4\\n1 0 obj\\nendobj\\n%%EOF\\n"
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    @Test
    void attachmentAndLinkSaveAtomicallyAndReplayWithoutReinserting() {
        seedLinkTarget();
        var original = command("141", false);
        var enhanced = new NoteCreateCommand(original.operationId(), original.noteId(),
            original.candidate(), original.notes(),
            new NoteAttachment("proof.pdf", samplePdf()), List.of(LINK_TARGET));
        creator.create(enhanced, ACTOR, TODAY);
        assertArrayEquals(samplePdf(), jdbc.queryForObject(
            "select attachment_data from core.notes where id = ?", byte[].class, enhanced.noteId()));
        assertEquals("application/pdf", jdbc.queryForObject(
            "select attachment_content_type from core.notes where id = ?",
            String.class, enhanced.noteId()));
        assertEquals(1L, count("select count(*) from core.note_attachments"));
        assertEquals(1L, count("select count(*) from core.note_links"));
        assertEquals(3L, count("select count(*) from audit.logs"));
        assertEquals(enhanced.noteId(), creator.create(enhanced, ACTOR, TODAY));
        assertEquals(1L, count("select count(*) from core.note_attachments"));
        assertEquals(1L, count("select count(*) from core.note_links"));
        assertEquals(3L, count("select count(*) from audit.logs"));
    }

    @Test
    void replayWithChangedAttachmentOrLinksMustConflict() {
        seedLinkTarget();
        var basic = command("142", false);
        var uploaded = new NoteCreateCommand(basic.operationId(), basic.noteId(),
            basic.candidate(), basic.notes(),
            new NoteAttachment("proof.pdf", samplePdf()), List.of(LINK_TARGET));
        creator.create(uploaded, ACTOR, TODAY);
        var differentBytes = new NoteCreateCommand(basic.operationId(), basic.noteId(),
            basic.candidate(), basic.notes(),
            new NoteAttachment("proof.pdf", "%PDF-1.4\\n%%EOF\\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
            List.of(LINK_TARGET));
        assertThrows(NoteCreateConflict.class,
            () -> creator.create(differentBytes, ACTOR, TODAY));
        var missingLink = new NoteCreateCommand(basic.operationId(), basic.noteId(),
            basic.candidate(), basic.notes(),
            new NoteAttachment("proof.pdf", samplePdf()), List.of());
        assertThrows(NoteCreateConflict.class,
            () -> creator.create(missingLink, ACTOR, TODAY));
    }

    @Test
    void invalidAttachmentOrForeignLinkCannotReserveOperation() {
        seedLinkTarget();
        var basic = command("143", false);
        var unsafe = new NoteCreateCommand(basic.operationId(), basic.noteId(),
            basic.candidate(), basic.notes(),
            new NoteAttachment("../proof.pdf", samplePdf()), List.of());
        assertThrows(NoteSaveValidationException.class,
            () -> creator.create(unsafe, ACTOR, TODAY));
        var unknownLink = new NoteCreateCommand(basic.operationId(), basic.noteId(),
            basic.candidate(), basic.notes(), null, List.of(UUID.randomUUID()));
        assertThrows(NoteCreateConflict.class,
            () -> creator.create(unknownLink, ACTOR, TODAY));
        assertEquals(0L, count("select count(*) from core.note_create_operations"));
        assertEquals(0L, count("select count(*) from core.note_attachments"));
        assertEquals(0L, count("select count(*) from core.note_links"));
    }

    @Test
    void auditFailureRollsBackAttachmentAndLinkAsWellAsNote() {
        seedLinkTarget();
        var base = command("999", false);
        var attached = new NoteCreateCommand(base.operationId(), base.noteId(),
            base.candidate(), base.notes(),
            new NoteAttachment("file.pdf", samplePdf()), List.of(LINK_TARGET));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> creator.create(attached, ACTOR, TODAY));
        assertEquals(0L, count("select count(*) from core.note_attachments"));
        assertEquals(0L, count("select count(*) from core.note_links"));
        assertEquals(0L, count("select count(*) from core.note_create_operations"));
        assertEquals(1L, count("select count(*) from core.notes")); // only seeded EXIT target
    }

}
