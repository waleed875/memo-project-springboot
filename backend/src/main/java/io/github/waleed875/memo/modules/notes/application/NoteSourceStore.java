package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteSourceDraft;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceView;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL adapter port. Writes, authorization recheck and audit are atomic. */
public interface NoteSourceStore {
    Optional<NoteSourceView> read(UUID noteId, UUID actorId, boolean ownOnly);
    void save(UUID noteId, UUID actorId, boolean ownOnly, NoteSourceDraft draft);
    void remove(UUID noteId, UUID actorId, boolean ownOnly);
}
