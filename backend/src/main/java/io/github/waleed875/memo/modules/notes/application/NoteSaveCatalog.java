package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import io.github.waleed875.memo.modules.notes.domain.RequiredNoteField;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Query port for an eventual transactional save. Preflight does NOT persist.
 * Callers must recheck all data inside the save transaction and lock all
 * duplicate/relationship keys before any business side effects.
 */
public interface NoteSaveCatalog {
    List<RequiredNoteField> activeFields(UUID noteTypeId);
    Optional<ExistingNote> existing(UUID noteId);

    record ExistingNote(
        UUID id, UUID createdBy, NoteStatus status, LocalDateTime updatedAt
    ) {}
}
