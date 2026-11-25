package io.github.waleed875.memo.modules.dashboard.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.dashboard.application.*;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import java.time.LocalDate;
import java.util.UUID;
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

/** Tests original duplicate reference routing, recent notes, backlog age buckets. */
@Testcontainers
class DashboardActivityPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_activity_it")
            .withUsername("memo_it").withPassword("test_only");

    private static final LocalDate DAY=LocalDate.of(2026,11,25);
    private final UUID adminRole=UUID.fromString("da000000-0000-4000-8000-000000000001");
    private final UUID entryRole=UUID.fromString("da000000-0000-4000-8000-000000000002");
    private final UUID admin=UUID.fromString("da000000-0000-4000-8000-000000000003");
    private final UUID entry=UUID.fromString("da000000-0000-4000-8000-000000000004");
    private final UUID company=UUID.fromString("da000000-0000-4000-8000-000000000005");
    private final UUID factory=UUID.fromString("da000000-0000-4000-8000-000000000006");
    private final UUID line=UUID.fromString("da000000-0000-4000-8000-000000000007");
    private final UUID type=UUID.fromString("da000000-0000-4000-8000-000000000008");
    private final UUID a=UUID.fromString("da000000-0000-4000-8000-000000000011");
    private final UUID b=UUID.fromString("da000000-0000-4000-8000-000000000012");
    private final UUID c=UUID.fromString("da000000-0000-4000-8000-000000000013");
    private final UUID d=UUID.fromString("da000000-0000-4000-8000-000000000014");
    private final UUID old=UUID.fromString("da000000-0000-4000-8000-000000000015");
    private final UUID deleted=UUID.fromString("da000000-0000-4000-8000-000000000016");
    private JdbcTemplate jdbc;
    private DashboardActivityReader reader;

    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(
            postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("drop schema if exists audit cascade");
        jdbc.execute("drop schema if exists core cascade");
        jdbc.execute("drop schema if exists auth cascade");
        jdbc.execute("drop schema if exists system cascade");
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql"),
            new ClassPathResource("postgres-dashboard-summary-schema.sql")).execute(ds);

        var proxy=new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcDashboardActivityReader(new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{DashboardActivityReader.class});
        proxy.afterPropertiesSet();
        reader=(DashboardActivityReader)proxy.getObject();

        jdbc.update("insert into auth.roles(id,code,name_ar) values (?,'ADMIN','Admin'),(?,'DATA_ENTRY','Entry')",
            adminRole,entryRole);
        jdbc.update("""
            insert into auth.users(id,username,password_hash,full_name,role_id)
            values (?,'admin','x','Admin',?),(?,'entry','x','Entry',?)
            """,admin,adminRole,entry,entryRole);
        jdbc.update("insert into core.companies(id,name) values (?,'شركة')",company);
        jdbc.update("insert into core.factories(id,company_id,name) values (?,?,'مصنع')",factory,company);
        jdbc.update("insert into core.production_lines(id,factory_id,name) values (?,?,'خط')",line,factory);
        jdbc.update("insert into core.note_types(id,code,name_ar) values (?,'ENTRY','نوع')",type);

        note(a,"NEEDS_REVIEW","2026-10-28","2026-11-25","2026-11-25",false);
        note(b,"UNDER_REVIEW","2026-11-20","2026-11-24","2026-11-23",false);
        note(c,"HAS_ERRORS","2026-11-20","2026-11-23","2026-11-20",false);
        note(d,"COMPLETE","2026-11-20","2026-11-22","2026-11-15",false);
        note(old,"APPROVED","2026-10-15","2026-10-20","2026-10-20",false);
        note(deleted,"APPROVED","2026-11-20","2026-11-25","2026-11-25",true);

        // COMPANY_RESEND counts the matched note; duplicate number counts created note.
        jdbc.update("""
            insert into core.duplicate_incidents(
                classification,created_note_id,matched_note_id) values
                ('COMPANY_DUPLICATE_NUMBER',?,?),
                ('COMPANY_RESEND',?,?),
                ('COMPANY_RESEND',?,?),
                ('COMPANY_DUPLICATE_NUMBER',?,?)
            """,a,old,b,a,c,b,deleted,a);
        jdbc.update("update core.notes set review_started_at = timestamp '2026-11-23 10:00:00' where id = ?",b);
    }

    private void note(UUID id,String status,String writing,String arrival,
                      String created,boolean deletedFlag) {
        jdbc.update("""
            insert into core.notes(
              id,company_id,factory_id,production_line_id,note_type_id,
              note_number,writing_date,arrival_at,status,created_by,is_deleted,created_at)
            values (?,?,?,?,?,'N',cast(? as date),cast(? as date),?,?,?,cast(? as timestamp))
            """,id,company,factory,line,type,writing,arrival,status,entry,deletedFlag,created+" 08:00:00");
    }

    private DashboardSummaryCriteria f(String basis,LocalDate from,LocalDate to,String status) {
        return new DashboardSummaryCriteria(from,to,basis,null,null,null,null,status,null);
    }

    @Test void classifyDuplicateIncidentsByReferenceNoteAndFillEmptyMonths() {
        var snapshot=reader.read(admin,f("ArrivalDate",
            LocalDate.of(2026,10,1),LocalDate.of(2026,12,31),null),DAY);
        var trend=snapshot.duplicateTrend();
        assertEquals(3,trend.size());
        assertEquals("2026-10",trend.get(0).periodKey());
        assertEquals(0,trend.get(0).companyResend());
        assertEquals(0,trend.get(0).duplicateNumber());
        assertEquals("2026-11",trend.get(1).periodKey());
        assertEquals(2,trend.get(1).companyResend());
        assertEquals(1,trend.get(1).duplicateNumber());
        assertEquals("نوفمبر 2026",trend.get(1).periodLabel());
        assertEquals(0,trend.get(2).companyResend());
        assertEquals(0,trend.get(2).duplicateNumber());
        assertEquals(2,snapshot.duplicateBreakdown().getFirst().count());
        assertEquals("COMPANY_RESEND",snapshot.duplicateBreakdown().getFirst().classification());
    }

    @Test void writingDateChangesIncidentPeriodWithoutChangingIncidentIdentity() {
        var snapshot=reader.read(admin,f("WritingDate",
            LocalDate.of(2026,10,1),LocalDate.of(2026,11,30),null),DAY);
        assertEquals(2,snapshot.duplicateTrend().size());
        assertEquals(1,snapshot.duplicateTrend().get(0).duplicateNumber());
        assertEquals(1,snapshot.duplicateTrend().get(0).companyResend());
        assertEquals(1,snapshot.duplicateTrend().get(1).companyResend());
        assertEquals(0,snapshot.duplicateTrend().get(1).duplicateNumber());
    }

    @Test void recentNotesSortByArrivalThenCreationAndNeverReturnDeletedNotes() {
        var recent=reader.read(admin,f("ArrivalDate",null,null,null),DAY).recentNotes();
        assertEquals(5,recent.size());
        assertEquals(a,recent.get(0).id());
        assertEquals(b,recent.get(1).id());
        assertEquals("NEEDS_REVIEW",recent.getFirst().status());
        assertEquals("تحتاج مراجعة",recent.getFirst().statusLabel());
        assertTrue(recent.getFirst().isLate());
        assertEquals("شركة",recent.getFirst().companyName());
        assertEquals("نوع",recent.getFirst().noteTypeName());
        assertEquals(old,recent.getLast().id());
        assertTrue(recent.stream().noneMatch(n->n.id().equals(deleted)));
    }

    @Test void reviewBacklogRespectsFourOriginalAgeBandsAndReviewStartedAnchor() {
        var b=reader.read(admin,f("ArrivalDate",null,null,null),DAY).backlogByAge();
        assertEquals(4,b.size());
        assertEquals("lt-1-day",b.get(0).code());
        assertEquals("1-3-days",b.get(1).code());
        assertEquals("4-7-days",b.get(2).code());
        assertEquals("gt-7-days",b.get(3).code());
        for(var band:b) assertEquals(1L,band.value());
    }

    @Test void statusAndTimeFiltersApplyConsistentlyToAllCollections() {
        var filtered=reader.read(admin,f("ArrivalDate",
            LocalDate.of(2026,11,24),LocalDate.of(2026,11,25),"NEEDS_REVIEW"),DAY);
        assertEquals(1,filtered.recentNotes().size());
        assertEquals(a,filtered.recentNotes().getFirst().id());
        assertEquals(1,filtered.backlogByAge().size());
        assertEquals(1,filtered.backlogByAge().getFirst().value());
        assertEquals(2,filtered.duplicateBreakdown().size());
        assertTrue(filtered.duplicateBreakdown().stream()\n            .anyMatch(x->x.classification().equals("COMPANY_RESEND")));
    }

    @Test void roleRevocationAndMissingAdminAreDeniedInJdbc() {
        assertThrows(MasterDataForbiddenException.class,
            ()->reader.read(entry,f(null,null,null,null),DAY));
        jdbc.update("update auth.users set is_active = false where id = ?",admin);
        assertThrows(MasterDataForbiddenException.class,
            ()->reader.read(admin,f(null,null,null,null),DAY));
    }

    @Test void emptyWindowReturnsZeroFilledTrendAndEmptyCollections() {
        var r=reader.read(admin,f("ArrivalDate",
            LocalDate.of(2027,1,1),LocalDate.of(2027,2,28),null),DAY);
        assertEquals(2,r.duplicateTrend().size());
        assertEquals(0L,r.duplicateTrend().get(0).duplicateNumber());
        assertTrue(r.duplicateBreakdown().isEmpty());
        assertTrue(r.recentNotes().isEmpty());
        assertTrue(r.backlogByAge().isEmpty());
    }
}
