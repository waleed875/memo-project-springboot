package io.github.waleed875.memo.modules.dashboard.infrastructure;

import io.github.waleed875.memo.modules.dashboard.application.DashboardDistributionLabels;
import io.github.waleed875.memo.modules.dashboard.application.DashboardDistributions;
import io.github.waleed875.memo.modules.dashboard.application.DashboardDistributionsReader;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only projections of the original DashboardQueryService SnapshotSql:
 * month activity, statuses, top companies and time compliance by
 * company/factory/line. Indicator quality counts are NOT inferred.
 * A single repeatable-read transaction gives every projection one DB snapshot.
 */
@Repository
public class JdbcDashboardDistributionsReader implements DashboardDistributionsReader {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcDashboardDistributionsReader(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public DashboardDistributions read(UUID actorId, DashboardSummaryCriteria f) {
        var auth = new MapSqlParameterSource().addValue("actor", actorId);
        Boolean allowed = jdbc.queryForObject("""
            select exists(
                select 1 from auth.users u join auth.roles r on r.id = u.role_id
                where u.id = :actor and u.is_active and not u.is_deleted
                  and r.is_active and r.code = 'ADMIN')
            """, auth, Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) throw new MasterDataForbiddenException();

        String period = "ArrivalDate".equals(f.dateBasis())
            ? "n.arrival_at::date" : "n.writing_date::date";
        // Allowlist-controlled SQL expression, never raw request data.
        String base = """
            with base as (
                select n.id, n.company_id, n.factory_id, n.production_line_id,
                       n.status, n.writing_date, n.arrival_at, %s as period_day
                from core.notes n
                where n.is_deleted = false
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
            )
            """.formatted(period, period, period);

        var args = new MapSqlParameterSource()
            .addValue("fromDate", f.from(), Types.DATE)
            .addValue("toDate", f.to(), Types.DATE)
            .addValue("companyId", f.companyId(), Types.OTHER)
            .addValue("factoryId", f.factoryId(), Types.OTHER)
            .addValue("lineId", f.productionLineId(), Types.OTHER)
            .addValue("typeId", f.noteTypeId(), Types.OTHER)
            .addValue("status", f.status(), Types.VARCHAR)
            .addValue("cutoff", f.entryCutoffDate(), Types.DATE);

        List<DashboardDistributions.MonthlyActivity> monthly = monthly(base, args, f);

        var statuses = jdbc.query(base + """
            select status, count(*) as total
            from base group by status order by total desc, status
            """, args, (rs, index) -> new DashboardDistributions.StatusDistribution(
                rs.getString("status"),
                DashboardDistributionLabels.status(rs.getString("status")),
                rs.getLong("total")));

        var companies = jdbc.query(base + """
            select c.id, c.name, count(b.id) as note_count
            from core.companies c
            left join base b on b.company_id = c.id
            where c.is_active = true and c.is_deleted = false
              and (cast(:companyId as uuid) is null or c.id = :companyId)
            group by c.id, c.name order by note_count desc, c.name
            """, args, (rs, index) -> new DashboardDistributions.CompanyTopItem(
                rs.getObject("id",UUID.class),
                rs.getString("name"),rs.getLong("note_count")));

        String counters = """
            count(b.id) as total_notes,
            count(b.id) filter (
                where b.writing_date is not null and b.arrival_at >= b.writing_date
            ) as eligible_count,
            count(b.id) filter (
                where b.writing_date is not null and b.arrival_at >= b.writing_date
                  and b.arrival_at::date - b.writing_date::date > 5
            ) as late_count,
            count(b.id) filter (
                where b.writing_date is not null and b.arrival_at >= b.writing_date
                  and b.arrival_at::date - b.writing_date::date between 0 and 5
            ) as on_time_count
            """;

        var byCompany = jdbc.query(base + """
            select c.id as dimension_id, c.name as label,
            """ + counters + """
            from core.companies c left join base b on b.company_id = c.id
            where c.is_active = true and c.is_deleted = false
              and (cast(:companyId as uuid) is null or c.id = :companyId)
            group by c.id, c.name order by total_notes desc, c.name
            """, args, (rs, index) -> item(rs));

        var byFactory = jdbc.query(base + """
            select f.id as dimension_id, f.name as label,
            """ + counters + """
            from base b join core.factories f on f.id = b.factory_id
              and f.is_deleted = false
            group by f.id, f.name order by total_notes desc, f.name limit 10
            """, args, (rs, index) -> item(rs));

        var byLine = jdbc.query(base + """
            select pl.id as dimension_id, pl.name as label,
            """ + counters + """
            from base b join core.production_lines pl on pl.id = b.production_line_id
              and pl.is_deleted = false
            group by pl.id, pl.name order by total_notes desc, pl.name limit 10
            """, args, (rs, index) -> item(rs));

        return new DashboardDistributions(
            monthly, statuses, companies, byCompany, byFactory, byLine, f);
    }

    private List<DashboardDistributions.MonthlyActivity> monthly(
        String base, MapSqlParameterSource args, DashboardSummaryCriteria f
    ) {
        var rows = jdbc.query(base + """
            select date_trunc('month',period_day)::date as month_start,
                   count(*) as total
            from base where period_day is not null
            group by 1 order by 1
            """, args, (rs, row) -> Map.entry(
                YearMonth.from(rs.getObject("month_start",LocalDate.class)),
                rs.getLong("total")));

        Map<YearMonth, Long> counts = new TreeMap<>();
        for (var row : rows) counts.merge(row.getKey(), row.getValue(), Long::sum);

        if (f.from() != null && f.to() != null) {
            YearMonth current = YearMonth.from(f.from());
            YearMonth last = YearMonth.from(f.to());
            while (!current.isAfter(last)) {
                counts.putIfAbsent(current, 0L);
                current = current.plusMonths(1);
            }
        }
        List<DashboardDistributions.MonthlyActivity> items = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            YearMonth month = entry.getKey();
            items.add(new DashboardDistributions.MonthlyActivity(
                month.toString(), DashboardDistributionLabels.month(month),
                entry.getValue()));
        }
        return List.copyOf(items);
    }

    private static DashboardDistributions.TimelinessItem item(java.sql.ResultSet rs)
        throws java.sql.SQLException {
        long eligible = rs.getLong("eligible_count");
        long late = rs.getLong("late_count");
        Double rate = eligible == 0L ? null
            : BigDecimal.valueOf(late).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(eligible), 2, RoundingMode.HALF_UP).doubleValue();
        return new DashboardDistributions.TimelinessItem(
            rs.getObject("dimension_id", UUID.class),
            rs.getString("label"),rs.getLong("total_notes"),
            eligible,late,rs.getLong("on_time_count"),rate);
    }
}
