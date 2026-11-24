package io.github.waleed875.memo.modules.notes.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataReadOnlyException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveCandidate;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoteCreateServiceTest {
    static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-11-24T22:00:00Z"), ZoneId.of("Asia/Damascus"));

    static class Recorder implements NoteCreator {
        int saves;
        LocalDate seenDate;
        UUID seenUser;
        @Override public UUID create(NoteCreateCommand command, UUID actor, LocalDate today) {
            saves++;
            seenDate = today;
            seenUser = actor;
            return command.noteId();
        }
    }
    static NoteCreateCommand command() {
        return new NoteCreateCommand(UUID.randomUUID(), UUID.randomUUID(),
            new NoteSaveCandidate("123", UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, LocalDate.of(2026, 11, 24), true,
                false, List.of()), null);
    }
    static UserProfile user(String role) {
        return new UserProfile(UUID.randomUUID(), "user", "User", role);
    }

    @Test void anonymousAndNonEditorRolesFailBeforeGateOrPersistence() {
        Recorder r = new Recorder();
        int[] gates = {0};
        var service = new NoteCreateService(r, () -> gates[0]++, CLOCK);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.create(null, command()));
        assertThrows(MasterDataForbiddenException.class,
            () -> service.create(user("VIEWER"), command()));
        assertThrows(MasterDataForbiddenException.class,
            () -> service.create(user("REVIEWER"), command()));
        assertEquals(0, r.saves);
        assertEquals(0, gates[0]);
    }

    @Test void writeGateAndOperationIdentifiersFailClosed() {
        Recorder r = new Recorder();
        var gate = new NoteCreateService(r,
            () -> {throw new MasterDataReadOnlyException();}, CLOCK);
        assertThrows(MasterDataReadOnlyException.class,
            () -> gate.create(user("ADMIN"), command()));
        var service = new NoteCreateService(r, () -> {}, CLOCK);
        var c = command();
        assertThrows(NoteSaveValidationException.class,
            () -> service.create(user("ADMIN"), new NoteCreateCommand(
                null, c.noteId(), c.candidate(), null)));
        assertThrows(NoteSaveValidationException.class,
            () -> service.create(user("ADMIN"), new NoteCreateCommand(
                c.operationId(), new UUID(0, 0), c.candidate(), null)));
        assertEquals(0, r.saves);
    }

    @Test void validCreateReceivesSessionActorAndDamascusDate() {
        Recorder r = new Recorder();
        var service = new NoteCreateService(r, () -> {}, CLOCK);
        UserProfile actor = user("DATA_ENTRY");
        var c = command();
        assertEquals(c.noteId(), service.create(actor, c));
        assertEquals(actor.id(), r.seenUser);
        assertEquals(LocalDate.of(2026, 11, 25), r.seenDate);
        assertEquals(1, r.saves);
    }
}
