package io.github.waleed875.memo.modules.dashboard.infrastructure;

import io.github.waleed875.memo.modules.dashboard.application.DashboardCompanyComparison;
import io.github.waleed875.memo.modules.dashboard.application.DashboardCompanyMetrics;
import io.github.waleed875.memo.modules.dashboard.application.DashboardCompanyMetricsReader;
import io.github.waleed875.memo.modules.dashboard.application.DashboardCompanyPeriod;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calls the original migration-069 reporting function unchanged. All 43
 * original C# CompanyMetrics fields are projected in their DB column order.
 * Both periods are read inside the same repeatable-read transaction.
 */
@Repository
public class JdbcDashboardCompanyMetricsReader implements DashboardCompanyMetricsReader {
    private static final String COMPANY_SQL = """
        select * from reporting.fn_company_metrics_snapshot_v4_filtered(
            :companyId, cast(:fromDate as date), cast(:toDate as date),
            :factoryId, :lineId, :noteTypeId, :status,
            cast(:cutoff as date), :dateBasis)
        """;
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcDashboardCompanyMetricsReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    @Override
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, timeout=30)
    public DashboardCompanyComparison read(
        UUID actorId, DashboardSummaryCriteria filters, DashboardCompanyPeriod previous
    ) {
        Boolean allowed=jdbc.queryForObject("""
            select exists (
                select 1 from auth.users u
                join auth.roles r on r.id=u.role_id
                where u.id=:actor and u.is_active and not u.is_deleted
                  and r.is_active and r.code='ADMIN'
            )
            """,new MapSqlParameterSource("actor",actorId),Boolean.class);
        if(!Boolean.TRUE.equals(allowed)) throw new MasterDataForbiddenException();

        DashboardCompanyMetrics current=fetch(parameters(filters,
            filters.companyId(),filters.from(),filters.to(),filters.entryCutoffDate()));
        DashboardCompanyMetrics prior=previous==null?null:fetch(parameters(filters,
            filters.companyId(),previous.from(),previous.to(),previous.entryCutoffDate()));
        return new DashboardCompanyComparison(current,prior,previous,filters);
    }

    private DashboardCompanyMetrics fetch(MapSqlParameterSource args) {
        var rows=jdbc.query(COMPANY_SQL,args,(rs,index)->map(rs));
        if(rows.size()!=1) throw new DataRetrievalFailureException(
            "Canonical company metrics function must return exactly one row.");
        return rows.getFirst();
    }

    private static MapSqlParameterSource parameters(
        DashboardSummaryCriteria criteria,UUID companyId,
        java.time.LocalDate from,java.time.LocalDate to,
        java.time.LocalDate cutoff
    ) {
        return new MapSqlParameterSource()
            .addValue("companyId",companyId,Types.OTHER)
            .addValue("fromDate",from,Types.DATE)
            .addValue("toDate",to,Types.DATE)
            .addValue("factoryId",criteria.factoryId(),Types.OTHER)
            .addValue("lineId",criteria.productionLineId(),Types.OTHER)
            .addValue("noteTypeId",criteria.noteTypeId(),Types.OTHER)
            .addValue("status",criteria.status(),Types.VARCHAR)
            .addValue("cutoff",cutoff,Types.DATE)
            .addValue("dateBasis",criteria.dateBasis(),Types.VARCHAR);
    }

    private static DashboardCompanyMetrics map(ResultSet rs) throws SQLException {
        return new DashboardCompanyMetrics(
            rs.getInt("total_notes"),
            rs.getInt("expected_items"),
            rs.getInt("reviewed_items"),
            rs.getInt("unreviewed_items"),
            rs.getInt("applicable_reviewed_items"),
            rs.getInt("correct_items"),
            rs.getInt("error_items"),
            rs.getInt("not_applicable_items"),
            rs.getBigDecimal("review_coverage_percentage"),
            rs.getBigDecimal("item_quality_percentage"),
            rs.getBigDecimal("errors_per_100_checked"),
            rs.getInt("quality_eligible_note_count"),
            rs.getInt("clean_reviewed_notes"),
            rs.getInt("all_not_applicable_note_count"),
            rs.getInt("no_expected_items_note_count"),
            rs.getBigDecimal("clean_reviewed_note_percent"),
            rs.getInt("error_notes"),
            rs.getInt("incomplete_review_notes"),
            rs.getInt("time_eligible_notes"),
            rs.getInt("on_time_notes"),
            rs.getInt("late_notes"),
            rs.getInt("late_6_to_10_notes"),
            rs.getInt("late_11_to_20_notes"),
            rs.getInt("late_over_20_notes"),
            rs.getInt("missing_writing_date_notes"),
            rs.getInt("invalid_date_order_notes"),
            rs.getBigDecimal("time_compliance_percentage"),
            rs.getBigDecimal("average_late_days"),
            rs.getBigDecimal("median_late_days"),
            rs.getBigDecimal("p90_late_days"),
            (Integer)rs.getObject("maximum_late_days"),
            rs.getBigDecimal("average_excess_delay_days"),
            rs.getInt("duplicate_number_incidents"),
            rs.getInt("company_resend_incidents"),
            rs.getInt("duplicate_incidents"),
            rs.getInt("affected_notes"),
            rs.getBigDecimal("incidents_per_100_notes"),
            rs.getBigDecimal("affected_note_rate_percentage"),
            rs.getBigDecimal("duplicate_free_percentage"),
            rs.getInt("entry_notes"),
            rs.getInt("linked_entry_notes"),
            rs.getInt("unlinked_entry_notes"),
            rs.getBigDecimal("link_efficiency_percentage")
        );
    }
}
