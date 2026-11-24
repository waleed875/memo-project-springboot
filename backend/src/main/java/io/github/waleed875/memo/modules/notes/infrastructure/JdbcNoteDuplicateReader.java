package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.notes.application.NoteDuplicateReader;
import io.github.waleed875.memo.modules.notes.domain.NoteDuplicate;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL 062 numeric/year duplicate semantics; read only.
 * Does NOT authorize creation or resolve duplicate classifications.
 */
@Repository
public class JdbcNoteDuplicateReader implements NoteDuplicateReader {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcNoteDuplicateReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NoteDuplicate> find(UUID currentNoteId, UUID companyId,
        UUID noteTypeId, String normalizedNumber, Long numericNumber,
        Integer noteYear, LocalDate exactWritingDate, boolean isInvoice
    ) {
        var params = new MapSqlParameterSource()
            .addValue("company", companyId)
            .addValue("noteType", noteTypeId)
            .addValue("normalized", normalizedNumber)
            .addValue("numeric", numericNumber, Types.BIGINT)
            .addValue("year", noteYear, Types.INTEGER)
            .addValue("current", currentNoteId, Types.OTHER)
            .addValue("writing", exactWritingDate, Types.DATE)
            .addValue("invoice", isInvoice);
        return jdbc.query("""
            select n.id, n.note_number, n.company_id, c.name as company_name,
                   n.factory_id, f.name as factory_name,
                   n.production_line_id, pl.name as production_line_name,
                   n.note_type_id, nt.name_ar as note_type_name,
                   n.writing_date, n.arrival_at, n.created_at,
                   n.attachment_file_name, n.attachment_content_type
            from core.notes n
            join core.companies c on c.id = n.company_id
            join core.note_types nt on nt.id = n.note_type_id
            left join core.factories f on f.id = n.factory_id
            left join core.production_lines pl on pl.id = n.production_line_id
            where n.is_deleted = false
              and n.company_id = :company
              and n.note_type_id = :noteType
              and (
                  (:numeric is not null and n.note_number_value = :numeric
                   and (:year is null or n.note_year = :year))
                  or (n.note_number_value is null
                      and n.note_number_normalized = :normalized
                      and (:year is null
                           or extract(year from n.writing_date)::int = :year))
              )
              and (cast(:current as uuid) is null or n.id <> :current)
              and (:invoice = false
                   or n.writing_date is not distinct from :writing)
            order by n.created_at desc
            """, params, (rs, row) -> {
                var writing = rs.getDate("writing_date");
                var arrival = rs.getDate("arrival_at");
                return new NoteDuplicate(
                    rs.getObject("id", UUID.class), rs.getString("note_number"),
                    rs.getObject("company_id", UUID.class), rs.getString("company_name"),
                    rs.getObject("factory_id", UUID.class), rs.getString("factory_name"),
                    rs.getObject("production_line_id", UUID.class),
                    rs.getString("production_line_name"),
                    rs.getObject("note_type_id", UUID.class), rs.getString("note_type_name"),
                    writing == null ? null : writing.toLocalDate(),
                    arrival.toLocalDate(),
                    rs.getObject("created_at", java.time.LocalDateTime.class),
                    rs.getString("attachment_file_name"),
                    rs.getString("attachment_content_type")
                );
            });
    }
}
