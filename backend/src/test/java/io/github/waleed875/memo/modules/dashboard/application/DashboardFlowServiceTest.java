package io.github.waleed875.memo.modules.dashboard.application;

import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardFlowServiceTest {
    private static DashboardSummaryCriteria filters(LocalDate from,LocalDate to) {
        return new DashboardSummaryCriteria(from,to,null,null,null,null,null,null,null);
    }

    @Test void adminOnlyAndDateRangeAreRequired() {
        var called=new java.util.ArrayList<UUID>();
        DashboardFlowReader reader=(actor,filter)->{
            called.add(actor);
            return new DashboardFlowSnapshot(List.of(),List.of(),filter);
        };
        var service=new DashboardFlowService(reader);
        assertThrows(UnauthenticatedMasterDataAccess.class,
            ()->service.read(null,filters(null,null)));
        for(String role:List.of("DATA_ENTRY","REVIEWER","VIEWER","UNKNOWN")){
            assertThrows(MasterDataForbiddenException.class,()->
                service.read(new UserProfile(UUID.randomUUID(),"u","User",role),filters(null,null)));
        }
        UUID admin=UUID.randomUUID();
        var user=new UserProfile(admin,"a","Admin","ADMIN");
        assertThrows(InvalidDashboardSummaryRequest.class,()->
            service.read(user,filters(LocalDate.of(2000,1,1),LocalDate.of(2026,11,25))));
        assertTrue(called.isEmpty());
        var result=service.read(user,filters(LocalDate.of(2026,11,1),LocalDate.of(2026,11,30)));
        assertNotNull(result);
        assertEquals(List.of(admin),called);
    }
}
