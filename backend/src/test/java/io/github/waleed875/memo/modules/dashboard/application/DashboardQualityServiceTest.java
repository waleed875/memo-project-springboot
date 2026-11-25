package io.github.waleed875.memo.modules.dashboard.application;

import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardQualityServiceTest {
    @Test void rolesAndSampleBoundsFailBeforeAnyJdbcCall() {
        var calls = new java.util.ArrayList<UUID>();
        DashboardQualityReader reader = (actor,filters,minSample) -> {
            calls.add(actor);
            return new DashboardQualitySnapshot(null,List.of(),List.of(),List.of(),
                List.of(),List.of(),filters,minSample);
        };
        var service = new DashboardQualityService(reader);
        var f = new DashboardSummaryCriteria(null,null,null,null,null,null,null,null,null);
        assertThrows(UnauthenticatedMasterDataAccess.class,()->service.read(null,f,10));
        for (String role : List.of("DATA_ENTRY","REVIEWER","VIEWER","UNKNOWN")) {
            assertThrows(MasterDataForbiddenException.class,()->
                service.read(new UserProfile(UUID.randomUUID(),"u","U",role),f,10));
        }
        UUID admin=UUID.randomUUID();
        var user=new UserProfile(admin,"a","Admin","ADMIN");
        assertThrows(InvalidDashboardSummaryRequest.class,()->service.read(user,f,0));
        assertThrows(InvalidDashboardSummaryRequest.class,()->service.read(user,f,10001));
        assertTrue(calls.isEmpty());
        assertEquals(10,service.read(user,f,10).minSample());
        assertEquals(List.of(admin),calls);
    }

    @Test void indicatorLabelsFollowOriginalCodeZeroToSix() {
        assertEquals("لم تتم المراجعة",DashboardIndicatorLabels.state(0));
        assertEquals("صحيح",DashboardIndicatorLabels.state(1));
        assertEquals("غير صحيح",DashboardIndicatorLabels.state(2));
        assertEquals("مفقود",DashboardIndicatorLabels.state(3));
        assertEquals("غير واضح",DashboardIndicatorLabels.state(4));
        assertEquals("غير مكتمل",DashboardIndicatorLabels.state(5));
        assertEquals("لا ينطبق",DashboardIndicatorLabels.state(6));
    }
}
