package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSearchCriteria;
import io.github.waleed875.memo.modules.notes.domain.PagedNotes;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.LocalDate;

/**
 * Pure application use case: validates search and makes the user scope
 * immutable before SQL. A DATA_ENTRY cannot override createdBy by query.
 */
public final class NoteSearchService {
    public static final LocalDate DEFAULT_FROM = LocalDate.of(1, 1, 1);
    public static final LocalDate DEFAULT_TO = LocalDate.of(9999, 12, 31);

    private final NoteSearchReader reader;

    public NoteSearchService(NoteSearchReader reader) {
        this.reader = reader;
    }

    public PagedNotes search(UserProfile actor, NoteSearchCriteria requested) {
        if (actor == null || actor.id() == null || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException unknownRole) {
            throw new NoteAccessDenied();
        }
        if (requested == null) {
            throw new InvalidNoteSearch("معايير البحث غير صالحة.");
        }

        LocalDate from = requested.from() == null ? DEFAULT_FROM : requested.from();
        LocalDate to = requested.to() == null ? DEFAULT_TO : requested.to();
        if (from.isAfter(to)) {
            throw new InvalidNoteSearch("تاريخ البداية يجب ألا يكون بعد تاريخ النهاية.");
        }

        NoteSearchCriteria secured = new NoteSearchCriteria(
            requested.noteNumber(), requested.companyId(), requested.factoryId(),
            requested.productionLineId(), requested.noteTypeId(),
            role == MemoRole.DATA_ENTRY ? actor.id() : requested.createdBy(),
            requested.status(), from, to, requested.dateBasis(),
            requested.hasWritingDate(), requested.unlinkedOnly(), requested.isLoot(),
            requested.isInvoice(), requested.entryCutoffDate(),
            Math.max(1, requested.pageNumber()), Math.clamp(requested.pageSize(), 1, 200)
        );
        return reader.search(secured);
    }
}
