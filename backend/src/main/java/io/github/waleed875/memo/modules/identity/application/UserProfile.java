package io.github.waleed875.memo.modules.identity.application;

import io.github.waleed875.memo.modules.identity.domain.UserAccount;
import java.util.UUID;

/** Safe public shape, with no password hash or security stamp. */
public record UserProfile(UUID id, String username, String fullName, String role) {
    public static UserProfile from(UserAccount user) {
        return new UserProfile(user.id(), user.username(), user.fullName(), user.role().name());
    }
}
