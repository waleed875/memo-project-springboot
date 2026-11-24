package io.github.waleed875.memo.modules.masterdata.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataReader;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only port using the exact joins, soft-deletion filters and sorting
 * of legacy CompanyService/FactoryService/ProductionLineService.
 */
@Repository
public class JdbcMasterDataReader implements MasterDataReader {
    private static final String COMPANY_SELECT = """
        select id, name, is_active
        from core.companies
        where is_deleted = false
        """;

    private static final String FACTORY_SELECT = """
        select f.id, f.name, f.company_id, c.name as company_name, f.is_active
        from core.factories f
        left join core.companies c on c.id = f.company_id
        where not f.is_deleted
        """;

    private static final String LINE_SELECT = """
        select pl.id, pl.factory_id, f.name as factory_name,
               f.company_id, c.name as company_name, pl.name, pl.is_active
        from core.production_lines pl
        join core.factories f on f.id = pl.factory_id
        left join core.companies c on c.id = f.company_id
        where not pl.is_deleted
        """;

    private final JdbcTemplate jdbc;

    public JdbcMasterDataReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Company> companies() {
        return jdbc.query(COMPANY_SELECT + " order by name", (rs, row) ->
            new Company(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getBoolean("is_active")));
    }

    @Override
    public Optional<Company> company(UUID id) {
        return jdbc.query(COMPANY_SELECT + " and id = ?", (rs, row) ->
            new Company(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getBoolean("is_active")), id
        ).stream().findFirst();
    }

    @Override
    public List<Factory> factories(UUID companyId) {
        String sql = FACTORY_SELECT +
            (companyId == null ? "" : " and f.company_id = ?") +
            " order by c.name, f.name";
        return companyId == null ? jdbc.query(sql, this::factory) :
            jdbc.query(sql, this::factory, companyId);
    }

    private Factory factory(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Factory(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getObject("company_id", UUID.class),
            rs.getString("company_name"),
            rs.getBoolean("is_active")
        );
    }

    @Override
    public List<ProductionLine> productionLines(UUID factoryId) {
        String sql = LINE_SELECT +
            (factoryId == null ? "" : " and pl.factory_id = ?") +
            " order by c.name, f.name, pl.name";
        return factoryId == null ? jdbc.query(sql, this::line) :
            jdbc.query(sql, this::line, factoryId);
    }

    private ProductionLine line(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ProductionLine(
            rs.getObject("id", UUID.class),
            rs.getObject("factory_id", UUID.class),
            rs.getString("factory_name"),
            rs.getObject("company_id", UUID.class),
            rs.getString("company_name"),
            rs.getString("name"),
            rs.getBoolean("is_active")
        );
    }
}
