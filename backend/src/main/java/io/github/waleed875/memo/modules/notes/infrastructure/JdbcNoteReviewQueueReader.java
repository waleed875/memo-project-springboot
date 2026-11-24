package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.notes.application.NoteReviewQueueReader;
import io.github.waleed875.memo.modules.notes.domain.NoteReviewQueueItem;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of NoteService.GetReviewQueueAsync, using real core.notes and auth tables.
 * Only unclaimed eligible notes and the caller's own UNDER_REVIEW notes are shown.
 * The legacy query also includes eligible statuses assigned to another reviewer
 * if those rows exist; avoid displaying that inconsistent claimed state here.
 */
@Repository
public class JdbcNoteReviewQueueReader implements NoteReviewQueueReader {
    private static final String QUERY = """
        select n.id, n.updated_at, n.note_number, c.name as company_name,
               nt.name_ar as note_type_name, factory.name as factory_name,
               production_line.name as production_line_name,
               n.arrival_at, n.status, n.created_by, creator.full_name as created_by_name,
               n.created_at, n.reviewer_id, reviewer.full_name as reviewer_name,
               n.review_started_at
        from core.notes n
        join core.companies c on c.id = n.company_id
        join core.note_types nt on nt.id = n.note_type_id
        left join core.factories factory on factory.id = n.factory_id
        left join core.production_lines production_line on production_line.id = n.production_line_id
        left join auth.users creator on creator.id = n.created_by
        left join auth.users reviewer on reviewer.id = n.reviewer_id
        where n.is_deleted = false
          and (n.created_by is null or n.created_by <> :reviewerId)
          and (
              (n.status in ('COMPLETE', 'HAS_ERRORS', 'NEEDS_REVIEW') and n.reviewer_id is null)
              or (n.status = 'UNDER_REVIEW' and n.reviewer_id = :reviewerId)
          )
        order by case when n.reviewer_id = :reviewerId then 0 else 1 end,
                 n.arrival_at asc, n.created_at asc, n.id asc
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcNoteReviewQueueReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NoteReviewQueueItem> findForReviewer(UUID reviewerId) {
        return jdbc.query(QUERY, Map.of("reviewerId", reviewerId), this::map);
    }

    private NoteReviewQueueItem map(ResultSet rs, int row) throws SQLException {
        return new NoteReviewQueueItem(
            rs.getObject("id", UUID.class),
            rs.getObject("updated_at", java.time.LocalDateTime.class),
            rs.getString("note_number"),
            rs.getString("company_name"),
            rs.getString("note_type_name"),
            rs.getString("factory_name"),
            rs.getString("production_line_name"),
            rs.getObject("arrival_at", java.time.LocalDate.class),
            NoteStatus.valueOf(rs.getString("status")),
            rs.getObject("created_by", UUID.class),
            rs.getString("created_by_name"),
            rs.getObject("created_at", java.time.LocalDateTime.class),
            rs.getObject("reviewer_id", UUID.class),
            rs.getString("reviewer_name"),
            rs.getObject("review_started_at", java.time.LocalDateTime.class)
        );
    }
}

