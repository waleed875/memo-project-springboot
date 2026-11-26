package io.github.waleed875.memo.platform.status;

import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only diagnostics. Never returns connection URLs, credentials, SQL errors,
 * hostnames or account information. Only a live DB-validated ADMIN session can
 * inspect schema version and migration status.
 */
@RestController
public final class SystemStatusController {
    private static final String EXPECTED_SCHEMA = "070";

    private final SessionUserResolver sessions;
    private final JdbcTemplate jdbc;

    public SystemStatusController(SessionUserResolver sessions, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/system/health")
    public ResponseEntity<java.util.Map<String, String>> publicHealth() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(java.util.Map.of("backend", "UP"));
    }

    @GetMapping("/api/admin/system-status")
    public ResponseEntity<StatusResponse> status(HttpServletRequest request) {
        var actor = sessions.resolve(request);
        if (actor.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore()).build();
        }
        if (!"ADMIN".equals(actor.get().role())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore()).build();
        }

        String database = "UNAVAILABLE";
        String version = null;
        String migrations = "UNKNOWN";
        try {
            jdbc.queryForObject("select 1", Integer.class);
            database = "CONNECTED";
            try {
                version = jdbc.queryForObject(
                    "select database_version from system.database_settings where singleton = true",
                    String.class
                );
                migrations = EXPECTED_SCHEMA.equals(version) ? "CURRENT" : "REQUIRES_REVIEW";
            } catch (DataAccessException ignored) {
                // A reachable database may still lack the expected migration metadata.
            }
        } catch (DataAccessException ignored) {
            // Details are deliberately omitted; do not echo SQL or connection errors.
        }

        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new StatusResponse("UP", database, version, EXPECTED_SCHEMA, migrations));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> sessionDatabaseUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore()).build();
    }

    public record StatusResponse(
        String backend,
        String database,
        String databaseVersion,
        String expectedDatabaseVersion,
        String migrations
    ) {}
}
