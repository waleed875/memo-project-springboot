package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteDetails;
import java.util.Optional;
import java.util.UUID;

/** Port: full detail retrieval must enforce the owner predicate in SQL too. */
public interface NoteDetailsReader {
    Optional<NoteOwnership> ownership(UUID noteId);
    Optional<NoteDetails> find(UUID noteId, UUID actorId, boolean ownOnly);
}
