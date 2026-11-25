package io.github.waleed875.memo.modules.dashboard.application;

import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardDistributionsServiceTest {
    @Test void limitAndRoleAreEnforcedBeforeRead() {
        var calls=new java.util.ArrayList<UUID>();
        DashboardDistributionsReader reader=(actor,filter)->{
            calls.add(actor);
            return new DashboardDistributions(List.of(),List.of(),List.of(),List.of(),
                List.of(),List.of(),filter);
        };
        var service=new DashboardDistributionsService(reader);
        assertThrows(MasterDataForbiddenException.class,
            ()->service.read(new UserProfile(UUID.randomUUID(),"u","User","VIEWER"),
                filter(null,null)));
        UUID admin=UUID.randomUUID();
        var actor=new UserProfile(admin,"a","Admin","ADMIN");
        assertThrows(InvalidDashboardSummaryRequest.class,
            ()->service.read(actor,filter(LocalDate.of(2000,1,1),
                LocalDate.of(2026,11,25))));
        assertTrue(calls.isEmpty());
        assertNotNull(service.read(actor,filter(LocalDate.of(2026,1,1),
            LocalDate.of(2026,12,31))));
        assertEquals(List.of(admin),calls);
    }
    private DashboardSummaryCriteria filter(LocalDate from,LocalDate to) {
        return new DashboardSummaryCriteria(from,to,null,null,null,null,null,null,null);
    }
    @Test void labelsRemainCompatibleWithOriginalCSharp() {
        assertEquals("أكتوبر 2026",
            DashboardDistributionLabels.month(YearMonth.of(2026,10)));
        assertEquals("معتمدة",DashboardDistributionLabels.status("APPROVED"));
        assertEquals("غير معروف",DashboardDistributionLabels.status("X"));
    }
}
