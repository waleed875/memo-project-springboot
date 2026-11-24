package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteReviewQueueServiceTest {
    private static final class Reader implements NoteReviewQueueReader {
        int reads;
        UUID requested;
        public List<io.github.waleed875.memo.modules.notes.domain.NoteReviewQueueItem> findForReviewer(UUID reviewer) {
            reads++;
            requested = reviewer;
            return List.of();
        }
    }

    private static UserProfile profile(String role, UUID id) {
        return new UserProfile(id, "reviewer", "Reviewer", role);
    }

    @Test void anonymousAndMalformedIdentityDoNotReachDatabase() {
        Reader reader = new Reader();
        var service = new NoteReviewQueueService(reader);
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.findForReviewer(null));
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.findForReviewer(profile(null, UUID.randomUUID())));
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.findForReviewer(profile("REVIEWER", null)));
        assertEquals(0, reader.reads);
    }

    @Test void onlyReviewerAndAdminAreAllowed() {
        Reader reader = new Reader();
        var service = new NoteReviewQueueService(reader);
        for (String role : List.of("VIEWER", "DATA_ENTRY", "UNKNOWN")) {
            assertThrows(NoteAccessDenied.class, () -> service.findForReviewer(profile(role, UUID.randomUUID())));
        }
        assertEquals(0, reader.reads);
        UUID reviewer = UUID.randomUUID();
        service.findForReviewer(profile("REVIEWER", reviewer));
        assertEquals(reviewer, reader.requested);
        service.findForReviewer(profile("ADMIN", reviewer));
        assertEquals(2, reader.reads);
    }
}

