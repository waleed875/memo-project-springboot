package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Framework-independent use case. Only ADMIN may delete a memo, per C#.
 * The global write gate is evaluated before touching the repository.
 */
public final class NoteDeleteService {
    private final NoteDeleteRepository repository;
    private final MasterDataWriteGate gate;

    public NoteDeleteService(NoteDeleteRepository repository, MasterDataWriteGate gate) {
        this.repository = repository;
        this.gate = gate;
    }

    public void delete(UserProfile actor, UUID noteId, LocalDateTime expectedUpdatedAt) {
        if (actor == null || actor.id() == null || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        if (!"ADMIN".equals(actor.role())) throw new MasterDataForbiddenException();
        gate.requireWritable();
        if (noteId == null || noteId.equals(new UUID(0L, 0L)) || expectedUpdatedAt == null) {
            throw new MasterDataOperationException("معرّف المذكرة وتاريخ التعديل المتوقع مطلوبان.");
        }
        repository.delete(noteId, actor.id(), expectedUpdatedAt);
    }
}
