package io.github.waleed875.memo.modules.dashboard.infrastructure;

import io.github.waleed875.memo.modules.dashboard.application.DashboardIndicatorLabels;
import io.github.waleed875.memo.modules.dashboard.application.DashboardQualityReader;
import io.github.waleed875.memo.modules.dashboard.application.DashboardQualitySnapshot;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of original C# DashboardQueryService quality projections, using the
 * original migration-060 reporting.vw_note_indicator_facts_v3 contract.
 * A consistent read transaction and live DB role check protect every report.
 */
@Repository
public class JdbcDashboardQualityReader implements DashboardQualityReader {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcDashboardQualityReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public DashboardQualitySnapshot read(
        UUID actorId, DashboardSummaryCriteria filters, int minSample
    ) {
        var identity = new MapSqlParameterSource().addValue("actorId", actorId);
        Boolean permitted = jdbc.queryForObject("""
            select exists (
              select 1 from auth.users u
              join auth.roles r on r.id = u.role_id
              where u.id = :actorId and u.is_active and not u.is_deleted
                and r.is_active and r.code = 'ADMIN'
            )
            """, identity, Boolean.class);
        if (!Boolean.TRUE.equals(permitted)) throw new MasterDataForbiddenException();

        // The C# BuildSnapshotSql chooses the calendar period from this
        // fixed allowlist. Entry cutoff always uses arrival date, not this basis.
        String period = "ArrivalDate".equals(filters.dateBasis())
            ? "n.arrival_at::date" : "n.writing_date::date";
        var params = new MapSqlParameterSource()
            .addValue("fromDate", filters.from(), Types.DATE)
            .addValue("toDate", filters.to(), Types.DATE)
            .addValue("companyId", filters.companyId(), Types.OTHER)
            .addValue("factoryId", filters.factoryId(), Types.OTHER)
            .addValue("lineId", filters.productionLineId(), Types.OTHER)
            .addValue("typeId", filters.noteTypeId(), Types.OTHER)
            .addValue("status", filters.status(), Types.VARCHAR)
            .addValue("cutoff", filters.entryCutoffDate(), Types.DATE)
            .addValue("minSample", minSample, Types.INTEGER);

        String factsSql = """
            with scoped as (
              select n.id
              from core.notes n
              where not n.is_deleted
                and (cast(:fromDate as date) is null
                     or %s >= cast(:fromDate as date))
                and (cast(:toDate as date) is null
                     or %s <= cast(:toDate as date))
                and (cast(:companyId as uuid) is null or n.company_id = :companyId)
                and (cast(:factoryId as uuid) is null or n.factory_id = :factoryId)
                and (cast(:lineId as uuid) is null or n.production_line_id = :lineId)
                and (cast(:typeId as uuid) is null or n.note_type_id = :typeId)
                and (cast(:status as text) is null or n.status = :status)
                and (cast(:cutoff as date) is null
                     or n.arrival_at::date <= cast(:cutoff as date))
            ), facts as (
              select f.* from reporting.vw_note_indicator_facts_v3 f
              join scoped s on s.id = f.note_id
            ), note_quality as (
              select note_id, company_id, factory_id, production_line_id,
                     count(*)::bigint as expected_items,
                     count(*) filter (where is_unreviewed)::bigint as unreviewed_items,
                     count(*) filter (where is_error)::bigint as error_items,
                     count(*) filter (where is_applicable_reviewed)::bigint
                       as applicable_reviewed_items,
                     count(*) filter (where is_not_applicable)::bigint
                       as not_applicable_items
              from facts
              group by note_id, company_id, factory_id, production_line_id
            )
            """.formatted(period, period);

        DashboardQualitySnapshot.FieldQualitySummary total = jdbc.queryForObject(
            factsSql + """
                select count(*) filter (where is_applicable_reviewed) as total_fields,
                       count(*) filter (where is_applicable_reviewed and is_correct) as correct_fields,
                       count(*) filter (where is_applicable_reviewed and is_error) as error_fields,
                       count(*) filter (where is_unreviewed) as unknown_fields
                from facts
                """, params, (rs, row) -> new DashboardQualitySnapshot.FieldQualitySummary(
                rs.getLong("total_fields"), rs.getLong("correct_fields"),
                rs.getLong("error_fields"), rs.getLong("unknown_fields"),
                rate(rs.getLong("correct_fields"), rs.getLong("total_fields"))));

        var companyQuality = jdbc.query(factsSql + """
            , company_fields as (
              select company_id,
                     count(*) filter (where is_applicable_reviewed) as total_fields,
                     count(*) filter (where is_applicable_reviewed and is_correct) as correct_fields,
                     count(*) filter (where is_applicable_reviewed and is_error) as error_fields,
                     count(*) filter (where is_unreviewed) as unknown_fields
              from facts group by company_id
            )
            select c.id as company_id, c.name as company_name,
                   count(q.note_id) as sample_size,
                   count(q.note_id) filter (
                     where q.error_items = 0 and q.unreviewed_items = 0
                       and q.applicable_reviewed_items > 0) as correct_count,
                   count(q.note_id) filter (where q.error_items > 0) as error_count,
                   count(q.note_id) filter (
                     where q.error_items > 0 or
                       (q.error_items = 0 and q.unreviewed_items = 0
                        and q.applicable_reviewed_items > 0)) as eligible_count,
                   count(q.note_id) filter (
                     where q.error_items = 0 and q.unreviewed_items = 0
                       and q.applicable_reviewed_items = 0
                       and q.not_applicable_items = q.expected_items) as all_na_count,
                   cf.total_fields, cf.correct_fields, cf.error_fields, cf.unknown_fields
            from note_quality q
            join core.companies c on c.id = q.company_id
            join company_fields cf on cf.company_id = q.company_id
            group by c.id, c.name, cf.total_fields, cf.correct_fields,
                     cf.error_fields, cf.unknown_fields
            order by error_count desc, sample_size desc, c.name
            """, params, (rs, row) -> company(rs));

        var byFactory = jdbc.query(factsSql + """
            select f.id as dimension_id, f.name as label,
                   count(q.note_id) as sample_count,
                   count(q.note_id) filter (
                     where q.error_items = 0 and q.unreviewed_items = 0
                       and q.applicable_reviewed_items > 0) as correct_count,
                   count(q.note_id) filter (where q.error_items > 0) as error_count,
                   count(q.note_id) filter (
                     where q.error_items > 0 or
                       (q.error_items = 0 and q.unreviewed_items = 0
                        and q.applicable_reviewed_items > 0)) as eligible_count
            from note_quality q
            join core.factories f on f.id = q.factory_id and not f.is_deleted
            group by f.id, f.name
            order by sample_count desc, f.name limit 10
            """, params, (rs, row) -> dimension(rs));

        var byLine = jdbc.query(factsSql + """
            select pl.id as dimension_id, pl.name as label,
                   count(q.note_id) as sample_count,
                   count(q.note_id) filter (
                     where q.error_items = 0 and q.unreviewed_items = 0
                       and q.applicable_reviewed_items > 0) as correct_count,
                   count(q.note_id) filter (where q.error_items > 0) as error_count,
                   count(q.note_id) filter (
                     where q.error_items > 0 or
                       (q.error_items = 0 and q.unreviewed_items = 0
                        and q.applicable_reviewed_items > 0)) as eligible_count
            from note_quality q
            join core.production_lines pl on pl.id = q.production_line_id
              and not pl.is_deleted
            group by pl.id, pl.name
            order by sample_count desc, pl.name limit 10
            """, params, (rs, row) -> dimension(rs));

        var topErrors = jdbc.query(factsSql + """
            select field_id, field_name,
                   count(*) filter (where is_applicable_reviewed) as sample_size,
                   count(*) filter (where is_applicable_reviewed and is_error) as error_count
            from facts
            group by field_id, field_name
            having count(*) filter (where is_applicable_reviewed) >= :minSample
            order by error_count desc, sample_size desc, field_name
            limit 10
            """, params, (rs, row) -> new DashboardQualitySnapshot.TopErrorField(
                rs.getObject("field_id",UUID.class),rs.getString("field_name"),
                rs.getString("field_name"),rs.getLong("sample_size"),
                rs.getLong("error_count"),rate(rs.getLong("error_count"),
                    rs.getLong("sample_size"))));

        var states = jdbc.query(factsSql + """
            select effective_field_value as code, count(*) as value
            from facts group by effective_field_value
            order by effective_field_value
            """, params, (rs, row) -> new DashboardQualitySnapshot.IndicatorState(
                rs.getInt("code"), DashboardIndicatorLabels.state(rs.getInt("code")),
                rs.getLong("value")));

        return new DashboardQualitySnapshot(total, companyQuality, byFactory,
            byLine, topErrors, states, filters, minSample);
    }

