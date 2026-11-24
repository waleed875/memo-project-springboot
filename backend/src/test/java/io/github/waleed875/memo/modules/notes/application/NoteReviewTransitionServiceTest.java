package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataOperationException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataWriteGate;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteReviewTransitionServiceTest {
    static class Recorder implements NoteReviewTransitionRepository {
        int calls; String action; String reason; UUID actor;
        public void start(UUID id, UUID user, LocalDateTime expected) {
            calls++; action = "start"; actor = user;
        }
        public void returnForCorrection(UUID id, UUID user, String value, LocalDateTime expected) {
            calls++; action = "return"; reason = value; actor = user;
        }
        public void approve(UUID id, UUID user, LocalDateTime expected) {
            calls++; action = "approve"; actor = user;
        }
        public void release(UUID id, UUID user, String value) {
            calls++; action = "release"; reason = value; actor = user;
        }
    }

    static UserProfile actor(String role) {
        return new UserProfile(UUID.randomUUID(), "user", "User", role);
    }

    @Test void invalidOrUnauthorizedActorNeverTouchesWriteGateOrDb() {
        Recorder r = new Recorder();
        int[] gates = {0};
        var svc = new NoteReviewTransitionService(r, () -> gates[0]++);
        UUID id = UUID.randomUUID();
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> svc.start(null, id, null));
        assertThrows(MasterDataForbiddenException.class, () -> svc.start(actor("VIEWER"), id, null));
        assertThrows(MasterDataForbiddenException.class, () -> svc.start(actor("DATA_ENTRY"), id, null));
        assertThrows(MasterDataForbiddenException.class, () -> svc.start(actor("UNKNOWN"), id, null));
        assertThrows(MasterDataForbiddenException.class, () -> svc.release(actor("REVIEWER"), id, "reason"));
        assertEquals(0, gates[0]);
        assertEquals(0, r.calls);
    }

    @Test void writeGateFailsClosedBeforePersistence() {
        Recorder r = new Recorder();
        MasterDataWriteGate gate = () -> { throw new MasterDataReadOnlyException(); };
        var svc = new NoteReviewTransitionService(r, gate);
        assertThrows(MasterDataReadOnlyException.class, () ->
            svc.start(actor("REVIEWER"), UUID.randomUUID(), null));
        assertEquals(0, r.calls);
    }

    @Test void reasonIsRequiredAndTrimmedWithLegacyOptionalVersion() {
        Recorder r = new Recorder();
        var svc = new NoteReviewTransitionService(r, () -> {});
        UUID id = UUID.randomUUID();
        assertThrows(MasterDataOperationException.class, () ->
            svc.returnForCorrection(actor("REVIEWER"), id, " ", null));
        assertThrows(MasterDataOperationException.class, () ->
            svc.release(actor("ADMIN"), id, null));
        assertEquals(0, r.calls);
        var reviewer = actor("REVIEWER");
        svc.start(reviewer, id, null);
        assertEquals("start", r.action);
        assertEquals(reviewer.id(), r.actor);
        svc.returnForCorrection(reviewer, id, "  correction  ", null);
        assertEquals("correction", r.reason);
        svc.release(actor("ADMIN"), id, "  release  ");
        assertEquals("release", r.reason);
        svc.approve(reviewer, id, null);
        assertEquals("approve", r.action);
        assertEquals(4, r.calls);
    }
}
