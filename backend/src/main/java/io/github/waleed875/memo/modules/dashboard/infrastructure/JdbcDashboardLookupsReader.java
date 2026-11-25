package io.github.waleed875.memo.modules.dashboard.infrastructure;

import io.github.waleed875.memo.modules.dashboard.application.DashboardLookups;
import io.github.waleed875.memo.modules.dashboard.application.DashboardLookupsReader;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQL and four projection shapes match DashboardQueryService.LookupsSql in
 * the pinned C# repository. Repeatable read avoids mixed catalog snapshots.
 * This class intentionally is not final: Spring must proxy @Transactional.
 */
@Repository
public class JdbcDashboardLookupsReader implements DashboardLookupsReader {
    private final JdbcTemplate jdbc;

    public JdbcDashboardLookupsReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardLookups read(UUID actorId) {
        Boolean admin = jdbc.queryForObject("""
            select exists (
              select 1 from auth.users u
              join auth.roles r on r.id = u.role_id
              where u.id = ? and u.is_active and not u.is_deleted
                and r.is_active and r.code = 'ADMIN'
            )
            """, Boolean.class, actorId);
        if (!Boolean.TRUE.equals(admin)) throw new MasterDataForbiddenException();

        var companies = jdbc.query("""
            select id, name from core.companies
            where is_active = true and is_deleted = false
            order by name
            """, (rs, row) -> new DashboardLookups.CompanyLookup(
                rs.getObject("id", UUID.class), rs.getString("name")));

        var factories = jdbc.query("""
            select id, company_id, name from core.factories
            where is_active = true and is_deleted = false
            order by name
            """, (rs, row) -> new DashboardLookups.FactoryLookup(
                rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name")));

        var productionLines = jdbc.query("""
            select id, factory_id, name from core.production_lines
            where is_active = true and is_deleted = false
            order by name
            """, (rs, row) -> new DashboardLookups.ProductionLineLookup(
                rs.getObject("id", UUID.class), rs.getObject("factory_id", UUID.class),
                rs.getString("name")));

        var noteTypes = jdbc.query("""
            select id, code, name_ar from core.note_types
            where is_active = true and is_deleted = false
            order by name_ar
            """, (rs, row) -> new DashboardLookups.NoteTypeLookup(
                rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name_ar")));

        return new DashboardLookups(companies, factories, productionLines, noteTypes);
    }
}
