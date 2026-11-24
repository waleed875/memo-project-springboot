package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.LocalDateTime;
import java.util.UUID;

/** Narrow, framework-free administrative edit boundary. */
public final class AdminNoteTextEditService {
    private static final UUID EMPTY = new UUID(0L, 0L);
    private final AdminNoteTextEditRepository repository;
    private final MasterDataWriteGate gate;

    public AdminNoteTextEditService(AdminNoteTextEditRepository repository, MasterDataWriteGate gate) {
        this.repository = repository;
        this.gate = gate;
    }

    public LocalDateTime updateText(UserProfile actor, AdminNoteTextEditCommand command) {
        if (actor == null || actor.id() == null || EMPTY.equals(actor.id())
            || actor.role() == null) throw new UnauthenticatedMasterDataAccess();
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException ex) {
            throw new MasterDataForbiddenException();
        }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();
        gate.requireWritable();
        if (command == null || command.noteId() == null || EMPTY.equals(command.noteId())
            || command.expectedUpdatedAt() == null) {
            throw new NoteSaveValidationException(
                "معرّف المذكرة ونسخة التعديل المتوقعة مطلوبان.");
        }
        // Deliberately permit null text to clear an existing optional note.
        return repository.updateText(command, actor.id());
    }
}
