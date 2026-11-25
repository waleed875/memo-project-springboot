package io.github.waleed875.memo.modules.dashboard.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.dashboard.application.DashboardFlowReader;
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

/** PostgreSQL 16 golden tests: independent arrival/approval axes and missing metadata. */
@Testcontainers
class DashboardFlowPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_dashboard_flow_it")
            .withUsername("memo_it").withPassword("test_only");

    private final UUID adminRole=UUID.fromString("df000000-0000-4000-8000-000000000001");
    private final UUID entryRole=UUID.fromString("df000000-0000-4000-8000-000000000002");
    private final UUID admin=UUID.fromString("df000000-0000-4000-8000-000000000003");
    private final UUID entry=UUID.fromString("df000000-0000-4000-8000-000000000004");
    private final UUID company=UUID.fromString("df000000-0000-4000-8000-000000000005");
    private final UUID factory=UUID.fromString("df000000-0000-4000-8000-000000000006");
    private final UUID line=UUID.fromString("df000000-0000-4000-8000-000000000007");
    private final UUID type=UUID.fromString("df000000-0000-4000-8000-000000000008");
    private JdbcTemplate jdbc;
    private DashboardFlowReader reader;

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
            new ClassPathResource("postgres-notes-detail-schema.sql")).execute(ds);
        var proxy=new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcDashboardFlowReader(new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{DashboardFlowReader.class});
        proxy.afterPropertiesSet();
        reader=(DashboardFlowReader)proxy.getObject();

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

        // Approval date and arrival date deliberately fall into DIFFERENT months.
        note("2026-10-29","2026-11-03","2026-11-26",true,true,true,false);
        note(null,"2026-11-14",null,false,false,false,false);
        note("2026-11-10","2026-11-16","2026-11-18",true,false,false,false);
        note(null,"2026-11-17",null,false,false,false,true);
    }

    private void note(
        String writing, String arrival, String approved,
        boolean hasFactory, boolean hasLine, boolean createdBeforeCutoff,
        boolean deleted
    ) {
        jdbc.update("""
            insert into core.notes(
                id,company_id,factory_id,production_line_id,note_type_id,
                note_number,writing_date,arrival_at,approved_at,status,
                created_by,is_deleted,created_at)
            values (?,?,?,?,?,'N',cast(? as date),cast(? as date),
                    cast(? as timestamp),'APPROVED',?,?,cast(? as timestamp))
            """, UUID.randomUUID(),company,hasFactory?factory:null,
            hasLine?line:null,type,writing,arrival,approved,entry,deleted,
            createdBeforeCutoff?"2026-10-31 08:00:00":"2026-11-14 08:00:00");
    }

    private DashboardSummaryCriteria filters(
        String basis,LocalDate from,LocalDate to,LocalDate cutoff
    ) {
        return new DashboardSummaryCriteria(from,to,basis,null,null,null,null,null,cutoff);
    }

    @Test void eventMonthsUseArrivalAndApprovalIndependently() {
        var result=reader.read(admin,filters("WritingDate",null,null,null));
        assertEquals(1,result.incomingVsApproved().size());
        assertEquals("2026-11",result.incomingVsApproved().getFirst().periodKey());
        assertEquals(3L,result.incomingVsApproved().getFirst().incoming());
        assertEquals(2L,result.incomingVsApproved().getFirst().approved());
        
    }

    @Test void completenessCountsMissingColumnsAndFiltersByWritingBasis() {
        var result=reader.read(admin,filters("ArrivalDate",null,null,null));
        assertEquals(3,result.dataCompleteness().size());
        assertEquals(1L,result.dataCompleteness().get(0).value());
        assertEquals(1L,result.dataCompleteness().get(1).value());
        assertEquals(2L,result.dataCompleteness().get(2).value());
        var october=reader.read(admin,filters("WritingDate",
            LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),null));
        assertEquals(0L,october.dataCompleteness().get(0).value());
        assertEquals(0L,october.dataCompleteness().get(1).value());
    }

    @Test void entryCutoffUsesArrivalDateAfterOriginalBuildSnapshotSql() {
        var filtered=reader.read(admin,filters("ArrivalDate",null,null,
            LocalDate.of(2026,10,31)));
        assertTrue(filtered.incomingVsApproved().isEmpty());
        assertEquals(0L,filtered.dataCompleteness().get(0).value());
    }

    @Test void writingBasisScopesIncomingButArrivalStillGroupsItsMonth() {
        var result=reader.read(admin,filters("WritingDate",
            LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),null));
        assertEquals(1,result.incomingVsApproved().size());
        assertEquals("2026-11",result.incomingVsApproved().getFirst().periodKey());
        assertEquals(1L,result.incomingVsApproved().getFirst().incoming());
        assertEquals(2L,result.incomingVsApproved().getFirst().approved());
    }

    @Test void emptyWindowProducesZeroCompletenessAndNoEventMonths() {
        var empty=reader.read(admin,filters("ArrivalDate",
            LocalDate.of(2027,1,1),LocalDate.of(2027,2,28),null));
        assertTrue(empty.incomingVsApproved().isEmpty());
        assertEquals(3,empty.dataCompleteness().size());
        assertEquals(0L,empty.dataCompleteness().getFirst().value());
    }

    @Test void currentRoleRevocationAndNonAdminAreRejected() {
        assertThrows(MasterDataForbiddenException.class,
            ()->reader.read(entry,filters(null,null,null,null)));
        jdbc.update("update auth.users set is_active=false where id=?",admin);
        assertThrows(MasterDataForbiddenException.class,
            ()->reader.read(admin,filters(null,null,null,null)));
    }
}
