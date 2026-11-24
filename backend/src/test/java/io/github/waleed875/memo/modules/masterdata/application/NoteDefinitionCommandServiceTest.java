package io.github.waleed875.memo.modules.masterdata.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.domain.NoteTypeFieldDefinition;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteDefinitionCommandServiceTest {
    private static final UUID TYPE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UserProfile ADMIN = new UserProfile(ACTOR, "admin", "Admin", "ADMIN");
    private static final UserProfile VIEWER = new UserProfile(ACTOR, "viewer", "Viewer", "VIEWER");

    private static final class RecordingWriter implements NoteDefinitionWriter {
        int calls;
        NoteTypeFieldDefinition last;
        @Override public NoteTypeFieldDefinition save(NoteTypeFieldDefinition d, UUID actor) {
            calls++; last = d; return d;
        }
        @Override public void deactivate(UUID id, UUID actor) { calls++; }
    }
    private NoteTypeFieldDefinition definition(String code, String name) {
        return new NoteTypeFieldDefinition(null, TYPE, null,
            code, name, null, 3, true, true);
    }

    @Test void unauthorizedAndNonAdminNeverReachGateOrWriter() {
        RecordingWriter writer = new RecordingWriter();
        int[] gateCalls = {0};
        var service = new NoteDefinitionCommandService(writer, () -> gateCalls[0]++);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.save(null, definition("A", "حقل")));
        assertThrows(MasterDataForbiddenException.class,
            () -> service.save(VIEWER, definition("A", "حقل")));
        assertThrows(MasterDataForbiddenException.class,
            () -> service.deactivate(VIEWER, UUID.randomUUID()));
        assertEquals(0, gateCalls[0]);
        assertEquals(0, writer.calls);
    }

    @Test void readOnlyBlocksCommands() {
        RecordingWriter writer = new RecordingWriter();
        var service = new NoteDefinitionCommandService(writer,
            () -> { throw new MasterDataReadOnlyException(); });
        assertThrows(MasterDataReadOnlyException.class,
            () -> service.save(ADMIN, definition("A", "حقل")));
        assertThrows(MasterDataReadOnlyException.class,
            () -> service.deactivate(ADMIN, UUID.randomUUID()));
        assertEquals(0, writer.calls);
    }

    @Test void validatesAndNormalizesDefinitionWithoutFramework() {
        RecordingWriter writer = new RecordingWriter();
        var service = new NoteDefinitionCommandService(writer, () -> {});
        assertThrows(MasterDataOperationException.class,
            () -> service.save(ADMIN, definition(" ", "حقل")));
        assertThrows(MasterDataOperationException.class,
            () -> service.save(ADMIN, definition("A", " ")));
        assertThrows(MasterDataOperationException.class,
            () -> service.save(ADMIN, new NoteTypeFieldDefinition(
                null, null, null, "A", "حقل", null, 1, true, true)));
        assertEquals(0, writer.calls);
        service.save(ADMIN, definition(" F1 ", " مؤشر "));
        assertEquals("F1", writer.last.fieldCode());
        assertEquals("مؤشر", writer.last.fieldNameAr());
        assertEquals(1, writer.calls);
        assertThrows(MasterDataOperationException.class,
            () -> service.deactivate(ADMIN, new UUID(0L, 0L)));
    }
}
