package io.github.waleed875.memo.modules.masterdata.application;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import java.util.UUID;
/** Each write and its audit.log insert must be atomic. */
public interface MasterDataWriteRepository {
    Company saveCompany(Company value, UUID actor, boolean create);
    void deleteCompany(UUID id, UUID actor);
    Factory saveFactory(Factory value, UUID actor, boolean create);
    void deleteFactory(UUID id, UUID actor);
    ProductionLine saveProductionLine(ProductionLine value, UUID actor, boolean create);
    void deleteProductionLine(UUID id, UUID actor);
    void toggleProductionLine(UUID id, boolean active, UUID actor);
}
