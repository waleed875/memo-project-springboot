package io.github.waleed875.memo.modules.notes.application;
import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.*;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
class AdminNoteLinkSyncServiceTest {
    static class Stub implements AdminNoteLinkSyncRepository {
        int calls; UUID actor;
        public SyncResult sync(AdminNoteLinkSyncCommand c, UUID user) {
            calls++; actor=user;
            return new SyncResult(c.noteId(),c.relatedNoteIds(),0,0,c.expectedUpdatedAt());
        }
    }
    static UserProfile user(String role){ return new UserProfile(UUID.randomUUID(),"u","User",role); }
    static AdminNoteLinkSyncCommand cmd(UUID... ids) {
        return new AdminNoteLinkSyncCommand(UUID.randomUUID(),LocalDateTime.parse("2026-11-25T09:00:00"),List.of(ids));
    }
    @Test void deniesInvalidRolesAndReadOnly() {
        Stub r=new Stub(); int[] gates={0}; var s=new AdminNoteLinkSyncService(r,()->gates[0]++);
        assertThrows(UnauthenticatedMasterDataAccess.class,()->s.sync(null,cmd()));
        for(String role:List.of("VIEWER","REVIEWER","DATA_ENTRY","UNKNOWN"))
            assertThrows(MasterDataForbiddenException.class,()->s.sync(user(role),cmd()));
        assertEquals(0,gates[0]); assertEquals(0,r.calls);
        var closed=new AdminNoteLinkSyncService(r,()->{throw new MasterDataReadOnlyException();});
        assertThrows(MasterDataReadOnlyException.class,()->closed.sync(user("ADMIN"),cmd()));
    }
    @Test void rejectsInvalidIdsAndSupportsEmptyReplacement() {
        Stub r=new Stub(); var s=new AdminNoteLinkSyncService(r,()->{});
        UUID note=UUID.randomUUID(), target=UUID.randomUUID();
        assertThrows(NoteSaveValidationException.class,()->s.sync(user("ADMIN"),
            new AdminNoteLinkSyncCommand(note,LocalDateTime.now(),List.of(target,target))));
        assertThrows(NoteSaveValidationException.class,()->s.sync(user("ADMIN"),
            new AdminNoteLinkSyncCommand(note,LocalDateTime.now(),List.of(note))));
        assertThrows(NoteSaveValidationException.class,()->s.sync(user("ADMIN"),
            new AdminNoteLinkSyncCommand(note,LocalDateTime.now(),null)));
        assertEquals(0,r.calls);
        UserProfile admin=user("ADMIN");
        assertTrue(s.sync(admin,cmd()).relatedNoteIds().isEmpty());
        assertEquals(admin.id(),r.actor);
    }
}
