package io.github.waleed875.memo.modules.masterdata.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.domain.NoteType;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeField;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldImpactPreview;
import java.util.List;
import java.util.UUID;

/** Use cases with explicit authorisation before any SQL query. */
public final class NoteDefinitionQueryService {
    private final NoteDefinitionReader reader;

    public NoteDefinitionQueryService(NoteDefinitionReader reader) {
        this.reader = reader;
    }

    private static void requireUser(UserProfile actor) {
        if (actor == null || actor.id() == null || actor.role() == null
            || actor.role().isBlank()) {
            throw new UnauthenticatedMasterDataAccess();
        }
    }

    public List<NoteType> noteTypes(UserProfile actor) {
        requireUser(actor);
        return reader.noteTypes();
    }

    public List<NoteTypeField> fieldsForType(UserProfile actor, UUID noteTypeId) {
        requireUser(actor);
        return reader.fieldsForType(noteTypeId);
    }

    public NoteTypeFieldImpactPreview preview(
        UserProfile actor, UUID noteTypeId, UUID fieldId
    ) {
        requireUser(actor);
        if (!"ADMIN".equals(actor.role())) {
            throw new MasterDataForbiddenException();
        }
        if (noteTypeId == null || noteTypeId.equals(new UUID(0L, 0L))) {
            throw new MasterDataOperationException("يجب تحديد نوع المذكرة.");
        }
        return reader.preview(noteTypeId, fieldId);
    }
}
