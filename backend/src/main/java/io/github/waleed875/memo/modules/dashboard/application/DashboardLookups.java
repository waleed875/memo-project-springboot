package io.github.waleed875.memo.modules.dashboard.application;

import java.util.List;
import java.util.UUID;

/**
 * Exact four-collection contract from original DashboardLookups C# DTO.
 * JSON record properties are camelCase as in the original web API.
 */
public record DashboardLookups(
    List<CompanyLookup> companies,
    List<FactoryLookup> factories,
    List<ProductionLineLookup> productionLines,
    List<NoteTypeLookup> noteTypes
) {
    public DashboardLookups {
        companies = List.copyOf(companies);
        factories = List.copyOf(factories);
        productionLines = List.copyOf(productionLines);
        noteTypes = List.copyOf(noteTypes);
    }

    public record CompanyLookup(UUID id, String name) {}
    public record FactoryLookup(UUID id, UUID companyId, String name) {}
    public record ProductionLineLookup(UUID id, UUID factoryId, String name) {}
    public record NoteTypeLookup(UUID id, String code, String nameAr) {}
}
