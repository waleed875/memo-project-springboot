package io.github.waleed875.memo.modules.dashboard.application;

import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardActivityServiceTest {
    private static DashboardSummaryCriteria filters(LocalDate from,LocalDate to) {
        return new DashboardSummaryCriteria(from,to,null,null,null,null,null,null,null);
    }

    @Test void liveAdminSessionAndDamascusBusinessDayRequired() {
        var dates=new ArrayList<LocalDate>();
        var actors=new ArrayList<UUID>();
        UUID adminId=UUID.randomUUID();
        DashboardActivityReader reader=(actor,criteria,day)->{
            actors.add(actor);
            dates.add(day);
            return new DashboardActivitySnapshot(List.of(),List.of(),List.of(),List.of(),criteria);
        };
        var service=new DashboardActivityService(reader,
            Clock.fixed(Instant.parse("2026-11-24T22:00:00Z"),ZoneId.of("Asia/Damascus")));
        assertThrows(UnauthenticatedMasterDataAccess.class,()->
            service.read(null,filters(null,null)));
        for(String role:List.of("DATA_ENTRY","VIEWER","REVIEWER","UNKNOWN")) {
            assertThrows(MasterDataForbiddenException.class,()->
                service.read(new UserProfile(UUID.randomUUID(),"u","U",role),filters(null,null)));
        }
        var admin=new UserProfile(adminId,"a","Admin","ADMIN");
        assertThrows(InvalidDashboardSummaryRequest.class,()->
            service.read(admin,filters(LocalDate.of(2000,1,1),LocalDate.of(2026,11,25))));
        assertTrue(actors.isEmpty());
        assertNotNull(service.read(admin,filters(LocalDate.of(2026,10,1),LocalDate.of(2026,12,31))));
        assertEquals(List.of(adminId),actors);
        assertEquals(List.of(LocalDate.of(2026,11,25)),dates);
    }

    @Test void classificationAndBacklogLabelsMatchPinnedOriginal() {
        assertEquals("إعادة إرسال من الشركة",DashboardActivityLabels.duplicate("COMPANY_RESEND"));
        assertEquals("أرقام مكررة",DashboardActivityLabels.duplicate("COMPANY_DUPLICATE_NUMBER"));
        assertEquals("غير مصنف",DashboardActivityLabels.duplicate("OTHER"));
        assertEquals("أقل من يوم",DashboardActivityLabels.backlog("lt-1-day"));
        assertEquals("1-3 أيام",DashboardActivityLabels.backlog("1-3-days"));
        assertEquals("4-7 أيام",DashboardActivityLabels.backlog("4-7-days"));
        assertEquals("أكثر من 7 أيام",DashboardActivityLabels.backlog("gt-7-days"));
    }
}
