package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteReviewQueueItem;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.util.List;

/** Framework-independent, fail-closed review queue authorization. */
public final class NoteReviewQueueService {
    private final NoteReviewQueueReader reader;

    public NoteReviewQueueService(NoteReviewQueueReader reader) {
        this.reader = reader;
    }

    public List<NoteReviewQueueItem> findForReviewer(UserProfile actor) {
        if (actor == null || actor.id() == null || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException ignored) {
            throw new NoteAccessDenied();
        }
        if (role != MemoRole.REVIEWER && role != MemoRole.ADMIN) {
            throw new NoteAccessDenied();
        }
        return List.copyOf(reader.findForReviewer(actor.id()));
    }
}

