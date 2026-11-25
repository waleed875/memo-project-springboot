package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteAttachment;
import java.util.List;
import java.util.UUID;

/**
 * Restricted internal create: optional single attachment plus pre-existing
 * opposite-type note links. No legacy write HTTP route is enabled here.
 */
public record NoteCreateCommand(
    UUID operationId,
    UUID noteId,
    NoteSaveCandidate candidate,
    String notes,
    NoteAttachment attachment,
    List<UUID> relatedNoteIds,
    NoteDuplicateResolution resolution
) {
    public NoteCreateCommand {
        relatedNoteIds = relatedNoteIds == null ? List.of() : List.copyOf(relatedNoteIds);
    }

    /** Existing six-argument commands keep identical default semantics. */
    public NoteCreateCommand(
        UUID operationId, UUID noteId, NoteSaveCandidate candidate, String notes,
        NoteAttachment attachment, List<UUID> relatedNoteIds
    ) {
        this(operationId, noteId, candidate, notes, attachment, relatedNoteIds, null);
    }

    /** Backward-compatible original four-argument command. */
    public NoteCreateCommand(
        UUID operationId, UUID noteId, NoteSaveCandidate candidate, String notes
    ) {
        this(operationId, noteId, candidate, notes, null, List.of(), null);
    }
}
