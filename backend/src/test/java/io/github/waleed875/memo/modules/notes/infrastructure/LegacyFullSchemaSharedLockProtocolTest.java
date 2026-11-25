package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cooperative lock protocol on the fully replayed 001-070 schema.
 * A legacy source writer MUST take the same advisory xact lock BEFORE
 * modifying source refs; the preexisting raw legacy API does not yet do so.
 */
@EnabledIfEnvironmentVariable(named = "MEMO_FULL_LEGACY_SCHEMA", matches = "true")
class LegacyFullSchemaSharedLockProtocolTest {
    private static final String LOCK_SQL =
        "select pg_advisory_xact_lock(hashtextextended('memo:admin-note-link-sync',23070))";
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private JdbcTransactionManager manager;
    private AdminNoteLinkSyncRepository repository;
    private UUID admin, company, entryType, exitType;

    @BeforeEach void init() {
        ds = new DriverManagerDataSource(
            "jdbc:postgresql://" + System.getenv("PGHOST") + ":" +
                System.getenv("PGPORT") + "/" + System.getenv("PGDATABASE"),
            System.getenv("PGUSER"), System.getenv("PGPASSWORD"));
        jdbc = new JdbcTemplate(ds);
        manager = new JdbcTransactionManager(ds);
        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcAdminNoteLinkSyncRepository(
            jdbc, new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(manager);
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{AdminNoteLinkSyncRepository.class});
        proxy.afterPropertiesSet();
        repository = (AdminNoteLinkSyncRepository) proxy.getObject();
        admin = jdbc.queryForObject("""
            select u.id from auth.users u join auth.roles r on r.id=u.role_id
            where r.code='ADMIN' and u.is_active and not u.is_deleted
            order by u.created_at limit 1
            """, UUID.class);
        entryType = jdbc.queryForObject(
            "select id from core.note_types where code='ENTRY'", UUID.class);
        exitType = jdbc.queryForObject(
            "select id from core.note_types where code='EXIT'", UUID.class);
        company=UUID.randomUUID();
        jdbc.update("insert into core.companies(id,name) values (?,'Shared lock integration')",company);
    }

    private UUID memo(UUID type) {
        UUID id=UUID.randomUUID();
        String number=Long.toString(100000L+Math.floorMod(id.getMostSignificantBits(),1000000000L));
        jdbc.update("""
            insert into core.notes(id,note_number,note_number_normalized,note_type_id,
              company_id,writing_date,arrival_at,created_by,status,is_loot)
            values (?,?,?,?,?,current_date,current_date,?,'COMPLETE',false)
            """,id,number,number,type,company,admin);
        return id;
    }
    private AdminNoteLinkSyncCommand emptySnapshot(UUID e,UUID target) {
        LocalDateTime revision=jdbc.queryForObject(
            "select updated_at from core.notes where id=?",LocalDateTime.class,e);
        return new AdminNoteLinkSyncCommand(e,revision,List.of(),List.of(target));
    }
    private int links(UUID e,UUID x) {
        return jdbc.queryForObject("""
            select count(*) from core.note_links
            where (source_note_id=? and related_note_id=?)
               or (source_note_id=? and related_note_id=?)
            """,Integer.class,e,x,x,e);
    }
    private int refs(UUID e,UUID x) {
        return jdbc.queryForObject("""
            select count(*) from core.note_source_exit_refs r
            join core.note_sources s on s.id=r.source_id
            where s.note_id=? and r.exit_note_id=?
            """,Integer.class,e,x);
    }

    private void acquire(Connection c) throws Exception {
        try (var statement=c.createStatement()) {
            statement.execute("set local lock_timeout = '5s'");
            statement.execute(LOCK_SQL);
        }
    }
    private void directSourceInsert(Connection c,UUID e,UUID x) throws Exception {
        UUID source=UUID.randomUUID();
        try(var sql=c.prepareStatement("""
            insert into core.note_sources(id,note_id,source_type,created_by)
            values (?,?,'EXIT',?)
            """)) {
            sql.setObject(1,source);sql.setObject(2,e);sql.setObject(3,admin);
            sql.executeUpdate();
        }
        try(var sql=c.prepareStatement("""
            insert into core.note_source_exit_refs(source_id,exit_note_id,reference_order)
            values (?,?,1)
            """)) {
            sql.setObject(1,source);sql.setObject(2,x);sql.executeUpdate();
        }
    }

