package io.github.waleed875.memo.modules.notes.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Safe write preflight input. No database mutation or trusted actor fields.
 * Related links, attachments and duplicate-resolution commands require their
 * own dedicated transactional handling before the save endpoint can open.
 */
public record NoteSaveCandidate(
    String noteNumber, UUID companyId, UUID noteTypeId,
    UUID factoryId, UUID productionLineId,
    LocalDate writingDate, LocalDate arrivalAt,
    boolean saveAsDraft, boolean markAllIndicatorsCorrect,
    List<IndicatorInput> fieldValues
) {
    public NoteSaveCandidate {
        fieldValues = fieldValues == null ? null : List.copyOf(fieldValues);
    }

    public record IndicatorInput(UUID fieldId, IndicatorValue value) {}
}
