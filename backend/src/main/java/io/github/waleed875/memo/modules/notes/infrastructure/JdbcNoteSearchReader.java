package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.notes.application.NoteSearchReader;
import io.github.waleed875.memo.modules.notes.domain.NoteSearchCriteria;
import io.github.waleed875.memo.modules.notes.domain.NoteSearchItem;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import io.github.waleed875.memo.modules.notes.domain.PagedNotes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exact legacy reporting.vw_note_details projection and scoped filters.
 * Count and page are read from one repeatable-read snapshot for consistency.
 *
 * All user values are bound parameters. Boolean, UUID and nullable filters are
 * applied by conditional fixed SQL fragments; never interpolate request text.
 */
@Repository
public class JdbcNoteSearchReader implements NoteSearchReader {
    private static final String FROM = """
        from reporting.vw_note_details d
        join core.notes n on n.id = d.note_id and n.is_deleted = false
        join core.note_types note_type_scope on note_type_scope.id = d.note_type_id
        """;

    private static final String SELECT = """
        select d.note_id, d.note_number, d.note_type_name, d.company_name,
               d.writing_date, d.arrival_at, d.status,
               d.total_fields, d.active_count, d.inactive_count, d.unknown_count,
               d.accuracy_percentage, d.error_percentage,
               d.created_by, d.created_by_name, d.created_at, d.review_started_at,
               d.approved_at, d.approved_by_name, n.updated_at,
               d.factory_id, d.factory_name, d.production_line_id, d.production_line_name,
               n.is_loot, n.is_invoice,
               (select count(*) from core.note_links nl
                where nl.source_note_id = d.note_id
                   or nl.related_note_id = d.note_id) as link_count
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcNoteSearchReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PagedNotes search(NoteSearchCriteria criteria) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        parameters.addValue("from", criteria.from());
        parameters.addValue("to", criteria.to());

        StringBuilder where = new StringBuilder(" where ");
        if ("WritingDate".equalsIgnoreCase(criteria.dateBasis())) {
            where.append("""
                (case when d.writing_date is not null
                      then d.writing_date between :from and :to
                      else (d.arrival_at between :from and :to)
                end)
                """);
            // Legacy uses arrival as fallback only if hasWritingDate is null.
            if (criteria.hasWritingDate() != null) {
                where.setLength(0);
                where.append("""
                    where d.writing_date is not null
                      and d.writing_date between :from and :to
                    """);
            }
        } else {
            where.append(" d.arrival_at between :from and :to ");
        }
        if (criteria.hasWritingDate() != null) {
            where.append(criteria.hasWritingDate()
                ? " and d.writing_date is not null "
                : " and d.writing_date is null ");
        }

        filter(where, parameters, "d.company_id", "companyId", criteria.companyId());
        filter(where, parameters, "d.factory_id", "factoryId", criteria.factoryId());
        filter(where, parameters, "d.production_line_id", "productionLineId", criteria.productionLineId());
        filter(where, parameters, "d.note_type_id", "noteTypeId", criteria.noteTypeId());
        filter(where, parameters, "d.created_by", "createdBy", criteria.createdBy());
        if (criteria.status() != null) {
            filter(where, parameters, "d.status", "status", criteria.status().name());
        }
        if (criteria.noteNumber() != null && !criteria.noteNumber().isBlank()) {
            where.append(" and d.note_number ilike :noteNumber ");
            parameters.addValue("noteNumber", "%" + criteria.noteNumber().trim() + "%");
        }
        if (criteria.entryCutoffDate() != null) {
            where.append(" and d.arrival_at <= :entryCutoffDate ");
            parameters.addValue("entryCutoffDate", criteria.entryCutoffDate());
        }

        if (criteria.isLoot() != null) {
            String sourceExists = """
                exists (select 1 from core.note_sources s
                        where s.note_id = d.note_id and s.source_type = 'LOOT')
                """;
            where.append(criteria.isLoot()
                ? " and (coalesce(n.is_loot, false) = true or " + sourceExists + ") "
                : " and (coalesce(n.is_loot, false) = false and not " + sourceExists + ") ");
        }
        if (criteria.isInvoice() != null) {
            filter(where, parameters, "n.is_invoice", "isInvoice", criteria.isInvoice());
        }
        if (Boolean.TRUE.equals(criteria.unlinkedOnly())) {
            where.append("""
                 and note_type_scope.code = 'ENTRY'
                 and not coalesce(n.is_loot, false)
                 and not exists (
                     select 1 from core.note_sources s
                     where s.note_id = d.note_id and s.source_type = 'LOOT'
                 )
                 and not exists (
                     select 1 from core.v_note_links_compat link
                     where link.source_note_id = d.note_id
                        or link.related_note_id = d.note_id
                 )
                """);
        }

        Integer count = jdbc.queryForObject(
            "select count(*) " + FROM + where, parameters, Integer.class);
        int total = count == null ? 0 : count;
        int totalPages = total == 0 ? 0 : 1 + (total - 1) / criteria.pageSize();
        int page = totalPages > 0
            ? Math.min(criteria.pageNumber(), totalPages)
            : criteria.pageNumber();

        parameters.addValue("limit", criteria.pageSize());
        parameters.addValue("offset", (long) (page - 1) * criteria.pageSize());

        List<NoteSearchItem> results = jdbc.query(
            SELECT + FROM + where + """
                order by d.arrival_at desc, d.created_at desc, d.note_id desc
                limit :limit offset :offset
                """, parameters, this::map);
        return PagedNotes.of(results, total, page, criteria.pageSize());
    }

    private void filter(
        StringBuilder where,
        MapSqlParameterSource parameters,
        String column,
        String name,
        Object value
    ) {
        if (value != null) {
            where.append(" and ").append(column).append(" = :").append(name).append(' ');
            parameters.addValue(name, value);
        }
    }

    private NoteSearchItem map(ResultSet rs, int row) throws SQLException {
        java.sql.Date writing = rs.getDate("writing_date");
        java.sql.Date arrival = rs.getDate("arrival_at");
        return new NoteSearchItem(
            rs.getObject("note_id", java.util.UUID.class),
            rs.getString("note_number"),
            rs.getString("note_type_name"), rs.getString("company_name"),
            rs.getObject("factory_id", java.util.UUID.class), rs.getString("factory_name"),
            rs.getObject("production_line_id", java.util.UUID.class), rs.getString("production_line_name"),
            writing == null ? null : writing.toLocalDate(),
            arrival == null ? null : arrival.toLocalDate(),
            NoteStatus.valueOf(rs.getString("status")),
            null, // C# SearchNotes explicitly returns ImagePath = null.
            rs.getObject("created_by", java.util.UUID.class), rs.getString("created_by_name"),
            rs.getObject("created_at", java.time.LocalDateTime.class),
            rs.getObject("review_started_at", java.time.LocalDateTime.class),
            rs.getObject("approved_at", java.time.LocalDateTime.class),
            rs.getString("approved_by_name"),
            rs.getObject("updated_at", java.time.LocalDateTime.class),
            rs.getInt("total_fields"), rs.getInt("active_count"),
            rs.getInt("inactive_count"), rs.getInt("unknown_count"),
            rs.getBigDecimal("accuracy_percentage"), rs.getBigDecimal("error_percentage"),
            rs.getBoolean("is_loot"), rs.getBoolean("is_invoice"), rs.getInt("link_count")
        );
    }
}
