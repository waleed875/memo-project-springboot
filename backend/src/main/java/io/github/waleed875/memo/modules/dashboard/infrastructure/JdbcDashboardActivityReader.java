package io.github.waleed875.memo.modules.dashboard.infrastructure;

import io.github.waleed875.memo.modules.dashboard.application.DashboardActivityLabels;
import io.github.waleed875.memo.modules.dashboard.application.DashboardActivityReader;
import io.github.waleed875.memo.modules.dashboard.application.DashboardActivitySnapshot;
import io.github.waleed875.memo.modules.dashboard.application.DashboardDistributionLabels;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Four original dashboard slices from one PostgreSQL repeatable-read snapshot:
 * duplicate trend, duplicate classification, recent notes, backlog aging.
 * DateBasis is selected via a fixed allowlist, not interpolated from the user.
 * The old public dashboard snapshot route stays blocked.
 */
@Repository
public class JdbcDashboardActivityReader implements DashboardActivityReader {
    private static final String[] BUCKETS = {
        "lt-1-day", "1-3-days", "4-7-days", "gt-7-days"
    };
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDashboardActivityReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public DashboardActivitySnapshot read(
        UUID actorId, DashboardSummaryCriteria criteria, LocalDate businessDay
    ) {
        Boolean admin = jdbc.queryForObject("""
            select exists (
              select 1 from auth.users u
              join auth.roles r on r.id = u.role_id
              where u.id = :actor and u.is_active and not u.is_deleted
                and r.is_active and r.code = 'ADMIN'
            )
            """, new MapSqlParameterSource("actor", actorId), Boolean.class);
        if (!Boolean.TRUE.equals(admin)) throw new MasterDataForbiddenException();

        String basis = "ArrivalDate".equals(criteria.dateBasis())
            ? "n.arrival_at::date" : "n.writing_date::date";

        String scoped = """
            with scoped as (
              select n.id, n.note_number, n.company_id, n.factory_id,
                     n.production_line_id, n.note_type_id, n.status,
                     n.writing_date, n.arrival_at, n.review_started_at,
                     n.created_at, %s as period_day
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
            )
            """.formatted(basis, basis, basis);
        String incidents = scoped + """
            , incidents as (
              select d.classification, reference_note.period_day
              from core.duplicate_incidents d
              join scoped reference_note on reference_note.id =
                case d.classification
                  when 'COMPANY_DUPLICATE_NUMBER' then d.created_note_id
                  when 'COMPANY_RESEND' then d.matched_note_id
                  else null
                end
              where d.classification in ('COMPANY_RESEND','COMPANY_DUPLICATE_NUMBER')
            )
            """;

        var params = new MapSqlParameterSource()
            .addValue("fromDate", criteria.from(), Types.DATE)
            .addValue("toDate", criteria.to(), Types.DATE)
            .addValue("companyId", criteria.companyId(), Types.OTHER)
            .addValue("factoryId", criteria.factoryId(), Types.OTHER)
            .addValue("lineId", criteria.productionLineId(), Types.OTHER)
            .addValue("typeId", criteria.noteTypeId(), Types.OTHER)
            .addValue("status", criteria.status(), Types.VARCHAR)
            .addValue("cutoff", criteria.entryCutoffDate(), Types.DATE)
            .addValue("businessDay", businessDay, Types.DATE);

        var duplicates = jdbc.query(incidents + """
            select classification, count(*) as incident_count
            from incidents group by classification
            order by incident_count desc, classification
            """, params, (rs, index) -> new DashboardActivitySnapshot.DuplicateBreakdownItem(
                rs.getString("classification"),
                DashboardActivityLabels.duplicate(rs.getString("classification")),
                rs.getLong("incident_count")));

        var monthlyRows = jdbc.query(incidents + """
            select date_trunc('month',period_day)::date as month_start,
                   count(*) filter (where classification = 'COMPANY_RESEND') as resend,
                   count(*) filter (where classification = 'COMPANY_DUPLICATE_NUMBER')
                     as duplicate_number
            from incidents
            where period_day is not null
            group by 1 order by 1
            """, params, (rs, index) -> new DuplicateMonth(
                YearMonth.from(rs.getObject("month_start", LocalDate.class)),
                rs.getLong("resend"), rs.getLong("duplicate_number")));

        var recent = jdbc.query(scoped + """
            select n.id, n.note_number, c.name as company_name,
                   f.name as factory_name, pl.name as production_line_name,
                   nt.name_ar as note_type_name,
                   n.status, n.writing_date, n.arrival_at::timestamp as arrival_at
            from scoped n
            left join core.companies c on c.id = n.company_id
            left join core.factories f on f.id = n.factory_id
            left join core.production_lines pl on pl.id = n.production_line_id
            left join core.note_types nt on nt.id = n.note_type_id
            order by n.arrival_at desc, n.created_at desc, n.id desc
            limit 10
            """, params, (rs, index) -> {
                LocalDate writing = rs.getObject("writing_date", LocalDate.class);
                LocalDateTime arrivalTime =
                    rs.getObject("arrival_at", LocalDateTime.class);
                LocalDate arrival = arrivalTime == null ? null : arrivalTime.toLocalDate();
                String status = rs.getString("status");
                return new DashboardActivitySnapshot.RecentNoteItem(
                    rs.getObject("id", UUID.class),rs.getString("note_number"),
                    rs.getString("company_name"),rs.getString("factory_name"),
                    rs.getString("production_line_name"),rs.getString("note_type_name"),
                    status, DashboardDistributionLabels.status(status),writing,arrival,
                    writing != null && arrival != null
                        && ChronoUnit.DAYS.between(writing, arrival) > 5,
                    arrivalTime);
            });

        var ageRows = jdbc.query(scoped + """
            , ages as (
              select case
                when cast(:businessDay as date) -
                     coalesce(n.review_started_at, n.created_at, n.arrival_at::timestamp)::date < 1
                     then 'lt-1-day'
                when cast(:businessDay as date) -
                     coalesce(n.review_started_at, n.created_at, n.arrival_at::timestamp)::date <= 3
                     then '1-3-days'
                when cast(:businessDay as date) -
                     coalesce(n.review_started_at, n.created_at, n.arrival_at::timestamp)::date <= 7
                     then '4-7-days'
                else 'gt-7-days'
              end as bucket
              from scoped n
              where n.status in ('COMPLETE','HAS_ERRORS','NEEDS_REVIEW','UNDER_REVIEW')
            )
            select bucket, count(*) as count_value
            from ages group by bucket
            """, params, (rs, index) -> Map.entry(
                rs.getString("bucket"), rs.getLong("count_value")));

        Map<String,Long> ages = new HashMap<>();
        for (var row : ageRows) ages.put(row.getKey(), row.getValue());
        List<DashboardActivitySnapshot.BacklogAgeItem> backlog = new ArrayList<>();
        for (String code : BUCKETS) {
            Long count = ages.get(code);
            if (count != null) backlog.add(
                new DashboardActivitySnapshot.BacklogAgeItem(
                    code, DashboardActivityLabels.backlog(code), count));
        }

        return new DashboardActivitySnapshot(
            fillMonths(monthlyRows,criteria), duplicates, recent, backlog,criteria);
    }

    private record DuplicateMonth(YearMonth month,long resend,long duplicateNumber) {}

    private static List<DashboardActivitySnapshot.DuplicateTrendItem> fillMonths(
        List<DuplicateMonth> monthlyRows, DashboardSummaryCriteria criteria
    ) {
        var values = new TreeMap<YearMonth,DuplicateMonth>();
        for (var row : monthlyRows) values.put(row.month(),row);
        if (criteria.from() != null && criteria.to() != null) {
            YearMonth current=YearMonth.from(criteria.from());
            YearMonth last=YearMonth.from(criteria.to());
            while (!current.isAfter(last)) {
                values.putIfAbsent(current,new DuplicateMonth(current,0,0));
                current=current.plusMonths(1);
            }
        }
        List<DashboardActivitySnapshot.DuplicateTrendItem> result = new ArrayList<>();
        for (DuplicateMonth month : values.values()) {
            String label=DashboardDistributionLabels.month(month.month());
            result.add(new DashboardActivitySnapshot.DuplicateTrendItem(
                month.month().toString(),label,label,
                month.resend(),month.duplicateNumber()));
        }
        return List.copyOf(result);
    }
}
