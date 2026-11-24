package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteDetails;
import io.github.waleed875.memo.modules.notes.domain.NoteReadPolicy;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.Optional;
import java.util.UUID;

/**
 * Framework-free read use case. Ownership checked before loading binary data.
 * The SQL adapter also enforces the same ownership predicate for DATA_ENTRY.
 */
public final class NoteDetailsQueryService {
    private final NoteDetailsReader reader;
    private final NoteReadPolicy policy;

    public NoteDetailsQueryService(NoteDetailsReader reader, NoteReadPolicy policy) {
        this.reader = reader;
        this.policy = policy;
    }

    public Optional<NoteDetails> find(UserProfile actor, UUID noteId) {
        if (actor == null || actor.id() == null || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException invalidRole) {
            throw new NoteAccessDenied();
        }
        if (noteId == null) return Optional.empty();
        Optional<NoteOwnership> ownership = reader.ownership(noteId);
        if (ownership.isEmpty()) return Optional.empty();
        if (!policy.canRead(role, actor.id(), ownership.get().createdBy())) {
            throw new NoteAccessDenied();
        }
        return reader.find(noteId, actor.id(), role == MemoRole.DATA_ENTRY);
    }
}
