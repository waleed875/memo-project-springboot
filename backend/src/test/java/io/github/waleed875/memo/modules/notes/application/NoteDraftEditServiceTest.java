package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteDraftEditServiceTest {
    static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-11-24T22:00:00Z"), ZoneId.of("Asia/Damascus"));

    static final class Recorder implements NoteDraftEditRepository {
        int writes;
        UUID actor;
        LocalDate today;
        @Override public EditResult edit(NoteDraftEditCommand command, UUID actorId, LocalDate date) {
            writes++;
            actor = actorId;
            today = date;
            return new EditResult(command.noteId(), io.github.waleed875.memo.modules.notes.domain.NoteStatus.DRAFT,
                command.expectedUpdatedAt());
        }
    }
    static UserProfile actor(String role) {
        return new UserProfile(UUID.randomUUID(), "entry", "Entry", role);
    }
    static NoteDraftEditCommand command() {
        return new NoteDraftEditCommand(UUID.randomUUID(), LocalDateTime.parse("2026-11-24T11:00:00"),
            "ملاحظة", true, false, List.of());
    }

    @Test void rejectsAnonymousViewerReviewerAndAdminBeforeWriteGate() {
        Recorder recorder = new Recorder();
        int[] gates = {0};
        var service = new NoteDraftEditService(recorder, () -> gates[0]++, CLOCK);
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.edit(null, command()));
        for (String role : List.of("ADMIN", "REVIEWER", "VIEWER", "UNKNOWN")) {
            assertThrows(MasterDataForbiddenException.class,
                () -> service.edit(actor(role), command()));
        }
        assertEquals(0, gates[0]);
        assertEquals(0, recorder.writes);
    }

    @Test void writeGateAndInvalidCommandsFailBeforeSql() {
        Recorder r = new Recorder();
        var gated = new NoteDraftEditService(r, () -> {
            throw new MasterDataReadOnlyException();
        }, CLOCK);
        assertThrows(MasterDataReadOnlyException.class, () -> gated.edit(actor("DATA_ENTRY"), command()));
        var service = new NoteDraftEditService(r, () -> {}, CLOCK);
        assertThrows(NoteSaveValidationException.class, () -> service.edit(actor("DATA_ENTRY"),
            new NoteDraftEditCommand(UUID.randomUUID(), null, "", true, false, List.of())));
        assertThrows(NoteSaveValidationException.class, () -> service.edit(actor("DATA_ENTRY"),
            new NoteDraftEditCommand(UUID.randomUUID(), LocalDateTime.now(), "", true, false, null)));
        assertEquals(0, r.writes);
    }

    @Test void authorizedUserAndOperationalTodayAreDerivedFromSessionAndClock() {
        Recorder r = new Recorder();
        var service = new NoteDraftEditService(r, () -> {}, CLOCK);
        UserProfile user = actor("DATA_ENTRY");
        var command = command();
        assertEquals(command.noteId(), service.edit(user, command).noteId());
        assertEquals(user.id(), r.actor);
        assertEquals(LocalDate.of(2026, 11, 25), r.today);
    }
}
