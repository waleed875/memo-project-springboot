package io.github.waleed875.memo.modules.notes.application;
import java.util.Optional;
import java.util.UUID;
/** The implementation must verify the active role and note owner in the same query as file bytes. */
public interface NoteAttachmentDownloadReader {
    Optional<DownloadedNoteAttachment> find(UUID noteId, UUID attachmentId, UUID actorId);
}
