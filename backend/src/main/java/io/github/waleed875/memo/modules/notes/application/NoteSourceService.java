package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteReadPolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceDraft;
import io.github.waleed875.memo.modules.notes.domain.NoteSourcePolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceView;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.Optional;
import java.util.UUID;

/** No Spring imports; authorization, validation and read/write gating. */
public final class NoteSourceService {
    private final NoteDetailsReader details;
    private final NoteSourceStore store;
    private final MasterDataWriteGate gate;
    private final NoteReadPolicy readPolicy = new NoteReadPolicy();

    public NoteSourceService(
        NoteDetailsReader details, NoteSourceStore store, MasterDataWriteGate gate
    ) {
        this.details = details; this.store = store; this.gate = gate;
    }

    private record Actor(UUID id, MemoRole role) {}
    private Actor authenticate(UserProfile actor) {
        if (actor == null || actor.id() == null || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        try {
            return new Actor(actor.id(), MemoRole.fromDatabase(actor.role()));
        } catch (IllegalArgumentException unknown) {
            throw new NoteAccessDenied();
        }
    }

    public Optional<NoteSourceView> read(UserProfile actor, UUID noteId) {
        Actor current = authenticate(actor);
        var ownership = details.ownership(noteId);
        if (ownership.isEmpty()) return Optional.empty();
        if (!readPolicy.canRead(current.role(), current.id(), ownership.get().createdBy())) {
            throw new NoteAccessDenied();
        }
        return store.read(noteId, current.id(), current.role() == MemoRole.DATA_ENTRY);
    }

    public void save(UserProfile actor, UUID noteId, NoteSourceDraft draft) {
        Actor current = authenticate(actor);
        if (current.role() != MemoRole.ADMIN && current.role() != MemoRole.DATA_ENTRY) {
            throw new MasterDataForbiddenException();
        }
        gate.requireWritable();
        NoteSourcePolicy.validate(draft);
        store.save(noteId, current.id(), current.role() == MemoRole.DATA_ENTRY, draft);
    }

    public void remove(UserProfile actor, UUID noteId) {
        Actor current = authenticate(actor);
        if (current.role() != MemoRole.ADMIN && current.role() != MemoRole.DATA_ENTRY) {
            throw new MasterDataForbiddenException();
        }
        gate.requireWritable();
        store.remove(noteId, current.id(), current.role() == MemoRole.DATA_ENTRY);
    }
}
