package io.github.waleed875.memo.modules.masterdata.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.TransactionDefinition;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verify actual PostgreSQL soft-delete, FK and same-transaction audit rollback.
 * The repository is wrapped in a Spring transactional proxy, as in production.
 */
@Testcontainers
class MasterDataWritePostgresIT {
    @Container
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
            .withDatabaseName("memo_master_write_it")
            .withUsername("memo_it")
            .withPassword("test_only_password");
    static final UUID ROLE = UUID.fromString("a11fe000-0000-4000-8000-000000000001");
    static final UUID USER = UUID.fromString("a11fe000-0000-4000-8000-000000000002");

    private static JdbcTemplate jdbc;
    private static io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteRepository writer;
    private static DriverManagerDataSource dataSource;

    @BeforeAll static void init() {
        dataSource = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("postgres-masterdata-schema.sql"))
            .execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        // Explicit transaction proxy ensures tests exercise @Transactional.
        var factory = new TransactionProxyFactoryBean();
        factory.setTarget(new JdbcMasterDataWriteRepository(jdbc));
        factory.setTransactionManager(new JdbcTransactionManager(dataSource));
        factory.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        factory.setProxyInterfaces(new Class[] {
            io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteRepository.class
        });
        factory.afterPropertiesSet();
        writer = (io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteRepository) factory.getObject();

        jdbc.update("insert into auth.roles(id, code, name_ar) values (?, 'ADMIN', 'مدير')", ROLE);
        jdbc.update("""
            insert into auth.users(id, username, password_hash, full_name, role_id)
            values (?, 'admin', 'test-only', 'مدير', ?)
            """, USER, ROLE);
    }

    @BeforeEach void clean() {
        jdbc.update("delete from audit.logs");
        jdbc.update("delete from core.notes");
        jdbc.update("delete from core.production_lines");
        jdbc.update("delete from core.factories");
        jdbc.update("delete from core.companies");
        jdbc.update("delete from system.app_settings");
        jdbc.update("insert into system.app_settings(key, value) values ('APP_MODE', 'SERVER')");
    }

    @Test void writeGateIsFailClosedUntilAllThreeConditionsPass() {
        assertThrows(MasterDataReadOnlyException.class,
            () -> new JdbcMasterDataWriteGate(jdbc, false, "SERVER").requireWritable());
        assertThrows(MasterDataReadOnlyException.class,
            () -> new JdbcMasterDataWriteGate(jdbc, true, "LOCAL").requireWritable());
        JdbcMasterDataWriteGate gate = new JdbcMasterDataWriteGate(jdbc, true, "SERVER");
        assertDoesNotThrow(gate::requireWritable);
        jdbc.update("update system.app_settings set value = 'LOCAL_READONLY' where key = 'APP_MODE'");
        assertThrows(MasterDataReadOnlyException.class, gate::requireWritable);
        jdbc.update("delete from system.app_settings");
        assertThrows(MasterDataReadOnlyException.class, gate::requireWritable);
    }

    @Test void companyCreateUpdateAndSoftDeleteProduceAudits() {
        UUID id = UUID.randomUUID();
        writer.saveCompany(new Company(id, "شركة أولى", true), USER, true);
        writer.saveCompany(new Company(id, "شركة ثانية", false), USER, false);
        writer.deleteCompany(id, USER);
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
            "select is_deleted from core.companies where id = ?", Boolean.class, id));
        assertEquals(3L, jdbc.queryForObject(
            "select count(*) from audit.logs where record_id = ?", Long.class, id));
        assertThrows(MasterDataOperationException.class,
            () -> writer.deleteCompany(id, USER));
    }

    @Test void companyWithFactoryCannotBeDeletedAndAuditDoesNotChange() {
        UUID company = UUID.randomUUID();
        UUID factory = UUID.randomUUID();
        writer.saveCompany(new Company(company, "الشركة", true), USER, true);
        Factory saved = writer.saveFactory(
            new Factory(factory, "المعمل", company, null, true), USER, true);
        assertEquals("الشركة", saved.companyName());
        long before = jdbc.queryForObject("select count(*) from audit.logs", Long.class);
        assertThrows(MasterDataOperationException.class,
            () -> writer.deleteCompany(company, USER));
        assertEquals(before, jdbc.queryForObject("select count(*) from audit.logs", Long.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "select is_deleted from core.companies where id = ?", Boolean.class, company));
    }

    @Test void productionLineAuditAndChildRelationshipsPreventUnsafeDeletion() {
        UUID company = UUID.randomUUID(), factory = UUID.randomUUID(), line = UUID.randomUUID();
        writer.saveCompany(new Company(company, "شركة", true), USER, true);
        writer.saveFactory(new Factory(factory, "معمل", company, null, true), USER, true);
        ProductionLine created = writer.saveProductionLine(
            new ProductionLine(line, factory, null, null, null, "خط", true), USER, true);
        assertEquals(factory, created.factoryId());
        assertEquals(company, created.companyId());
        writer.toggleProductionLine(line, false, USER);
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "select is_active from core.production_lines where id = ?", Boolean.class, line));
        assertThrows(MasterDataOperationException.class,
            () -> writer.deleteFactory(factory, USER));
        jdbc.update("""
            insert into core.notes(id, company_id, factory_id, production_line_id)
            values (?, ?, ?, ?)
            """, UUID.randomUUID(), company, factory, line);
        assertThrows(MasterDataOperationException.class,
            () -> writer.deleteProductionLine(line, USER));
        assertThrows(MasterDataOperationException.class,
            () -> writer.deleteCompany(company, USER));
    }

    @Test void auditInsertFailureRollsBackCompanyMutation() {
        UUID id = UUID.randomUUID();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> writer.saveCompany(new Company(id, "لا تدقيق", true),
                UUID.randomUUID(), true));
        assertEquals(0L, jdbc.queryForObject(
            "select count(*) from core.companies where id = ?", Long.class, id));
    }

    @Test void duplicateFactoryNameFailsWithoutExtraAudit() {
        UUID company = UUID.randomUUID();
        writer.saveCompany(new Company(company, "شركة", true), USER, true);
        writer.saveFactory(new Factory(UUID.randomUUID(), "معمل", company, null, true),
            USER, true);
        long before = jdbc.queryForObject("select count(*) from audit.logs", Long.class);
        assertThrows(MasterDataOperationException.class,
            () -> writer.saveFactory(
                new Factory(UUID.randomUUID(), "معمل", company, null, true),
                USER, true));
        assertEquals(before, jdbc.queryForObject("select count(*) from audit.logs", Long.class));
    }
}
