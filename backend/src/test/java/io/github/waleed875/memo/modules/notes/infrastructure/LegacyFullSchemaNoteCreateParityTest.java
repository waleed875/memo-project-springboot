package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.notes.application.NoteCreateCommand;
import io.github.waleed875.memo.modules.notes.application.NoteCreateConflict;
import io.github.waleed875.memo.modules.notes.application.NoteCreateService;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateClassification;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateResolution;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateDecisionCommand;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateDecisionRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDuplicateDecisionService;
import io.github.waleed875.memo.modules.notes.application.NoteCreator;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

/**
 * A source-contract-derived comparison for original C# SaveNoteAsync create,
 * NOT an execution of the C# web/API save on this database.
 *
 * Uses the REAL Java application service and JDBC adapter against an ephemeral
 * PostgreSQL 16 database with exact original 001-070 migrations and triggers.
 */
@EnabledIfEnvironmentVariable(named = "MEMO_FULL_LEGACY_SCHEMA", matches = "true")
class LegacyFullSchemaNoteCreateParityTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    private static final LocalDate WRITING = LocalDate.of(2026, 11, 20);
    private static final LocalDate ARRIVAL = LocalDate.of(2026, 11, 24);
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\nendobj\n%%EOF\n"
        .getBytes(StandardCharsets.US_ASCII);

    private JdbcTemplate jdbc;
    private NoteCreateService service;
    private NoteDuplicateDecisionService decisions;
    private UUID user, company, entryType, exitType;
    private UserProfile actor;

    @BeforeEach
    void init() {
        var dataSource = new DriverManagerDataSource(
            "jdbc:postgresql://" + System.getenv("PGHOST") + ":" +
                System.getenv("PGPORT") + "/" + System.getenv("PGDATABASE"),
            System.getenv("PGUSER"), System.getenv("PGPASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcNoteCreator(
            jdbc, new NamedParameterJdbcTemplate(dataSource)));
        proxy.setTransactionManager(new JdbcTransactionManager(dataSource));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{NoteCreator.class});
        proxy.afterPropertiesSet();
        service = new NoteCreateService((NoteCreator) proxy.getObject(), () -> {},
            Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC));
        var decisionProxy = new TransactionProxyFactoryBean();
        decisionProxy.setTarget(new JdbcNoteDuplicateDecisionRepository(
            jdbc, new NamedParameterJdbcTemplate(dataSource)));
        decisionProxy.setTransactionManager(new JdbcTransactionManager(dataSource));
        decisionProxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        decisionProxy.setProxyInterfaces(new Class<?>[]{NoteDuplicateDecisionRepository.class});
        decisionProxy.afterPropertiesSet();
        decisions = new NoteDuplicateDecisionService(
            (NoteDuplicateDecisionRepository)decisionProxy.getObject(), () -> {});

        UUID role = jdbc.queryForObject(
            "select id from auth.roles where code = 'DATA_ENTRY'", UUID.class);
        entryType = jdbc.queryForObject(
            "select id from core.note_types where code = 'ENTRY'", UUID.class);
        exitType = jdbc.queryForObject(
            "select id from core.note_types where code = 'EXIT'", UUID.class);
        assertNotNull(role);
        assertNotNull(entryType);
        assertNotNull(exitType);

        user = UUID.randomUUID();
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, ?, 'test-only-no-login', 'Create parity actor', ?)
            """, user, "create_" + user.toString().substring(0, 12), role);
        actor = new UserProfile(user, "create", "Create parity actor", "DATA_ENTRY");
        company = UUID.randomUUID();
        jdbc.update("insert into core.companies(id, name) values (?, 'Create parity company')",
            company);
    }

    private List<NoteSaveCandidate.IndicatorInput> allCorrect() {
        List<UUID> activeFields = jdbc.query("""
            select ntf.field_id from core.note_type_fields ntf
              join core.note_fields nf on nf.id = ntf.field_id
            where ntf.note_type_id = ? and ntf.is_active and nf.is_active
              and not ntf.is_deleted and not nf.is_deleted
            order by ntf.sort_order, ntf.field_id
            """, (rs, i) -> rs.getObject(1, UUID.class), entryType);
        assertFalse(activeFields.isEmpty(), "Original ENTRY type should define active fields");
        return activeFields.stream()
            .map(id -> new NoteSaveCandidate.IndicatorInput(id, IndicatorValue.Correct))
            .toList();
    }

    private NoteCreateCommand command(boolean draft, boolean withAttachment,
                                      List<UUID> linkedNotes,
                                      List<NoteSaveCandidate.IndicatorInput> indicators) {
        UUID noteId = UUID.randomUUID();
        String number = Long.toString(800_000_000L +
            Math.floorMod(noteId.getMostSignificantBits(), 100_000_000L));
        var candidate = new NoteSaveCandidate(number, company, entryType,
            null, null, WRITING, ARRIVAL, draft, false, indicators);
        return new NoteCreateCommand(UUID.randomUUID(), noteId, candidate,
            "Create parity test",
            withAttachment ? new NoteAttachment("invoice.pdf", PDF) : null, linkedNotes);
    }

    private UUID exitTarget() {
        UUID id = UUID.randomUUID();
        String number = Long.toString(700_000_000L +
            Math.floorMod(id.getMostSignificantBits(), 100_000_000L));
        jdbc.update("""
            insert into core.notes(
                id, note_number, note_number_normalized, note_number_value,
                note_year, note_type_id, company_id, writing_date,
                arrival_at, status, created_by)
            values (?, ?, ?, ?, 2026, ?, ?, ?, ?, 'COMPLETE', ?)
            """, id, number, number, Long.parseLong(number),
            exitType, company, WRITING, ARRIVAL, user);
        return id;
    }

    private int count(String table, String column, UUID id) {
        return jdbc.queryForObject(
            "select count(*) from " + table + " where " + column + " = ?",
            Integer.class, id);
    }
    private int auditCount(UUID id) {
        return count("audit.logs", "record_id", id);
    }
    private int physicalLinks(UUID id) {
        return jdbc.queryForObject("""
            select count(*) from core.note_links
            where source_note_id = ? or related_note_id = ?
            """, Integer.class, id, id);
    }
    private int sourceReferences(UUID id) {
        return jdbc.queryForObject("""
            select count(*) from core.note_source_exit_refs r
            join core.note_sources s on s.id = r.source_id
            where s.note_id = ?
            """, Integer.class, id);
    }
    private int reservations(UUID operationId) {
        return count("core.note_create_operations", "operation_id", operationId);
    }

    @Test
    void fullSubmitStoresIndicatorsAttachmentLinkAndAllAuditEventsAtomically() {
        UUID target = exitTarget();
        var c = command(false, true, List.of(target), allCorrect());
        assertEquals(c.noteId(), service.create(actor, c));
        assertEquals("COMPLETE", jdbc.queryForObject(
            "select status from core.notes where id = ?", String.class, c.noteId()));
        assertEquals(c.candidate().noteNumber(), jdbc.queryForObject(
            "select note_number from core.notes where id = ?", String.class, c.noteId()));
        assertEquals(c.candidate().fieldValues().size(),
            count("core.note_field_values", "note_id", c.noteId()));
        assertArrayEquals(PDF, jdbc.queryForObject(
            "select attachment_data from core.notes where id = ?", byte[].class, c.noteId()));
        assertEquals(1, count("core.note_attachments", "note_id", c.noteId()));
        assertEquals(1, physicalLinks(c.noteId()));
        assertEquals(1, sourceReferences(c.noteId()));
        assertEquals(1, reservations(c.operationId()));
        assertEquals("SUBMIT_FOR_REVIEW", jdbc.queryForObject("""
            select action from core.note_review_history where note_id = ?
            """, String.class, c.noteId()));

        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id = ? and action_type = 'LINK_NOTES'
            """, Integer.class, c.noteId()));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id = ? and action_type = 'CREATE_NOTE'
              and new_value->>'status' = 'COMPLETE'
            """, Integer.class, c.noteId()));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id = ? and action_type = 'SAVE_INDICATORS'
              and (new_value->>'indicatorCount')::int = ?
            """, Integer.class, c.noteId(), c.candidate().fieldValues().size()));
        assertEquals(3, auditCount(c.noteId()));

        // A byte-identical replay returns the same note with no extra history,
        // attachment, links, trigger-created sources or audit events.
        assertEquals(c.noteId(), service.create(actor, c));
        assertEquals(1, count("core.note_attachments", "note_id", c.noteId()));
        assertEquals(1, physicalLinks(c.noteId()));
        assertEquals(1, sourceReferences(c.noteId()));
        assertEquals(1, count("core.note_review_history", "note_id", c.noteId()));
        assertEquals(3, auditCount(c.noteId()));
    }

    @Test
    void draftAllowsIncompleteIndicatorsWithOriginalHistoryAndAuditNames() {
        var c = command(true, false, List.of(), List.of());
        assertEquals(c.noteId(), service.create(actor, c));
        assertEquals("DRAFT", jdbc.queryForObject(
            "select status from core.notes where id = ?", String.class, c.noteId()));
        assertEquals("CREATE_DRAFT", jdbc.queryForObject(
            "select action from core.note_review_history where note_id = ?",
            String.class, c.noteId()));
        assertEquals(0, count("core.note_field_values", "note_id", c.noteId()));
        assertEquals(0, count("core.note_attachments", "note_id", c.noteId()));
        assertEquals(0, physicalLinks(c.noteId()));
        assertEquals(1, reservations(c.operationId()));
        assertEquals(2, auditCount(c.noteId()));
    }

    @Test
    void incompleteSubmissionAndChangedReplayNeverProducePartialChanges() {
        List<NoteSaveCandidate.IndicatorInput> fields = new ArrayList<>(allCorrect());
        fields.removeFirst();
        var incomplete = command(false, true, List.of(), fields);
        assertThrows(NoteSaveValidationException.class,
            () -> service.create(actor, incomplete));
        assertNoCreatedSideEffects(incomplete);

        var valid = command(false, false, List.of(), allCorrect());
        assertEquals(valid.noteId(), service.create(actor, valid));
        var reusedOperation = new NoteCreateCommand(valid.operationId(), valid.noteId(),
            valid.candidate(), "payload changed", valid.attachment(), valid.relatedNoteIds());
        assertThrows(NoteCreateConflict.class,
            () -> service.create(actor, reusedOperation));
        assertEquals(1, reservations(valid.operationId()));
        assertEquals(2, auditCount(valid.noteId()));
        assertEquals(1, count("core.note_review_history", "note_id", valid.noteId()));
    }

    @Test
    void lateAuditFailureRollsBackNoteIndicatorsAttachmentLinksSourceAndReservation() {
        UUID target = exitTarget();
        var command = command(false, true, List.of(target), allCorrect());
        jdbc.execute("""
            alter table audit.logs add constraint ck_create_late_audit_reject
            check (action_type <> 'SAVE_INDICATORS') not valid
            """);
        try {
            assertThrows(Exception.class, () -> service.create(actor, command));
            assertNoCreatedSideEffects(command);
            assertEquals(1, count("core.notes", "id", target));
        } finally {
            jdbc.execute("""
                alter table audit.logs
                drop constraint if exists ck_create_late_audit_reject
                """);
        }
    }

    @Test
    void foreignCompanyLinkIsRejectedBeforePersistence() {
        UUID foreign = UUID.randomUUID();
        jdbc.update("insert into core.companies(id, name) values (?, 'Foreign test company')",
            foreign);
        UUID target = UUID.randomUUID();
        String number = Long.toString(600_000_000L +
            Math.floorMod(target.getMostSignificantBits(), 100_000_000L));
        jdbc.update("""
            insert into core.notes(
                id, note_number, note_number_normalized, note_number_value,
                note_year, note_type_id, company_id, writing_date,
                arrival_at, status, created_by)
            values (?, ?, ?, ?, 2026, ?, ?, ?, ?, 'COMPLETE', ?)
            """, target, number, number, Long.parseLong(number),
            exitType, foreign, WRITING, ARRIVAL, user);
        var c = command(false, true, List.of(target), allCorrect());
        assertThrows(NoteCreateConflict.class, () -> service.create(actor, c));
        assertNoCreatedSideEffects(c);
    }

    private void assertNoCreatedSideEffects(NoteCreateCommand c) {
        UUID id = c.noteId();
        assertEquals(0, count("core.notes", "id", id));
        assertEquals(0, count("core.note_field_values", "note_id", id));
        assertEquals(0, count("core.note_attachments", "note_id", id));
        assertEquals(0, count("core.note_review_history", "note_id", id));
        assertEquals(0, auditCount(id));
        assertEquals(0, physicalLinks(id));
        assertEquals(0, sourceReferences(id));
        assertEquals(0, reservations(c.operationId()));
    }

    private NoteCreateCommand duplicateCandidate(
        NoteCreateCommand original, UUID matchedId
    ) {
        return new NoteCreateCommand(UUID.randomUUID(), UUID.randomUUID(),
            original.candidate(), "distinct duplicate payload",
            new NoteAttachment("duplicate.pdf", PDF), List.of(),
            new NoteDuplicateResolution(
                NoteDuplicateClassification.COMPANY_DUPLICATE_NUMBER, matchedId));
    }

    private NoteDuplicateDecisionCommand decision(
        NoteCreateCommand original, NoteDuplicateClassification kind, UUID matchedId
    ) {
        var c = original.candidate();
        return new NoteDuplicateDecisionCommand(
            kind, matchedId, c.noteNumber(), c.companyId(), c.noteTypeId(),
            c.factoryId(), c.productionLineId(), c.writingDate(), false);
    }

    private int incidents(UUID noteId, String classification) {
        return jdbc.queryForObject("""
            select count(*) from core.duplicate_incidents
            where matched_note_id=? and classification=?
            """, Integer.class, noteId, classification);
    }

    @Test
    void explicitCompanyDuplicateCreatesSecondMemoWithOneIncidentAndStableReplay() {
        var original = command(true, false, List.of(), List.of());
        service.create(actor, original);
        var duplicate = duplicateCandidate(original, original.noteId());
        assertEquals(duplicate.noteId(), service.create(actor, duplicate));
        assertEquals(1, count("core.notes", "id", duplicate.noteId()));
        assertEquals(1, count("core.note_attachments", "note_id", duplicate.noteId()));
        assertEquals(1, count("core.note_review_history", "note_id", duplicate.noteId()));
        assertEquals(1, incidents(original.noteId(), "COMPANY_DUPLICATE_NUMBER"));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from core.duplicate_incidents
             where created_note_id=?
               and matched_note_id=? and classification='COMPANY_DUPLICATE_NUMBER'
            """, Integer.class, duplicate.noteId(), original.noteId()));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id=? and action_type='COMPANY_DUPLICATE_NUMBER'
            """, Integer.class, duplicate.noteId()));

        assertEquals(duplicate.noteId(), service.create(actor, duplicate));
        assertEquals(1, incidents(original.noteId(), "COMPANY_DUPLICATE_NUMBER"));
        assertEquals(1, count("core.note_attachments", "note_id", duplicate.noteId()));

        var changedDecision = new NoteCreateCommand(duplicate.operationId(),
            duplicate.noteId(), duplicate.candidate(), duplicate.notes(),
            duplicate.attachment(), duplicate.relatedNoteIds(),
            new NoteDuplicateResolution(NoteDuplicateClassification.COMPANY_DUPLICATE_NUMBER,
                UUID.randomUUID()));
        assertThrows(NoteCreateConflict.class, () -> service.create(actor, changedDecision));
        assertEquals(1, incidents(original.noteId(), "COMPANY_DUPLICATE_NUMBER"));
    }

    @Test
    void resendAndEntryAttemptClassificationsDoNotCreateNotes() {
        var original = command(true, false, List.of(), List.of());
        service.create(actor, original);
        int before = jdbc.queryForObject(
            "select count(*) from core.notes where company_id=?", Integer.class, company);
        decisions.record(actor, decision(
            original, NoteDuplicateClassification.COMPANY_RESEND, original.noteId()));
        assertEquals(1, incidents(original.noteId(), "COMPANY_RESEND"));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id=? and action_type='COMPANY_RESEND'
            """, Integer.class, original.noteId()));
        decisions.record(actor, decision(original,
            NoteDuplicateClassification.DATA_ENTRY_DUPLICATE_ATTEMPT, original.noteId()));
        assertEquals(1, jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id=? and action_type='DATA_ENTRY_DUPLICATE_ATTEMPT'
              and new_value->>'matchedNoteId'=?
            """, Integer.class, original.noteId(), original.noteId().toString()));
        assertEquals(0, incidents(original.noteId(), "DATA_ENTRY_DUPLICATE_ATTEMPT"));
        assertEquals(before, jdbc.queryForObject(
            "select count(*) from core.notes where company_id=?", Integer.class, company));
    }

    @Test
    void staleMatchedNoteAndUnclassifiedDuplicatesAreRejectedWithoutWrites() {
        var original = command(true, false, List.of(), List.of());
        service.create(actor, original);
        var raw = new NoteCreateCommand(UUID.randomUUID(), UUID.randomUUID(),
            original.candidate(), "no decision", null, List.of());
        assertThrows(NoteCreateConflict.class, () -> service.create(actor, raw));
        assertNoCreatedSideEffects(raw);

        var stale = duplicateCandidate(original, UUID.randomUUID());
        assertThrows(NoteCreateConflict.class, () -> service.create(actor, stale));
        assertNoCreatedSideEffects(stale);
        assertThrows(NoteCreateConflict.class, () -> decisions.record(actor,
            decision(original, NoteDuplicateClassification.COMPANY_RESEND,
                UUID.randomUUID())));
        assertEquals(0, incidents(original.noteId(), "COMPANY_RESEND"));
    }

    @Test
    void rejectedDuplicateIncidentRollsBackNewNoteMediaAuditAndOperationReservation() {
        var original = command(true, false, List.of(), List.of());
        service.create(actor, original);
        var candidate = duplicateCandidate(original, original.noteId());
        jdbc.execute("""
            alter table core.duplicate_incidents add constraint ck_duplicate_forced_failure
            check (classification <> 'COMPANY_DUPLICATE_NUMBER') not valid
            """);
        try {
            assertThrows(Exception.class, () -> service.create(actor, candidate));
            assertNoCreatedSideEffects(candidate);
            assertEquals(0, incidents(original.noteId(), "COMPANY_DUPLICATE_NUMBER"));
        } finally {
            jdbc.execute("""
                alter table core.duplicate_incidents
                drop constraint if exists ck_duplicate_forced_failure
                """);
        }
    }

    @Test
    void resendAuditFailureRollsBackIncident() {
        var original = command(true, false, List.of(), List.of());
        service.create(actor, original);
        jdbc.execute("""
            alter table audit.logs add constraint ck_resend_forced_failure
            check (action_type <> 'COMPANY_RESEND') not valid
            """);
        try {
            assertThrows(Exception.class, () -> decisions.record(actor,
                decision(original, NoteDuplicateClassification.COMPANY_RESEND,
                    original.noteId())));
            assertEquals(0, incidents(original.noteId(), "COMPANY_RESEND"));
        } finally {
            jdbc.execute("""
                alter table audit.logs drop constraint if exists ck_resend_forced_failure
                """);
        }
    }
}
