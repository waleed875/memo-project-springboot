package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteNumberNormalizer;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteSavePolicy;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import io.github.waleed875.memo.modules.notes.domain.NoteStatus;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Use case for validating an intended create/edit without writing anything.
 * It is deliberately NOT exposed as POST/PUT /api/notes yet: this command is
 * only the first gate in the future transactional save orchestration.
 */
public final class NoteSavePreflight {
    private final NoteSaveCatalog catalog;
    private final MasterDataWriteGate gate;
    private final Clock clock;

    public NoteSavePreflight(NoteSaveCatalog catalog, MasterDataWriteGate gate, Clock clock) {
        this.catalog = catalog;
        this.gate = gate;
        this.clock = clock;
    }

    public CheckResult check(
        UserProfile actor, UUID existingNoteId, LocalDateTime expectedUpdatedAt,
        NoteSaveCandidate submitted
    ) {
        if (actor == null || actor.id() == null || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException ex) {
            throw new MasterDataForbiddenException();
        }
        if (role != MemoRole.ADMIN && role != MemoRole.DATA_ENTRY) {
            throw new MasterDataForbiddenException();
        }
        gate.requireWritable();

        if (submitted == null) {
            throw new NoteSaveValidationException("بيانات المذكرة غير صالحة أو فارغة.");
        }

        if (existingNoteId != null) {
            if (expectedUpdatedAt == null) {
                throw new NoteSaveValidationException("نسخة تعديل المذكرة مطلوبة.");
            }
            var previous = catalog.existing(existingNoteId)
                .orElseThrow(() -> new NoteSaveValidationException("المذكرة غير موجودة أو حُذفت."));
            if (!expectedUpdatedAt.equals(previous.updatedAt())) {
                throw new NoteDeleteConflict("تعذر الحفظ لأن المذكرة تغيرت. أعد تحميلها ثم حاول مجددًا.");
            }
            if (role == MemoRole.DATA_ENTRY && !actor.id().equals(previous.createdBy())) {
                throw new NoteAccessDenied();
            }
            NoteSavePolicy.ensureEditAllowed(role, previous.status(), submitted.saveAsDraft());
        }

        // DB field definitions are loaded after role and ownership checks.
        var activeFields = catalog.activeFields(submitted.noteTypeId());
        LocalDate today = LocalDate.now(clock);
        NoteSavePolicy.validate(submitted, activeFields, today);
        String normalized = NoteNumberNormalizer.normalize(submitted.noteNumber());
        if (normalized.isEmpty()) {
            throw new NoteSaveValidationException(
                "رقم المذكرة يجب أن يحتوي على حرف أو رقم واحد على الأقل بعد التطبيع.");
        }
        var numeric = NoteNumberNormalizer.positiveNumericValue(submitted.noteNumber());
        if (numeric.isEmpty()) {
            throw new NoteSaveValidationException(
                "رقم المذكرة يجب أن يكون عددًا صحيحًا موجبًا، ويمكن إدخال الأرقام العربية أو اللاتينية فقط.");
        }

        NoteStatus calculated = NoteSavePolicy.calculateStatus(
            activeFields, submitted.fieldValues(), submitted.saveAsDraft());
        if (!submitted.saveAsDraft() && calculated == NoteStatus.DRAFT) {
            throw new NoteSaveValidationException("لا يمكن إرسال المذكرة قبل مراجعة جميع البنود الإلزامية.");
        }
        return new CheckResult(normalized, numeric.getAsLong(), calculated, today);
    }

    public record CheckResult(
        String normalizedNumber,
        long numericNoteNumber,
        NoteStatus computedStatus,
        LocalDate operationalDate
    ) {}
}
