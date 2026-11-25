package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditCommand;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditService;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;

@EnabledIfEnvironmentVariable(named = "MEMO_FULL_LEGACY_SCHEMA", matches = "true")
class LegacyFullSchemaOwnerDraftMediaLinksIT {
    private JdbcTemplate jdbc;
    private NoteDraftEditService service;
    private UserProfile actor;
    private UUID user, company, entryType, exitType;
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\nendobj\n%%EOF\n"
        .getBytes(StandardCharsets.US_ASCII);

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(
            "jdbc:postgresql://" + System.getenv("PGHOST") + ":"
                + System.getenv("PGPORT") + "/" + System.getenv("PGDATABASE"),
            System.getenv("PGUSER"), System.getenv("PGPASSWORD"));
        jdbc = new JdbcTemplate(ds);
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcNoteDraftEditRepository(
            jdbc, new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{NoteDraftEditRepository.class});
        proxy.afterPropertiesSet();
        service = new NoteDraftEditService(
            (NoteDraftEditRepository)proxy.getObject(), () -> {},
            Clock.fixed(Instant.parse("2026-11-25T09:00:00Z"), ZoneOffset.UTC));
        UUID role = jdbc.queryForObject(
            "select id from auth.roles where code = 'DATA_ENTRY'", UUID.class);
        user = UUID.randomUUID();
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, ?, 'integration-only', 'Draft owner', ?)
            """, user, "owner_" + user.toString().substring(0, 12), role);
        actor = new UserProfile(user, "owner", "Draft owner", "DATA_ENTRY");
        company = UUID.randomUUID();
        jdbc.update("insert into core.companies(id,name) values (?,'Draft media test')", company);
        entryType = jdbc.queryForObject(
            "select id from core.note_types where code = 'ENTRY'", UUID.class);
        exitType = jdbc.queryForObject(
            "select id from core.note_types where code = 'EXIT'", UUID.class);
    }

    private UUID note(UUID type, String state) {
        UUID id = UUID.randomUUID();
        String number = Long.toString(500000000L
            + Math.floorMod(id.getMostSignificantBits(), 100000000L));
        jdbc.update("""
            insert into core.notes(id, note_number, note_number_normalized,
               note_number_value, note_year, note_type_id, company_id,
               writing_date, arrival_at, created_by, status, notes)
            values (?, ?, ?, ?, 2026, ?, ?, date '2026-11-20',
                    date '2026-11-24', ?, ?, 'before')
            """, id, number, number, Long.parseLong(number),
            type, company, user, state);
        return id;
    }

    private List<NoteDraftEditCommand.IndicatorInput> requiredCorrect() {
        return jdbc.query("""
            select ntf.field_id from core.note_type_fields ntf
            join core.note_fields nf on nf.id = ntf.field_id
            where ntf.note_type_id = ? and ntf.is_active and nf.is_active
              and not ntf.is_deleted and not nf.is_deleted
            order by ntf.sort_order, ntf.field_id
            """, (rs, i) -> new NoteDraftEditCommand.IndicatorInput(
                rs.getObject(1, UUID.class), IndicatorValue.Correct, null), entryType);
    }

    private LocalDateTime version(UUID id) {
        return jdbc.queryForObject(
            "select updated_at from core.notes where id = ?", LocalDateTime.class, id);
    }
    private int count(String table, String field, UUID id) {
        return jdbc.queryForObject("select count(*) from " + table
            + " where " + field + " = ?", Integer.class, id);
    }
    private int links(UUID id) {
        return jdbc.queryForObject(
            "select count(*) from core.note_links where source_note_id=? or related_note_id=?",
            Integer.class, id, id);
    }
    private int sourceRefs(UUID id) {
        return jdbc.queryForObject("""
            select count(*) from core.note_source_exit_refs r
              join core.note_sources s on s.id=r.source_id where s.note_id=?
            """, Integer.class, id);
    }
    private NoteDraftEditCommand command(UUID id, List<UUID> before, List<UUID> after,
                                         NoteAttachment attachment) {
        return new NoteDraftEditCommand(id, version(id), "after", false, false,
            requiredCorrect(), attachment, before, after);
    }

    @Test void resubmitWithAttachmentAndLinkedExitIsOneAtomicUpdate() {
        UUID id = note(entryType, "RETURNED_FOR_CORRECTION");
        UUID exit = note(exitType, "COMPLETE");
        jdbc.update("""
            update core.notes set return_reason='fix field', review_started_at=now()
            where id=?
            """, id);
        var cmd = command(id, List.of(), List.of(exit),
            new NoteAttachment("evidence.pdf", PDF));
        service.edit(actor, cmd);
        assertEquals("COMPLETE", jdbc.queryForObject(
            "select status from core.notes where id=?", String.class, id));
        assertNull(jdbc.queryForObject(
            "select return_reason from core.notes where id=?", String.class, id));
        assertEquals("RESUBMIT_FOR_REVIEW", jdbc.queryForObject(
            "select action from core.note_review_history where note_id=?",
            String.class, id));
        assertArrayEquals(PDF, jdbc.queryForObject(
            "select attachment_data from core.notes where id=?", byte[].class, id));
        assertEquals(1, count("core.note_attachments", "note_id", id));
        assertEquals(1, links(id));
        assertEquals(1, sourceRefs(id));
        assertEquals(requiredCorrect().size(), count("core.note_field_values", "note_id", id));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id=? and action_type='LINK_NOTES'
            """, Integer.class, id));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id=? and action_type='UPDATE_NOTE'
            """, Integer.class, id));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id=? and action_type='SAVE_INDICATORS'
            """, Integer.class, id));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs
            where action_type='NOTE_ATTACHMENT_UPLOADED'
              and new_value->>'noteId'=?
            """, Integer.class, id.toString()));
    }

    @Test void staleLinkSnapshotDoesNotLoseAnotherSourceReference() {
        UUID id = note(entryType, "DRAFT");
        UUID a = note(exitType, "COMPLETE");
        UUID b = note(exitType, "COMPLETE");
        var cmd = command(id, List.of(), List.of(b), null);
        UUID source = UUID.randomUUID();
        jdbc.update("""
            insert into core.note_sources(id,note_id,source_type,created_by)
            values (?,?,'EXIT',?)
            """, source, id, user);
        jdbc.update("""
            insert into core.note_source_exit_refs(source_id,exit_note_id,reference_order)
            values (?,?,1)
            """, source, a);
        assertThrows(NoteDraftEditConflict.class, () -> service.edit(actor, cmd));
        assertEquals(1, links(id));
        assertEquals(1, sourceRefs(id));
        assertEquals(0, count("core.note_attachments", "note_id", id));
        assertEquals(0, count("core.note_review_history", "note_id", id));
    }

    @Test void auditFailureRollsBackIndicatorsAttachmentLinksAndResubmission() {
        UUID id = note(entryType, "RETURNED_FOR_CORRECTION");
        UUID target = note(exitType, "COMPLETE");
        LocalDateTime before = version(id);
        var cmd = command(id, List.of(), List.of(target),
            new NoteAttachment("proof.pdf", PDF));
        jdbc.execute("""
            alter table audit.logs add constraint ck_owner_draft_fail
            check (action_type <> 'SAVE_INDICATORS') not valid
            """);
        try {
            assertThrows(Exception.class, () -> service.edit(actor, cmd));
            assertEquals("RETURNED_FOR_CORRECTION", jdbc.queryForObject(
                "select status from core.notes where id=?", String.class, id));
            assertEquals("before", jdbc.queryForObject(
                "select notes from core.notes where id=?", String.class, id));
            assertEquals(before, version(id));
            assertEquals(0, count("core.note_field_values", "note_id", id));
            assertEquals(0, count("core.note_attachments", "note_id", id));
            assertEquals(0, links(id));
            assertEquals(0, sourceRefs(id));
            assertEquals(0, count("core.note_review_history", "note_id", id));
        } finally {
            jdbc.execute("alter table audit.logs drop constraint if exists ck_owner_draft_fail");
        }
    }
}
