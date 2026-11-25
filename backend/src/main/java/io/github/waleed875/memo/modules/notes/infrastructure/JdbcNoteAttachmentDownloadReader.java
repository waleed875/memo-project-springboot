package io.github.waleed875.memo.modules.notes.infrastructure;
import io.github.waleed875.memo.modules.notes.application.DownloadedNoteAttachment;
import io.github.waleed875.memo.modules.notes.application.NoteAttachmentDownloadReader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
/**
 * Current DB role, note ownership, attachment-to-note association and bounded
 * data size are checked by a single SELECT (not a previous client-side check).
 * No attachment-ID-only retrieval is allowed.
 */
@Repository
public class JdbcNoteAttachmentDownloadReader implements NoteAttachmentDownloadReader {
    private final JdbcTemplate jdbc;
    public JdbcNoteAttachmentDownloadReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional(readOnly = true)
    public Optional<DownloadedNoteAttachment> find(UUID noteId, UUID attachmentId, UUID actorId) {
        List<DownloadedNoteAttachment> items = jdbc.query("""
            select a.file_name, a.file_data
            from core.note_attachments a
            join core.notes n on n.id = a.note_id and not n.is_deleted
            join auth.users u on u.id = ? and u.is_active and not u.is_deleted
            join auth.roles r on r.id = u.role_id and r.is_active
            where a.note_id = ? and a.id = ?
              and a.file_data is not null
              and octet_length(a.file_data) between 1 and 10485760
              and (r.code in ('ADMIN', 'REVIEWER', 'VIEWER')
                   or (r.code = 'DATA_ENTRY' and n.created_by = u.id))
            """, (rs, row) -> new DownloadedNoteAttachment(
                rs.getString("file_name"), rs.getBytes("file_data")),
            actorId, noteId, attachmentId);
        return items.stream().findFirst();
    }
}
