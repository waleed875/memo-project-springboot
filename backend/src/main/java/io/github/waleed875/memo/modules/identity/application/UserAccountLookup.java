package io.github.waleed875.memo.modules.identity.application;

import io.github.waleed875.memo.modules.identity.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;

/** Port: only active, undeleted users with active roles are returned. */
public interface UserAccountLookup {
    Optional<UserAccount> findActiveByUsername(String username);
    Optional<UserAccount> findActiveById(UUID id);
}
