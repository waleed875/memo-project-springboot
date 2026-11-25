package io.github.waleed875.memo.modules.dashboard.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.waleed875.memo.modules.dashboard.DashboardUnifiedFixture;
import io.github.waleed875.memo.modules.dashboard.application.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import io.github.waleed875.memo.platform.security.SessionUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serialize through the actual Spring MVC endpoint; CI feeds its snapshots into
 * original linked C# DashboardSnapshot + DashboardContractBuilder and checks
 * every nested value of the returned JSON tree.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardSnapshotWireParityTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @MockitoBean SessionUserResolver sessions;
    @MockitoBean DashboardUnifiedService service;

    private static final UUID ACTOR=UUID.fromString("e1000000-0000-4000-8000-000000000010");

    @Test void comparisonWithExecutablePinnedCSharpDtoAndViewBuilder() throws Exception {
        when(sessions.resolve(any(HttpServletRequest.class))).thenReturn(
            Optional.of(new UserProfile(ACTOR,"admin","Admin","ADMIN")));
        var scenarios=new LinkedHashMap<String,DashboardUnifiedSnapshot>();
        scenarios.put("scoped",DashboardUnifiedFixture.build(DashboardUnifiedFixture.companyUuid()));
        scenarios.put("global",DashboardUnifiedFixture.build(null));
        scenarios.put("empty",emptySnapshot());
        scenarios.put("metrics",withMetrics());
        String export=System.getenv("MEMO_DASHBOARD_EXPORT_DIR");
        String reference=System.getenv("MEMO_DASHBOARD_CSHARP_ORACLE_DIR");
        assertFalse(reference!=null && !reference.isBlank() && (export==null || export.isBlank()),
            "Cross-runtime comparison must also export its independently serialized Java fixture");

        for(var entry:scenarios.entrySet()) {
            when(service.read(any(),any(),eq(10))).thenReturn(entry.getValue());
            var response=mvc.perform(get("/api/internal/dashboard/snapshot-preview")
                .param("dateBasis",entry.getValue().effectiveFilters().dateBasis()))
                .andReturn().getResponse();
            assertEquals(200,response.getStatus(),entry.getKey());
            assertTrue(response.getHeader("Cache-Control").contains("no-store"));
            JsonNode root=mapper.readTree(response.getContentAsString());
            assertFalse(root.path("readyForPublicApi").asBoolean());
            assertEquals("browser parity pending",root.path("parityGaps").get(0).asText());
            JsonNode actual=root.path("snapshot");
            assertTrue(actual.isObject(),entry.getKey());
            if(export!=null && !export.isBlank()) {
                Path file=Path.of(export,entry.getKey()+".json");
                Files.createDirectories(file.getParent());
                Files.writeString(file,mapper.writeValueAsString(actual),StandardCharsets.UTF_8);
            }
            if(reference!=null && !reference.isBlank()) {
                Path file=Path.of(reference,entry.getKey()+".json");
                assertTrue(Files.isRegularFile(file),"C# oracle output missing: "+file);
                JsonNode expected=mapper.readTree(Files.readString(file,StandardCharsets.UTF_8));
                assertEquivalent(expected,actual,"$."+entry.getKey());
            }
        }
        // A real JS/browser consumer route remains closed.
        assertEquals(403,mvc.perform(get("/api/dashboard/snapshot"))
            .andReturn().getResponse().getStatus());
    }

    private void assertEquivalent(JsonNode expected,JsonNode actual,String path) {
        if(expected.isNumber() && actual.isNumber()) {
            assertEquals(0,expected.decimalValue().compareTo(actual.decimalValue()),
                "numeric mismatch at "+path);
            return;
        }
        assertEquals(expected.getNodeType(),actual.getNodeType(),"JSON type mismatch "+path);
        if(expected.isObject()) {
            var expectedNames=new java.util.HashSet<String>();
            expected.propertyNames().forEach(expectedNames::add);
            var actualNames=new java.util.HashSet<String>();
            actual.propertyNames().forEach(actualNames::add);
            assertEquals(expectedNames,actualNames,"JSON object properties "+path);
            for(String name:expectedNames)
                assertEquivalent(expected.get(name),actual.get(name),path+"."+name);
        } else if(expected.isArray()) {
            assertEquals(expected.size(),actual.size(),"Array length mismatch "+path);
            for(int i=0;i<expected.size();i++)
                assertEquivalent(expected.get(i),actual.get(i),path+"["+i+"]");
        } else {
            assertEquals(expected,actual,"JSON value mismatch "+path);
        }
    }

    private static DashboardUnifiedSnapshot emptySnapshot() {
        var filters=new DashboardSummaryCriteria(
            null,null,"WritingDate",null,null,null,null,null,null);
        var core=new DashboardCoreSummary(
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,null,null,null);
        var sla=new DashboardCoreSla(0,0,0,null,0);
        var dist=new DashboardDistributions(List.of(),List.of(),List.of(),
            List.of(),List.of(),List.of(),filters);
        var qual=new DashboardQualitySnapshot(null,List.of(),List.of(),
            List.of(),List.of(),List.of(),filters,10);
        var perf=new DashboardPerformanceBreakdown(List.of(),List.of(),List.of(),filters);
        var activity=new DashboardActivitySnapshot(List.of(),List.of(),List.of(),List.of(),filters);
        var flow=new DashboardFlowSnapshot(List.of(),List.of(),filters);
        var company=new DashboardCompanyComparison(null,null,null,filters);
        var views=DashboardUnifiedViewsBuilder.build(
            new DashboardCoreSnapshot(core,sla,filters),
            dist,qual,perf,activity,flow,company);
        return new DashboardUnifiedSnapshot(
            core,sla,dist,qual,perf,activity,flow,company,
            views,filters,java.time.OffsetDateTime.parse("2026-11-25T12:00:00+03:00"),
            10,List.of("browser parity pending"));
    }

    private static DashboardUnifiedSnapshot withMetrics() throws Exception {
        var original=DashboardUnifiedFixture.build(DashboardUnifiedFixture.companyUuid());
        var filters=original.effectiveFilters();
        var prev=new DashboardCompanyPeriod(
            LocalDate.of(2026,10,2),LocalDate.of(2026,10,31),"ArrivalDate",null);
        var company=new DashboardCompanyComparison(metrics(3,true),metrics(1,false),prev,filters);
        var views=DashboardUnifiedViewsBuilder.build(
            new DashboardCoreSnapshot(original.summary(),original.sla(),filters),
            original.distributions(),original.quality(),original.performance(),
            original.activity(),original.flow(),company);
        return new DashboardUnifiedSnapshot(
            original.summary(),original.sla(),original.distributions(),original.quality(),
            original.performance(),original.activity(),original.flow(),company,
            views,filters,original.generatedAt(),10,List.of("browser parity pending"));
    }

    private static DashboardCompanyMetrics metrics(int total,boolean current) throws Exception {
        var components=DashboardCompanyMetrics.class.getRecordComponents();
        var argTypes=new Class<?>[components.length];
        var args=new Object[components.length];
        for(int i=0;i<components.length;i++) {
            var comp=components[i];
            argTypes[i]=comp.getType();
            if(comp.getType()==int.class) args[i]=0;
            else args[i]=null;
            switch(comp.getName()) {
                case "totalNotes" -> args[i]=total;
                case "expectedItems" -> args[i]=current?4:1;
                case "reviewedItems" -> args[i]=current?3:1;
                case "correctItems" -> args[i]=current?2:1;
                case "unreviewedItems" -> args[i]=current?1:0;
                case "reviewCoveragePercentage" -> args[i]=new BigDecimal(current?"75.00":"100.00");
                case "itemQualityPercentage" -> args[i]=new BigDecimal(current?"66.67":"100.00");
                case "timeCompliancePercentage" -> args[i]=new BigDecimal(current?"50.00":"100.00");
                case "maximumLateDays" -> args[i]=current?6:null;
                default -> {}
            }
        }
        Constructor<DashboardCompanyMetrics> constructor=
            DashboardCompanyMetrics.class.getDeclaredConstructor(argTypes);
        return constructor.newInstance(args);
    }
}
