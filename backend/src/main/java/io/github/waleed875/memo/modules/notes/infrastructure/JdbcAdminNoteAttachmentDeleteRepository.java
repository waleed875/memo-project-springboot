package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentDeleteRepository;
import io.github.waleed875.memo.modules.notes.application.NoteAttachmentNotFound;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restricted C# DELETE /{id}/attachments/{attachmentId} implementation:
 * - database revalidates active ADMIN role
 * - note FOR UPDATE serializes against uploads and other attachment deletes
 * - DELETE scoped to note_id avoids cross-note IDOR
 * - primary legacy columns are replaced by oldest remaining attachment or null
 * - NOTE_ATTACHMENT_DELETED audit is in the same transaction
 *
 * No HTTP mapping is enabled by this internal repository.
 */
@Repository
public class JdbcAdminNoteAttachmentDeleteRepository
    implements AdminNoteAttachmentDeleteRepository {

    private record Before(
        String primaryFileName, String primaryContentType, String status,
        UUID reviewerId, UUID approvedBy, LocalDateTime approvedAt,
        LocalDateTime updatedAt
    ) {}

    private record Primary(String fileName, String contentType, byte[] bytes) {}

    private final JdbcTemplate jdbc;

    public JdbcAdminNoteAttachmentDeleteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public DeleteResult delete(
        UUID noteId, UUID attachmentId, LocalDateTime expectedUpdatedAt, UUID actorId
    ) {
        requireActiveAdmin(actorId);

        List<Before> notes = jdbc.query("""
            select attachment_file_name, attachment_content_type, status,
                   reviewer_id, approved_by, approved_at, updated_at
            from core.notes
            where id = ? and not is_deleted
            for update
            """, (rs, row) -> new Before(
                rs.getString("attachment_file_name"),
                rs.getString("attachment_content_type"),
                rs.getString("status"),
                rs.getObject("reviewer_id", UUID.class),
                rs.getObject("approved_by", UUID.class),
                rs.getObject("approved_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
            ), noteId);
        if (notes.isEmpty() || !expectedUpdatedAt.equals(notes.getFirst().updatedAt())) {
            throw new NoteDraftEditConflict(
                "تعذر حذف المرفق لأن المذكرة تغيرت. أعد تحميلها ثم حاول مجدداً.");
        }

        // DELETE ... RETURNING is a PostgreSQL query. Both IDs are mandatory:
        // a guessed attachment UUID belonging to a different memo cannot be deleted.
        List<String> deleted = jdbc.query("""
            delete from core.note_attachments
            where id = ? and note_id = ?
            returning file_name
            """, (rs, row) -> rs.getString("file_name"), attachmentId, noteId);
        if (deleted.isEmpty()) throw new NoteAttachmentNotFound();

        // C# picks the earliest remaining created_at. The UUID tie breaker
        // makes equal-timestamp results deterministic. Do not silently retain
        // a stale legacy primary attachment when the final row is deleted.
        List<Primary> remaining = jdbc.query("""
            select file_name, content_type, file_data
            from core.note_attachments
            where note_id = ?
            order by created_at asc, id asc
            limit 1
            """, (rs, row) -> new Primary(
                rs.getString("file_name"), rs.getString("content_type"),
                rs.getBytes("file_data")
            ), noteId);
        Primary next = remaining.isEmpty() ? null : remaining.getFirst();
        String nextName = next == null ? null : next.fileName();
        String nextContentType = next == null ? null : next.contentType();
        byte[] nextBytes = next == null ? null : next.bytes();

        int updated = jdbc.update("""
            update core.notes
            set attachment_file_name = ?, attachment_content_type = ?,
                attachment_data = ?, updated_by = ?
            where id = ? and not is_deleted and updated_at = ?
            """, nextName, nextContentType, nextBytes,
            actorId, noteId, expectedUpdatedAt);
        if (updated != 1) {
            throw new NoteDraftEditConflict("تغيرت المذكرة أثناء حذف المرفق.");
        }
        LocalDateTime nextVersion = jdbc.queryForObject(
            "select updated_at from core.notes where id = ?",
            LocalDateTime.class, noteId);
        Before old = notes.getFirst();

        // No attachment bytes in the audit; keep note id, file metadata,
        // and workflow status for traceability.
        jdbc.update("""
            insert into audit.logs (
                user_id, action_type, schema_name, table_name, record_id,
                description, old_value, new_value
            ) values (
                ?, 'NOTE_ATTACHMENT_DELETED', 'core', 'note_attachments', ?,
                'تم حذف مرفق المذكرة مع تحديث المرفق الأساسي',
                jsonb_build_object(
                    'noteId', cast(? as text), 'fileName', ?,
                    'primaryFileName', cast(? as text),
                    'primaryContentType', cast(? as text),
                    'status', ?, 'reviewerId', cast(? as text),
                    'approvedBy', cast(? as text), 'approvedAt', cast(? as timestamp),
                    'updatedAt', cast(? as timestamp)),
                jsonb_build_object(
                    'noteId', cast(? as text), 'primaryFileName', cast(? as text),
                    'primaryContentType', cast(? as text),
                    'status', ?, 'reviewerId', cast(? as text),
                    'approvedBy', cast(? as text), 'approvedAt', cast(? as timestamp),
                    'updatedAt', cast(? as timestamp))
            )
            """, actorId, attachmentId,
            noteId.toString(), deleted.getFirst(),
            old.primaryFileName(), old.primaryContentType(),
            old.status(), asText(old.reviewerId()), asText(old.approvedBy()),
            old.approvedAt(), old.updatedAt(),
            noteId.toString(), nextName, nextContentType,
            old.status(), asText(old.reviewerId()), asText(old.approvedBy()),
            old.approvedAt(), nextVersion);
        return new DeleteResult(attachmentId, deleted.getFirst(), nextName, nextVersion);
    }

    private void requireActiveAdmin(UUID actorId) {
        List<String> roles = jdbc.query("""
            select r.code
            from auth.users u join auth.roles r on r.id = u.role_id
            where u.id = ? and u.is_active and not u.is_deleted and r.is_active
            for share of u, r
            """, (rs, row) -> rs.getString(1), actorId);
        if (roles.size() != 1 || !"ADMIN".equals(roles.getFirst())) {
            throw new MasterDataForbiddenException();
        }
    }

    private static String asText(UUID id) {
        return id == null ? null : id.toString();
    }
}
