package io.github.waleed875.memo.modules.notes.domain;

import java.time.LocalDateTime;

public record NoteReviewHistory(
    String action, String reason, String userName,
    String userRoleName, LocalDateTime createdAt
) {}
