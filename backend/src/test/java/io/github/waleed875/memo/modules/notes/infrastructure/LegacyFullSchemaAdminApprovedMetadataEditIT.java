package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminApprovedMetadataEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminApprovedMetadataEditRepository;
import io.github.waleed875.memo.modules.notes.application.AdminApprovedMetadataEditService;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDraftMetadata;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * Real Spring JDBC path against the original, unchanged C# migrations 001–070.
 * Expected behavior follows SaveNoteAsync's ADMIN_EDIT_NOTE + preserve_workflow_state,
 * not an invocation of the C# endpoint.
 */
@EnabledIfEnvironmentVariable(named = "MEMO_FULL_LEGACY_SCHEMA", matches = "true")
class LegacyFullSchemaAdminApprovedMetadataEditIT {
    private JdbcTemplate jdbc;
    private AdminApprovedMetadataEditService service;
    private UUID admin, entryUser, company, type;
    private UserProfile adminActor;
    private final LocalDate writing = LocalDate.of(2026, 11, 19);
    private final LocalDate arrival = LocalDate.of(2026, 11, 24);

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(
            "jdbc:postgresql://" + System.getenv("PGHOST") + ":"
                + System.getenv("PGPORT") + "/" + System.getenv("PGDATABASE"),
            System.getenv("PGUSER"), System.getenv("PGPASSWORD"));
        jdbc = new JdbcTemplate(ds);
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcAdminApprovedMetadataEditRepository(
            jdbc, new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{AdminApprovedMetadataEditRepository.class});
        proxy.afterPropertiesSet();
        service = new AdminApprovedMetadataEditService(
            (AdminApprovedMetadataEditRepository) proxy.getObject(), () -> {},
            Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC));
        UUID adminRole = jdbc.queryForObject(
            "select id from auth.roles where code = 'ADMIN'", UUID.class);
        UUID entryRole = jdbc.queryForObject(
            "select id from auth.roles where code = 'DATA_ENTRY'", UUID.class);
        admin = user(adminRole, "admin");
        entryUser = user(entryRole, "entry");
        adminActor = new UserProfile(admin, "test-admin", "Test admin", "ADMIN");
        type = jdbc.queryForObject(
            "select id from core.note_types where code='ENTRY'", UUID.class);
        company = UUID.randomUUID();
        jdbc.update("insert into core.companies(id,name) values (?, 'Approved admin metadata')",
            company);
    }

    private UUID user(UUID role, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into auth.users(id,username,password_hash,full_name,role_id)
            values (?,?,'integration-only','Actor',?)
            """, id, prefix + id.toString().substring(0, 10), role);
        return id;
    }

    private UUID note(String status) {
        UUID id = UUID.randomUUID();
        String number = Long.toString(400000000L
            + Math.floorMod(id.getMostSignificantBits(), 100000000L));
        jdbc.update("""
            insert into core.notes(id,note_number,note_number_normalized,
                note_number_value,note_year,note_type_id,company_id,writing_date,
                arrival_at,created_by,status,reviewer_id,review_started_at,
                approved_by,approved_at,notes)
            values (?,?,?,?,2026,?,?,date '2026-11-19',date '2026-11-24',
                ?,?, ?,timestamp '2026-11-20 08:00:00',
                ?,timestamp '2026-11-20 09:00:00','unchanged')
            """, id, number, number, Long.parseLong(number),
            type, company, entryUser, status, entryUser, admin);
        return id;
    }
    private LocalDateTime version(UUID id) {
        return jdbc.queryForObject(
            "select updated_at from core.notes where id=?", LocalDateTime.class, id);
    }
    private String number(UUID id) {
        return jdbc.queryForObject(
            "select note_number from core.notes where id=?", String.class, id);
    }
    private NoteDraftMetadata metadata(String number) {
        return new NoteDraftMetadata(number,company,type,null,null,writing,arrival);
    }
    private AdminApprovedMetadataEditCommand command(UUID id,String number) {
        return new AdminApprovedMetadataEditCommand(id,version(id),metadata(number));
    }
    private int audits(UUID id) {
        return jdbc.queryForObject("""
            select count(*) from audit.logs
            where record_id=? and action_type='ADMIN_EDIT_NOTE'
            """, Integer.class, id);
    }
    private int history(UUID id) {
        return jdbc.queryForObject(
            "select count(*) from core.note_review_history where note_id=?",
            Integer.class, id);
    }

    @Test void updatesArabicNumberAndPreservesEveryApprovalFieldAndHistory() {
        UUID id = note("APPROVED");
        LocalDateTime oldVersion = version(id);
        var changed = new NoteDraftMetadata("٨٢١٣٥", company, type, null, null,
            LocalDate.of(2026,11,20), arrival);
        var command = new AdminApprovedMetadataEditCommand(id,oldVersion,changed);
        LocalDateTime next = service.edit(adminActor,command);
        assertEquals("٨٢١٣٥", number(id));
        assertEquals(82135L, jdbc.queryForObject(
            "select note_number_value from core.notes where id=?", Long.class,id));
        assertEquals("APPROVED", jdbc.queryForObject(
            "select status from core.notes where id=?", String.class,id));
        assertEquals(admin, jdbc.queryForObject(
            "select approved_by from core.notes where id=?", UUID.class,id));
        assertEquals(entryUser, jdbc.queryForObject(
            "select reviewer_id from core.notes where id=?", UUID.class,id));
        assertEquals(LocalDateTime.parse("2026-11-20T08:00:00"),
            jdbc.queryForObject("select review_started_at from core.notes where id=?",
                LocalDateTime.class,id));
        assertEquals(LocalDateTime.parse("2026-11-20T09:00:00"),
            jdbc.queryForObject("select approved_at from core.notes where id=?",
                LocalDateTime.class,id));
        assertEquals(0,history(id));
        assertEquals(1,audits(id));
        assertEquals("APPROVED", jdbc.queryForObject("""
            select new_value->>'status' from audit.logs
            where record_id=? and action_type='ADMIN_EDIT_NOTE'
            """, String.class,id));
        assertFalse(next.isBefore(oldVersion));
    }

    @Test void duplicateAndCompanyReclassificationAreRejectedWithoutSideEffects() {
        UUID a = note("APPROVED"), b = note("APPROVED");
        assertThrows(NoteSaveValidationException.class,
            () -> service.edit(adminActor,command(b,number(a))));
        UUID otherCompany = UUID.randomUUID();
        jdbc.update("insert into core.companies(id,name) values (?,'Foreign')",
            otherCompany);
        assertThrows(NoteSaveValidationException.class, () ->
            service.edit(adminActor,new AdminApprovedMetadataEditCommand(
                b,version(b),new NoteDraftMetadata(
                    "989898",otherCompany,type,null,null,writing,arrival))));
        assertEquals(0,audits(b));
        assertEquals(0,history(b));
        assertEquals("APPROVED",jdbc.queryForObject(
            "select status from core.notes where id=?",String.class,b));
    }

    @Test void rejectsNonAdminAndStaleRevisionAndNonApprovedState() {
        UUID approved = note("APPROVED"), draft = note("DRAFT");
        var entry = new UserProfile(entryUser,"entry","Entry","DATA_ENTRY");
        assertThrows(MasterDataForbiddenException.class,
            () -> service.edit(entry,command(approved,"889900")));
        assertThrows(NoteDraftEditConflict.class, () ->
            service.edit(adminActor,new AdminApprovedMetadataEditCommand(
                approved,LocalDateTime.parse("2020-01-01T00:00:00"),
                metadata("889900"))));
        assertThrows(NoteDraftEditConflict.class,
            () -> service.edit(adminActor,command(draft,"889900")));
        assertEquals(0,audits(approved));
        assertEquals(0,audits(draft));
    }

    @Test void rejectedAuditInsertRollsBackNumberAndApprovalPreservation() {
        UUID id = note("APPROVED");
        String original = number(id);
        LocalDateTime oldVersion = version(id);
        var command = command(id,"909090");
        jdbc.execute("""
            alter table audit.logs add constraint ck_admin_metadata_reject
            check (action_type <> 'ADMIN_EDIT_NOTE') not valid
            """);
        try {
            assertThrows(Exception.class,() -> service.edit(adminActor,command));
            assertEquals(original,number(id));
            assertEquals(oldVersion,version(id));
            assertEquals(0,history(id));
            assertEquals(0,audits(id));
            assertEquals("APPROVED",jdbc.queryForObject(
                "select status from core.notes where id=?",String.class,id));
            assertEquals(admin,jdbc.queryForObject(
                "select approved_by from core.notes where id=?",UUID.class,id));
        } finally {
            jdbc.execute("""
                alter table audit.logs drop constraint if exists ck_admin_metadata_reject
                """);
        }
    }
}
