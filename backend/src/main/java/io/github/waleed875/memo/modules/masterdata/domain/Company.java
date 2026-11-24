package io.github.waleed875.memo.modules.masterdata.domain;

import java.util.UUID;

/** Legacy JSON contract: id, name, isActive. */
public record Company(UUID id, String name, boolean isActive) {}
