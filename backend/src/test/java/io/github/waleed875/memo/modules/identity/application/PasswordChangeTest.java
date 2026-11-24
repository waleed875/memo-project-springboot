package io.github.waleed875.memo.modules.identity.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.domain.UserAccount;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasswordChangeTest {
    private static final UUID ID = UUID.randomUUID();
    private final UserAccount user = new UserAccount(ID, "admin", "Admin",
        "$2a$old", MemoRole.ADMIN, true, LocalDateTime.of(2026, 1, 1, 12, 0));

    private static final class Accounts implements UserAccountLookup {
        UserAccount user;
        Accounts(UserAccount u) { this.user = u; }
        @Override public Optional<UserAccount> findActiveByUsername(String name) {
            return Optional.ofNullable(user).filter(u -> u.username().equalsIgnoreCase(name));
        }
        @Override public Optional<UserAccount> findActiveById(UUID id) {
            return Optional.ofNullable(user).filter(u -> u.id().equals(id));
        }
    }

    @Test void changesPasswordAtomicallyAndAuditsSuccess() {
        List<String> events = new ArrayList<>();
        UserPasswordWriter writer = (id, old, next) ->
            id.equals(ID) && old.equals("$2a$old") && next.equals("$2a$new");
        IdentityApplicationService auth = new IdentityApplicationService(
            new Accounts(user), (raw, hash) -> raw.equals("old-password"),
            raw -> "$2a$new", writer,
            (id, action, msg) -> events.add(action), Clock.systemUTC()
        );
        assertEquals(PasswordChangeOutcome.SUCCESS,
            auth.changePassword("admin", "old-password", "new-password"));
        assertTrue(events.contains("PASSWORD_CHANGED"));
    }

    @Test void invalidPasswordOrConcurrentModificationFailsClosed() {
        IdentityApplicationService auth = new IdentityApplicationService(
            new Accounts(user), (raw, hash) -> raw.equals("old-password"),
            raw -> "$2a$new", (id, old, next) -> false,
            (id, action, msg) -> {}, Clock.systemUTC()
        );
        assertEquals(PasswordChangeOutcome.INVALID_NEW_PASSWORD,
            auth.changePassword("admin", "old-password", "short"));
        assertEquals(PasswordChangeOutcome.INVALID_CREDENTIALS,
            auth.changePassword("admin", "wrong", "new-password"));
        assertEquals(PasswordChangeOutcome.INVALID_CREDENTIALS,
            auth.changePassword("admin", "old-password", "new-password"));
    }
}
