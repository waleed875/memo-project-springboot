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

class DashboardSummaryServiceTest {
    @Test void validatesLegacyFiltersAndNormalizesDateBasisAndStatus() {
        assertEquals("WritingDate", criteria(null, null, "unexpected", "approved").dateBasis());
        assertEquals("APPROVED", criteria(null, null, "unexpected", "approved").status());
        assertEquals("ArrivalDate", criteria(null, null, "arrivaldate", " DRAFT ").dateBasis());
        assertThrows(InvalidDashboardSummaryRequest.class, () ->
            criteria(LocalDate.of(2026,11,25), LocalDate.of(2026,11,24), null,null));
        assertThrows(InvalidDashboardSummaryRequest.class, () ->
            criteria(null,null,null,"UNKNOWN"));
    }

    private static DashboardSummaryCriteria criteria(
        LocalDate from, LocalDate to, String basis, String status
    ) {
        return new DashboardSummaryCriteria(from,to,basis,null,null,null,null,status,null);
    }

    @Test void sessionRoleIsEnforcedAndClockUsesDamascusBusinessDate() {
        var calls = new ArrayList<LocalDate>();
        UUID admin = UUID.randomUUID();
        DashboardSummaryReader reader = (id,f,day) -> {
            assertEquals(admin,id);
            calls.add(day);
            return new DashboardCoreSnapshot(null,null,f);
        };
        var service = new DashboardSummaryService(reader,
            Clock.fixed(Instant.parse("2026-11-24T22:00:00Z"),ZoneId.of("Asia/Damascus")));
        assertThrows(UnauthenticatedMasterDataAccess.class,
            () -> service.read(null,criteria(null,null,null,null)));
        for (String role : List.of("DATA_ENTRY","VIEWER","REVIEWER","UNKNOWN")) {
            assertThrows(MasterDataForbiddenException.class, () ->
                service.read(new UserProfile(UUID.randomUUID(),"u","User",role),
                    criteria(null,null,null,null)));
        }
        assertTrue(calls.isEmpty());
        assertNotNull(service.read(new UserProfile(admin,"a","Admin","ADMIN"),
            criteria(null,null,null,null)));
        assertEquals(List.of(LocalDate.of(2026,11,25)),calls);
    }
}
