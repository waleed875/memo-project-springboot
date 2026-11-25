package io.github.waleed875.memo.modules.dashboard.application;

import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardPerformanceServiceTest {
    @Test void rejectsInactiveSessionRoleBeforeRepositoryInvocation() {
        var actors=new java.util.ArrayList<UUID>();
        DashboardPerformanceReader reader=(id,criteria)->{
            actors.add(id);
            return new DashboardPerformanceBreakdown(List.of(),List.of(),List.of(),criteria);
        };
        var service=new DashboardPerformanceService(reader);
        var criteria=new DashboardSummaryCriteria(null,null,null,null,null,null,null,null,null);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            ()->service.read(null,criteria));
        for(String role:List.of("DATA_ENTRY","VIEWER","REVIEWER","UNKNOWN")) {
            assertThrows(MasterDataForbiddenException.class,
                ()->service.read(new UserProfile(UUID.randomUUID(),"u","U",role),criteria));
        }
        assertTrue(actors.isEmpty());
        UUID admin=UUID.randomUUID();
        var response=service.read(new UserProfile(admin,"a","Admin","ADMIN"),criteria);
        assertTrue(response.performanceByFactory().isEmpty());
        assertEquals(List.of(admin),actors);
    }
}
