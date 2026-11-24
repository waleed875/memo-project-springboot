package io.github.waleed875.memo.modules.notes.domain;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Domain validation for the source payload from C# SaveNoteSourceAsync.
 * Strictly rejects irrelevant combinations instead of silently dropping data.
 */
public final class NoteSourcePolicy {
    private static final UUID ZERO = new UUID(0L, 0L);
    private NoteSourcePolicy() {}

    public static void validate(NoteSourceDraft draft) {
        if (draft == null || draft.sourceType() == null) {
            throw new NoteSaveValidationException("نوع مصدر المذكرة مطلوب.");
        }
        if (draft.exitReferences().size() > 100 || draft.invoices().size() > 100) {
            throw new NoteSaveValidationException("عدد المراجع أو الفواتير كبير جدًا.");
        }
        switch (draft.sourceType()) {
            case Exit -> {
                if (draft.exitReferences().isEmpty()) {
                    throw new NoteSaveValidationException(
                        "يجب تحديد مذكرة إخراج واحدة على الأقل كمصدر.");
                }
                HashSet<UUID> seen = new HashSet<>();
                for (var reference : draft.exitReferences()) {
                    if (reference == null || reference.exitNoteId() == null
                        || ZERO.equals(reference.exitNoteId())
                        || !seen.add(reference.exitNoteId())) {
                        throw new NoteSaveValidationException(
                            "لا يجوز تكرار مذكرة الإخراج في قائمة المصادر.");
                    }
                }
                rejectOther(draft.invoices(), draft.lootDescription(), draft.otherDescription());
            }
            case Invoice -> {
                if (draft.invoices().isEmpty()) {
                    throw new NoteSaveValidationException(
                        "يجب تحديد فاتورة واحدة على الأقل كمصدر.");
                }
                for (var invoice : draft.invoices()) {
                    if (invoice == null || invoice.invoiceNumber() == null
                        || invoice.invoiceNumber().isBlank()) {
                        throw new NoteSaveValidationException("رقم الفاتورة مطلوب.");
                    }
                    if (invoice.invoiceNumber().length() > 120) {
                        throw new NoteSaveValidationException("رقم الفاتورة طويل جدًا.");
                    }
                    NoteAttachmentPolicy.validate(invoice.attachment());
                }
                rejectOther(draft.exitReferences(), draft.lootDescription(), draft.otherDescription());
            }
            case Loot -> {
                if (draft.lootDescription() == null || draft.lootDescription().isBlank()) {
                    throw new NoteSaveValidationException("وصف المضبوطات مطلوب.");
                }
                rejectOther(draft.exitReferences(), draft.invoices(), draft.otherDescription());
            }
            case Other -> {
                if (draft.otherDescription() == null || draft.otherDescription().isBlank()) {
                    throw new NoteSaveValidationException("وصف المصدر الآخر مطلوب.");
                }
                rejectOther(draft.exitReferences(), draft.invoices(), draft.lootDescription());
            }
        }
    }

    private static void rejectOther(Object first, Object second, Object third) {
        if (nonEmpty(first) || nonEmpty(second) || nonEmpty(third)) {
            throw new NoteSaveValidationException("لا يمكن خلط أنواع مصادر مختلفة.");
        }
    }
    private static boolean nonEmpty(Object value) {
        if (value == null) return false;
        if (value instanceof List<?> list) return !list.isEmpty();
        if (value instanceof String s) return !s.isBlank();
        return true;
    }
}
