package io.github.waleed875.memo.modules.dashboard.application;

import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardCompanyMetricsServiceTest {
    private static DashboardSummaryCriteria criteria(
        LocalDate from,LocalDate to,UUID company,LocalDate cutoff
    ) {
        return new DashboardSummaryCriteria(from,to,"ArrivalDate",company,
            null,null,null,null,cutoff);
    }

    @Test void previousComparisonUsesInclusiveWindowAndShiftedCutoff() {
        var previous=new ArrayList<DashboardCompanyPeriod>();
        var actors=new ArrayList<UUID>();
        DashboardCompanyMetricsReader reader=(actor,filters,period)->{
            actors.add(actor);
            previous.add(period);
            return new DashboardCompanyComparison(null,null,period,filters);
        };
        var service=new DashboardCompanyMetricsService(reader);
        UUID id=UUID.randomUUID();
        var user=new UserProfile(id,"a","Admin","ADMIN");
        var filter=criteria(LocalDate.of(2026,11,11),LocalDate.of(2026,11,20),
            UUID.randomUUID(),LocalDate.of(2026,11,21));
        var result=service.read(user,filter);
        assertEquals(LocalDate.of(2026,11,1),result.previousPeriod().from());
        assertEquals(LocalDate.of(2026,11,10),result.previousPeriod().to());
        assertEquals(LocalDate.of(2026,11,11),result.previousPeriod().entryCutoffDate());
        assertEquals("ArrivalDate",result.previousPeriod().dateBasis());
        assertEquals(List.of(id),actors);
        assertEquals(1,previous.size());
    }

    @Test void noComparisonWithoutCompanyOrBothBoundariesAndNoUnauthorizedReads() {
        var actors=new ArrayList<UUID>();
        DashboardCompanyMetricsReader reader=(actor,filters,previous)->{
            actors.add(actor);
            assertNull(previous);
            return new DashboardCompanyComparison(null,null,null,filters);
        };
        var service=new DashboardCompanyMetricsService(reader);
        UUID admin=UUID.randomUUID();
        var valid=new UserProfile(admin,"a","Admin","ADMIN");
        assertThrows(UnauthenticatedMasterDataAccess.class,
            ()->service.read(null,criteria(null,null,null,null)));
        for(String role:List.of("DATA_ENTRY","VIEWER","REVIEWER","UNKNOWN")) {
            assertThrows(MasterDataForbiddenException.class,()->
                service.read(new UserProfile(UUID.randomUUID(),"u","User",role),
                    criteria(null,null,null,null)));
        }
        assertTrue(actors.isEmpty());
        assertNull(service.read(valid,criteria(LocalDate.of(2026,11,11),
            LocalDate.of(2026,11,20),null,null)).previousPeriod());
        assertNull(service.read(valid,criteria(null,
            LocalDate.of(2026,11,20),UUID.randomUUID(),null)).previousPeriod());
        assertEquals(2,actors.size());
    }
}
