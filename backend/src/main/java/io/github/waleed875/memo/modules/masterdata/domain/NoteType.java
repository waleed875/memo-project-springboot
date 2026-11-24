package io.github.waleed875.memo.modules.masterdata.domain;

import java.util.UUID;

/** Note type dictionary entry, preserving the legacy HTTP field names. */
public record NoteType(UUID id, String code, String nameAr,
                       String description, boolean isActive) {}
