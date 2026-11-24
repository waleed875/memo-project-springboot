package io.github.waleed875.memo.modules.masterdata.application;

import io.github.waleed875.memo.modules.masterdata.domain.NoteType;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeField;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldImpactPreview;
import java.util.List;
import java.util.UUID;

/** Query port; no Spring, JDBC or web dependencies. */
public interface NoteDefinitionReader {
    List<NoteType> noteTypes();
    List<NoteTypeField> fieldsForType(UUID noteTypeId);
    NoteTypeFieldImpactPreview preview(UUID noteTypeId, UUID fieldId);
}
