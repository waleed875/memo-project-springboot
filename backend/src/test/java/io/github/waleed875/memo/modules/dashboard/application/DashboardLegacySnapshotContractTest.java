package io.github.waleed875.memo.modules.dashboard.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.dashboard.DashboardUnifiedFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Locked source-field manifest from the pinned C# DashboardDtos.cs and
 * DashboardContractDtos.cs. No accidental drift in Java record components.
 */
class DashboardLegacySnapshotContractTest {
    @Test void allLegacyRootAndViewMembersMatchPinnedCSharpManifest() throws IOException {
        assertFields("DashboardSnapshot",DashboardLegacySnapshot.class);
        assertFields("DashboardSummary",DashboardLegacySnapshot.Summary.class);
        assertFields("RecentNoteItem",DashboardLegacySnapshot.RecentNote.class);
        assertFields("TopErrorFieldItem",DashboardLegacySnapshot.TopErrorField.class);
        assertFields("DashboardEffectiveFilters",DashboardLegacySnapshot.EffectiveFilters.class);
        assertFields("DashboardPeriod",DashboardLegacySnapshot.Period.class);
        assertFields("DashboardViewsContract",DashboardLegacyViewsContract.class);
        assertFields("DashboardContractContext",DashboardLegacyViewsContract.Context.class);
        assertFields("DashboardContractPeriod",DashboardLegacyViewsContract.Period.class);
        assertFields("DashboardContractScope",DashboardLegacyViewsContract.Scope.class);
        assertFields("DashboardDataAvailabilityContract",DashboardLegacyViewsContract.Availability.class);
        assertFields("ExecutiveDashboardContract",DashboardLegacyViewsContract.ExecutiveSummary.class);
        assertFields("OperationsDashboardContract",DashboardLegacyViewsContract.OperationsSummary.class);
        assertFields("CompanyDashboardContract",DashboardLegacyViewsContract.CompanySummary.class);
        assertFields("CompanyComparisonContract",DashboardUnifiedViews.Comparison.class);
    }

    private void assertFields(String name,Class<?> dto) throws IOException {
        try(var stream=getClass().getResourceAsStream("/contracts/dashboard-snapshot-fields.json")) {
            assertNotNull(stream,"pinned C# JSON manifest is missing");
            String json=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            var key=Pattern.compile("\""+Pattern.quote(name)+"\"\\s*:\\s*\\[(.*?)]",Pattern.DOTALL);
            var matches=key.matcher(json);
            assertTrue(matches.find(),"No C# manifest section for "+name);
            var values=Pattern.compile("\"([^\"]+)\"").matcher(matches.group(1));
            List<String> expected=new ArrayList<>();
            while(values.find()) expected.add(values.group(1));
            List<String> actual=Arrays.stream(dto.getRecordComponents())
                .map(c->c.getName()).toList();
            assertEquals(expected,actual,"C# field-order drift: "+name);
        }
    }

    @Test void adapterCopiesEveryLegacySectionAndKeepsOriginalDefaults() {
        var unified=DashboardUnifiedFixture.build(UUID.randomUUID());
        var preview=DashboardLegacySnapshotAdapter.toPreview(unified);
        assertFalse(preview.readyForPublicApi());
        assertEquals(List.of("browser parity pending"),preview.parityGaps());
        var s=preview.snapshot();
        assertTrue(s.isHealthy());
        assertNull(s.errorMessage());
        assertEquals(s.serverTime(),s.generatedAt());
        assertEquals(3,s.summary().totalNotes());
        assertEquals(10,s.minSample());
        assertEquals("ArrivalDate",s.effectiveFilters().dateBasis());
        assertEquals("2026-11-25T00:00",
            s.summary().latestArrivalDate().toString());
        assertEquals("2026-11-25T00:00",
            s.recentNotes().getFirst().arrivalAt().toString());
        assertEquals("2026-11-25T12:00",s.generatedAt().toString());
        var error=s.topErrorFields().getFirst();
        assertEquals(1,error.errorCount());
        assertEquals(0,error.totalFieldCount());
        assertEquals(0,error.correctFieldCount());
        assertEquals(0,error.errorFieldCount());
        assertEquals(0,error.unknownFieldCount());
        assertNull(error.fieldAccuracyRate());
        assertSame(s.topErrorFields(),s.views().operationsSummary().topErrorFields());
        assertNotNull(s.views().companySummary());
        assertEquals(s.effectiveFilters().companyId(),
            s.views().context().scope().companyId());
        assertEquals("2026-11-01T00:00",
            s.views().context().period().from().toString());
        assertEquals("available",s.views().executiveSummary().availability().state());
    }

    @Test void globalScopeOmitCompanySummaryAsOriginalBuilderDoes() {
        var s=DashboardLegacySnapshotAdapter.toPreview(
            DashboardUnifiedFixture.build(null)).snapshot();
        assertNull(s.views().companySummary());
        assertNull(s.effectiveFilters().companyId());
        assertEquals("2026-11-25T12:00",s.serverTime().toString());
    }
}
