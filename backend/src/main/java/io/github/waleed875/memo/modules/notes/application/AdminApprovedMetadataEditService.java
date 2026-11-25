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
 * ADMIN-only approved metadata edit. Preserve approval/reviewer/history
 * independently of any stale role claim in an HTTP session.
 */
public final class AdminApprovedMetadataEditService {
    private static final UUID ZERO = new UUID(0, 0);
    private final AdminApprovedMetadataEditRepository repository;
    private final MasterDataWriteGate gate;
    private final Clock clock;

    public AdminApprovedMetadataEditService(AdminApprovedMetadataEditRepository repository,
                                             MasterDataWriteGate gate, Clock clock) {
        this.repository = repository;
        this.gate = gate;
        this.clock = clock;
    }

    public LocalDateTime edit(UserProfile actor, AdminApprovedMetadataEditCommand command) {
        if (actor == null || actor.id() == null || ZERO.equals(actor.id())
            || actor.role() == null) throw new UnauthenticatedMasterDataAccess();
        try {
            if (MemoRole.fromDatabase(actor.role()) != MemoRole.ADMIN)
                throw new MasterDataForbiddenException();
        } catch (IllegalArgumentException ex) {
            throw new MasterDataForbiddenException();
        }
        gate.requireWritable();
        if (command == null || command.noteId() == null || ZERO.equals(command.noteId())
            || command.expectedUpdatedAt() == null || command.metadata() == null
            || command.metadata().noteNumber() == null
            || command.metadata().companyId() == null
            || ZERO.equals(command.metadata().companyId())
            || command.metadata().noteTypeId() == null
            || ZERO.equals(command.metadata().noteTypeId())
            || command.metadata().arrivalAt() == null) {
            throw new NoteSaveValidationException("تعديل بيانات المذكرة يتطلب القيم الأساسية والنسخة الحالية.");
        }
        return repository.edit(command, actor.id(), LocalDate.now(clock));
    }
}
