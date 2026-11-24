package io.github.waleed875.memo.modules.masterdata.application;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Use cases for read-only reference data. The original API allows all
 * authenticated roles to read. Authorization runs before repository calls.
 *
 * UserProfile must come from a validated SessionUserResolver, not request JSON.
 */
public final class MasterDataQueryService {
    private final MasterDataReader reader;

    public MasterDataQueryService(MasterDataReader reader) {
        this.reader = reader;
    }

    private void requireUser(UserProfile user) {
        if (user == null || user.id() == null || user.role() == null || user.role().isBlank()) {
            throw new UnauthenticatedMasterDataAccess();
        }
    }

    public List<Company> companies(UserProfile user) {
        requireUser(user);
        return reader.companies();
    }

    public Optional<Company> company(UserProfile user, UUID id) {
        requireUser(user);
        return reader.company(id);
    }

    public List<Factory> factories(UserProfile user, UUID companyId) {
        requireUser(user);
        return reader.factories(companyId);
    }

    public List<ProductionLine> productionLines(UserProfile user, UUID factoryId) {
        requireUser(user);
        return reader.productionLines(factoryId);
    }
}
