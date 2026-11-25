package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.notes.application.NoteDeleteConflict;
import io.github.waleed875.memo.modules.notes.application.NoteDeleteRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletion, unlinking and audit are one PostgreSQL transaction.
 * Lock and validate the version BEFORE deleting any links.
 * A failed audit insert rolls back all changes.
 */
@Repository
public class JdbcNoteDeleteRepository implements NoteDeleteRepository {
    private final JdbcTemplate jdbc;

    public JdbcNoteDeleteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void delete(UUID noteId, UUID actorId, LocalDateTime expectedUpdatedAt) {
        // Global link lock before note row lock; deletion removes links.
        NoteLinkWriteLock.acquire(jdbc);
        List<String> details = jdbc.query("""
            select note_number from core.notes
            where id = ? and is_deleted = false and updated_at = ?
            for update
            """, (rs, n) -> rs.getString(1), noteId, expectedUpdatedAt);
        if (details.isEmpty()) {
            throw new NoteDeleteConflict(
                "تعذر الحذف لأن حالة المذكرة تغيّرت. أعد تحميلها ثم حاول مجددًا.");
        }
        String noteNumber = details.get(0);
        List<String> names = jdbc.query("""
            select coalesce(nullif(full_name, ''), username)
            from auth.users where id = ?
            """, (rs, n) -> rs.getString(1), actorId);
        String actorName = names.isEmpty() ? actorId.toString() : names.get(0);

        int count = jdbc.update("""
            delete from core.note_links
            where source_note_id = ? or related_note_id = ?
            """, noteId, noteId);
        if (count > 0) {
            jdbc.update("""
                insert into audit.logs(
                    user_id, action_type, schema_name, table_name, record_id, description)
                values (?, 'DELETE_LINK', 'core', 'note_links', ?, ?)
                """, actorId, noteId, "إزالة " + count + " رابط قبل حذف المذكرة");
        }

        int updated = jdbc.update("""
            update core.notes set
                is_deleted = true, deleted_at = now(),
                deleted_by = ?, updated_by = ?
            where id = ? and is_deleted = false and updated_at = ?
            """, actorId, actorId, noteId, expectedUpdatedAt);
        if (updated != 1) {
            throw new NoteDeleteConflict(
                "تعذر الحذف لأن حالة المذكرة تغيّرت. أعد تحميلها ثم حاول مجددًا.");
        }
        // Structured JSONB audit: DB clock and actor metadata, no client-supplied identity.
        jdbc.update("""
            insert into audit.logs(
                user_id, action_type, schema_name, table_name, record_id,
                description, old_value, new_value)
            values (?, 'DELETE_NOTE', 'core', 'notes', ?,
                'حذف منطقي لمذكرة من مدير النظام',
                jsonb_build_object('isDeleted', false, 'noteNumber', ?),
                jsonb_build_object(
                    'action', 'حذف مذكرة', 'noteNumber', ?, 'deletedBy', ?,
                    'deletedByUserId', cast(? as text),
                    'deletedAt', now()))
            """, actorId, noteId, noteNumber, noteNumber, actorName, actorId.toString());
    }
}
