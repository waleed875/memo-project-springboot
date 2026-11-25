package io.github.waleed875.memo.modules.dashboard.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.dashboard.application.DashboardLookupsReader;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Four original dashboard lookup SQL projections against actual PostgreSQL 16,
 * with role revocation and inactive/deleted catalog rows.
 */
@Testcontainers
class DashboardLookupsPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_dashboard_lookups_it")
            .withUsername("memo_it").withPassword("test_only");

    private static final UUID ADMIN_ROLE = UUID.fromString("ad000000-0000-4000-8000-000000000001");
    private static final UUID ENTRY_ROLE = UUID.fromString("ad000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN = UUID.fromString("ad000000-0000-4000-8000-000000000003");
    private static final UUID ENTRY = UUID.fromString("ad000000-0000-4000-8000-000000000004");
    private static final UUID COMPANY = UUID.fromString("ad000000-0000-4000-8000-000000000005");
    private static final UUID INACTIVE_COMPANY = UUID.fromString("ad000000-0000-4000-8000-000000000006");
    private static final UUID FACTORY = UUID.fromString("ad000000-0000-4000-8000-000000000007");
    private static final UUID LINE = UUID.fromString("ad000000-0000-4000-8000-000000000008");
    private static final UUID TYPE = UUID.fromString("ad000000-0000-4000-8000-000000000009");

    private JdbcTemplate jdbc;
    private DashboardLookupsReader reader;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("drop schema if exists audit cascade");
        jdbc.execute("drop schema if exists core cascade");
        jdbc.execute("drop schema if exists auth cascade");
        jdbc.execute("drop schema if exists system cascade");
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql")).execute(ds);

        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcDashboardLookupsReader(jdbc));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{DashboardLookupsReader.class});
        proxy.afterPropertiesSet();
        reader = (DashboardLookupsReader) proxy.getObject();

        jdbc.update("""
            insert into auth.roles(id, code, name_ar) values
            (?, 'ADMIN', 'Admin'), (?, 'DATA_ENTRY', 'Entry')
            """, ADMIN_ROLE, ENTRY_ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'admin', 'x', 'Admin', ?), (?, 'entry', 'x', 'Entry', ?)
            """, ADMIN, ADMIN_ROLE, ENTRY, ENTRY_ROLE);
        jdbc.update("""
            insert into core.companies(id, name, is_active, is_deleted)
            values (?, 'Active Company', true, false),
                   (?, 'Hidden Company', false, false)
            """, COMPANY, INACTIVE_COMPANY);
        jdbc.update("""
            insert into core.factories(id, company_id, name, is_active, is_deleted)
            values (?, ?, 'Factory', true, false)
            """, FACTORY, COMPANY);
        jdbc.update("""
            insert into core.production_lines(id, factory_id, name, is_active, is_deleted)
            values (?, ?, 'Production Line', true, false)
            """, LINE, FACTORY);
        jdbc.update("""
            insert into core.note_types(id, code, name_ar, is_active, is_deleted)
            values (?, 'ENTRY', 'نوع', true, false)
            """, TYPE);
    }

    @Test void readsAllFourProjectionsWithExactForeignKeysAndFilters() {
        var data = reader.read(ADMIN);
        assertEquals(1, data.companies().size());
        assertEquals(COMPANY, data.companies().getFirst().id());
        assertEquals(1, data.factories().size());
        assertEquals(COMPANY, data.factories().getFirst().companyId());
        assertEquals(1, data.productionLines().size());
        assertEquals(FACTORY, data.productionLines().getFirst().factoryId());
        assertEquals(1, data.noteTypes().size());
        assertEquals(TYPE, data.noteTypes().getFirst().id());
        assertEquals("ENTRY", data.noteTypes().getFirst().code());
        assertEquals("نوع", data.noteTypes().getFirst().nameAr());
    }

    @Test void inactiveDeletedEntriesAreNotExposed() {
        jdbc.update("update core.factories set is_deleted = true where id = ?", FACTORY);
        jdbc.update("update core.production_lines set is_active = false where id = ?", LINE);
        jdbc.update("update core.note_types set is_deleted = true where id = ?", TYPE);
        var data = reader.read(ADMIN);
        assertEquals(1, data.companies().size());
        assertTrue(data.factories().isEmpty());
        assertTrue(data.productionLines().isEmpty());
        assertTrue(data.noteTypes().isEmpty());
    }

    @Test void revokedRoleAndDisabledOrDeletedAccountFailClosed() {
        assertThrows(MasterDataForbiddenException.class, () -> reader.read(ENTRY));
        assertThrows(MasterDataForbiddenException.class, () -> reader.read(UUID.randomUUID()));
        jdbc.update("update auth.users set role_id = ? where id = ?", ENTRY_ROLE, ADMIN);
        assertThrows(MasterDataForbiddenException.class, () -> reader.read(ADMIN));
        jdbc.update("update auth.users set role_id = ?, is_active = false where id = ?",
            ADMIN_ROLE, ADMIN);
        assertThrows(MasterDataForbiddenException.class, () -> reader.read(ADMIN));
        jdbc.update("update auth.users set is_active = true, is_deleted = true where id = ?", ADMIN);
        assertThrows(MasterDataForbiddenException.class, () -> reader.read(ADMIN));
        jdbc.update("update auth.users set is_deleted = false where id = ?", ADMIN);
        jdbc.update("update auth.roles set is_active = false where id = ?", ADMIN_ROLE);
        assertThrows(MasterDataForbiddenException.class, () -> reader.read(ADMIN));
    }

    @Test void rowOrderingMatchesOriginalSqlAndRetainsCrossDimensionRelations() {
        var otherCompany = UUID.randomUUID();
        var otherFactory = UUID.randomUUID();
        var otherLine = UUID.randomUUID();
        var otherType = UUID.randomUUID();
        jdbc.update("insert into core.companies(id, name) values (?, 'A Company')", otherCompany);
        jdbc.update("insert into core.factories(id, company_id, name) values (?, ?, 'A Factory')",
            otherFactory, otherCompany);
        jdbc.update("insert into core.production_lines(id, factory_id, name) values (?, ?, 'A Line')",
            otherLine, otherFactory);
        jdbc.update("insert into core.note_types(id, code, name_ar) values (?, 'A_TYPE', 'ألف')", otherType);
        var data = reader.read(ADMIN);
        assertEquals(otherCompany, data.companies().getFirst().id());
        assertEquals(otherFactory, data.factories().getFirst().id());
        assertEquals(otherLine, data.productionLines().getFirst().id());
        assertEquals(otherType, data.noteTypes().getFirst().id());
    }
}
