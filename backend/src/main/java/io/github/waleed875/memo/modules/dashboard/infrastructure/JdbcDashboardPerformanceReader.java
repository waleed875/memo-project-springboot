package io.github.waleed875.memo.modules.dashboard.infrastructure;

import io.github.waleed875.memo.modules.dashboard.application.DashboardPerformanceBreakdown;
import io.github.waleed875.memo.modules.dashboard.application.DashboardPerformanceItem;
import io.github.waleed875.memo.modules.dashboard.application.DashboardPerformanceReader;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes C# PerformanceItem fields in a single consistent read transaction.
 * Quality note classification comes from the same v3 reporting view as migration
 * 060: errors override unreviewed, clean requires reviewed applicable field.
 * No global role cache and no arbitrary SQL interpolated from HTTP input.
 */
@Repository
public class JdbcDashboardPerformanceReader implements DashboardPerformanceReader {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDashboardPerformanceReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public DashboardPerformanceBreakdown read(UUID actorId, DashboardSummaryCriteria criteria) {
        Boolean allowed = jdbc.queryForObject("""
            select exists (
                select 1 from auth.users u
                join auth.roles r on r.id = u.role_id
                where u.id = :actor and u.is_active and not u.is_deleted
                  and r.is_active and r.code = 'ADMIN'
            )
            """, new MapSqlParameterSource("actor", actorId), Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) throw new MasterDataForbiddenException();

        String period = "ArrivalDate".equals(criteria.dateBasis())
            ? "n.arrival_at::date" : "n.writing_date::date";

        String base = """
            with scoped as (
                select n.id, n.company_id, n.factory_id, n.production_line_id,
                       n.writing_date, n.arrival_at
                from core.notes n
                where not n.is_deleted
                  and (cast(:fromDate as date) is null or %s >= cast(:fromDate as date))
                  and (cast(:toDate as date) is null or %s <= cast(:toDate as date))
                  and (cast(:companyId as uuid) is null or n.company_id = :companyId)
                  and (cast(:factoryId as uuid) is null or n.factory_id = :factoryId)
                  and (cast(:lineId as uuid) is null or n.production_line_id = :lineId)
                  and (cast(:typeId as uuid) is null or n.note_type_id = :typeId)
                  and (cast(:status as text) is null or n.status = :status)
                  and (cast(:cutoff as date) is null
                       or n.arrival_at::date <= cast(:cutoff as date))
            ), per_note_quality as (
                select f.note_id,
                    count(*) filter (where f.is_error) as errors,
                    count(*) filter (where f.is_unreviewed) as unreviewed,
                    count(*) filter (where f.is_applicable_reviewed) as applicable
                from reporting.vw_note_indicator_facts_v3 f
                join scoped n on n.id = f.note_id
                group by f.note_id
            ), facts as (
                select n.*,
                       (n.writing_date is not null
                        and n.arrival_at >= n.writing_date) as time_eligible,
                       (n.writing_date is not null
                        and n.arrival_at >= n.writing_date
                        and n.arrival_at::date - n.writing_date::date > 5) as is_late,
                       (n.writing_date is not null
                        and n.arrival_at >= n.writing_date
                        and n.arrival_at::date - n.writing_date::date between 0 and 5)
                           as is_on_time,
                       (q.note_id is not null) as has_quality,
                       (coalesce(q.errors,0) > 0) as has_error,
                       (q.note_id is not null and q.errors = 0
                        and q.unreviewed = 0 and q.applicable > 0) as is_clean
                from scoped n
                left join per_note_quality q on q.note_id = n.id
            ), dimension_counts as (
                select d.kind, d.id as dimension_id, d.label,
                       count(f.id) as total_notes,
                       count(f.id) filter (where f.time_eligible) as eligible_count,
                       count(f.id) filter (where f.is_late) as late_count,
                       count(f.id) filter (where f.is_on_time) as on_time_count,
                       count(f.id) filter (where f.has_quality) as sample_count,
                       count(f.id) filter (where f.is_clean) as correct_count,
                       count(f.id) filter (where f.has_error) as error_count,
                       count(f.id) filter (where f.has_error or f.is_clean)
                           as quality_eligible_count
                from (
                    select 'company' as kind, c.id, c.name as label,
                           c.id as company_id, null::uuid as factory_id,
                           null::uuid as line_id
                    from core.companies c
                    where c.is_active and not c.is_deleted
                      and (cast(:companyId as uuid) is null or c.id = :companyId)
                    union all
                    select 'factory', f.id, f.name, null::uuid, f.id, null::uuid
                    from core.factories f where not f.is_deleted
                    union all
                    select 'line', pl.id, pl.name, null::uuid, null::uuid, pl.id
                    from core.production_lines pl where not pl.is_deleted
                ) d
                left join facts f on
                  (d.kind = 'company' and f.company_id = d.company_id)
                  or (d.kind = 'factory' and f.factory_id = d.factory_id)
                  or (d.kind = 'line' and f.production_line_id = d.line_id)
                group by d.kind, d.id, d.label
            )
            select kind, dimension_id, label, total_notes, eligible_count,
                   late_count, on_time_count, sample_count, correct_count,
                   error_count, quality_eligible_count
            from dimension_counts
            where kind = 'company' or total_notes > 0
            order by kind, total_notes desc, label, dimension_id
            """.formatted(period, period);

