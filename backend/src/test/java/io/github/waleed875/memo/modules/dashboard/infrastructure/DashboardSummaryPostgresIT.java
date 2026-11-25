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

/**
 * Golden fixture: edge-day date bases, legacy 5-day delay, state counts,
 * duplicate incident reference mapping, arrival-based cutoff, and live admin.
 */
@Testcontainers
class DashboardSummaryPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_dashboard_summary_it")
            .withUsername("memo_it").withPassword("test_only");

    private final UUID adminRole=UUID.fromString("bd000000-0000-4000-8000-000000000001");
    private final UUID userRole=UUID.fromString("bd000000-0000-4000-8000-000000000002");
    private final UUID admin=UUID.fromString("bd000000-0000-4000-8000-000000000003");
    private final UUID user=UUID.fromString("bd000000-0000-4000-8000-000000000004");
    private final UUID company=UUID.fromString("bd000000-0000-4000-8000-000000000005");
    private final UUID otherCompany=UUID.fromString("bd000000-0000-4000-8000-000000000006");
    private final UUID type=UUID.fromString("bd000000-0000-4000-8000-000000000007");
    private final UUID a=UUID.fromString("bd000000-0000-4000-8000-000000000011");
    private final UUID b=UUID.fromString("bd000000-0000-4000-8000-000000000012");
    private final UUID c=UUID.fromString("bd000000-0000-4000-8000-000000000013");
    private final UUID d=UUID.fromString("bd000000-0000-4000-8000-000000000014");
    private final UUID deleted=UUID.fromString("bd000000-0000-4000-8000-000000000015");
    private JdbcTemplate jdbc;
    private DashboardSummaryReader reader;
    private final LocalDate day=LocalDate.of(2026,11,25);

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
            new ClassPathResource("postgres-dashboard-summary-schema.sql")
        ).execute(ds);
        var proxy=new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcDashboardSummaryReader(new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{DashboardSummaryReader.class});
        proxy.afterPropertiesSet();
        reader=(DashboardSummaryReader)proxy.getObject();

        jdbc.update("insert into auth.roles(id,code,name_ar) values (?,'ADMIN','Admin'),(?,'DATA_ENTRY','Entry')",adminRole,userRole);
        jdbc.update("""
            insert into auth.users(id,username,password_hash,full_name,role_id)
            values (?,'admin','x','Admin',?),(?,'user','x','User',?)
            """,admin,adminRole,user,userRole);
        jdbc.update("insert into core.companies(id,name) values (?,'A'),(?,'B')",company,otherCompany);
        jdbc.update("insert into core.note_types(id,code,name_ar) values (?,'ENTRY','Entry')",type);
        note(a,company,"APPROVED","2026-11-19","2026-11-25",false); // 6 days, late
        note(b,company,"NEEDS_REVIEW","2026-11-20","2026-11-25",false); // 5 days, on time
        note(c,company,"RETURNED_FOR_CORRECTION","2026-10-31","2026-11-06",false); // late cross-month
        note(d,otherCompany,"DRAFT",null,"2026-11-25",false); // missing writing date
        note(deleted,otherCompany,"APPROVED","2026-11-10","2026-11-25",true);
        jdbc.update("""
            insert into core.duplicate_incidents(
                classification,created_note_id,matched_note_id)
            values ('COMPANY_DUPLICATE_NUMBER',?,?),
                   ('COMPANY_RESEND',?,?),
                   ('COMPANY_RESEND',?,?)
            """,a,b,b,a,c,a);
    }

    private void note(UUID id, UUID companyId, String state, String writing, String arrival, boolean isDeleted) {
        jdbc.update("""
            insert into core.notes(id,company_id,note_type_id,note_number,
                writing_date,arrival_at,status,created_by,is_deleted)
            values (?, ?, ?, '123', cast(? as date), cast(? as date), ?, ?, ?)
            """,id,companyId,type,writing,arrival,state,user,isDeleted);
    }

    private DashboardSummaryCriteria filters(String basis, LocalDate from,LocalDate to,
                                              UUID companyId, LocalDate cutoff, String status) {
        return new DashboardSummaryCriteria(from,to,basis,companyId,null,null,null,status,cutoff);
    }

    @Test void unfilteredSummaryAndSlaMatchPinnedLegacyRules() {
        var result=reader.read(admin,filters("ArrivalDate",null,null,null,null,null),day);
        var s=result.summary();
        assertEquals(4,s.totalNotes());
        assertEquals(3,s.notesToday());
        assertEquals(4,s.notesCurrentMonth()); // all four nondeleted notes arrived in November
        assertEquals(2,s.activeCompaniesTotal());
        assertEquals(2,s.companiesWithNotes());
        assertEquals(1,s.approved());
        assertEquals(1,s.pendingReview());
        assertEquals(1,s.returnedForCorrection());
        assertEquals(2,s.companyResend());
        assertEquals(1,s.duplicateNumbers());
        assertEquals(3,s.duplicateIncidentCount());
        assertEquals(1,s.duplicateAffectedNotes()); // all three incidents reference a
        assertEquals(3,s.nonDuplicateNotes());
        assertEquals(2,s.lateTotal());
        assertEquals(1,s.lateSameMonth());
        assertEquals(1,s.lateCrossMonth());
        assertEquals(1,s.onTime());
        assertEquals(6.0,s.avgLateDays());
        assertEquals(6.0,s.maxLateDays());
        assertEquals(day,s.latestArrivalDate());
        assertEquals(3,result.sla().eligibleNotes());
        assertEquals(1,result.sla().onTimeNotes());
        assertEquals(2,result.sla().lateNotes());
        assertEquals(33.33,result.sla().onTimeRate());
        assertEquals(0,result.sla().invalidDateOrderNotes());
    }

    @Test void dateBasisAndInclusiveEndDayArePreserved() {
        var arrivals=reader.read(admin,filters("ArrivalDate",day,day,null,null,null),day);
        assertEquals(3,arrivals.summary().totalNotes());
        var writing=reader.read(admin,filters("WritingDate",day,day,null,null,null),day);
        assertEquals(0,writing.summary().totalNotes());
        var writingRange=reader.read(admin,filters("WritingDate",
            LocalDate.of(2026,11,19),LocalDate.of(2026,11,20),null,null,null),day);
        assertEquals(2,writingRange.summary().totalNotes());
    }

    @Test void cutoffUsesArrivalAndCompanyAndStatusFilterShareOneSnapshot() {
        var result=reader.read(admin,filters("WritingDate",null,null,
            company,LocalDate.of(2026,11,24),null),day);
        assertEquals(1,result.summary().totalNotes()); // c arrived 6th only
        assertEquals(2,result.summary().activeCompaniesTotal());
        assertEquals(1,result.summary().companiesWithNotes());
        var approved=reader.read(admin,filters("ArrivalDate",null,null,
            company,null,"APPROVED"),day);
        assertEquals(1,approved.summary().totalNotes());
        assertEquals(1,approved.summary().approved());
        assertEquals(1,approved.summary().duplicateNumbers());
    }

    @Test void revokedOrInactiveAdminCannotRunQuery() {
        assertThrows(MasterDataForbiddenException.class, () ->
            reader.read(user,filters(null,null,null,null,null,null),day));
        jdbc.update("update auth.users set is_active=false where id=?",admin);
        assertThrows(MasterDataForbiddenException.class, () ->
            reader.read(admin,filters(null,null,null,null,null,null),day));
    }

    @Test void emptyResultKeepsNullableMeansAndRate() {
        var empty=reader.read(admin,filters("ArrivalDate",
            LocalDate.of(2027,1,1),LocalDate.of(2027,1,2),null,null,null),day);
        assertEquals(0,empty.summary().totalNotes());
        assertNull(empty.summary().avgLateDays());
        assertNull(empty.summary().maxLateDays());
        assertNull(empty.sla().onTimeRate());
    }
}
