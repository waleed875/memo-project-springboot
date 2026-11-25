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

/** Executes the migration-060 view against real PostgreSQL and tests quality denominators. */
@Testcontainers
class DashboardQualityPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_quality_it").withUsername("memo_it").withPassword("test_only");

    private final UUID adminRole=UUID.fromString("ce000000-0000-4000-8000-000000000001");
    private final UUID entryRole=UUID.fromString("ce000000-0000-4000-8000-000000000002");
    private final UUID admin=UUID.fromString("ce000000-0000-4000-8000-000000000003");
    private final UUID entry=UUID.fromString("ce000000-0000-4000-8000-000000000004");
    private final UUID company=UUID.fromString("ce000000-0000-4000-8000-000000000005");
    private final UUID factory=UUID.fromString("ce000000-0000-4000-8000-000000000006");
    private final UUID line=UUID.fromString("ce000000-0000-4000-8000-000000000007");
    private final UUID type=UUID.fromString("ce000000-0000-4000-8000-000000000008");
    private final UUID[] fields={
        UUID.fromString("ce000000-0000-4000-8000-000000000011"),
        UUID.fromString("ce000000-0000-4000-8000-000000000012"),
        UUID.fromString("ce000000-0000-4000-8000-000000000013"),
        UUID.fromString("ce000000-0000-4000-8000-000000000014")};
    private final UUID clean=UUID.fromString("ce000000-0000-4000-8000-000000000021");
    private final UUID errors=UUID.fromString("ce000000-0000-4000-8000-000000000022");
    private final UUID incomplete=UUID.fromString("ce000000-0000-4000-8000-000000000023");
    private final UUID notApplicable=UUID.fromString("ce000000-0000-4000-8000-000000000024");
    private final UUID deleted=UUID.fromString("ce000000-0000-4000-8000-000000000025");
    private JdbcTemplate jdbc;
    private DashboardQualityReader reader;

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
        proxy.setTarget(new JdbcDashboardQualityReader(new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{DashboardQualityReader.class});
        proxy.afterPropertiesSet();
        reader=(DashboardQualityReader)proxy.getObject();

        jdbc.update("insert into auth.roles(id,code,name_ar) values (?,'ADMIN','Admin'),(?,'DATA_ENTRY','Entry')",
            adminRole,entryRole);
        jdbc.update("""
            insert into auth.users(id,username,password_hash,full_name,role_id)
            values (?,'admin','x','Admin',?),(?,'entry','x','Entry',?)
            """,admin,adminRole,entry,entryRole);
        jdbc.update("insert into core.companies(id,name) values (?,'Company')",company);
        jdbc.update("insert into core.factories(id,company_id,name) values (?,?,'Factory')",factory,company);
        jdbc.update("insert into core.production_lines(id,factory_id,name) values (?,?,'Line')",line,factory);
        jdbc.update("insert into core.note_types(id,code,name_ar) values (?,'ENTRY','Entry')",type);
        for(int i=0;i<fields.length;i++) {
            jdbc.update("""
                insert into core.note_fields(id,code,name_ar) values (?,?,?)
                """,fields[i],"F"+i,"Field "+i);
            jdbc.update("""
                insert into core.note_type_fields(id,note_type_id,field_id,sort_order)
                values (?,?,?,?)
                """,UUID.randomUUID(),type,fields[i],i);
        }

        note(clean,"APPROVED","2026-10-31","2026-11-05",false);
        note(errors,"HAS_ERRORS","2026-11-20","2026-11-25",false);
        note(incomplete,"NEEDS_REVIEW","2026-11-20","2026-11-25",false);
        note(notApplicable,"APPROVED","2026-11-20","2026-11-25",false);
        note(deleted,"APPROVED","2026-11-20","2026-11-25",true);

        values(clean,new Integer[]{1,1,6,6}); // clean, despite N/A values
        values(errors,new Integer[]{2,null,6,6}); // errors win over unreviewed
        values(incomplete,new Integer[]{0,6,6,6}); // cannot count as clean
        values(notApplicable,new Integer[]{6,6,6,6}); // not a clean sample
        values(deleted,new Integer[]{2,2,2,2}); // excluded by view
    }

    private void note(UUID id,String status,String writing,String arrival,boolean isDeleted) {
        jdbc.update("""
            insert into core.notes(id,company_id,factory_id,production_line_id,
              note_type_id,note_number,writing_date,arrival_at,status,created_by,is_deleted)
            values (?,?,?,?,?,'X',cast(? as date),cast(? as date),?,?,?)
            """,id,company,factory,line,type,writing,arrival,status,entry,isDeleted);
    }

    private void values(UUID id,Integer[] values) {
        for(int i=0;i<values.length;i++) {
            if(values[i]==null) continue; // missing value means effective 0 in v3
            jdbc.update("""
                insert into core.note_field_values(id,note_id,field_id,field_value)
                values (?,?,?,?)
                """,UUID.randomUUID(),id,fields[i],values[i]);
        }
    }

    private DashboardSummaryCriteria criteria(String basis,LocalDate from,LocalDate to,
                                              String status) {
        return new DashboardSummaryCriteria(from,to,basis,null,null,null,null,status,null);
    }

    @Test void theSourceViewClassifiesNullZeroErrorCorrectAndNotApplicable() {
        assertEquals(16L,jdbc.queryForObject(
            "select count(*) from reporting.vw_note_indicator_facts_v3",Long.class));
        assertEquals(2L,jdbc.queryForObject(
            "select count(*) from reporting.vw_note_indicator_facts_v3 where is_unreviewed",Long.class));
        assertEquals(1L,jdbc.queryForObject(
            "select count(*) from reporting.vw_note_indicator_facts_v3 where is_error",Long.class));
        assertEquals(2L,jdbc.queryForObject(
            "select count(*) from reporting.vw_note_indicator_facts_v3 where is_correct",Long.class));
        assertEquals(3L,jdbc.queryForObject(
            "select count(*) from reporting.vw_note_indicator_facts_v3 where is_applicable_reviewed",Long.class));
    }

    @Test void companyAndDimensionQualityMatchCSharpDenominators() {
        var result=reader.read(admin,criteria("ArrivalDate",null,null,null),1);
        assertEquals(3L,result.fieldQuality().totalFieldCount());
        assertEquals(2L,result.fieldQuality().correctFieldCount());
        assertEquals(1L,result.fieldQuality().errorFieldCount());
        assertEquals(2L,result.fieldQuality().unknownFieldCount());
        assertEquals(66.67,result.fieldQuality().accuracyRate());
        var q=result.qualityByCompany().getFirst();
        assertEquals(company,q.companyId());
        assertEquals(4L,q.sampleSize());
        assertEquals(1L,q.correctCount());
        assertEquals(1L,q.errorCount());
        assertEquals(2L,q.qualityEligibleCount());
        assertEquals(1L,q.allNotApplicableCount());
        assertEquals(50.0,q.accuracyRate());
        assertEquals(50.0,q.errorRate());
        assertEquals(2L,q.unknownFieldCount());
        assertEquals(factory,result.qualityByFactory().getFirst().dimensionId());
        assertEquals(50.0,result.qualityByFactory().getFirst().accuracyRate());
        assertEquals(line,result.qualityByLine().getFirst().dimensionId());
        assertEquals(50.0,result.qualityByLine().getFirst().accuracyRate());
        assertEquals(0,result.indicatorStateDistribution().getFirst().code());
        assertEquals(2L,result.indicatorStateDistribution().getFirst().value());
        assertEquals(fields[0],result.topErrorFields().getFirst().fieldId());
        assertEquals(2L,result.topErrorFields().getFirst().sampleSize());
        assertEquals(1L,result.topErrorFields().getFirst().errorCount());
        assertEquals(50.0,result.topErrorFields().getFirst().errorRate());
    }

    @Test void minSampleAndDateBasisAndStatusApplyToAllFacts() {
        var noTop=reader.read(admin,criteria("ArrivalDate",null,null,null),3);
        assertTrue(noTop.topErrorFields().isEmpty());
        var october=reader.read(admin,criteria("WritingDate",
            LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),null),1);
        assertEquals(2L,october.fieldQuality().totalFieldCount());
        assertEquals(100.0,october.fieldQuality().accuracyRate());
        var arrival=reader.read(admin,criteria("ArrivalDate",
            LocalDate.of(2026,11,25),LocalDate.of(2026,11,25),null),1);
        assertEquals(1L,arrival.fieldQuality().totalFieldCount());
        assertEquals(0.0,arrival.fieldQuality().accuracyRate());
        var approved=reader.read(admin,criteria("ArrivalDate",null,null,"APPROVED"),1);
        assertEquals(2L,approved.qualityByCompany().getFirst().sampleSize());
        assertEquals(2L,approved.fieldQuality().correctFieldCount());
        assertEquals(0L,approved.fieldQuality().errorFieldCount());
    }

    @Test void accountOrRoleRevocationIsEffectiveInReadTransaction() {
        assertThrows(MasterDataForbiddenException.class,
            ()->reader.read(entry,criteria(null,null,null,null),1));
        jdbc.update("update auth.users set is_active = false where id = ?",admin);
        assertThrows(MasterDataForbiddenException.class,
            ()->reader.read(admin,criteria(null,null,null,null),1));
        jdbc.update("update auth.users set is_active = true where id = ?",admin);
        jdbc.update("update auth.roles set is_active = false where id = ?",adminRole);
        assertThrows(MasterDataForbiddenException.class,
            ()->reader.read(admin,criteria(null,null,null,null),1));
    }

    @Test void emptyPeriodYieldsNullRatesAndNoImputedZero() {
        var empty=reader.read(admin,criteria("ArrivalDate",
            LocalDate.of(2027,1,1),LocalDate.of(2027,1,2),null),1);
        assertEquals(0L,empty.fieldQuality().totalFieldCount());
        assertNull(empty.fieldQuality().accuracyRate());
        assertTrue(empty.qualityByCompany().isEmpty());
        assertTrue(empty.topErrorFields().isEmpty());
    }
}
