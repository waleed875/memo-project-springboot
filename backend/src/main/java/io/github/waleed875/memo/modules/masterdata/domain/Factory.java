package io.github.waleed875.memo.modules.masterdata.domain;

import java.util.UUID;

/** Legacy JSON contract: nullable companyId/companyName are retained. */
public record Factory(
    UUID id, String name, UUID companyId, String companyName, boolean isActive
) {}
