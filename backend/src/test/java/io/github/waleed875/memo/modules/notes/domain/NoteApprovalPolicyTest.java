package io.github.waleed875.memo.modules.notes.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteApprovalPolicyTest {
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final UUID CREATOR = UUID.randomUUID();
    private static final UUID TYPE = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID FIELD = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private static final RequiredNoteField REQUIRED = new RequiredNoteField(FIELD, "مؤشر مطلوب", true, true);

    private NoteSaveCandidate candidate(List<NoteSaveCandidate.IndicatorInput> values) {
        return new NoteSaveCandidate("123", COMPANY, TYPE, null, null,
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), false, false, values);
    }

    @Test void correctAndIncorrectReviewedValuesBothPermitApproval() {
        for (var value : List.of(IndicatorValue.Correct, IndicatorValue.Incorrect)) {
            assertDoesNotThrow(() -> NoteApprovalPolicy.validate(
                REVIEWER, CREATOR, REVIEWER, NoteStatus.UNDER_REVIEW,
                candidate(List.of(new NoteSaveCandidate.IndicatorInput(FIELD, value))),
                List.of(REQUIRED), TODAY));
        }
    }

    @Test void rejectsSelfApprovalAndForeignReservation() {
        var completed = candidate(List.of(new NoteSaveCandidate.IndicatorInput(FIELD, IndicatorValue.Correct)));
        assertThrows(NoteSaveValidationException.class, () -> NoteApprovalPolicy.validate(
            CREATOR, CREATOR, CREATOR, NoteStatus.UNDER_REVIEW,
            completed, List.of(REQUIRED), TODAY));
        assertThrows(NoteSaveValidationException.class, () -> NoteApprovalPolicy.validate(
            REVIEWER, CREATOR, CREATOR, NoteStatus.UNDER_REVIEW,
            completed, List.of(REQUIRED), TODAY));
    }

    @Test void rejectsMissingUnreviewedAndInvalidDates() {
        assertThrows(NoteSaveValidationException.class, () -> NoteApprovalPolicy.validate(
            REVIEWER, CREATOR, REVIEWER, NoteStatus.UNDER_REVIEW,
            candidate(List.of()), List.of(REQUIRED), TODAY));
        assertThrows(NoteSaveValidationException.class, () -> NoteApprovalPolicy.validate(
            REVIEWER, CREATOR, REVIEWER, NoteStatus.UNDER_REVIEW,
            candidate(List.of(new NoteSaveCandidate.IndicatorInput(FIELD, IndicatorValue.Unreviewed))),
            List.of(REQUIRED), TODAY));
        var future = new NoteSaveCandidate("123", COMPANY, TYPE, null, null,
            null, TODAY.plusDays(1), false, false,
            List.of(new NoteSaveCandidate.IndicatorInput(FIELD, IndicatorValue.Correct)));
        assertThrows(NoteSaveValidationException.class, () -> NoteApprovalPolicy.validate(
            REVIEWER, CREATOR, REVIEWER, NoteStatus.UNDER_REVIEW, future,
            List.of(REQUIRED), TODAY));
    }

    @Test void ignoresInactiveDefinitionsLikeCSharp() {
        assertDoesNotThrow(() -> NoteApprovalPolicy.validate(
            REVIEWER, CREATOR, REVIEWER, NoteStatus.UNDER_REVIEW,
            candidate(List.of()), List.of(new RequiredNoteField(FIELD, "غير فعال", true, false)), TODAY));
    }
}