    @Test void coordinatedLegacyWriterWinsFirstAndJavaRejectsStaleSnapshot() throws Exception {
        UUID e=memo(entryType),a=memo(exitType),b=memo(exitType);
        var stale=emptySnapshot(e,b);
        var pool=Executors.newSingleThreadExecutor();
        try(Connection source=ds.getConnection()) {
            source.setAutoCommit(false);
            acquire(source);
            directSourceInsert(source,e,a);
            CountDownLatch started=new CountDownLatch(1);
            var java=pool.submit((Callable<Void>)()->{
                started.countDown();
                assertThrows(NoteDraftEditConflict.class,
                    ()->repository.sync(stale,admin));
                return null;
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            // Java must wait for the source transaction's advisory lock.
            assertThrows(TimeoutException.class,()->java.get(300,TimeUnit.MILLISECONDS));
            source.commit();
            java.get(15,TimeUnit.SECONDS);
            assertEquals(1,links(e,a));
            assertEquals(1,refs(e,a));
            assertEquals(0,links(e,b));
            assertEquals(0,refs(e,b));
        } finally {pool.shutdownNow();}
    }

    @Test void javaTransactionWinsFirstAndCoordinatedSourceWaitsForCommit() throws Exception {
        UUID e=memo(entryType),a=memo(exitType),b=memo(exitType);
        var command=emptySnapshot(e,a);
        var pool=Executors.newFixedThreadPool(2);
        CountDownLatch javaApplied=new CountDownLatch(1),releaseJava=new CountDownLatch(1);
        CountDownLatch sourceStarted=new CountDownLatch(1);
        try {
            var java=pool.submit((Callable<Void>)()->{
                new TransactionTemplate(manager).executeWithoutResult(status->{
                    repository.sync(command,admin);
                    javaApplied.countDown();
                    try {
                        if(!releaseJava.await(10,TimeUnit.SECONDS))
                            throw new IllegalStateException("Java release latch timed out");
                    } catch(InterruptedException ex) {
                        Thread.currentThread().interrupt();throw new IllegalStateException(ex);
                    }
                });
                return null;
            });
            assertTrue(javaApplied.await(10,TimeUnit.SECONDS));
            var legacy=pool.submit((Callable<Void>)()->{
                try(Connection conn=ds.getConnection()) {
                    conn.setAutoCommit(false);
                    sourceStarted.countDown();
                    acquire(conn);
                    UUID sourceId;
                    try(var q=conn.prepareStatement("select id from core.note_sources where note_id=?")) {
                        q.setObject(1,e);
                        try(var rs=q.executeQuery()) {
                            if(!rs.next())throw new IllegalStateException("EXIT source absent");
                            sourceId=(UUID)rs.getObject(1);
                        }
                    }
                    try(var q=conn.prepareStatement("""
                        insert into core.note_source_exit_refs(source_id,exit_note_id,reference_order)
                        values (?,?,2)
                        """)) {
                        q.setObject(1,sourceId);q.setObject(2,b);q.executeUpdate();
                    }
                    conn.commit();
                }
                return null;
            });
            assertTrue(sourceStarted.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->legacy.get(300,TimeUnit.MILLISECONDS));
            releaseJava.countDown();
            java.get(15,TimeUnit.SECONDS);
            legacy.get(15,TimeUnit.SECONDS);
            assertEquals(1,links(e,a));
            assertEquals(1,links(e,b));
            assertEquals(1,refs(e,a));
            assertEquals(1,refs(e,b));
        } finally {
            releaseJava.countDown();
            pool.shutdownNow();
        }
    }
}
