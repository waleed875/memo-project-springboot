package io.github.waleed875.memo.modules.notes.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Exact approval preconditions from legacy C# NoteService.ApproveNoteAsync.
 * After locking note and effective field definitions, reuse the already ported
 * NoteValidator rules on the persisted note, never a caller-supplied payload.
 */
public final class NoteApprovalPolicy {
    private NoteApprovalPolicy() {}

    public static void validate(
        UUID actorId, UUID createdBy, UUID reviewerId, NoteStatus status,
        NoteSaveCandidate persisted, List<RequiredNoteField> activeDefinitions,
        LocalDate operationalToday
    ) {
        if (actorId == null || actorId.equals(createdBy)) {
            throw new NoteSaveValidationException(
                "لا يمكن اعتماد المذكرة من المستخدم الذي أدخلها.");
        }
        if (status != NoteStatus.UNDER_REVIEW || !actorId.equals(reviewerId)) {
            throw new NoteSaveValidationException(
                "يجب أن تكون المذكرة محجوزة للتدقيق من قبلك قبل اعتمادها.");
        }
        NoteSavePolicy.validate(persisted, activeDefinitions, operationalToday);
    }
}
