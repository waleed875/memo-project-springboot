package io.github.waleed875.memo.modules.masterdata.application;

import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL-independent port. The infrastructure adapter owns the legacy queries. */
public interface MasterDataReader {
    List<Company> companies();
    Optional<Company> company(UUID id);
    List<Factory> factories(UUID companyId);
    List<ProductionLine> productionLines(UUID factoryId);
}
