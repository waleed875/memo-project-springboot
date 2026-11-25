package io.github.waleed875.memo.modules.notes.infrastructure;
import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.notes.application.*;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
@Testcontainers
class RetryingAdminNoteLinkSyncPostgresIT {
    @Container static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:16-bookworm")
          .withDatabaseName("link_retry_it").withUsername("memo_it")
          .withPassword("test_only");
    JdbcTemplate jdbc; JdbcTransactionManager manager;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(
            postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc=new JdbcTemplate(ds);manager=new JdbcTransactionManager(ds);
        jdbc.execute("drop table if exists retry_probe");
        jdbc.execute("create table retry_probe(id uuid primary key,attempt int not null)");
    }
    @Test void rolledBackFirstAttemptCannotLeakIntoSecondTransaction() {
        AtomicInteger calls=new AtomicInteger();UUID actor=UUID.randomUUID();
        var cmd=new AdminNoteLinkSyncCommand(UUID.randomUUID(),
            LocalDateTime.parse("2026-11-25T09:00:00"),List.of(),List.of(UUID.randomUUID()));
        AdminNoteLinkSyncRepository delegate=(c,user)->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(actor,user);
            assertEquals(0,jdbc.queryForObject("select count(*) from retry_probe",Integer.class));
            int attempt=calls.incrementAndGet();
            jdbc.update("insert into retry_probe(id,attempt) values (?,?)",c.noteId(),attempt);
            if(attempt==1)throw new RuntimeException(new SQLException("simulated deadlock","40P01"));
            return new AdminNoteLinkSyncRepository.SyncResult(
                c.noteId(),c.relatedNoteIds(),1,0,c.expectedUpdatedAt());
        };
        var retry=new RetryingAdminNoteLinkSyncRepository(delegate,manager,millis->{});
        assertEquals(cmd.noteId(),retry.sync(cmd,actor).noteId());
        assertEquals(2,calls.get());
        assertEquals(1,jdbc.queryForObject("select count(*) from retry_probe",Integer.class));
        assertEquals(2,jdbc.queryForObject(
            "select attempt from retry_probe where id=?",Integer.class,cmd.noteId()));
    }
}
