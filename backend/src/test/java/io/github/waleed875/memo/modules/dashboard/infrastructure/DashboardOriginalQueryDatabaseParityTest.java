package io.github.waleed875.memo.modules.dashboard.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.dashboard.application.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Original C# GetSnapshotAsync (NOT reconstructed SQL) vs actual independent
 * Spring JDBC readers, against exactly the same original 001-070 schema and
 * PostgreSQL test dataset. Compare value sets for each scenario, not just DTO
 * serialization. Order is significant for ranked arrays only.
 */
class DashboardOriginalQueryDatabaseParityTest {
    private static final UUID A=UUID.fromString("e2000000-0000-4000-8000-000000000001");
    private static final UUID B=UUID.fromString("e2000000-0000-4000-8000-000000000002");
    private record Scenario(String name,UUID company,String from,String to,
                            String status,String basis,String cutoff) {}
    private static final List<Scenario> CASES=List.of(
        new Scenario("company-arrival",A,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
        new Scenario("company-writing",A,"2026-11-01","2026-11-30",null,"WritingDate",null),
        new Scenario("company-status",A,"2026-11-01","2026-11-30","APPROVED","ArrivalDate",null),
        new Scenario("company-cutoff",A,"2026-11-01","2026-11-30",null,"ArrivalDate","2026-11-15"),
        new Scenario("global-arrival",null,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
        new Scenario("other-company",B,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
        new Scenario("empty-window",A,"2027-01-01","2027-01-31",null,"ArrivalDate",null),
        new Scenario("prior-period",A,"2026-11-11","2026-11-20",null,"ArrivalDate","2026-11-20")
    );

    @Test void independentlyExecutedOriginalSnapshotQueriesMatchSpringReaders()
        throws Exception {
        String path=System.getenv("MEMO_DASHBOARD_FULL_ORACLE");
        if(path==null||path.isBlank()) return; // CI opt-in, never fake a passing comparison
        String url=System.getenv("MEMO_DASHBOARD_DB_JDBC_URL");
        String user=System.getenv("MEMO_DASHBOARD_DB_USER");
        String password=System.getenv("MEMO_DASHBOARD_DB_PASSWORD");
        assertNotNull(url);assertNotNull(user);assertNotNull(password);
        var mapper=JsonMapper.builder().build();
        JsonNode original=mapper.readTree(
            Files.readString(Path.of(path),StandardCharsets.UTF_8));
        assertEquals(CASES.size(),original.size());
        var jdbc=new NamedParameterJdbcTemplate(new DriverManagerDataSource(url,user,password));
        UUID actor=jdbc.getJdbcTemplate().queryForObject(
            "select id from auth.users where username='admin'",UUID.class);
        assertNotNull(actor);
        var summaryReader=new JdbcDashboardSummaryReader(jdbc);
        var distributionReader=new JdbcDashboardDistributionsReader(jdbc);
        var activityReader=new JdbcDashboardActivityReader(jdbc);
        var flowReader=new JdbcDashboardFlowReader(jdbc);
        var qualityReader=new JdbcDashboardQualityReader(jdbc);
        var performanceReader=new JdbcDashboardPerformanceReader(jdbc);
        for(var c:CASES) {
            DashboardSummaryCriteria f=new DashboardSummaryCriteria(
                LocalDate.parse(c.from()),LocalDate.parse(c.to()),c.basis(),
                c.company(),null,null,null,c.status(),
                c.cutoff()==null?null:LocalDate.parse(c.cutoff()));
            JsonNode expected=original.get(c.name());
            assertNotNull(expected,"missing original C# result "+c.name());
            assertTrue(expected.path("isHealthy").asBoolean(),"C# query was unhealthy "+c.name());
            // C# uses the explicit upper date boundary as CurrentDate, not "now".
            LocalDate anchor=f.to();
            var core=summaryReader.read(actor,f,anchor);
            var d=distributionReader.read(actor,f);
            var a=activityReader.read(actor,f,anchor);
            var flow=flowReader.read(actor,f);
            var quality=qualityReader.read(actor,f,1);
            var performance=performanceReader.read(actor,f);
            String root="$."+c.name();
            compareSubset(mapper,expected.path("summary"),core.summary(),root+".summary",
                Set.of("latestArrivalDate"));
            compareSubset(mapper,expected.path("sla"),core.sla(),root+".sla",Set.of());
            compareArray(mapper,expected.path("companies"),d.companies(),
                root+".companies",Set.of());
            compareArray(mapper,expected.path("monthlyActivity"),d.monthlyActivity(),
                root+".monthlyActivity",Set.of());
            compareArray(mapper,expected.path("statusDistribution"),d.statusDistribution(),
                root+".statusDistribution",Set.of());
            compareArray(mapper,expected.path("duplicateBreakdown"),a.duplicateBreakdown(),
                root+".duplicateBreakdown",Set.of());
            compareArray(mapper,expected.path("duplicateTrend"),a.duplicateTrend(),
                root+".duplicateTrend",Set.of());
            compareArray(mapper,expected.path("backlogByAge"),a.backlogByAge(),
                root+".backlogByAge",Set.of());
            compareArray(mapper,expected.path("incomingVsApproved"),flow.incomingVsApproved(),
                root+".incomingVsApproved",Set.of());
            compareArray(mapper,expected.path("dataCompleteness"),flow.dataCompleteness(),
                root+".dataCompleteness",Set.of());
            compareArray(mapper,expected.path("recentNotes"),a.recentNotes(),
                root+".recentNotes",Set.of("writingDate","arrivalAt"));
            compareSubset(mapper,expected.path("fieldQuality"),quality.fieldQuality(),
                root+".fieldQuality",Set.of());
            compareArray(mapper,expected.path("qualityByCompany"),quality.qualityByCompany(),
                root+".qualityByCompany",Set.of());
            compareArray(mapper,expected.path("indicatorStateDistribution"),
                quality.indicatorStateDistribution(),root+".indicatorStateDistribution",
                Set.of());
            compareArray(mapper,expected.path("topErrorFields"),quality.topErrorFields(),
                root+".topErrorFields",Set.of());
            compareArray(mapper,expected.path("timelinessByCompany"),
                performance.timelinessByCompany(),root+".timelinessByCompany",Set.of());
            compareArray(mapper,expected.path("performanceByFactory"),
                performance.performanceByFactory(),root+".performanceByFactory",Set.of());
            compareArray(mapper,expected.path("performanceByLine"),
                performance.performanceByLine(),root+".performanceByLine",Set.of());
        }
    }

    private static void compareArray(JsonMapper mapper,JsonNode expected,List<?> actual,
                                     String path,Set<String> ignore) {
        assertTrue(expected.isArray(),"Original value is not an array: "+path);
        JsonNode tree=mapper.valueToTree(actual);
        assertEquals(expected.size(),tree.size(),"array size "+path);
        for(int i=0;i<expected.size();i++)
            compareObjects(expected.get(i),tree.get(i),path+"["+i+"]",ignore);
    }

    private static void compareSubset(JsonMapper mapper,JsonNode expected,Object actual,
                                      String path,Set<String> ignore) {
        compareObjects(expected,mapper.valueToTree(actual),path,ignore);
    }

    private static void compareObjects(JsonNode expected,JsonNode actual,String path,
                                       Set<String> ignore) {
        if(expected.isNull()||actual.isNull()) {
            assertEquals(expected,actual,"null mismatch "+path);
            return;
        }
        assertTrue(expected.isObject()&&actual.isObject(),"not objects: "+path);
        actual.propertyNames().forEach(name->{
            if(ignore.contains(name))return;
            JsonNode exp=expected.get(name),got=actual.get(name);
            assertNotNull(exp,"original property missing: "+path+"."+name);
            if(exp.isNumber()&&got.isNumber())
                assertEquals(0,exp.decimalValue().compareTo(got.decimalValue()),path+"."+name);
            else
                assertEquals(exp,got,path+"."+name);
        });
    }
}
