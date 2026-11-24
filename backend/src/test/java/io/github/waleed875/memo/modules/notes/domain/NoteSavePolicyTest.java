package io.github.waleed875.memo.modules.notes.domain;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteSavePolicyTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private final UUID companyId = UUID.randomUUID();
    private final UUID typeId = UUID.randomUUID();
    private final UUID requiredId = UUID.randomUUID();
    private final UUID optionalId = UUID.randomUUID();
    private final UUID inactiveId = UUID.randomUUID();

    private List<RequiredNoteField> definitions() {
        return List.of(
            new RequiredNoteField(requiredId, "إلزامي", true, true),
            new RequiredNoteField(optionalId, "اختياري", false, true),
            new RequiredNoteField(inactiveId, "قديم", true, false)
        );
    }
    private NoteSaveCandidate candidate(boolean draft, LocalDate writing, LocalDate arrival,
                                        List<NoteSaveCandidate.IndicatorInput> values) {
        return new NoteSaveCandidate("123", companyId, typeId, null, null,
            writing, arrival, draft, false, values);
    }

    @Test void arrivalDateIsRequiredCannotPredateWritingOrExceedDamascusToday() {
        var noArrival = candidate(false, null, null, List.of());
        assertThrows(NoteSaveValidationException.class,
            () -> NoteSavePolicy.validate(noArrival, definitions(), TODAY));
        assertThrows(NoteSaveValidationException.class, () ->
            NoteSavePolicy.validate(candidate(false, TODAY,
                TODAY.minusDays(1), List.of()), definitions(), TODAY));
        assertThrows(NoteSaveValidationException.class, () ->
            NoteSavePolicy.validate(candidate(false, null,
                TODAY.plusDays(1), List.of()), definitions(), TODAY));
    }

    @Test void draftsCanKeepUnreviewedRequiredValuesButSubmissionCannot() {
        var unreviewed = List.of(
            new NoteSaveCandidate.IndicatorInput(requiredId, IndicatorValue.Unreviewed));
        assertDoesNotThrow(() -> NoteSavePolicy.validate(
            candidate(true, null, TODAY, unreviewed), definitions(), TODAY));
        assertThrows(NoteSaveValidationException.class, () ->
            NoteSavePolicy.validate(
                candidate(false, null, TODAY, unreviewed), definitions(), TODAY));
        assertThrows(NoteSaveValidationException.class, () ->
            NoteSavePolicy.validate(
                candidate(false, null, TODAY, List.of()), definitions(), TODAY));
        var correct = List.of(
            new NoteSaveCandidate.IndicatorInput(requiredId, IndicatorValue.Correct));
        assertDoesNotThrow(() -> NoteSavePolicy.validate(
            candidate(false, null, TODAY, correct), definitions(), TODAY));
    }

    @Test void inactiveDefinitionsCannotBlockSubmissionOrSetErrorStatus() {
        var submitted = List.of(
            new NoteSaveCandidate.IndicatorInput(requiredId, IndicatorValue.Correct),
            new NoteSaveCandidate.IndicatorInput(inactiveId, IndicatorValue.Incorrect));
        NoteSavePolicy.validate(candidate(false, null, TODAY, submitted), definitions(), TODAY);
        assertEquals(NoteStatus.COMPLETE,
            NoteSavePolicy.calculateStatus(definitions(), submitted, false));
        assertEquals(NoteStatus.DRAFT,
            NoteSavePolicy.calculateStatus(definitions(), submitted, true));
    }

    @Test void activeOptionalIncorrectIndicatorChangesStatusToHasErrors() {
        var submitted = List.of(
            new NoteSaveCandidate.IndicatorInput(requiredId, IndicatorValue.Correct),
            new NoteSaveCandidate.IndicatorInput(optionalId, IndicatorValue.Missing));
        NoteSavePolicy.validate(candidate(false, null, TODAY, submitted), definitions(), TODAY);
        assertEquals(NoteStatus.HAS_ERRORS,
            NoteSavePolicy.calculateStatus(definitions(), submitted, false));
    }

    @Test void markAllCorrectRequiresAllSuppliedIndicatorsToBeCorrect() {
        var input = new NoteSaveCandidate("123", companyId, typeId, null, null,
            null, TODAY, false, true,
            List.of(new NoteSaveCandidate.IndicatorInput(requiredId, IndicatorValue.Missing)));
        assertThrows(NoteSaveValidationException.class, () ->
            NoteSavePolicy.validate(input, definitions(), TODAY));
    }

    @Test void rejectsDuplicateInputFieldsAndNullIndicator() {
        var duplicates = List.of(
            new NoteSaveCandidate.IndicatorInput(requiredId, IndicatorValue.Correct),
            new NoteSaveCandidate.IndicatorInput(requiredId, IndicatorValue.Missing));
        assertThrows(NoteSaveValidationException.class, () ->
            NoteSavePolicy.validate(candidate(true, null, TODAY, duplicates), definitions(), TODAY));
        assertThrows(NoteSaveValidationException.class, () ->
            NoteSavePolicy.validate(candidate(true, null, TODAY,
                List.of(new NoteSaveCandidate.IndicatorInput(requiredId, null))),
                definitions(), TODAY));
    }

    @Test void unapprovedEditsRestrictedAndApprovedCannotBecomeDraft() {
        assertThrows(NoteSaveValidationException.class, () ->
            NoteSavePolicy.ensureEditAllowed(MemoRole.DATA_ENTRY, NoteStatus.APPROVED, false));
        assertThrows(NoteSaveValidationException.class, () ->
            NoteSavePolicy.ensureEditAllowed(MemoRole.ADMIN, NoteStatus.APPROVED, true));
        assertDoesNotThrow(() ->
            NoteSavePolicy.ensureEditAllowed(MemoRole.ADMIN, NoteStatus.APPROVED, false));
        assertDoesNotThrow(() ->
            NoteSavePolicy.ensureEditAllowed(MemoRole.DATA_ENTRY,
                NoteStatus.RETURNED_FOR_CORRECTION, true));
    }

    @Test void missingRequiredIdentityAndNumberAreRejected() {
        var blankNumber = new NoteSaveCandidate("  ", companyId, typeId, null, null,
            null, TODAY, true, false, List.of());
        assertThrows(NoteSaveValidationException.class, () ->
            NoteSavePolicy.validate(blankNumber, definitions(), TODAY));
        var noCompany = new NoteSaveCandidate("123", null, typeId, null, null,
            null, TODAY, true, false, List.of());
        assertThrows(NoteSaveValidationException.class, () ->
            NoteSavePolicy.validate(noCompany, definitions(), TODAY));
    }
}
