package io.github.waleed875.memo.modules.notes.application;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.Optional;
import java.util.UUID;
/** Fail closed: current DB role and note ownership are checked again in JDBC. */
public final class NoteAttachmentDownloadService {
    private static final UUID EMPTY = new UUID(0L, 0L);
    private final NoteAttachmentDownloadReader reader;
    public NoteAttachmentDownloadService(NoteAttachmentDownloadReader reader) { this.reader = reader; }
    public Optional<DownloadedNoteAttachment> find(UserProfile actor, UUID noteId, UUID attachmentId) {
        if (actor == null || actor.id() == null || EMPTY.equals(actor.id()) || actor.role() == null)
            throw new UnauthenticatedMasterDataAccess();
        try {
            MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException ex) {
            throw new NoteAccessDenied();
        }
        if (noteId == null || EMPTY.equals(noteId) || attachmentId == null || EMPTY.equals(attachmentId))
            return Optional.empty();
        return reader.find(noteId, attachmentId, actor.id());
    }
}
