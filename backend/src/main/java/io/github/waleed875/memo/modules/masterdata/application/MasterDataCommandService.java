package io.github.waleed875.memo.modules.masterdata.application;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import java.util.UUID;
/** Framework-independent command use cases; no Spring, SQL or HTTP dependencies. */
public final class MasterDataCommandService {
    private final MasterDataWriteRepository writer;
    private final MasterDataWriteGate gate;
    public MasterDataCommandService(MasterDataWriteRepository writer, MasterDataWriteGate gate) {
        this.writer = writer;
        this.gate = gate;
    }
    private UUID authorize(UserProfile user) {
        if (user == null || user.id() == null || user.role() == null)
            throw new UnauthenticatedMasterDataAccess();
        if (!"ADMIN".equals(user.role())) throw new MasterDataForbiddenException();
        gate.requireWritable();
        return user.id();
    }
    private static String name(String value, String error) {
        if (value == null || value.isBlank()) throw new MasterDataOperationException(error);
        return value;
    }
    private static UUID id(UUID value, String error) {
        if (value == null || value.equals(new UUID(0L, 0L)))
            throw new MasterDataOperationException(error);
        return value;
    }
    public Company saveCompany(UserProfile user, UUID existingId, String companyName, Boolean active) {
        UUID actor = authorize(user);
        boolean create = existingId == null;
        return writer.saveCompany(new Company(create ? UUID.randomUUID() : existingId,
            name(companyName, "اسم الشركة مطلوب."), active == null || active), actor, create);
    }
    public void deleteCompany(UserProfile user, UUID companyId) {
        UUID actor = authorize(user);
        writer.deleteCompany(id(companyId, "الشركة غير موجودة."), actor);
    }
    public Factory saveFactory(UserProfile user, UUID existingId, String factoryName,
                               UUID companyId, Boolean active) {
        UUID actor = authorize(user);
        boolean create = existingId == null;
        return writer.saveFactory(new Factory(create ? UUID.randomUUID() : existingId,
            name(factoryName, "اسم المعمل مطلوب.").trim(),
            id(companyId, "الشركة مطلوبة للمعمل."), null, active == null || active), actor, create);
    }
    public void deleteFactory(UserProfile user, UUID factoryId) {
        UUID actor = authorize(user);
        writer.deleteFactory(id(factoryId, "المعمل غير موجود."), actor);
    }
    public ProductionLine saveProductionLine(UserProfile user, UUID existingId, String lineName,
                                              UUID factoryId, Boolean active) {
        UUID actor = authorize(user);
        boolean create = existingId == null;
        return writer.saveProductionLine(new ProductionLine(create ? UUID.randomUUID() : existingId,
            id(factoryId, "المعمل مطلوب لخط الإنتاج."), null, null, null,
            name(lineName, "اسم خط الإنتاج مطلوب.").trim(), active == null || active), actor, create);
    }
    public void deleteProductionLine(UserProfile user, UUID lineId) {
        UUID actor = authorize(user);
        writer.deleteProductionLine(id(lineId, "خط الإنتاج غير موجود."), actor);
    }
    public void toggleProductionLine(UserProfile user, UUID lineId, boolean active) {
        UUID actor = authorize(user);
        writer.toggleProductionLine(id(lineId, "خط الإنتاج غير موجود."), active, actor);
    }
}
