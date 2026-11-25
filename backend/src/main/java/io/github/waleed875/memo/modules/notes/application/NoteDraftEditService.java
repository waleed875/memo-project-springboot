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
        if ((command.expectedRelatedNoteIds() == null) != (command.relatedNoteIds() == null)) {
            throw new NoteSaveValidationException(
                "تحديث الروابط يتطلب القائمة السابقة والقائمة المطلوبة صراحةً.");
        }
        if (command.synchronizesLinks()) {
            validateLinks(command.noteId(), command.expectedRelatedNoteIds());
            validateLinks(command.noteId(), command.relatedNoteIds());
        }
        if (command.metadata() != null) {
            var metadata = command.metadata();
            if (metadata.noteNumber() == null || metadata.noteNumber().isBlank()
                || metadata.companyId() == null || ZERO.equals(metadata.companyId())
                || metadata.noteTypeId() == null || ZERO.equals(metadata.noteTypeId())
                || metadata.arrivalAt() == null) {
                throw new NoteSaveValidationException(
                    "رقم المذكرة والشركة والنوع وتاريخ الوصول مطلوبة.");
            }
        }
        io.github.waleed875.memo.modules.notes.domain.NoteAttachmentPolicy.validate(
            command.attachment());
        return repository.edit(command, actor.id(), LocalDate.now(clock));
    }
    private static void validateLinks(UUID noteId, java.util.List<UUID> ids) {
        if (ids.size() > 100) {
            throw new NoteSaveValidationException("تجاوز عدد المذكرات المرتبطة الحد.");
        }
        var seen = new java.util.HashSet<UUID>();
        for (UUID id : ids) {
            if (id == null || ZERO.equals(id) || id.equals(noteId)
                || !seen.add(id)) {
                throw new NoteSaveValidationException(
                    "روابط المذكرات تحتوي على معرف فارغ أو ذاتي أو مكرر.");
            }
        }
    }

}