        var params = new MapSqlParameterSource()
            .addValue("fromDate", criteria.from(), Types.DATE)
            .addValue("toDate", criteria.to(), Types.DATE)
            .addValue("companyId", criteria.companyId(), Types.OTHER)
            .addValue("factoryId", criteria.factoryId(), Types.OTHER)
            .addValue("lineId", criteria.productionLineId(), Types.OTHER)
            .addValue("typeId", criteria.noteTypeId(), Types.OTHER)
            .addValue("status", criteria.status(), Types.VARCHAR)
            .addValue("cutoff", criteria.entryCutoffDate(), Types.DATE);

        List<DashboardPerformanceItem> companies = new java.util.ArrayList<>();
        List<DashboardPerformanceItem> factories = new java.util.ArrayList<>();
        List<DashboardPerformanceItem> lines = new java.util.ArrayList<>();
        for (var row : jdbc.query(base, params, (rs, i) -> new GroupRow(
            rs.getString("kind"), item(rs)))) {
            switch (row.kind()) {
                case "company" -> companies.add(legacyCompanyPerformance(row.item()));
                case "factory" -> { if (factories.size() < 10) factories.add(row.item()); }
                case "line" -> { if (lines.size() < 10) lines.add(row.item()); }
                default -> throw new IllegalStateException("Unknown performance dimension");
            }
        }
        return new DashboardPerformanceBreakdown(companies, factories, lines, criteria);
    }

    /**
     * The pinned original C# SnapshotSql explicitly selects four zero
     * quality columns in the company timeliness result. True company
     * quality remains available in the dedicated qualityByCompany section.
     */
    private static DashboardPerformanceItem legacyCompanyPerformance(
        DashboardPerformanceItem item
    ) {
        return new DashboardPerformanceItem(
            item.dimensionId(),item.label(),item.totalNotes(),
            item.eligibleCount(),item.lateCount(),item.onTimeCount(),
            0,0,0,0,item.lateRate(),null);
    }

    private record GroupRow(String kind, DashboardPerformanceItem item) {}

    private static DashboardPerformanceItem item(ResultSet rs) throws SQLException {
        long eligible = rs.getLong("eligible_count");
        long late = rs.getLong("late_count");
        long qualityEligible = rs.getLong("quality_eligible_count");
        long correct = rs.getLong("correct_count");
        return new DashboardPerformanceItem(
            rs.getObject("dimension_id",UUID.class), rs.getString("label"),
            rs.getLong("total_notes"), eligible, late, rs.getLong("on_time_count"),
            rs.getLong("sample_count"), correct, rs.getLong("error_count"),
            qualityEligible, percentage(late,eligible), percentage(correct,qualityEligible));
    }

    private static Double percentage(long numerator,long denominator) {
        if (denominator == 0) return null;
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP).doubleValue();
    }
}
