package io.github.waleed875.memo.modules.notes.domain;

import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ports the core C# NoteValidator semantics. Does not grant permission to
 * persist: duplicate locking, FK validation, idempotency, attachments and
 * audit are separate mandatory transaction gates.
 */
public final class NoteSavePolicy {
    private static final UUID EMPTY = new UUID(0L, 0L);
    private NoteSavePolicy() {}

    public static void validate(
        NoteSaveCandidate draft, List<RequiredNoteField> activeDefinitions,
        LocalDate operationalToday
    ) {
        if (draft == null || activeDefinitions == null || operationalToday == null) {
            throw new NoteSaveValidationException("بيانات المذكرة غير صالحة أو فارغة.");
        }
        if (draft.fieldValues() == null) {
            throw new NoteSaveValidationException("قيم البنود مفقودة.");
        }
        if (draft.noteNumber() == null || draft.noteNumber().isBlank()) {
            throw new NoteSaveValidationException("رقم المذكرة مطلوب.");
        }
        if (draft.companyId() == null || EMPTY.equals(draft.companyId())) {
            throw new NoteSaveValidationException("الشركة مطلوبة.");
        }
        if (draft.noteTypeId() == null || EMPTY.equals(draft.noteTypeId())) {
            throw new NoteSaveValidationException("نوع المذكرة مطلوب.");
        }
        if (draft.arrivalAt() == null) {
            throw new NoteSaveValidationException("تاريخ الوصول مطلوب ولا يمكن افتراض تاريخ اليوم تلقائياً.");
        }
        if (draft.writingDate() != null && draft.arrivalAt().isBefore(draft.writingDate())) {
            throw new NoteSaveValidationException("لا يمكن أن يكون تاريخ كتابة المذكرة أحدث من تاريخ الوصول.");
        }
        if (draft.arrivalAt().isAfter(operationalToday)) {
            throw new NoteSaveValidationException("لا يمكن أن يكون تاريخ الوصول في المستقبل.");
        }
        Map<UUID, IndicatorValue> byId = new HashMap<>();
        for (var item : draft.fieldValues()) {
            if (item == null || item.value() == null || item.fieldId() == null) {
                throw new NoteSaveValidationException("قيمة البند غير صحيحة.");
            }
            // C# uses last matching value for persistence. For a new Java
            // command we reject ambiguous duplicates before opening writes.
            if (byId.putIfAbsent(item.fieldId(), item.value()) != null) {
                throw new NoteSaveValidationException("لا يمكن تكرار البند داخل المذكرة.");
            }
        }
        if (draft.markAllIndicatorsCorrect()
            && draft.fieldValues().stream().anyMatch(v -> v.value() != IndicatorValue.Correct)) {
            throw new NoteSaveValidationException(
                "لا يمكن تسجيل اعتبار جميع المؤشرات صحيحة لأن بعض القيم ليست صحيحة.");
        }
        if (!draft.saveAsDraft()) {
            for (RequiredNoteField field : activeDefinitions) {
                if (!field.isActive() || !field.isRequired()) continue;
                IndicatorValue value = byId.get(field.fieldId());
                if (value == null) {
                    throw new NoteSaveValidationException(
                        "يجب إدخال قيمة للبند: " + field.fieldNameAr());
                }
                if (value == IndicatorValue.Unreviewed) {
                    throw new NoteSaveValidationException(
                        "يجب مراجعة البند الإلزامي قبل الحفظ: " + field.fieldNameAr());
                }
            }
        }
    }

    /** Original C# status precedence for active definitions. */
    public static NoteStatus calculateStatus(
        List<RequiredNoteField> definitions,
        List<NoteSaveCandidate.IndicatorInput> submitted,
        boolean saveAsDraft
    ) {
        if (saveAsDraft) return NoteStatus.DRAFT;
        Map<UUID, IndicatorValue> byId = new HashMap<>();
        submitted.forEach(v -> byId.put(v.fieldId(), v.value()));
        for (RequiredNoteField field : definitions) {
            if (!field.isActive() || !field.isRequired()) continue;
            IndicatorValue value = byId.get(field.fieldId());
            if (value == null || value == IndicatorValue.Unreviewed) return NoteStatus.DRAFT;
        }
        for (RequiredNoteField field : definitions) {
            if (!field.isActive()) continue;
            IndicatorValue value = byId.get(field.fieldId());
            if (value != null && value != IndicatorValue.Correct
                && value != IndicatorValue.Unreviewed) return NoteStatus.HAS_ERRORS;
        }
        return NoteStatus.COMPLETE;
    }

    public static void ensureEditAllowed(
        MemoRole role, NoteStatus existing, boolean saveAsDraft
    ) {
        if (role != MemoRole.ADMIN && existing != NoteStatus.DRAFT
            && existing != NoteStatus.RETURNED_FOR_CORRECTION) {
            throw new NoteSaveValidationException(
                "تعديل المذكرات بعد إرسالها للتدقيق متاح لمدير النظام فقط أو بعد إعادتها للتصحيح.");
        }
        if (saveAsDraft && existing == NoteStatus.APPROVED) {
            throw new NoteSaveValidationException(
                "لا يمكن تحويل مذكرة معتمدة إلى مسودة. استخدم الحفظ الصريح لتعديلها مع بقاء أثر الاعتماد محفوظاً.");
        }
    }
}
