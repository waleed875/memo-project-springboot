package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * No Spring/JDBC dependencies. Administrator only, fail closed with
 * the same read-only-mode guard used by other notes commands.
 */
public final class AdminNoteIndicatorEditService {
    private static final UUID EMPTY = new UUID(0, 0);
    private final AdminNoteIndicatorEditRepository repository;
    private final MasterDataWriteGate writeGate;
    private final Clock clock;

    public AdminNoteIndicatorEditService(
        AdminNoteIndicatorEditRepository repository, MasterDataWriteGate writeGate, Clock clock
    ) {
        this.repository = repository;
        this.writeGate = writeGate;
        this.clock = clock;
    }

    public LocalDateTime updateIndicators(UserProfile actor, AdminNoteIndicatorEditCommand command) {
        if (actor == null || actor.id() == null || EMPTY.equals(actor.id())
            || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException invalid) {
            throw new MasterDataForbiddenException();
        }
        if (role != MemoRole.ADMIN) throw new MasterDataForbiddenException();

        writeGate.requireWritable();
        if (command == null || command.noteId() == null || EMPTY.equals(command.noteId())
            || command.expectedUpdatedAt() == null || command.fieldValues() == null) {
            throw new NoteSaveValidationException(
                "معرّف المذكرة ونسخة التعديل والمؤشرات مطلوبة.");
        }
        return repository.updateIndicators(command, actor.id(), LocalDate.now(clock));
    }
}
