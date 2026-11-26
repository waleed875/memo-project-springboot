package io.github.waleed875.memo.platform.bootstrap;

import io.github.waleed875.memo.modules.identity.application.PasswordHasher;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-shot, preview-only command-line bootstrap, never an HTTP endpoint.
 * It can only change the original untouched placeholder admin on a fresh,
 * fully migrated preview database. No credentials appear in command arguments,
 * environment variables, logs or an on-disk file.
 */
@Component
@Profile("preview-admin-bootstrap")
public final class PreviewAdminBootstrapRunner implements ApplicationRunner {
    private static final String INITIAL_HASH = "BOOTSTRAP_BCRYPT_ADMIN123";
    private static final String EXPECTED_DB = "memodb_preview";
    private static final String EXPECTED_ROLE = "memo_preview";
    private static final String EXPECTED_VERSION = "070";
    private static final String UPDATE_ADMIN = """
        update auth.users u
        set password_hash = ?, must_change_password = false,
            updated_at = greatest(clock_timestamp()::timestamp,
                u.updated_at + interval '1 millisecond')
        from auth.roles r
        where u.role_id = r.id and r.code = 'ADMIN' and r.is_active = true
          and u.username = 'admin'
          and u.password_hash = 'BOOTSTRAP_BCRYPT_ADMIN123'
          and u.is_active = true and u.is_deleted = false
          and (select count(*) from auth.users) = 1
          and (select count(*) from core.notes) = 0
          and (select database_version from system.database_settings where singleton = true) = '070'
        """;

    private final JdbcTemplate jdbc;
    private final PasswordHasher hasher;
    private final TransactionTemplate transaction;
    private final boolean stdinEnabled;

    public PreviewAdminBootstrapRunner(
        JdbcTemplate jdbc,
        PasswordHasher hasher,
        PlatformTransactionManager transactionManager,
        @Value("${memo.preview.bootstrap.stdin:false}") boolean stdinEnabled
    ) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.transaction = new TransactionTemplate(transactionManager);
        this.stdinEnabled = stdinEnabled;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        validatePreviewTarget();
        char[] password = readPassword();
        try {
            activate(password);
            System.out.println("Preview administrator 'admin' activated once. Login from the existing UI.");
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    void validatePreviewTarget() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        String dbRole = jdbc.queryForObject("select current_user", String.class);
        String version = jdbc.queryForObject(
            "select database_version from system.database_settings where singleton = true",
            String.class
        );
        Long users = jdbc.queryForObject("select count(*) from auth.users", Long.class);
        Long notes = jdbc.queryForObject("select count(*) from core.notes", Long.class);
        Long eligible = jdbc.queryForObject("""
            select count(*) from auth.users u
            join auth.roles r on r.id = u.role_id
            where u.username = 'admin' and r.code = 'ADMIN' and r.is_active = true
              and u.password_hash = 'BOOTSTRAP_BCRYPT_ADMIN123'
              and u.is_active = true and u.is_deleted = false
            """, Long.class);

        if (!EXPECTED_DB.equals(database) || !EXPECTED_ROLE.equals(dbRole)
            || !EXPECTED_VERSION.equals(version)
            || !Objects.equals(users, 1L) || !Objects.equals(notes, 0L)
            || !Objects.equals(eligible, 1L)) {
            throw new IllegalStateException(
                "Bootstrap refused: requires the fresh, unused preview database "
                + "with the original untouched admin placeholder. No changes made.");
        }
    }

    void activate(char[] candidate) {
        if (candidate == null || candidate.length < 12 || candidate.length > 72) {
            throw new IllegalArgumentException("Password must be 12-72 characters.");
        }
        String plaintext = new String(candidate);
        if (plaintext.getBytes(StandardCharsets.UTF_8).length > 72
            || plaintext.isBlank() || plaintext.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                "Password must be nonblank and at most 72 UTF-8 bytes (BCrypt limit).");
        }

        String encoded = hasher.hash(plaintext);
        if (encoded == null || !encoded.startsWith("$2") || encoded.length() < 50) {
            throw new IllegalStateException("Password encoder did not return a BCrypt hash.");
        }

        transaction.executeWithoutResult(status -> {
            int updated = jdbc.update(UPDATE_ADMIN, encoded);
            if (updated != 1) {
                throw new IllegalStateException(
                    "Bootstrap refused: seed account changed or preview contains data. No changes made.");
            }
            jdbc.update("""
                insert into audit.logs (user_id, action_type, description)
                select id, 'ADMIN_INITIALIZED', 'Initial preview administrator activated'
                from auth.users where username = 'admin'
                """);
        });
    }

    private char[] readPassword() throws IOException {
        Console console = System.console();
        if (console != null && !stdinEnabled) {
            char[] first = console.readPassword("New preview admin password (12-72 chars): ");
            char[] second = console.readPassword("Confirm password: ");
            boolean matches = first != null && second != null && Arrays.equals(first, second);
            if (second != null) Arrays.fill(second, '\0');
            if (!matches) {
                if (first != null) Arrays.fill(first, '\0');
                throw new IllegalArgumentException("Password confirmation did not match.");
            }
            return first;
        }
        if (!stdinEnabled) {
            throw new IllegalStateException(
                "No interactive console. Use the provided PowerShell bootstrap script "
                + "or enable explicit one-line stdin mode.");
        }
        String line = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (line == null || line.length() > 72) {
            throw new IllegalArgumentException("Expected one password line of 12-72 characters.");
        }
        return line.toCharArray();
    }
}
