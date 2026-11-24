package io.github.waleed875.memo.modules.masterdata.domain;

import java.util.UUID;

public record ProductionLine(
    UUID id,
    UUID factoryId,
    String factoryName,
    UUID companyId,
    String companyName,
    String name,
    boolean isActive
) {}
