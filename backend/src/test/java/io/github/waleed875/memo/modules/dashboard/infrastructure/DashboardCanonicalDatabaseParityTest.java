package io.github.waleed875.memo.modules.dashboard.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.dashboard.application.*;
import io.github.waleed875.memo.modules.identity.application.UserProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live DB differential check against original C# CompanyMetrics Dapper DTO.
 * Both runtimes use the SAME untouched original 001-070 PostgreSQL schema and
 * seeded rows, but independent JDBC vs Npgsql/Dapper column mapping.
 * Fails on every missing property, mismatched null and numeric value.
 */
class DashboardCanonicalDatabaseParityTest {
    private static final UUID A=UUID.fromString("e2000000-0000-4000-8000-000000000001");
    private static final UUID B=UUID.fromString("e2000000-0000-4000-8000-000000000002");
    private record Case(UUID company,String from,String to,String status,String basis,String cutoff) {}
    private static final Map<String,Case> CASES=Map.of(
        "company-arrival",new Case(A,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
        "company-writing",new Case(A,"2026-11-01","2026-11-30",null,"WritingDate",null),
        "company-status",new Case(A,"2026-11-01","2026-11-30","APPROVED","ArrivalDate",null),
        "company-cutoff",new Case(A,"2026-11-01","2026-11-30",null,"ArrivalDate","2026-11-15"),
        "global-arrival",new Case(null,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
        "other-company",new Case(B,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
        "empty-window",new Case(A,"2027-01-01","2027-01-31",null,"ArrivalDate",null),
        "prior-period",new Case(A,"2026-11-11","2026-11-20",null,"ArrivalDate","2026-11-20")
    );

    @Test void originalCSharpAndSpringReturnSameCompanyMetricsOnOneRealDatabase()
        throws Exception {
        String oraclePath=System.getenv("MEMO_DASHBOARD_DB_ORACLE");
        if(oraclePath==null||oraclePath.isBlank()) return; // opt-in CI differential gate
        String url=System.getenv("MEMO_DASHBOARD_DB_JDBC_URL");
        String user=System.getenv("MEMO_DASHBOARD_DB_USER");
        String password=System.getenv("MEMO_DASHBOARD_DB_PASSWORD");
        assertNotNull(url);
        assertNotNull(user);
        assertNotNull(password);
        Path oracle=Path.of(oraclePath);
        assertTrue(Files.isRegularFile(oracle));
        var mapper=JsonMapper.builder().build();
        JsonNode expected=mapper.readTree(Files.readString(oracle,StandardCharsets.UTF_8));
        assertEquals(CASES.keySet().size(),expected.size());
        var ds=new DriverManagerDataSource(url,user,password);
        var jdbc=new JdbcTemplate(ds);
        UUID actorId=jdbc.queryForObject(
            "select id from auth.users where username='admin'",UUID.class);
        assertNotNull(actorId);
        var proxy=new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcDashboardCompanyMetricsReader(new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{DashboardCompanyMetricsReader.class});
        proxy.afterPropertiesSet();
        var service=new DashboardCompanyMetricsService((DashboardCompanyMetricsReader)proxy.getObject());
        var actor=new UserProfile(actorId,"admin","Admin","ADMIN");
        for(var entry:CASES.entrySet()) {
            String name=entry.getKey();
            Case c=entry.getValue();
            var filters=new DashboardSummaryCriteria(
                LocalDate.parse(c.from()),LocalDate.parse(c.to()),c.basis(),
                c.company(),null,null,null,c.status(),
                c.cutoff()==null?null:LocalDate.parse(c.cutoff()));
            var result=service.read(actor,filters);
            var actual=mapper.valueToTree(Map.of(
                "current",result.companyMetrics(),
                "previous",result.previousCompanyMetrics()==null
                    ? java.util.Collections.emptyMap()
                    : result.previousCompanyMetrics()
            ));
            // Map.of disallows null. Compare current and previous separately
            // so a null prior stays null rather than becoming an empty object.
            JsonNode expectedRow=expected.get(name);
            assertNotNull(expectedRow,"Missing C# scenario "+name);
            assertTree(expectedRow.get("current"),
                mapper.valueToTree(result.companyMetrics()),"$."+name+".current");
            assertTree(expectedRow.get("previous"),
                mapper.valueToTree(result.previousCompanyMetrics()),"$."+name+".previous");
        }
    }

    private void assertTree(JsonNode expected,JsonNode actual,String path) {
        assertNotNull(expected,"C# omitted "+path);
        assertNotNull(actual,"Java omitted "+path);
        if(expected.isNumber() && actual.isNumber()) {
            assertEquals(0,expected.decimalValue().compareTo(actual.decimalValue()),path);
            return;
        }
        assertEquals(expected.getNodeType(),actual.getNodeType(),path);
        if(expected.isObject()) {
            var expectedKeys=new java.util.HashSet<String>();
            expected.propertyNames().forEach(expectedKeys::add);
            var actualKeys=new java.util.HashSet<String>();
            actual.propertyNames().forEach(actualKeys::add);
            assertEquals(expectedKeys,actualKeys,path);
            for(var key:expectedKeys)
                assertTree(expected.get(key),actual.get(key),path+"."+key);
        } else if(expected.isArray()) {
            assertEquals(expected.size(),actual.size(),path);
            for(int i=0;i<expected.size();i++)
                assertTree(expected.get(i),actual.get(i),path+"["+i+"]");
        } else {
            assertEquals(expected,actual,path);
        }
    }
}
