package io.github.waleed875.memo.modules.notes.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Review workflow use case. Authorization, write gate, and validation precede
 * DB access; repository checks active DB role again within the write transaction.
 */
public final class NoteReviewTransitionService {
    private static final UUID EMPTY = new UUID(0L, 0L);
    private final NoteReviewTransitionRepository repository;
    private final MasterDataWriteGate gate;

    public NoteReviewTransitionService(
        NoteReviewTransitionRepository repository, MasterDataWriteGate gate
    ) {
        this.repository = repository;
        this.gate = gate;
    }

    public void start(UserProfile actor, UUID noteId, LocalDateTime expectedUpdatedAt) {
        authorize(actor, false);
        gate.requireWritable();
        validateNoteId(noteId);
        repository.start(noteId, actor.id(), expectedUpdatedAt);
    }

    public void returnForCorrection(UserProfile actor, UUID noteId,
                                    String reason, LocalDateTime expectedUpdatedAt) {
        authorize(actor, false);
        gate.requireWritable();
        validateNoteId(noteId);
        repository.returnForCorrection(noteId, actor.id(), requiredReason(reason), expectedUpdatedAt);
    }

    public void approve(UserProfile actor, UUID noteId, LocalDateTime expectedUpdatedAt) {
        authorize(actor, false);
        gate.requireWritable();
        validateNoteId(noteId);
        repository.approve(noteId, actor.id(), expectedUpdatedAt);
    }

    public void release(UserProfile actor, UUID noteId, String reason) {
        authorize(actor, true);
        gate.requireWritable();
        validateNoteId(noteId);
        repository.release(noteId, actor.id(), requiredReason(reason));
    }

    private static void authorize(UserProfile actor, boolean adminOnly) {
        if (actor == null || actor.id() == null || EMPTY.equals(actor.id())
            || actor.role() == null) {
            throw new UnauthenticatedMasterDataAccess();
        }
        MemoRole role;
        try {
            role = MemoRole.fromDatabase(actor.role());
        } catch (IllegalArgumentException invalid) {
            throw new MasterDataForbiddenException();
        }
        if (adminOnly ? role != MemoRole.ADMIN
            : role != MemoRole.ADMIN && role != MemoRole.REVIEWER) {
            throw new MasterDataForbiddenException();
        }
    }

    private static String requiredReason(String value) {
        if (value == null || value.isBlank()) {
            throw new MasterDataOperationException("سبب العملية مطلوب.");
        }
        return value.trim();
    }

    private static void validateNoteId(UUID noteId) {
        if (noteId == null || EMPTY.equals(noteId)) {
            throw new MasterDataOperationException("معرّف المذكرة غير صالح.");
        }
    }
}
