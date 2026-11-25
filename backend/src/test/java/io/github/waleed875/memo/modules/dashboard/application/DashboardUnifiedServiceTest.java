package io.github.waleed875.memo.modules.dashboard.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.modules.masterdata.application.MasterDataForbiddenException;
import io.github.waleed875.memo.modules.masterdata.application.UnauthenticatedMasterDataAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardUnifiedServiceTest {
    @Test void allSlicesShareFiltersAndBusinessDayAndComposeViews() {
        UUID admin=UUID.randomUUID(),company=UUID.randomUUID();
        DashboardSummaryCriteria f=new DashboardSummaryCriteria(
            LocalDate.of(2026,11,11),LocalDate.of(2026,11,20),"ArrivalDate",
            company,null,null,null,null,null);
        var summary=mock(DashboardSummaryReader.class);
        var dist=mock(DashboardDistributionsReader.class);
        var quality=mock(DashboardQualityReader.class);
        var perf=mock(DashboardPerformanceReader.class);
        var activity=mock(DashboardActivityReader.class);
        var flow=mock(DashboardFlowReader.class);
        var metrics=mock(DashboardCompanyMetricsReader.class);
        var core=new DashboardCoreSummary(
            3,1,3,1,1,1,1,0,0,0,0,0,3,1,1,0,2,6.0,6.0,
            LocalDate.of(2026,11,20));
        when(summary.read(eq(admin),eq(f),eq(LocalDate.of(2026,11,25))))
            .thenReturn(new DashboardCoreSnapshot(core,new DashboardCoreSla(2,1,1,50.0,0),f));
        when(dist.read(eq(admin),eq(f))).thenReturn(new DashboardDistributions(
            List.of(),List.of(),List.of(new DashboardDistributions.CompanyTopItem(
                company,"شركة",3)),List.of(),List.of(),List.of(),f));
        when(quality.read(eq(admin),eq(f),eq(5))).thenReturn(new DashboardQualitySnapshot(
            new DashboardQualitySnapshot.FieldQualitySummary(4,3,1,0,75.0),
            List.of(),List.of(),List.of(),List.of(),List.of(),f,5));
        when(perf.read(eq(admin),eq(f))).thenReturn(new DashboardPerformanceBreakdown(
            List.of(),List.of(),List.of(),f));
        when(activity.read(eq(admin),eq(f),eq(LocalDate.of(2026,11,25))))
            .thenReturn(new DashboardActivitySnapshot(
                List.of(),List.of(),List.of(),List.of(),f));
        when(flow.read(eq(admin),eq(f))).thenReturn(new DashboardFlowSnapshot(
            List.of(),List.of(),f));
        when(metrics.read(eq(admin),eq(f),any(DashboardCompanyPeriod.class)))
            .thenAnswer(call -> new DashboardCompanyComparison(
                null,null,call.getArgument(2),f));
        var service=new DashboardUnifiedService(
            summary,dist,quality,perf,activity,flow,metrics,
            Clock.fixed(Instant.parse("2026-11-24T22:00:00Z"),
                ZoneId.of("Asia/Damascus")));
        var result=service.read(
            new UserProfile(admin,"admin","Admin","ADMIN"),f,5);
        assertEquals(3,result.summary().totalNotes());
        assertEquals(5,result.minSample());
        assertEquals(75.0,result.views().executiveSummary().fieldQuality().accuracyRate());
        assertEquals("available",result.views().operationsSummary().availability().state());
        assertEquals(company,result.views().companySummary().company().id());
        assertEquals(LocalDate.of(2026,11,1),
            result.company().previousPeriod().from());
        assertEquals("Asia/Damascus",result.generatedAt().getOffset().getTotalSeconds()==10800
            ?"Asia/Damascus":"unexpected");
        assertFalse(result.parityGaps().isEmpty());
        verify(summary).read(eq(admin),eq(f),eq(LocalDate.of(2026,11,25)));
        verify(dist).read(eq(admin),eq(f));
        verify(quality).read(eq(admin),eq(f),eq(5));
        verify(perf).read(eq(admin),eq(f));
        verify(activity).read(eq(admin),eq(f),eq(LocalDate.of(2026,11,25)));
        verify(flow).read(eq(admin),eq(f));
        verify(metrics).read(eq(admin),eq(f),any(DashboardCompanyPeriod.class));
        verifyNoMoreInteractions(summary,dist,quality,perf,activity,flow,metrics);
    }

    @Test void validationBlocksAllReadsBeforeRepositories() {
        var summary=mock(DashboardSummaryReader.class);
        var dist=mock(DashboardDistributionsReader.class);
        var quality=mock(DashboardQualityReader.class);
        var perf=mock(DashboardPerformanceReader.class);
        var activity=mock(DashboardActivityReader.class);
        var flow=mock(DashboardFlowReader.class);
        var metrics=mock(DashboardCompanyMetricsReader.class);
        var service=new DashboardUnifiedService(
            summary,dist,quality,perf,activity,flow,metrics,Clock.systemUTC());
        var f=new DashboardSummaryCriteria(null,null,null,null,null,null,null,null,null);
        assertThrows(UnauthenticatedMasterDataAccess.class,()->service.read(null,f,10));
        for(String role:List.of("VIEWER","DATA_ENTRY","REVIEWER","UNKNOWN")) {
            assertThrows(MasterDataForbiddenException.class,()->
                service.read(new UserProfile(UUID.randomUUID(),"u","U",role),f,10));
        }
        var admin=new UserProfile(UUID.randomUUID(),"a","A","ADMIN");
        assertThrows(InvalidDashboardSummaryRequest.class,()->service.read(admin,f,0));
        assertThrows(InvalidDashboardSummaryRequest.class,()->service.read(admin,f,10001));
        var longRange=new DashboardSummaryCriteria(
            LocalDate.of(2000,1,1),LocalDate.of(2026,11,25),
            null,null,null,null,null,null,null);
        assertThrows(InvalidDashboardSummaryRequest.class,
            ()->service.read(admin,longRange,10));
        verifyNoInteractions(summary,dist,quality,perf,activity,flow,metrics);
    }

    @Test void emptyScopedSummaryProducesExplicitNoDataWithoutFakeMetrics() {
        var filters=new DashboardSummaryCriteria(null,null,null,null,null,null,null,null,null);
        var core=new DashboardCoreSnapshot(
            new DashboardCoreSummary(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,null,null,null),
            new DashboardCoreSla(0,0,0,null,0),filters);
        var d=new DashboardDistributions(
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),filters);
        var q=new DashboardQualitySnapshot(null,List.of(),List.of(),
            List.of(),List.of(),List.of(),filters,10);
        var p=new DashboardPerformanceBreakdown(List.of(),List.of(),List.of(),filters);
        var a=new DashboardActivitySnapshot(List.of(),List.of(),List.of(),List.of(),filters);
        var fl=new DashboardFlowSnapshot(List.of(),List.of(),filters);
        var c=new DashboardCompanyComparison(null,null,null,filters);
        var views=DashboardUnifiedViewsBuilder.build(core,d,q,p,a,fl,c);
        assertEquals("no-data",views.executiveSummary().availability().state());
        assertFalse(views.operationsSummary().availability().hasRows());
        assertNotNull(views.operationsSummary().availability().explanation());
        assertNull(views.companySummary());
        assertNull(views.executiveSummary().metrics());
    }
}
