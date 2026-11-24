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

class AdminNoteIndicatorEditServiceTest {
    static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-11-24T22:00:00Z"), ZoneId.of("Asia/Damascus"));

    static final class Recorder implements AdminNoteIndicatorEditRepository {
        int writes;
        UUID actor;
        LocalDate today;
        public LocalDateTime updateIndicators(
            AdminNoteIndicatorEditCommand command, UUID actorId, LocalDate operationalDay
        ) {
            writes++;
            actor = actorId;
            today = operationalDay;
            return command.expectedUpdatedAt();
        }
    }
    static UserProfile actor(String role) {
        return new UserProfile(UUID.randomUUID(), "user", "User", role);
    }
    static AdminNoteIndicatorEditCommand command() {
        return new AdminNoteIndicatorEditCommand(
            UUID.randomUUID(), LocalDateTime.parse("2026-11-24T12:00:00"),
            false, List.of());
    }

    @Test void anonymousNonAdminAndUnknownRolesAreDeniedBeforeDbOrGate() {
        Recorder recorder = new Recorder();
        int[] gates = {0};
        var svc = new AdminNoteIndicatorEditService(recorder, () -> gates[0]++, CLOCK);
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> svc.updateIndicators(null, command()));
        for (var role : List.of("REVIEWER", "DATA_ENTRY", "VIEWER", "UNKNOWN")) {
            assertThrows(MasterDataForbiddenException.class,
                () -> svc.updateIndicators(actor(role), command()));
        }
        assertEquals(0, recorder.writes);
        assertEquals(0, gates[0]);
    }

    @Test void readOnlyAndMalformedCommandFailClosed() {
        Recorder r = new Recorder();
        var disabled = new AdminNoteIndicatorEditService(r,
            () -> { throw new MasterDataReadOnlyException(); }, CLOCK);
        assertThrows(MasterDataReadOnlyException.class,
            () -> disabled.updateIndicators(actor("ADMIN"), command()));
        var svc = new AdminNoteIndicatorEditService(r, () -> {}, CLOCK);
        assertThrows(NoteSaveValidationException.class, () -> svc.updateIndicators(
            actor("ADMIN"), new AdminNoteIndicatorEditCommand(
                UUID.randomUUID(), null, false, List.of())));
        assertThrows(NoteSaveValidationException.class, () -> svc.updateIndicators(
            actor("ADMIN"), new AdminNoteIndicatorEditCommand(
                UUID.randomUUID(), LocalDateTime.now(), false, null)));
        assertEquals(0, r.writes);
    }

    @Test void usesSessionUserAndDamascusOperationalDate() {
        Recorder r = new Recorder();
        var svc = new AdminNoteIndicatorEditService(r, () -> {}, CLOCK);
        UserProfile admin = actor("ADMIN");
        var command = command();
        assertEquals(command.expectedUpdatedAt(), svc.updateIndicators(admin, command));
        assertEquals(admin.id(), r.actor);
        assertEquals(LocalDate.of(2026, 11, 25), r.today);
        assertEquals(1, r.writes);
    }
}
