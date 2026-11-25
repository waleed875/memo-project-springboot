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

/** One DB snapshot for quality + timeliness, using the original SQL v3 facts. */
@Testcontainers
class DashboardPerformancePostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_performance_it")
            .withUsername("memo_it").withPassword("test_only");

    private final UUID adminRole=UUID.fromString("dc000000-0000-4000-8000-000000000001");
    private final UUID entryRole=UUID.fromString("dc000000-0000-4000-8000-000000000002");
    private final UUID admin=UUID.fromString("dc000000-0000-4000-8000-000000000003");
    private final UUID entry=UUID.fromString("dc000000-0000-4000-8000-000000000004");
    private final UUID company=UUID.fromString("dc000000-0000-4000-8000-000000000005");
    private final UUID emptyCompany=UUID.fromString("dc000000-0000-4000-8000-000000000006");
    private final UUID factory=UUID.fromString("dc000000-0000-4000-8000-000000000007");
    private final UUID line=UUID.fromString("dc000000-0000-4000-8000-000000000008");
    private final UUID type=UUID.fromString("dc000000-0000-4000-8000-000000000009");
    private final UUID field=UUID.fromString("dc000000-0000-4000-8000-000000000010");
    private final UUID lateClean=UUID.fromString("dc000000-0000-4000-8000-000000000011");
    private final UUID onTimeError=UUID.fromString("dc000000-0000-4000-8000-000000000012");
    private final UUID unreviewed=UUID.fromString("dc000000-0000-4000-8000-000000000013");
    private final UUID deleted=UUID.fromString("dc000000-0000-4000-8000-000000000014");
    private JdbcTemplate jdbc;
    private DashboardPerformanceReader reader;

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
            new ClassPathResource("postgres-dashboard-indicator-facts-v3.sql")).execute(ds);
        var proxy=new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcDashboardPerformanceReader(new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{DashboardPerformanceReader.class});
        proxy.afterPropertiesSet();
        reader=(DashboardPerformanceReader)proxy.getObject();

        jdbc.update("insert into auth.roles(id,code,name_ar) values (?,'ADMIN','Admin'),(?,'DATA_ENTRY','Entry')",adminRole,entryRole);
        jdbc.update("""
            insert into auth.users(id,username,password_hash,full_name,role_id)
            values (?,'admin','x','Admin',?),(?,'entry','x','Entry',?)
            """,admin,adminRole,entry,entryRole);
        jdbc.update("insert into core.companies(id,name) values (?,'A'),(?,'Empty')",company,emptyCompany);
        jdbc.update("insert into core.factories(id,company_id,name) values (?,?,'Factory')",factory,company);
        jdbc.update("insert into core.production_lines(id,factory_id,name) values (?,?,'Line')",line,factory);
        jdbc.update("insert into core.note_types(id,code,name_ar) values (?,'ENTRY','Entry')",type);
        jdbc.update("insert into core.note_fields(id,code,name_ar) values (?,'F1','Field 1')",field);
        jdbc.update("""
            insert into core.note_type_fields(id,note_type_id,field_id,sort_order)
            values (?,?,?,1)
            """,UUID.randomUUID(),type,field);
        note(lateClean,"APPROVED","2026-11-10","2026-11-16",false);
        note(onTimeError,"HAS_ERRORS","2026-11-11","2026-11-16",false);
        note(unreviewed,"NEEDS_REVIEW",null,"2026-11-17",false);
        note(deleted,"APPROVED","2026-11-10","2026-11-16",true);
        value(lateClean,1);value(onTimeError,2);value(deleted,2);
        // Missing row for unreviewed means effective 0 in the v3 view.
    }

    private void note(UUID id,String status,String writing,String arrival,boolean isDeleted) {
        jdbc.update("""
            insert into core.notes(id,company_id,factory_id,production_line_id,note_type_id,
                note_number,writing_date,arrival_at,status,created_by,is_deleted)
            values (?,?,?,?,?,'X',cast(? as date),cast(? as date),?,?,?)
            """,id,company,factory,line,type,writing,arrival,status,entry,isDeleted);
    }
    private void value(UUID id,int code) {
        jdbc.update("""
            insert into core.note_field_values(id,note_id,field_id,field_value)
            values (?,?,?,?)
            """,UUID.randomUUID(),id,field,code);
    }
    private DashboardSummaryCriteria filters(String dateBasis,LocalDate from,LocalDate to,String status) {
        return new DashboardSummaryCriteria(from,to,dateBasis,null,null,null,null,status,null);
    }

    @Test void companyTimeKpisKeepLegacyZeroQualityWhileFactoryLineRemainMeasured() {
        var result=reader.read(admin,filters("ArrivalDate",null,null,null));
        var c=result.timelinessByCompany().stream()
            .filter(x->x.dimensionId().equals(company)).findFirst().orElseThrow();
        assertEquals(3,c.totalNotes());
        assertEquals(2,c.eligibleCount());
        assertEquals(1,c.lateCount());
        assertEquals(1,c.onTimeCount());
        assertEquals(50.0,c.lateRate());
        assertEquals(0,c.sampleCount());
        assertEquals(0,c.correctCount());
        assertEquals(0,c.errorCount());
        assertEquals(0,c.qualityEligibleCount());
        assertNull(c.accuracyRate());
        assertEquals(factory,result.performanceByFactory().getFirst().dimensionId());
        assertEquals(line,result.performanceByLine().getFirst().dimensionId());
        assertEquals(3,result.performanceByFactory().getFirst().totalNotes());
        assertEquals(2,result.performanceByLine().getFirst().qualityEligibleCount());
    }

    @Test void zeroCountCompanyHasNullRatesAndNoPhantomSamples() {
        var r=reader.read(admin,filters("ArrivalDate",null,null,null));
        var empty=r.timelinessByCompany().stream()
            .filter(x->x.dimensionId().equals(emptyCompany)).findFirst().orElseThrow();
        assertEquals(0,empty.totalNotes());
        assertEquals(0,empty.sampleCount());
        assertNull(empty.lateRate());
        assertNull(empty.accuracyRate());
    }

    @Test void filtersPreserveWritingVsArrivalBasisAndWorkflowStatus() {
        var arrival=reader.read(admin,filters("ArrivalDate",
            LocalDate.of(2026,11,16),LocalDate.of(2026,11,16),null));
        assertEquals(2,arrival.performanceByFactory().getFirst().totalNotes());
        var writing=reader.read(admin,filters("WritingDate",
            LocalDate.of(2026,11,16),LocalDate.of(2026,11,16),null));
        assertTrue(writing.performanceByFactory().isEmpty());
        var approved=reader.read(admin,filters("ArrivalDate",null,null,"APPROVED"));
        assertEquals(1,approved.performanceByFactory().getFirst().totalNotes());
        assertEquals(1,approved.performanceByFactory().getFirst().correctCount());
        assertEquals(100.0,approved.performanceByFactory().getFirst().accuracyRate());
    }

    @Test void disabledAccountAndNonAdminCannotQueryAggregates() {
        assertThrows(MasterDataForbiddenException.class,()->
            reader.read(entry,filters(null,null,null,null)));
        jdbc.update("update auth.users set is_active=false where id=?",admin);
        assertThrows(MasterDataForbiddenException.class,()->
            reader.read(admin,filters(null,null,null,null)));
    }
}
