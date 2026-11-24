package io.github.waleed875.memo.modules.masterdata.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.domain.Company;
import io.github.waleed875.memo.modules.masterdata.domain.Factory;
import io.github.waleed875.memo.modules.masterdata.domain.ProductionLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MasterDataQueryServiceTest {
    private static final UUID COMPANY_ID = UUID.randomUUID();

    private static final class RecordingReader implements MasterDataReader {
        int reads;
        @Override public List<Company> companies() {
            reads++;
            return List.of(new Company(COMPANY_ID, "Test", true));
        }
        @Override public Optional<Company> company(UUID id) {
            reads++;
            return Optional.of(new Company(id, "Test", true));
        }
        @Override public List<Factory> factories(UUID companyId) {
            reads++;
            return List.of();
        }
        @Override public List<ProductionLine> productionLines(UUID factoryId) {
            reads++;
            return List.of();
        }
    }

    @Test void blocksMissingIdentityBeforeAnyRepositoryCall() {
        RecordingReader reader = new RecordingReader();
        MasterDataQueryService service = new MasterDataQueryService(reader);
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.companies(null));
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.company(null, COMPANY_ID));
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.factories(null, null));
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.productionLines(null, null));
        assertEquals(0, reader.reads);
    }

    @Test void permitsAuthenticatedNonAdminToReadReferenceData() {
        RecordingReader reader = new RecordingReader();
        MasterDataQueryService service = new MasterDataQueryService(reader);
        UserProfile user = new UserProfile(UUID.randomUUID(), "viewer", "Viewer", "VIEWER");
        assertEquals(1, service.companies(user).size());
        assertTrue(service.company(user, COMPANY_ID).isPresent());
        service.factories(user, null);
        service.productionLines(user, null);
        assertEquals(4, reader.reads);
    }
}
