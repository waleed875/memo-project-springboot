package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Restricted internal use case for a DATA_ENTRY's own DRAFT or RETURNED memo.
 * ADMIN editing preserves workflow state in the C# original and therefore needs
 * its own command; it is intentionally not accepted here.
 */
public final class NoteDraftEditService {
    private static final UUID ZERO = new UUID(0, 0);
    private final NoteDraftEditRepository repository;
    private final MasterDataWriteGate writeGate;
    private final Clock clock;

    public NoteDraftEditService(NoteDraftEditRepository repository,
                                MasterDataWriteGate writeGate, Clock clock) {
        this.repository = repository;
        this.writeGate = writeGate;
        this.clock = clock;
    }

    public NoteDraftEditRepository.EditResult edit(UserProfile actor, NoteDraftEditCommand command) {
        if (actor == null || actor.id() == null || ZERO.equals(actor.id())
            || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException ignored) {
            throw new MasterDataForbiddenException();
        }
        if (role != MemoRole.DATA_ENTRY) throw new MasterDataForbiddenException();
        writeGate.requireWritable();
        if (command == null || command.noteId() == null || ZERO.equals(command.noteId())
            || command.expectedUpdatedAt() == null || command.fieldValues() == null) {
            throw new NoteSaveValidationException(
                "معرّف المذكرة ونسخة التعديل والمؤشرات مطلوبة.");
        }
        return repository.edit(command, actor.id(), LocalDate.now(clock));
    }
}
