package io.github.waleed875.memo.modules.masterdata.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.masterdata.domain.Company;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verify query compatibility on a real ephemeral PostgreSQL 16 container. */
@Testcontainers
class MasterDataPostgresIT {
    @Container
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_master_it")
            .withUsername("memo_it")
            .withPassword("test_only_password");

    private static JdbcMasterDataReader reader;
    private static final UUID A = UUID.fromString("56e83c55-439e-4f8d-b92f-923bbc5ecb01");
    private static final UUID B = UUID.fromString("56e83c55-439e-4f8d-b92f-923bbc5ecb02");
    private static final UUID DELETED = UUID.fromString("56e83c55-439e-4f8d-b92f-923bbc5ecb03");
    private static final UUID FACTORY_A = UUID.fromString("56e83c55-439e-4f8d-b92f-923bbc5ecb04");
    private static final UUID FACTORY_B = UUID.fromString("56e83c55-439e-4f8d-b92f-923bbc5ecb05");
    private static final UUID LINE = UUID.fromString("56e83c55-439e-4f8d-b92f-923bbc5ecb06");

    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("postgres-masterdata-schema.sql"))
            .execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("""
            insert into core.companies(id, name, is_active, is_deleted)
            values (?, 'شركة أ', true, false),
                   (?, 'شركة ب', false, false),
                   (?, 'شركة محذوفة', true, true)
            """, A, B, DELETED);
        jdbc.update("""
            insert into core.factories(id, name, company_id, is_active, is_deleted)
            values (?, 'المعمل أ', ?, true, false),
                   (?, 'المعمل ب', ?, false, false)
            """, FACTORY_A, A, FACTORY_B, B);
        jdbc.update("""
            insert into core.production_lines(id, factory_id, name, is_active, is_deleted)
            values (?, ?, 'خط ١', false, false)
            """, LINE, FACTORY_A);
        reader = new JdbcMasterDataReader(jdbc);
    }

    @Test void listIncludesInactiveButExcludesSoftDeletedCompanies() {
        var companies = reader.companies();
        assertEquals(2, companies.size());
        assertTrue(companies.stream().anyMatch(c -> c.id().equals(A)));
        assertTrue(companies.stream().anyMatch(c -> c.id().equals(B) && !c.isActive()));
        assertFalse(companies.stream().anyMatch(c -> c.id().equals(DELETED)));
        assertTrue(reader.company(DELETED).isEmpty());
        assertEquals("شركة أ", reader.company(A).orElseThrow().name());
    }

    @Test void factoryReadsRespectCompanyFilterAndLegacyCompanyNameJoin() {
        var all = reader.factories(null);
        assertEquals(2, all.size());
        var filtered = reader.factories(A);
        assertEquals(1, filtered.size());
        assertEquals("شركة أ", filtered.get(0).companyName());
        assertEquals(FACTORY_A, filtered.get(0).id());
        assertEquals(0, reader.factories(DELETED).size());
    }

    @Test void productionLineReadsIncludeInactiveAndRespectFactoryFilter() {
        var all = reader.productionLines(null);
        assertEquals(1, all.size());
        assertEquals(LINE, all.get(0).id());
        assertEquals("المعمل أ", all.get(0).factoryName());
        assertEquals("شركة أ", all.get(0).companyName());
        assertFalse(all.get(0).isActive());
        assertEquals(1, reader.productionLines(FACTORY_A).size());
        assertTrue(reader.productionLines(FACTORY_B).isEmpty());
    }
}
