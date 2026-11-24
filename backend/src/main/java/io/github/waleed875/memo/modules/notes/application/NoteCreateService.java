package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Framework-independent create command boundary.
 * Metadata, duplicate checks and indicators are re-validated atomically
 * inside NoteCreator, not from a stale preflight result.
 */
public final class NoteCreateService {
    private static final UUID ZERO = new UUID(0, 0);
    private final NoteCreator creator;
    private final MasterDataWriteGate gate;
    private final Clock operationalClock;

    public NoteCreateService(NoteCreator creator, MasterDataWriteGate gate, Clock clock) {
        this.creator = creator;
        this.gate = gate;
        this.operationalClock = clock;
    }

    public UUID create(UserProfile actor, NoteCreateCommand command) {
        if (actor == null || actor.id() == null || actor.role() == null)
            throw new UnauthenticatedMasterDataAccess();
        if (!"ADMIN".equals(actor.role()) && !"DATA_ENTRY".equals(actor.role()))
            throw new MasterDataForbiddenException();
        gate.requireWritable();
        if (command == null || command.operationId() == null || ZERO.equals(command.operationId())
            || command.noteId() == null || ZERO.equals(command.noteId())
            || command.candidate() == null) {
            throw new NoteSaveValidationException(
                "معرّف العملية والمذكرة المثبتان وبيانات المذكرة مطلوبة.");
        }
        return creator.create(command, actor.id(), LocalDate.now(operationalClock));
    }
}
