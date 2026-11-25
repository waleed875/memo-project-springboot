package io.github.waleed875.memo.modules.dashboard.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.dashboard.application.DashboardDistributionsReader;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
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

/** PostgreSQL 16 golden fixtures for the read-only chart slice. */
@Testcontainers
class DashboardDistributionsPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_dashboard_distributions_it")
            .withUsername("memo_it").withPassword("test_only");
    private final UUID adminRole = UUID.fromString("bc000000-0000-4000-8000-000000000001");
    private final UUID entryRole = UUID.fromString("bc000000-0000-4000-8000-000000000002");
    private final UUID admin = UUID.fromString("bc000000-0000-4000-8000-000000000003");
    private final UUID entry = UUID.fromString("bc000000-0000-4000-8000-000000000004");
    private final UUID company = UUID.fromString("bc000000-0000-4000-8000-000000000005");
    private final UUID other = UUID.fromString("bc000000-0000-4000-8000-000000000006");
    private final UUID factory = UUID.fromString("bc000000-0000-4000-8000-000000000007");
    private final UUID line = UUID.fromString("bc000000-0000-4000-8000-000000000008");
    private final UUID type = UUID.fromString("bc000000-0000-4000-8000-000000000009");
    JdbcTemplate jdbc;
    DashboardDistributionsReader reader;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop schema if exists audit cascade");
        jdbc.execute("drop schema if exists core cascade");
        jdbc.execute("drop schema if exists auth cascade");
        jdbc.execute("drop schema if exists system cascade");
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql")).execute(ds);
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcDashboardDistributionsReader(new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{DashboardDistributionsReader.class});
        proxy.afterPropertiesSet();
        reader=(DashboardDistributionsReader)proxy.getObject();

        jdbc.update("insert into auth.roles(id,code,name_ar) values (?,'ADMIN','Admin'),(?,'DATA_ENTRY','Entry')",adminRole,entryRole);
        jdbc.update("""
            insert into auth.users(id,username,password_hash,full_name,role_id)
            values (?,'admin','x','Admin',?),(?,'entry','x','Entry',?)
            """,admin,adminRole,entry,entryRole);
        jdbc.update("insert into core.companies(id,name) values (?,'Alpha'),(?,'Beta')",company,other);
        jdbc.update("insert into core.factories(id,company_id,name) values (?,?,'Factory')",factory,company);
        jdbc.update("insert into core.production_lines(id,factory_id,name) values (?,?,'Line')",line,factory);
        jdbc.update("insert into core.note_types(id,code,name_ar) values (?,'ENTRY','Entry')",type);
        note("APPROVED","2026-10-30","2026-11-05",company,true,false);
        note("NEEDS_REVIEW","2026-11-20","2026-11-25",company,true,false);
        note("DRAFT",null,"2026-11-25",other,false,false);
        note("APPROVED","2026-11-01","2026-11-11",company,true,true);
    }

    private void note(String status,String writing,String arrival,UUID companyId,
                      boolean linked,boolean deleted) {
        jdbc.update("""
            insert into core.notes(id,company_id,factory_id,production_line_id,
                note_type_id,note_number,writing_date,arrival_at,status,created_by,is_deleted)
            values (?, ?, ?, ?, ?, 'X', cast(? as date),cast(? as date),?,?,?)
            """,UUID.randomUUID(),companyId,linked?factory:null,linked?line:null,
            type,writing,arrival,status,entry,deleted);
    }

    private DashboardSummaryCriteria f(String basis,LocalDate from,LocalDate to,
                                       UUID companyId,String status) {
        return new DashboardSummaryCriteria(from,to,basis,companyId,null,null,null,status,null);
    }

    @Test void groupsMonthsStatusesAndDimensionsWithLegacyDateRules() {
        var result=reader.read(admin,f("ArrivalDate",null,null,null,null));
        assertEquals(1,result.monthlyActivity().size());
        assertEquals("2026-11",result.monthlyActivity().getFirst().month());
        assertEquals("نوفمبر 2026",result.monthlyActivity().getFirst().monthLabel());
        assertEquals(3,result.monthlyActivity().getFirst().count());
        assertEquals(3,result.statusDistribution().stream().mapToLong(
            x->x.count()).sum());
        assertEquals("تحتاج مراجعة",result.statusDistribution().stream()
            .filter(x->x.status().equals("NEEDS_REVIEW")).findFirst().orElseThrow().statusLabel());
        assertEquals(company,result.companies().getFirst().id());
        assertEquals(2,result.companies().getFirst().noteCount());
        assertEquals(2,result.timelinessByCompany().getFirst().totalNotes());
        assertEquals(2,result.timelinessByCompany().getFirst().eligibleCount());
        assertEquals(1,result.timelinessByCompany().getFirst().lateCount());
        assertEquals(1,result.timelinessByCompany().getFirst().onTimeCount());
        assertEquals(50.0,result.timelinessByCompany().getFirst().lateRate());
        assertEquals(1,result.timelinessByFactory().size());
        assertEquals(factory,result.timelinessByFactory().getFirst().dimensionId());
        assertEquals(1,result.timelinessByLine().size());
        assertEquals(line,result.timelinessByLine().getFirst().dimensionId());
    }

    @Test void selectedDateBasisIsEffectiveAndEmptyMonthsAreFilled() {
        var dates=reader.read(admin,f("ArrivalDate",LocalDate.of(2026,10,1),
            LocalDate.of(2026,12,31),null,null));
        assertEquals(3,dates.monthlyActivity().size());
        assertEquals(0,dates.monthlyActivity().get(0).count());
        assertEquals(3,dates.monthlyActivity().get(1).count());
        assertEquals(0,dates.monthlyActivity().get(2).count());

        var writing=reader.read(admin,f("WritingDate",LocalDate.of(2026,10,1),
            LocalDate.of(2026,12,31),null,null));
        assertEquals(1,writing.monthlyActivity().get(0).count());
        assertEquals(1,writing.monthlyActivity().get(1).count());
        assertEquals(0,writing.monthlyActivity().get(2).count());
        assertEquals(2,writing.statusDistribution().stream().mapToLong(x->x.count()).sum());
    }

    @Test void statusAndCompanyFiltersApplyToEveryAggregateAndExcludeDeletedNotes() {
        var filtered=reader.read(admin,f("ArrivalDate",null,null,company,"APPROVED"));
        assertEquals(1,filtered.statusDistribution().size());
        assertEquals("APPROVED",filtered.statusDistribution().getFirst().status());
        assertEquals(1,filtered.companies().size());
        assertEquals(1,filtered.companies().getFirst().noteCount());
        assertEquals(1,filtered.timelinessByFactory().getFirst().totalNotes());
        assertEquals(1,filtered.timelinessByLine().getFirst().lateCount());
    }

    @Test void inactiveRoleOrAccountIsRejectedAtDatabaseReadTime() {
        assertThrows(MasterDataForbiddenException.class,
            ()->reader.read(entry,f(null,null,null,null,null)));
        jdbc.update("update auth.users set is_active=false where id=?",admin);
        assertThrows(MasterDataForbiddenException.class,
            ()->reader.read(admin,f(null,null,null,null,null)));
    }

    @Test void emptyResultsRetainZeroRowsAndNullRates() {
        var empty=reader.read(admin,f("ArrivalDate",
            LocalDate.of(2027,1,1),LocalDate.of(2027,1,31),null,null));
        assertEquals(1,empty.monthlyActivity().size());
        assertEquals(0,empty.monthlyActivity().getFirst().count());
        assertTrue(empty.statusDistribution().isEmpty());
        assertTrue(empty.timelinessByFactory().isEmpty());
        assertNull(empty.timelinessByCompany().getFirst().lateRate());
    }
}
