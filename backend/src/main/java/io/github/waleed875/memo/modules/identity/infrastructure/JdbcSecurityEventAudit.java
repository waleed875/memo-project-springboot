package io.github.waleed875.memo.modules.identity.infrastructure;

import io.github.waleed875.memo.modules.identity.application.SecurityEventAudit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Best-effort audit, mirroring the .NET web admin update audit behavior.
 * PostgreSQL updates.audit_log is created by the legacy migration 046.
 * Authentication continues when this optional log destination is unavailable;
 * deployments must monitor the warning.
 */
@Component
public class JdbcSecurityEventAudit implements SecurityEventAudit {
    private static final Logger logger = LoggerFactory.getLogger(JdbcSecurityEventAudit.class);
    private final JdbcTemplate jdbc;

    public JdbcSecurityEventAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID userId, String action, String description) {
        try {
            jdbc.update("""
                insert into updates.audit_log(user_id, action, description)
                values (?, ?, ?)
                """, userId, action, description);
        } catch (DataAccessException ex) {
            logger.warn("Unable to persist security audit event {}", action, ex);
        }
    }
}
