package io.github.waleed875.memo.modules.notes.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Atomic persistence port; no direct JDBC dependency in the application layer. */
public interface AdminApprovedMetadataEditRepository {
    LocalDateTime edit(AdminApprovedMetadataEditCommand command, UUID actorId, LocalDate today);
}
