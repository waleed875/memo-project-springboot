package io.github.waleed875.memo.modules.dashboard.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.dashboard.application.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import java.math.BigDecimal;
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
 * Executes the original migration-069 v4 filtered PostgreSQL function,
 * unmodified, against seeded fixture notes rather than mocking analytics.
 */
@Testcontainers
class DashboardCompanyMetricsPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_company_v4_it")
            .withUsername("memo_it").withPassword("test_only");

    private static final UUID ADMIN_ROLE=UUID.fromString("ae000000-0000-4000-8000-000000000001");
    private static final UUID ENTRY_ROLE=UUID.fromString("ae000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN=UUID.fromString("ae000000-0000-4000-8000-000000000003");
    private static final UUID ENTRY=UUID.fromString("ae000000-0000-4000-8000-000000000004");
    private static final UUID COMPANY=UUID.fromString("ae000000-0000-4000-8000-000000000005");
    private static final UUID TYPE=UUID.fromString("ae000000-0000-4000-8000-000000000006");
    private static final UUID FIELD=UUID.fromString("ae000000-0000-4000-8000-000000000007");
    private static final UUID CURRENT=UUID.fromString("ae000000-0000-4000-8000-000000000011");
    private static final UUID INCOMPLETE=UUID.fromString("ae000000-0000-4000-8000-000000000012");
    private static final UUID PREVIOUS=UUID.fromString("ae000000-0000-4000-8000-000000000013");
    private JdbcTemplate jdbc;
    private DashboardCompanyMetricsService service;

    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(
            postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("drop schema if exists reporting cascade");
        jdbc.execute("drop schema if exists audit cascade");
        jdbc.execute("drop schema if exists core cascade");
        jdbc.execute("drop schema if exists auth cascade");
        jdbc.execute("drop schema if exists system cascade");
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql"),
            new ClassPathResource("postgres-dashboard-summary-schema.sql"),
            new ClassPathResource("postgres-dashboard-indicator-facts-v3.sql"),
            new ClassPathResource("postgres-dashboard-company-v4-function.sql")).execute(ds);

        var proxy=new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcDashboardCompanyMetricsReader(new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{DashboardCompanyMetricsReader.class});
        proxy.afterPropertiesSet();
        service=new DashboardCompanyMetricsService((DashboardCompanyMetricsReader)proxy.getObject());

        jdbc.update("insert into auth.roles(id,code,name_ar) values (?,'ADMIN','Admin'),(?,'DATA_ENTRY','Entry')",
            ADMIN_ROLE,ENTRY_ROLE);
        jdbc.update("""
            insert into auth.users(id,username,password_hash,full_name,role_id)
            values (?,'admin','x','Admin',?),(?,'entry','x','Entry',?)
            """,ADMIN,ADMIN_ROLE,ENTRY,ENTRY_ROLE);
        jdbc.update("insert into core.companies(id,name) values (?,'شركة')",COMPANY);
        jdbc.update("insert into core.note_types(id,code,name_ar) values (?,'ENTRY','وارد')",TYPE);
        jdbc.update("insert into core.note_fields(id,code,name_ar) values (?,'F1','Field')",FIELD);
        jdbc.update("""
            insert into core.note_type_fields(id,note_type_id,field_id,sort_order)
            values (?,?,?,1)
            """,UUID.randomUUID(),TYPE,FIELD);

        note(CURRENT,"2026-11-09","2026-11-16",true);
        note(INCOMPLETE,null,"2026-11-18",false);
        note(PREVIOUS,"2026-11-05","2026-11-05",false);
        jdbc.update("""
            insert into core.note_field_values(id,note_id,field_id,field_value)
            values (?,?,?,1),(?,?,?,2)
            """,UUID.randomUUID(),CURRENT,FIELD,UUID.randomUUID(),PREVIOUS,FIELD);
    }

    private void note(UUID id,String writing,String arrival,boolean loot) {
        jdbc.update("""
            insert into core.notes(id,company_id,note_type_id,note_number,
                writing_date,arrival_at,status,created_by,is_loot,created_at)
            values (?,?,?,'X',cast(? as date),cast(? as date),
                'APPROVED',?,?,cast(? as timestamp))
            """,id,COMPANY,TYPE,writing,arrival,ENTRY,loot,arrival+" 08:00:00");
    }

    private UserProfile actor() {
        return new UserProfile(ADMIN,"admin","Admin","ADMIN");
    }

    private DashboardSummaryCriteria filters(
        String basis,LocalDate from,LocalDate to,UUID company
    ) {
        return new DashboardSummaryCriteria(from,to,basis,company,null,null,null,null,null);
    }

    @Test void currentAndPriorMetricSnapshotsUseSameCanonicalSqlFunction() {
        var current=service.read(actor(),filters("ArrivalDate",
            LocalDate.of(2026,11,11),LocalDate.of(2026,11,20),COMPANY));
        assertNotNull(current.previousPeriod());
        assertEquals(LocalDate.of(2026,11,1),current.previousPeriod().from());
        assertEquals(LocalDate.of(2026,11,10),current.previousPeriod().to());
        assertEquals("ArrivalDate",current.previousPeriod().dateBasis());

        var now=current.companyMetrics();
        var previous=current.previousCompanyMetrics();
        assertNotNull(now);
        assertNotNull(previous);
        assertEquals(2,now.totalNotes());
        assertEquals(2,now.expectedItems());
        assertEquals(1,now.correctItems());
        assertEquals(1,now.unreviewedItems());
        assertEquals(1,now.qualityEligibleNoteCount());
        assertEquals(1,now.cleanReviewedNotes());
        assertEquals(1,now.incompleteReviewNotes());
        assertEquals(1,now.timeEligibleNotes());
        assertEquals(1,now.lateNotes());
        assertEquals(2,now.entryNotes());
        assertEquals(1,now.linkedEntryNotes());
        assertEquals(new BigDecimal("0.00"),now.timeCompliancePercentage());
        assertEquals(1,previous.totalNotes());
        assertEquals(1,previous.errorItems());
        assertEquals(1,previous.errorNotes());
        assertEquals(1,previous.onTimeNotes());
        assertEquals(0,previous.linkedEntryNotes());
        assertEquals(new BigDecimal("0.00"),previous.itemQualityPercentage());
    }

    @Test void companylessOrUnboundedRequestsHaveNoPriorPeriod() {
        var global=service.read(actor(),filters("ArrivalDate",
            LocalDate.of(2026,11,11),LocalDate.of(2026,11,20),null));
        assertNull(global.previousPeriod());
        assertNull(global.previousCompanyMetrics());
        var withoutDates=service.read(actor(),filters("ArrivalDate",null,null,COMPANY));
        assertNull(withoutDates.previousPeriod());
        assertEquals(3,withoutDates.companyMetrics().totalNotes());
    }

    @Test void writingBasisAndDeletedOrRevokedAccessRemainCorrect() {
        var writing=service.read(actor(),filters("WritingDate",
            LocalDate.of(2026,11,11),LocalDate.of(2026,11,20),COMPANY));
        assertEquals(0,writing.companyMetrics().totalNotes());
        jdbc.update("update auth.users set is_active=false where id=?",ADMIN);
        assertThrows(MasterDataForbiddenException.class,()->
            service.read(actor(),filters("ArrivalDate",null,null,COMPANY)));
        assertThrows(MasterDataForbiddenException.class,()->
            service.read(new UserProfile(ENTRY,"entry","Entry","DATA_ENTRY"),
                filters("ArrivalDate",null,null,COMPANY)));
    }

    @Test void priorCutoffShiftsByExactlyTheInclusivePeriodLength() {
        var filter=new DashboardSummaryCriteria(
            LocalDate.of(2026,11,11),LocalDate.of(2026,11,20),
            "ArrivalDate",COMPANY,null,null,null,null,
            LocalDate.of(2026,11,20));
        var result=service.read(actor(),filter);
        assertEquals(LocalDate.of(2026,11,10),result.previousPeriod().entryCutoffDate());
    }
}
