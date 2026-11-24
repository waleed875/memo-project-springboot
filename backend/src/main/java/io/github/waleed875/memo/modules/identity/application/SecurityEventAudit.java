package io.github.waleed875.memo.modules.identity.application;

import java.util.UUID;

/**
 * Port for security events. Infrastructure writes to updates.audit_log.
 * Never include passwords, CSRF tokens or session IDs in descriptions.
 */
public interface SecurityEventAudit {
    void record(UUID userId, String action, String description);
}
