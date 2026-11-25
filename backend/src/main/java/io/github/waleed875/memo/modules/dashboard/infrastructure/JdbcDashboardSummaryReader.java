package io.github.waleed875.memo.modules.dashboard.infrastructure;

import io.github.waleed875.memo.modules.dashboard.application.DashboardCoreSla;
import io.github.waleed875.memo.modules.dashboard.application.DashboardCoreSnapshot;
import io.github.waleed875.memo.modules.dashboard.application.DashboardCoreSummary;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryReader;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restricted projection of original C# DashboardSummary and SLA counts.
 * Uses fixed allowlisted date expressions, no user-interpolated SQL fragments.
 * Timestamp cutoff remains arrival-date based as in BuildSnapshotSql.
 */
@Repository
public class JdbcDashboardSummaryReader implements DashboardSummaryReader {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDashboardSummaryReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public DashboardCoreSnapshot read(
        UUID actorId, DashboardSummaryCriteria f, LocalDate businessDay
    ) {
        var auth = new MapSqlParameterSource().addValue("actorId", actorId);
        Boolean admin = jdbc.queryForObject("""
            select exists (
                select 1 from auth.users u
                join auth.roles r on r.id = u.role_id
                where u.id = :actorId and u.is_active and not u.is_deleted
                  and r.is_active and r.code = 'ADMIN'
            )
            """, auth, Boolean.class);
        if (!Boolean.TRUE.equals(admin)) throw new MasterDataForbiddenException();

        // This fixed two-way choice preserves original BuildSnapshotSql(dateBasis).
        String period = "ArrivalDate".equals(f.dateBasis())
            ? "n.arrival_at::date" : "n.writing_date::date";
        String refPeriod = "ArrivalDate".equals(f.dateBasis())
            ? "reference_note.arrival_at::date" : "reference_note.writing_date::date";
        String sql = """
            with base as (
                select n.id, n.company_id, n.status, n.arrival_at, n.writing_date,
                       %s as period_day
                from core.notes n
                where n.is_deleted = false
                  and (cast(:fromDate as date) is null or %s >= cast(:fromDate as date))
                  and (cast(:toDate as date) is null or %s <= cast(:toDate as date))
                  and (cast(:companyId as uuid) is null or n.company_id = :companyId)
                  and (cast(:factoryId as uuid) is null or n.factory_id = :factoryId)
                  and (cast(:lineId as uuid) is null or n.production_line_id = :lineId)
                  and (cast(:typeId as uuid) is null or n.note_type_id = :typeId)
                  and (cast(:status as text) is null or n.status = :status)
                  and (cast(:cutoff as date) is null or n.arrival_at::date <= cast(:cutoff as date))
            ), dup as (
                select d.classification, reference_note.id as reference_id
                from core.duplicate_incidents d
                join core.notes reference_note on reference_note.id =
                  case d.classification
                    when 'COMPANY_DUPLICATE_NUMBER' then d.created_note_id
                    when 'COMPANY_RESEND' then d.matched_note_id
                    else null
                  end
                where d.classification in ('COMPANY_RESEND','COMPANY_DUPLICATE_NUMBER')
                  and reference_note.is_deleted = false
                  and (cast(:fromDate as date) is null or %s >= cast(:fromDate as date))
                  and (cast(:toDate as date) is null or %s <= cast(:toDate as date))
                  and (cast(:companyId as uuid) is null or reference_note.company_id = :companyId)
                  and (cast(:factoryId as uuid) is null or reference_note.factory_id = :factoryId)
                  and (cast(:lineId as uuid) is null or reference_note.production_line_id = :lineId)
                  and (cast(:typeId as uuid) is null or reference_note.note_type_id = :typeId)
                  and (cast(:status as text) is null or reference_note.status = :status)
                  and (cast(:cutoff as date) is null
                       or reference_note.arrival_at::date <= cast(:cutoff as date))
            ), note_totals as (
                select
                  count(*) as total_notes,
                  count(*) filter (where period_day = cast(:businessDay as date)) as notes_today,
                  count(*) filter (where date_trunc('month',period_day) =
                      date_trunc('month',cast(:businessDay as date))) as notes_current_month,
                  count(*) filter (where status = 'APPROVED') as approved,
                  count(*) filter (where status in (
                      'NEEDS_REVIEW','UNDER_REVIEW','COMPLETE','HAS_ERRORS')) as pending_review,
                  count(*) filter (where status = 'RETURNED_FOR_CORRECTION') as returned_for_correction,
                  count(*) filter (where writing_date is not null
                      and arrival_at::date - writing_date::date > 5) as late_total,
                  count(*) filter (where writing_date is not null
                      and arrival_at::date - writing_date::date > 5
                      and date_trunc('month',writing_date) = date_trunc('month',arrival_at))
                      as late_same_month,
                  count(*) filter (where writing_date is not null
                      and arrival_at::date - writing_date::date > 5
                      and date_trunc('month',writing_date) <> date_trunc('month',arrival_at))
                      as late_cross_month,
                  count(*) filter (where writing_date is not null
                      and (arrival_at::date - writing_date::date) between 0 and 5) as on_time,
                  round(avg(arrival_at::date - writing_date::date) filter (
                      where writing_date is not null
                        and arrival_at::date - writing_date::date > 5),1)
                      ::double precision as avg_late_days,
                  max(arrival_at::date - writing_date::date) filter (
                      where writing_date is not null
                        and arrival_at::date - writing_date::date > 5)
                      ::double precision as max_late_days,
                  max(arrival_at)::date as latest_arrival_date,
                  max(arrival_at)::timestamp as latest_arrival_timestamp,
                  count(*) filter (where writing_date is not null
                      and arrival_at >= writing_date) as eligible_notes,
                  count(*) filter (where writing_date is not null
                      and arrival_at >= writing_date
                      and arrival_at::date - writing_date::date between 0 and 5)
                      as on_time_notes,
                  count(*) filter (where writing_date is not null
                      and arrival_at >= writing_date
                      and arrival_at::date - writing_date::date > 5) as late_notes,
                  count(*) filter (where writing_date is not null
                      and arrival_at < writing_date) as invalid_date_order_notes
                from base
            ), duplicate_totals as (
                select
                    count(*) filter (where classification = 'COMPANY_RESEND') as company_resend,
                    count(*) filter (where classification = 'COMPANY_DUPLICATE_NUMBER') as duplicate_numbers,
                    count(distinct reference_id) as duplicate_affected_notes
                from dup
            )
            select nt.*, dt.*,
                (select count(*) from core.companies c
                 where c.is_active = true and c.is_deleted = false) as active_companies_total,
                (select count(distinct c.id) from core.companies c
                 join base b on b.company_id = c.id
                 where c.is_active = true and c.is_deleted = false) as companies_with_notes
            from note_totals nt cross join duplicate_totals dt
            """.formatted(period, period, period, refPeriod, refPeriod);

        var args = new MapSqlParameterSource()
            .addValue("businessDay", businessDay, Types.DATE)
            .addValue("fromDate", f.from(), Types.DATE)
            .addValue("toDate", f.to(), Types.DATE)
            .addValue("companyId", f.companyId(), Types.OTHER)
            .addValue("factoryId", f.factoryId(), Types.OTHER)
            .addValue("lineId", f.productionLineId(), Types.OTHER)
            .addValue("typeId", f.noteTypeId(), Types.OTHER)
            .addValue("status", f.status(), Types.VARCHAR)
            .addValue("cutoff", f.entryCutoffDate(), Types.DATE);

        return jdbc.queryForObject(sql, args, (rs, row) -> {
            long total = rs.getLong("total_notes");
            long companyResend = rs.getLong("company_resend");
            long dupNumbers = rs.getLong("duplicate_numbers");
            long affected = rs.getLong("duplicate_affected_notes");
            var summary = new DashboardCoreSummary(
                total, rs.getLong("notes_today"), rs.getLong("notes_current_month"),
                rs.getLong("active_companies_total"), rs.getLong("companies_with_notes"),
                rs.getLong("approved"), rs.getLong("pending_review"),
                rs.getLong("returned_for_correction"), companyResend, dupNumbers,
                companyResend + dupNumbers, affected, Math.max(0L,total-affected),
                rs.getLong("late_total"), rs.getLong("late_same_month"),
                rs.getLong("late_cross_month"), rs.getLong("on_time"),
                nullableDouble(rs.getObject("avg_late_days")),
                nullableDouble(rs.getObject("max_late_days")),
                rs.getObject("latest_arrival_date", LocalDate.class),
                rs.getObject("latest_arrival_timestamp", LocalDateTime.class));
            long eligible = rs.getLong("eligible_notes");
            long onTime = rs.getLong("on_time_notes");
            var sla = new DashboardCoreSla(eligible, onTime, rs.getLong("late_notes"),
                percentage(onTime, eligible), rs.getLong("invalid_date_order_notes"));
            return new DashboardCoreSnapshot(summary, sla, f);
        });
    }

    private static Double nullableDouble(Object raw) {
        return raw == null ? null : ((Number) raw).doubleValue();
    }

    private static Double percentage(long numerator, long denominator) {
        if (denominator <= 0) return null;
        // The C# MetricsCalculator.Rate uses 2 decimals, midpoint away from zero.
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP)
            .doubleValue();
    }
}
