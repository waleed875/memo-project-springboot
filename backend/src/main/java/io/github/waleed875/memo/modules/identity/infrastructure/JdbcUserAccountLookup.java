package io.github.waleed875.memo.modules.identity.infrastructure;

import io.github.waleed875.memo.modules.identity.application.UserAccountLookup;
import io.github.waleed875.memo.modules.identity.domain.UserAccount;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only JDBC adapter for the original PostgreSQL auth schema.
 * No Hibernate schema generation or implicit migration.
 */
@Repository
public final class JdbcUserAccountLookup implements UserAccountLookup {
    private static final String SELECT_ACTIVE = """
        select u.id, u.username, u.full_name, u.password_hash,
               u.must_change_password, u.updated_at, r.code
        from auth.users u
        join auth.roles r on r.id = u.role_id
        where u.is_active = true and u.is_deleted = false and r.is_active = true
        """;

    private final JdbcTemplate jdbc;

    public JdbcUserAccountLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findActiveByUsername(String username) {
        List<UserAccount> found = jdbc.query(
            SELECT_ACTIVE + " and lower(trim(u.username)) = lower(trim(?)) limit 1",
            this::map, username
        );
        return found.stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findActiveById(UUID id) {
        List<UserAccount> found = jdbc.query(
            SELECT_ACTIVE + " and u.id = ? limit 1",
            this::map, id
        );
        return found.stream().findFirst();
    }

    private UserAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccount(
            rs.getObject("id", UUID.class),
            rs.getString("username"),
            rs.getString("full_name"),
            rs.getString("password_hash"),
            MemoRole.fromDatabase(rs.getString("code")),
            rs.getBoolean("must_change_password"),
            rs.getObject("updated_at", LocalDateTime.class)
        );
    }
}
