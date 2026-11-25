package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentBatchUploadCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteAttachmentBatchUploadRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachmentPolicy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional, append-only port of C# multi-file upload's primary-column
 * COALESCE rule. All files, primary metadata, note revision and per-file audit
 * records commit together or not at all. No HTTP route is enabled.
 */
@Repository
public class JdbcAdminNoteAttachmentBatchUploadRepository
    implements AdminNoteAttachmentBatchUploadRepository {

    private record Before(
        String fileName, String contentType, String status, UUID reviewerId,
        UUID approvedBy, LocalDateTime approvedAt, LocalDateTime updatedAt
    ) {}

    private final JdbcTemplate jdbc;

    public JdbcAdminNoteAttachmentBatchUploadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public BatchResult upload(AdminNoteAttachmentBatchUploadCommand command, UUID actorId) {
        // Fail closed even for a caller bypassing the application service.
        requireActiveAdmin(actorId);
        List<Before> found = jdbc.query("""
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
            ), command.noteId());

        if (found.isEmpty() || !command.expectedUpdatedAt().equals(found.getFirst().updatedAt())) {
            throw new NoteDraftEditConflict(
                "تغيرت المذكرة أثناء رفع المرفقات؛ أعد تحميلها ثم حاول مجدداً.");
        }

        // Revalidate the authoritative file contents inside the transaction.
        // This also closes a repository-only bypass of application validation.
        List<UploadedFile> uploaded = new ArrayList<>();
        String firstName = null, firstContentType = null;
        byte[] firstBytes = null;
        long total = 0;
        if (command.attachments() == null || command.attachments().isEmpty()
            || command.attachments().size() > 20) {
            throw new io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException(
                "عدد المرفقات غير صالح.");
        }
        for (NoteAttachment attachment : command.attachments()) {
            String type = NoteAttachmentPolicy.validate(attachment);
            byte[] bytes = attachment.bytes();
            total += bytes.length;
            if (total > 40L * 1024 * 1024) {
                throw new io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException(
                    "يتجاوز مجموع أحجام المرفقات الحد المسموح.");
            }
            if (firstBytes == null) {
                firstName = attachment.fileName();
                firstContentType = type;
                firstBytes = bytes;
            }
            UUID attachmentId = UUID.randomUUID();
            jdbc.update("""
                insert into core.note_attachments(
                    id, note_id, file_name, content_type, file_data,
                    file_size, created_at, created_by)
                values (?, ?, ?, ?, ?, ?, clock_timestamp(), ?)
                """, attachmentId, command.noteId(), attachment.fileName(), type, bytes,
                (long) bytes.length, actorId);
            uploaded.add(new UploadedFile(
                attachmentId, attachment.fileName(), type, bytes.length));
        }

        // C# multi-upload updates the legacy primary values only if null.
        // The separate single-upload command intentionally always replaces.
        int updated = jdbc.update("""
            update core.notes
               set attachment_file_name = coalesce(attachment_file_name, ?),
                   attachment_content_type = coalesce(attachment_content_type, ?),
                   attachment_data = coalesce(attachment_data, ?),
                   updated_by = ?
             where id = ? and not is_deleted and updated_at = ?
            """, firstName, firstContentType, firstBytes, actorId,
            command.noteId(), command.expectedUpdatedAt());
        if (updated != 1) {
            throw new NoteDraftEditConflict(
                "تغيرت المذكرة أثناء حفظ دفعة المرفقات.");
        }

        LocalDateTime revision = jdbc.queryForObject("""
            select updated_at from core.notes where id = ?
            """, LocalDateTime.class, command.noteId());

        // The audit follows original multi-upload per-attachment record_id
        // semantics. No binary data is copied into audit.logs.
        for (UploadedFile file : uploaded) {
            jdbc.update("""
                insert into audit.logs(
                    user_id, action_type, schema_name, table_name, record_id,
                    description, old_value, new_value)
                values (
                    ?, 'NOTE_ATTACHMENT_UPLOADED', 'core', 'note_attachments', ?,
                    'تم رفع مرفق ضمن دفعة وحفظ بياناته الوصفية',
                    null,
                    jsonb_build_object(
                        'noteId', cast(? as text),
                        'fileName', ?, 'contentType', ?, 'fileSize', ?,
                        'status', ?, 'updatedAt', cast(? as timestamp)))
                """, actorId, file.id(),
                command.noteId().toString(), file.fileName(), file.contentType(),
                file.fileSize(), found.getFirst().status(), revision);
        }
        return new BatchResult(uploaded, revision);
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
}
