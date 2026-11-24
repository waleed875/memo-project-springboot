package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.notes.application.NoteDetailsReader;
import io.github.waleed875.memo.modules.notes.application.NoteOwnership;
import io.github.waleed875.memo.modules.notes.domain.IndicatorValue;
import io.github.waleed875.memo.modules.notes.domain.NoteDetails;
import io.github.waleed875.memo.modules.notes.domain.NoteFieldValue;
import io.github.waleed875.memo.modules.notes.domain.NoteReviewHistory;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only adapter against the existing memo schema (migration 070).
 *
 * Ownership is checked by the application before any attachment is read;
 * the full query additionally restricts DATA_ENTRY to its own record.
 * The four SQL reads share a repeatable-read transaction snapshot to prevent
 * ownership/content races while collecting dependent rows.
 */
@Repository
public class JdbcNoteDetailsReader implements NoteDetailsReader {
    private final JdbcTemplate jdbc;

    public JdbcNoteDetailsReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<NoteOwnership> ownership(UUID noteId) {
        List<NoteOwnership> result = jdbc.query("""
            select created_by from core.notes
            where id = ? and is_deleted = false
            """, (rs, n) -> new NoteOwnership(rs.getObject(1, UUID.class)), noteId);
        return result.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Optional<NoteDetails> find(UUID noteId, UUID actorId, boolean ownOnly) {
        List<NoteDetails> notes = jdbc.query("""
            select n.id, n.note_number, n.note_type_id, nt.name_ar as note_type_name,
                   n.company_id, c.name as company_name,
                   n.factory_id, f.name as factory_name,
                   n.production_line_id, pl.name as production_line_name,
                   n.writing_date, n.arrival_at, n.image_path, n.file_path,
                   n.attachment_file_name, n.attachment_content_type, n.attachment_data,
                   n.notes, n.status, n.created_by, creator.full_name as created_by_name,
                   n.created_at, n.updated_by, n.updated_at,
                   n.approved_by, n.approved_at, approver.full_name as approved_by_name,
                   n.reviewer_id, reviewer.full_name as reviewer_name,
                   n.review_started_at, n.return_reason, n.duplicate_reason,
                   (n.is_loot or exists (
                       select 1 from core.note_sources s
                       where s.note_id = n.id and s.source_type = 'LOOT')) as is_loot,
                   n.is_invoice
            from core.notes n
            join core.note_types nt on nt.id = n.note_type_id
            join core.companies c on c.id = n.company_id
            left join core.factories f on f.id = n.factory_id
            left join core.production_lines pl on pl.id = n.production_line_id
            left join auth.users creator on creator.id = n.created_by
            left join auth.users approver on approver.id = n.approved_by
            left join auth.users reviewer on reviewer.id = n.reviewer_id
            where n.id = ? and n.is_deleted = false
              and (? = false or n.created_by = ?)
            """, (rs, row) -> map(rs, noteId), noteId, ownOnly, actorId);
        return notes.stream().findFirst();
    }

    private NoteDetails map(ResultSet rs, UUID noteId) throws SQLException {
        List<UUID> links = jdbc.query("""
            select distinct
              case when source_note_id = ? then related_note_id else source_note_id end
            from core.v_note_links_compat
            where source_note_id = ? or related_note_id = ?
            """, (r, row) -> r.getObject(1, UUID.class), noteId, noteId, noteId);

        List<NoteFieldValue> fieldValues = jdbc.query("""
            select nfv.id, nfv.note_id, nfv.field_id, nf.name_ar as field_name,
                   nfv.field_value, nfv.inactive_reason
            from core.note_field_values nfv
            join core.note_fields nf on nf.id = nfv.field_id
            where nfv.note_id = ?
            order by nf.default_sort_order
            """, (r, row) -> new NoteFieldValue(
                r.getObject("id", UUID.class),
                r.getObject("note_id", UUID.class),
                r.getObject("field_id", UUID.class),
                r.getString("field_name"),
                IndicatorValue.fromDatabase(r.getShort("field_value")),
                r.getString("inactive_reason")
            ), noteId);

        List<NoteReviewHistory> history = jdbc.query("""
            select h.action, h.reason, u.full_name as user_name,
                   role.name_ar as user_role_name, h.created_at
            from core.note_review_history h
            left join auth.users u on u.id = h.user_id
            left join auth.roles role on role.id = u.role_id
            where h.note_id = ?
            order by h.created_at desc
            """, (r, row) -> new NoteReviewHistory(
                r.getString("action"), r.getString("reason"),
                r.getString("user_name"), r.getString("user_role_name"),
                r.getObject("created_at", LocalDateTime.class)
            ), noteId);

        java.sql.Date writing = rs.getDate("writing_date");
        java.sql.Date arrival = rs.getDate("arrival_at");

        return new NoteDetails(
            rs.getObject("id", UUID.class),
            rs.getString("note_number"),
            rs.getObject("note_type_id", UUID.class), rs.getString("note_type_name"),
            rs.getObject("company_id", UUID.class), rs.getString("company_name"),
            rs.getObject("factory_id", UUID.class), rs.getString("factory_name"),
            rs.getObject("production_line_id", UUID.class), rs.getString("production_line_name"),
            writing == null ? null : writing.toLocalDate(),
            arrival == null ? null : arrival.toLocalDate(),
            rs.getString("image_path"), rs.getString("file_path"),
            rs.getString("attachment_file_name"), rs.getString("attachment_content_type"),
            rs.getBytes("attachment_data"),
            rs.getString("notes"),
            NoteStatus.valueOf(rs.getString("status")),
            rs.getObject("created_by", UUID.class), rs.getString("created_by_name"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_by", UUID.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getObject("approved_by", UUID.class),
            rs.getObject("approved_at", LocalDateTime.class), rs.getString("approved_by_name"),
            rs.getObject("reviewer_id", UUID.class), rs.getString("reviewer_name"),
            rs.getObject("review_started_at", LocalDateTime.class),
            rs.getString("return_reason"), rs.getString("duplicate_reason"),
            rs.getBoolean("is_loot"), rs.getBoolean("is_invoice"),
            links, fieldValues, history
        );
    }
}
