package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentUploadRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachmentPolicy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional subset of C# POST /api/notes/{id}/attachment:
 * append one row to core.note_attachments, replace the primary legacy
 * attachment columns, and insert NOTE_ATTACHMENT_UPLOADED audit.
 *
 * Unlike C#, this internal migration slice is ADMIN-only, requires a revision
 * token, and is atomic. Existing secondary attachment rows are never deleted.
 * No public HTTP route is opened.
 */
@Repository
public class JdbcAdminNoteAttachmentUploadRepository
    implements AdminNoteAttachmentUploadRepository {

    private record Before(
        String fileName, String contentType, String status,
        UUID reviewerId, UUID approvedBy, LocalDateTime approvedAt,
        LocalDateTime updatedAt
    ) {}

    private final JdbcTemplate jdbc;

    public JdbcAdminNoteAttachmentUploadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public UploadResult upload(
        UUID noteId, LocalDateTime expectedUpdatedAt, NoteAttachment attachment, UUID actorId
    ) {
        requireActiveAdmin(actorId);
        List<Before> prior = jdbc.query("""
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
        if (prior.isEmpty() || !expectedUpdatedAt.equals(prior.getFirst().updatedAt())) {
            throw new NoteDraftEditConflict(
                "تعذر حفظ المرفق لأن المذكرة تغيرت. أعد تحميلها ثم حاول مجدداً.");
        }

        String contentType = NoteAttachmentPolicy.validate(attachment);
        byte[] bytes = attachment.bytes();
        UUID attachmentId = UUID.randomUUID();
        jdbc.update("""
            insert into core.note_attachments(
                id, note_id, file_name, content_type, file_data,
                file_size, created_at, created_by)
            values (?, ?, ?, ?, ?, ?, clock_timestamp(), ?)
            """, attachmentId, noteId, attachment.fileName(), contentType, bytes,
                (long) bytes.length, actorId);

        int updated = jdbc.update("""
            update core.notes
            set attachment_file_name = ?, attachment_content_type = ?,
                attachment_data = ?, updated_by = ?
            where id = ? and not is_deleted and updated_at = ?
            """, attachment.fileName(), contentType, bytes,
                actorId, noteId, expectedUpdatedAt);
        if (updated != 1) {
            throw new NoteDraftEditConflict("تغيرت المذكرة أثناء حفظ المرفق.");
        }
        LocalDateTime nextVersion = jdbc.queryForObject(
            "select updated_at from core.notes where id = ?",
            LocalDateTime.class, noteId);
        Before old = prior.getFirst();
        // Never store binary bytes in audit JSON: only metadata and the
        // freshly created attachment ID. No unrelated workflow fields mutate.
        jdbc.update("""
            insert into audit.logs (
                user_id, action_type, schema_name, table_name, record_id,
                description, old_value, new_value
            ) values (
                ?, 'NOTE_ATTACHMENT_UPLOADED', 'core', 'notes', ?,
                'تم رفع مرفق المذكرة وحفظ البيانات الوصفية',
                jsonb_build_object(
                    'fileName', cast(? as text), 'contentType', cast(? as text),
                    'status', ?, 'reviewerId', cast(? as text),
                    'approvedBy', cast(? as text), 'approvedAt', cast(? as timestamp),
                    'updatedAt', cast(? as timestamp)),
                jsonb_build_object(
                    'attachmentId', cast(? as text),
                    'fileName', ?, 'contentType', ?, 'fileSize', ?,
                    'status', ?, 'reviewerId', cast(? as text),
                    'approvedBy', cast(? as text), 'approvedAt', cast(? as timestamp),
                    'updatedAt', cast(? as timestamp))
            )
            """, actorId, noteId,
            old.fileName(), old.contentType(), old.status(), asText(old.reviewerId()),
            asText(old.approvedBy()), old.approvedAt(), old.updatedAt(),
            attachmentId.toString(), attachment.fileName(), contentType, (long) bytes.length,
            old.status(), asText(old.reviewerId()), asText(old.approvedBy()),
            old.approvedAt(), nextVersion);
        return new UploadResult(attachmentId, nextVersion, contentType, bytes.length);
    }

    private void requireActiveAdmin(UUID actorId) {
        List<String> roles = jdbc.query("""
            select r.code from auth.users u
            join auth.roles r on r.id = u.role_id
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
