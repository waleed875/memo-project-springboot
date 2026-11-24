package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.notes.domain.NoteDuplicate;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Database-independent duplicate query port. */
public interface NoteDuplicateReader {
    List<NoteDuplicate> find(UUID currentNoteId, UUID companyId, UUID noteTypeId,
        String normalizedNumber, Long numericNumber, Integer noteYear,
        LocalDate exactWritingDate, boolean isInvoice);
}
