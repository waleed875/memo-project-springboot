package io.github.waleed875.memo.modules.masterdata.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MasterDataCommandServiceTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UserProfile ADMIN = new UserProfile(ACTOR, "admin", "Admin", "ADMIN");
    private static final UserProfile VIEWER = new UserProfile(ACTOR, "viewer", "Viewer", "VIEWER");

    static final class Recorder implements MasterDataWriteRepository {
        int writes;
        @Override public Company saveCompany(Company c, UUID actor, boolean create) {
            writes++; return c;
        }
        @Override public void deleteCompany(UUID id, UUID actor) { writes++; }
        @Override public Factory saveFactory(Factory f, UUID actor, boolean create) { writes++; return f; }
        @Override public void deleteFactory(UUID id, UUID actor) { writes++; }
        @Override public ProductionLine saveProductionLine(ProductionLine l, UUID actor, boolean create) {
            writes++; return l;
        }
        @Override public void deleteProductionLine(UUID id, UUID actor) { writes++; }
        @Override public void toggleProductionLine(UUID id, boolean active, UUID actor) { writes++; }
    }

    @Test void anonymousAndNonAdminAreRejectedBeforeWriteGateAndRepository() {
        Recorder db = new Recorder();
        int[] gates = {0};
        MasterDataCommandService service = new MasterDataCommandService(db, () -> gates[0]++);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.saveCompany(null, null, "شركة", true));
        assertThrows(MasterDataForbiddenException.class,
            () -> service.saveCompany(VIEWER, null, "شركة", true));
        assertThrows(MasterDataForbiddenException.class,
            () -> service.deleteProductionLine(VIEWER, UUID.randomUUID()));
        assertEquals(0, db.writes);
        assertEquals(0, gates[0]);
    }

    @Test void explicitReadOnlyGateBlocksAllMutations() {
        Recorder db = new Recorder();
        MasterDataCommandService service = new MasterDataCommandService(
            db, () -> { throw new MasterDataReadOnlyException(); });
        assertThrows(MasterDataReadOnlyException.class,
            () -> service.saveCompany(ADMIN, null, "شركة", true));
        assertThrows(MasterDataReadOnlyException.class,
            () -> service.deleteCompany(ADMIN, UUID.randomUUID()));
        assertThrows(MasterDataReadOnlyException.class,
            () -> service.toggleProductionLine(ADMIN, UUID.randomUUID(), true));
        assertEquals(0, db.writes);
    }

    @Test void validatesCompanyAndRequiredForeignKeys() {
        Recorder db = new Recorder();
        MasterDataCommandService service = new MasterDataCommandService(db, () -> {});
        assertThrows(MasterDataOperationException.class,
            () -> service.saveCompany(ADMIN, null, " ", true));
        assertThrows(MasterDataOperationException.class,
            () -> service.saveFactory(ADMIN, null, "مصنع", null, true));
        assertThrows(MasterDataOperationException.class,
            () -> service.saveProductionLine(ADMIN, null, "خط", null, true));
        assertEquals(0, db.writes);
        assertNotNull(service.saveCompany(ADMIN, null, "شركة", null).id());
        assertEquals(1, db.writes);
    }
}
