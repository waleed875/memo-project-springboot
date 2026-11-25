package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.util.UUID;

/** Security and input boundary for the two no-new-note duplicate decisions. */
public final class NoteDuplicateDecisionService {
    private static final UUID ZERO = new UUID(0L, 0L);
    private final NoteDuplicateDecisionRepository repository;
    private final MasterDataWriteGate gate;

    public NoteDuplicateDecisionService(
        NoteDuplicateDecisionRepository repository, MasterDataWriteGate gate
    ) {
        this.repository = repository;
        this.gate = gate;
    }

    public void record(UserProfile actor, NoteDuplicateDecisionCommand command) {
        if (actor == null || actor.id() == null || ZERO.equals(actor.id())
            || actor.role() == null) throw new UnauthenticatedMasterDataAccess();
        if (!"ADMIN".equals(actor.role()) && !"DATA_ENTRY".equals(actor.role())) {
            throw new MasterDataForbiddenException();
        }
        gate.requireWritable();
        if (command == null || command.classification() == null
            || command.classification() == NoteDuplicateClassification.COMPANY_DUPLICATE_NUMBER
            || command.matchedNoteId() == null || ZERO.equals(command.matchedNoteId())
            || command.noteNumber() == null || command.noteNumber().isBlank()
            || command.companyId() == null || ZERO.equals(command.companyId())
            || command.noteTypeId() == null || ZERO.equals(command.noteTypeId())
            || (command.productionLineId() != null && command.factoryId() == null)) {
            throw new NoteSaveValidationException("بيانات قرار التكرار غير صالحة.");
        }
        repository.record(command, actor.id());
    }
}
