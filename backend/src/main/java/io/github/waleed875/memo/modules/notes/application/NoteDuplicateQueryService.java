package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteDuplicate;
import io.github.waleed875.memo.modules.notes.domain.NoteNumberNormalizer;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Port of FindDuplicateNotesAsync: only ADMIN and DATA_ENTRY may check. */
public final class NoteDuplicateQueryService {
    private final NoteDuplicateReader reader;

    public NoteDuplicateQueryService(NoteDuplicateReader reader) {
        this.reader = reader;
    }

    public List<NoteDuplicate> find(UserProfile actor, UUID currentNoteId,
        String number, UUID typeId, UUID companyId, LocalDate writingDate,
        boolean isInvoice
    ) {
        if (actor == null || actor.id() == null || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        if (!"ADMIN".equals(actor.role()) && !"DATA_ENTRY".equals(actor.role())) {
            throw new MasterDataForbiddenException();
        }
        String normalized = NoteNumberNormalizer.normalize(number);
        UUID zero = new UUID(0L, 0L);
        if (normalized.isEmpty() || companyId == null || zero.equals(companyId)
            || typeId == null || zero.equals(typeId)) {
            return List.of();
        }
        var numeric = NoteNumberNormalizer.positiveNumericValue(number);
        return reader.find(currentNoteId, companyId, typeId, normalized,
            numeric.isPresent() ? numeric.getAsLong() : null,
            writingDate == null ? null : writingDate.getYear(),
            writingDate, isInvoice);
    }
}
