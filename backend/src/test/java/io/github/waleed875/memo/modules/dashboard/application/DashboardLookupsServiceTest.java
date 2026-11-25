package io.github.waleed875.memo.modules.dashboard.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardLookupsServiceTest {
    @Test void onlyAdminReachesReaderWithServerVerifiedId() {
        var calls = new java.util.ArrayList<UUID>();
        DashboardLookupsReader reader = actor -> {
            calls.add(actor);
            return new DashboardLookups(List.of(), List.of(), List.of(), List.of());
        };
        var service = new DashboardLookupsService(reader);
        assertThrows(UnauthenticatedMasterDataAccess.class, () -> service.read(null));
        for (String role : List.of("DATA_ENTRY", "REVIEWER", "VIEWER", "UNKNOWN")) {
            assertThrows(MasterDataForbiddenException.class, () -> service.read(
                new UserProfile(UUID.randomUUID(), "u", "User", role)));
        }
        assertTrue(calls.isEmpty());
        UUID id = UUID.randomUUID();
        assertNotNull(service.read(new UserProfile(id, "a", "Admin", "ADMIN")));
        assertEquals(List.of(id), calls);
    }
}
