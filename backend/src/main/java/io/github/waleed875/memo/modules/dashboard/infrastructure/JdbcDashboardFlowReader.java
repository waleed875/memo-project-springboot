package io.github.waleed875.memo.modules.dashboard.infrastructure;

import io.github.waleed875.memo.modules.dashboard.application.DashboardDistributionLabels;
import io.github.waleed875.memo.modules.dashboard.application.DashboardFlowReader;
import io.github.waleed875.memo.modules.dashboard.application.DashboardFlowSnapshot;
import io.github.waleed875.memo.modules.dashboard.application.DashboardSummaryCriteria;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
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
 * Original C# BuildSnapshotSql replaces incoming date *filter* by dateBasis,
 * but incoming month labels still use arrival_at. Approved months and filters
 * use approved_at. Entry cutoff is arrival-date based after C# substitution.
 */
@Repository
public class JdbcDashboardFlowReader implements DashboardFlowReader {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcDashboardFlowReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    @Override
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public DashboardFlowSnapshot read(UUID actorId,DashboardSummaryCriteria f) {
        Boolean admin=jdbc.queryForObject("""
            select exists(select 1 from auth.users u
              join auth.roles r on r.id=u.role_id
              where u.id=:actor and u.is_active and not u.is_deleted
                and r.is_active and r.code='ADMIN')
            """,new MapSqlParameterSource("actor",actorId),Boolean.class);
        if(!Boolean.TRUE.equals(admin)) throw new MasterDataForbiddenException();

        // C# SQL applies dateBasis to incoming WHERE, but retains arrival month
        // grouping; the approved event filter remains on approved_at.
        String basis="ArrivalDate".equals(f.dateBasis())
            ?"n.arrival_at::date":"n.writing_date::date";
        String common="""
              and (cast(:companyId as uuid) is null or n.company_id=:companyId)
              and (cast(:factoryId as uuid) is null or n.factory_id=:factoryId)
              and (cast(:lineId as uuid) is null or n.production_line_id=:lineId)
              and (cast(:typeId as uuid) is null or n.note_type_id=:typeId)
              and (cast(:status as text) is null or n.status=:status)
              and (cast(:cutoff as date) is null or n.arrival_at::date <= cast(:cutoff as date))
            """;
        var params=new MapSqlParameterSource()
            .addValue("fromDate",f.from(),Types.DATE)
            .addValue("toDate",f.to(),Types.DATE)
            .addValue("companyId",f.companyId(),Types.OTHER)
            .addValue("factoryId",f.factoryId(),Types.OTHER)
            .addValue("lineId",f.productionLineId(),Types.OTHER)
            .addValue("typeId",f.noteTypeId(),Types.OTHER)
            .addValue("status",f.status(),Types.VARCHAR)
            .addValue("cutoff",f.entryCutoffDate(),Types.DATE);

        String sql="""
            with incoming as (
              select date_trunc('month',n.arrival_at)::date as month_start,
                     count(*) as incoming
              from core.notes n
              where not n.is_deleted and n.arrival_at is not null
                and (cast(:fromDate as date) is null or %s >= cast(:fromDate as date))
                and (cast(:toDate as date) is null or %s <= cast(:toDate as date))
            """.formatted(basis,basis)+common+"""
              group by 1
            ), approved as (
              select date_trunc('month',n.approved_at)::date as month_start,
                     count(*) as approved
              from core.notes n
              where not n.is_deleted and n.approved_at is not null
                and (cast(:fromDate as date) is null or n.approved_at::date >= cast(:fromDate as date))
                and (cast(:toDate as date) is null or n.approved_at::date <= cast(:toDate as date))
            """+common+"""
              group by 1
            )
            select coalesce(i.month_start,a.month_start) as month_start,
                   coalesce(i.incoming,0) as incoming,
                   coalesce(a.approved,0) as approved
            from incoming i full outer join approved a on a.month_start=i.month_start
            order by month_start
            """;
        var source=jdbc.query(sql,params,(rs,row)->new MonthRow(
            YearMonth.from(rs.getObject("month_start",LocalDate.class)),
            rs.getLong("incoming"),rs.getLong("approved")));
        var periods=new TreeMap<YearMonth,MonthRow>();
        for(var item:source) periods.put(item.month(),item);
        // The original incoming/approved SQL does NOT zero-fill between
        // dates. Only months with events are included in this legacy slice.
        List<DashboardFlowSnapshot.IncomingVsApprovedItem> rows=new ArrayList<>();
        for(var item:periods.values()){
            String label=DashboardDistributionLabels.month(item.month());
            rows.add(new DashboardFlowSnapshot.IncomingVsApprovedItem(
                item.month().toString(),label,label,item.incoming(),item.approved()));
        }

        var completeness=jdbc.queryForObject("""
            select count(*) filter(where n.writing_date is null) as missing_writing,
                   count(*) filter(where n.factory_id is null) as missing_factory,
                   count(*) filter(where n.production_line_id is null) as missing_line
            from core.notes n
            where not n.is_deleted
              and (cast(:fromDate as date) is null or %s >= cast(:fromDate as date))
              and (cast(:toDate as date) is null or %s <= cast(:toDate as date))
            """.formatted(basis,basis)+common,params,(rs,row)->List.of(
            new DashboardFlowSnapshot.DataCompletenessItem(
                "missing-writing-date","دون تاريخ كتابة",rs.getLong("missing_writing")),
            new DashboardFlowSnapshot.DataCompletenessItem(
                "missing-factory","دون مصنع",rs.getLong("missing_factory")),
            new DashboardFlowSnapshot.DataCompletenessItem(
                "missing-production-line","دون خط إنتاج",rs.getLong("missing_line"))));

        return new DashboardFlowSnapshot(rows,completeness,f);
    }

    private record MonthRow(YearMonth month,long incoming,long approved) {}
}
