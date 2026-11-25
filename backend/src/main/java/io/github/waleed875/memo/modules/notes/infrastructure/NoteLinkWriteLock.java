package io.github.waleed875.memo.modules.notes.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One link/source mutation lock shared by Spring Boot commands.
 *
 * Ordering: acquire before any core.notes row locks or source/link writes.
 * PostgreSQL releases this transaction-scoped advisory lock at commit/rollback.
 * Do not call outside an active @Transactional boundary.
 *
 * This does NOT make legacy C# or arbitrary SQL cooperate automatically.
 */
final class NoteLinkWriteLock {
    static final String KEY = "memo:admin-note-link-sync";
    static final int SEED = 23070;

    private NoteLinkWriteLock() {}

    static void acquire(JdbcTemplate jdbc) {
        jdbc.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, ?))",
            (rs, row) -> rs.getObject(1), KEY, SEED);
    }
}
