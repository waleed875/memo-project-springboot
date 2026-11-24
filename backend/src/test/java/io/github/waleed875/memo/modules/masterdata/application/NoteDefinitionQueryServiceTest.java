package io.github.waleed875.memo.modules.masterdata.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.domain.NoteType;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeField;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldImpactPreview;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteDefinitionQueryServiceTest {
    private final UUID typeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    static final class Reader implements NoteDefinitionReader {
        int calls;
        @Override public List<NoteType> noteTypes() {
            calls++;
            return List.of();
        }
        @Override public List<NoteTypeField> fieldsForType(UUID type) {
            calls++;
            return List.of();
        }
        @Override public NoteTypeFieldImpactPreview preview(UUID type, UUID field) {
            calls++;
            return new NoteTypeFieldImpactPreview(type, field, 0, 0, 0, field == null);
        }
    }

    @Test void anonymousAccessDeniedBeforeDatabaseRead() {
        Reader reader = new Reader();
        NoteDefinitionQueryService service = new NoteDefinitionQueryService(reader);
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.noteTypes(null));
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.fieldsForType(null, typeId));
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.preview(null, typeId, null));
        assertEquals(0, reader.calls);
    }

    @Test void viewerCanReadButCannotPreview() {
        Reader reader = new Reader();
        NoteDefinitionQueryService service = new NoteDefinitionQueryService(reader);
        UserProfile viewer = new UserProfile(userId, "viewer", "Viewer", "VIEWER");
        assertTrue(service.noteTypes(viewer).isEmpty());
        assertTrue(service.fieldsForType(viewer, typeId).isEmpty());
        assertThrows(MasterDataForbiddenException.class,
            () -> service.preview(viewer, typeId, null));
        assertEquals(2, reader.calls);
    }

    @Test void adminPreviewRequiresTypeIdAndPreservesNullFieldSemantics() {
        Reader reader = new Reader();
        NoteDefinitionQueryService service = new NoteDefinitionQueryService(reader);
        UserProfile admin = new UserProfile(userId, "admin", "Admin", "ADMIN");
        assertThrows(MasterDataOperationException.class,
            () -> service.preview(admin, null, null));
        assertEquals(0, reader.calls);
        NoteTypeFieldImpactPreview value = service.preview(admin, typeId, null);
        assertTrue(value.isNewField());
        assertEquals(typeId, value.noteTypeId());
        assertEquals(1, reader.calls);
    }
}
