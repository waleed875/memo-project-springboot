package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminNoteTextEditCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteTextEditRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restricted admin edit: update only core.notes.notes / updated_by.
 * Audit insert shares the same transaction; any failure rolls back.
 * Unlike normal resubmission, all workflow and approval data is preserved.
 */
@Repository
public class JdbcAdminNoteTextEditRepository implements AdminNoteTextEditRepository {
    private record Before(String notes, String status, UUID reviewerId,
                          LocalDateTime reviewStartedAt, UUID approvedBy,
                          LocalDateTime approvedAt, LocalDateTime updatedAt) {}

    private final JdbcTemplate jdbc;
    public JdbcAdminNoteTextEditRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public LocalDateTime updateText(AdminNoteTextEditCommand command, UUID actorId) {
        List<String> roles = jdbc.query("""
            select r.code from auth.users u
            join auth.roles r on r.id = u.role_id
            where u.id = ? and u.is_active and not u.is_deleted and r.is_active
            for share of u, r
            """, (rs, index) -> rs.getString(1), actorId);
        if (roles.size() != 1 || !"ADMIN".equals(roles.getFirst()))
            throw new MasterDataForbiddenException();

        List<Before> prior = jdbc.query("""
            select notes, status, reviewer_id, review_started_at,
                   approved_by, approved_at, updated_at
              from core.notes
             where id = ? and not is_deleted
             for update
            """, (rs, index) -> new Before(
                rs.getString("notes"), rs.getString("status"),
                rs.getObject("reviewer_id", UUID.class),
                rs.getObject("review_started_at", LocalDateTime.class),
                rs.getObject("approved_by", UUID.class),
                rs.getObject("approved_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
            ), command.noteId());
        if (prior.isEmpty() || !command.expectedUpdatedAt().equals(prior.getFirst().updatedAt())) {
            throw new NoteDraftEditConflict("تعذر الحفظ لأن حالة المذكرة تغيّرت. أعد تحميلها ثم حاول مجدداً.");
        }
        Before old = prior.getFirst();
        int changed = jdbc.update("""
            update core.notes set notes = ?, updated_by = ?
            where id = ? and not is_deleted and updated_at = ?
            """, command.notes(), actorId, command.noteId(), command.expectedUpdatedAt());
        if (changed != 1) throw new NoteDraftEditConflict("تغيرت المذكرة أثناء الحفظ.");
        LocalDateTime updatedAt = jdbc.queryForObject(
            "select updated_at from core.notes where id = ?", LocalDateTime.class, command.noteId());

        jdbc.update("""
            insert into audit.logs(
                user_id, action_type, schema_name, table_name, record_id,
                description, old_value, new_value
            ) values (?, 'ADMIN_EDIT_NOTE', 'core', 'notes', ?,
                'تعديل إداري مع حفظ الحالة وبيانات سير العمل',
                jsonb_build_object(
                    'notes', cast(? as text), 'status', ?,
                    'reviewerId', cast(? as text), 'reviewStartedAt', cast(? as timestamp),
                    'approvedBy', cast(? as text), 'approvedAt', cast(? as timestamp),
                    'updatedAt', cast(? as timestamp)),
                jsonb_build_object(
                    'notes', cast(? as text), 'status', ?,
                    'reviewerId', cast(? as text), 'reviewStartedAt', cast(? as timestamp),
                    'approvedBy', cast(? as text), 'approvedAt', cast(? as timestamp),
                    'updatedAt', cast(? as timestamp)))
            """, actorId, command.noteId(),
            old.notes(), old.status(), asText(old.reviewerId()),
            old.reviewStartedAt(), asText(old.approvedBy()), old.approvedAt(), old.updatedAt(),
            command.notes(), old.status(), asText(old.reviewerId()),
            old.reviewStartedAt(), asText(old.approvedBy()), old.approvedAt(), updatedAt);
        return updatedAt;
    }

    private static String asText(UUID id) { return id == null ? null : id.toString(); }
}
