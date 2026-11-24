package io.github.waleed875.memo.modules.identity.infrastructure;

import io.github.waleed875.memo.modules.identity.application.UserPasswordWriter;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Atomic update against the existing PostgreSQL auth schema. */
@Repository
public class JdbcUserPasswordWriter implements UserPasswordWriter {
    private final JdbcTemplate jdbc;

    public JdbcUserPasswordWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean replaceHashIfCurrent(UUID userId, String expectedHash, String replacementHash) {
        int updated = jdbc.update("""
            update auth.users
            set password_hash = ?, must_change_password = false,
                updated_at = greatest(clock_timestamp()::timestamp,
                    updated_at + interval '1 millisecond')
            where id = ? and password_hash = ?
              and is_active = true and is_deleted = false
              and exists (
                select 1 from auth.roles r
                where r.id = auth.users.role_id and r.is_active = true
              )
            """, replacementHash, userId, expectedHash);
        return updated == 1;
    }
}