    private static DashboardQualitySnapshot.CompanyQuality company(ResultSet rs)
        throws SQLException {
        long sample = rs.getLong("sample_size"), correct = rs.getLong("correct_count");
        long errors = rs.getLong("error_count"), eligible = rs.getLong("eligible_count");
        long totalFields = rs.getLong("total_fields"), correctFields = rs.getLong("correct_fields");
        return new DashboardQualitySnapshot.CompanyQuality(
            rs.getObject("company_id", UUID.class),rs.getString("company_name"),
            rs.getString("company_name"),sample,correct,errors,eligible,
            rs.getLong("all_na_count"),rate(correct,eligible),rate(errors,eligible),
            totalFields,correctFields,rs.getLong("error_fields"),
            rs.getLong("unknown_fields"),rate(correctFields,totalFields));
    }

    private static DashboardQualitySnapshot.DimensionQuality dimension(ResultSet rs)
        throws SQLException {
        long eligible = rs.getLong("eligible_count");
        long correct = rs.getLong("correct_count");
        return new DashboardQualitySnapshot.DimensionQuality(
            rs.getObject("dimension_id", UUID.class),rs.getString("label"),
            rs.getLong("sample_count"),correct,rs.getLong("error_count"),eligible,
            rate(correct,eligible));
    }

    private static Double rate(long n, long d) {
        if (d <= 0) return null;
        return BigDecimal.valueOf(n).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(d),2,RoundingMode.HALF_UP).doubleValue();
    }
}
