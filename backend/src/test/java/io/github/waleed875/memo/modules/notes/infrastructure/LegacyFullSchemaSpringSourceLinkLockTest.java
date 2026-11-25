package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.application.NoteSourceStore;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceDraft;
import io.github.waleed875.memo.modules.notes.domain.NoteSourceType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
 * Uses the REAL source and link adapters concurrently on all original 001-070
 * schema migrations, with source/ref sync triggers active.
 */
@EnabledIfEnvironmentVariable(named = "MEMO_FULL_LEGACY_SCHEMA", matches = "true")
class LegacyFullSchemaSpringSourceLinkLockTest {
    private JdbcTemplate jdbc;
    private JdbcTransactionManager manager;
    private NoteSourceStore sources;
    private AdminNoteLinkSyncRepository links;
    private UUID admin, company, entryType, exitType;

    @BeforeEach void setUp() {
        var ds = new DriverManagerDataSource(
            "jdbc:postgresql://" + System.getenv("PGHOST") + ":" +
                System.getenv("PGPORT") + "/" + System.getenv("PGDATABASE"),
            System.getenv("PGUSER"), System.getenv("PGPASSWORD"));
        jdbc = new JdbcTemplate(ds);
        manager = new JdbcTransactionManager(ds);
        var sourceProxy = new TransactionProxyFactoryBean();
        sourceProxy.setTarget(new JdbcNoteSourceStore(
            jdbc, new NamedParameterJdbcTemplate(ds)));
        sourceProxy.setTransactionManager(manager);
        sourceProxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        sourceProxy.setProxyInterfaces(new Class<?>[]{NoteSourceStore.class});
        sourceProxy.afterPropertiesSet();
        sources = (NoteSourceStore) sourceProxy.getObject();

        var linkProxy = new TransactionProxyFactoryBean();
        linkProxy.setTarget(new JdbcAdminNoteLinkSyncRepository(
            jdbc, new NamedParameterJdbcTemplate(ds)));
        linkProxy.setTransactionManager(manager);
        linkProxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        linkProxy.setProxyInterfaces(new Class<?>[]{AdminNoteLinkSyncRepository.class});
        linkProxy.afterPropertiesSet();
        links = (AdminNoteLinkSyncRepository) linkProxy.getObject();

        admin = jdbc.queryForObject("""
            select u.id from auth.users u join auth.roles r on r.id=u.role_id
            where r.code='ADMIN' and u.is_active and not u.is_deleted
            order by u.created_at limit 1
            """, UUID.class);
        entryType = jdbc.queryForObject(
            "select id from core.note_types where code='ENTRY'", UUID.class);
        exitType = jdbc.queryForObject(
            "select id from core.note_types where code='EXIT'", UUID.class);
        company = UUID.randomUUID();
        jdbc.update("insert into core.companies(id,name) values (?,'Spring lock test company')",company);
    }

    UUID memo(UUID type) {
        UUID id=UUID.randomUUID();
        String number=Long.toString(
            100000L+Math.floorMod(id.getMostSignificantBits(),1000000000L));
        jdbc.update("""
            insert into core.notes(id,note_number,note_number_normalized,
                note_type_id,company_id,writing_date,arrival_at,created_by,status,is_loot)
            values (?,?,?,?,?,current_date,current_date,?,'COMPLETE',false)
            """,id,number,number,type,company,admin);
        return id;
    }

    AdminNoteLinkSyncCommand emptySnapshot(UUID e,UUID target) {
        LocalDateTime version=jdbc.queryForObject(
            "select updated_at from core.notes where id=?",LocalDateTime.class,e);
        return new AdminNoteLinkSyncCommand(e,version,List.of(),List.of(target));
    }

    NoteSourceDraft exitSource(UUID... ids) {
        var references=java.util.stream.IntStream.range(0,ids.length)
            .mapToObj(i->new NoteSourceDraft.ExitReference(ids[i],i+1)).toList();
        return new NoteSourceDraft(NoteSourceType.Exit,null,null,references,List.of());
    }

    int linkCount(UUID e,UUID x) {
        return jdbc.queryForObject("""
            select count(*) from core.note_links
             where (source_note_id=? and related_note_id=?)
                or (source_note_id=? and related_note_id=?)
            """,Integer.class,e,x,x,e);
    }

    int refCount(UUID e,UUID x) {
        return jdbc.queryForObject("""
            select count(*) from core.note_source_exit_refs r
             join core.note_sources s on s.id=r.source_id
             where s.note_id=? and r.exit_note_id=?
            """,Integer.class,e,x);
    }

    @Test
    void springSourceWriterBlocksJavaUntilCommitAndJavaRejectsStaleLinks() throws Exception {
        UUID e=memo(entryType),a=memo(exitType),b=memo(exitType);
        var stale=emptySnapshot(e,b);
        var executor=Executors.newFixedThreadPool(2);
        var sourceApplied=new CountDownLatch(1);
        var releaseSource=new CountDownLatch(1);
        var javaStarted=new CountDownLatch(1);
        try {
            var sourceJob=executor.submit((Callable<Void>)()->{
                new TransactionTemplate(manager).executeWithoutResult(status->{
                    sources.save(e,admin,false,exitSource(a));
                    sourceApplied.countDown();
                    try {
                        if(!releaseSource.await(10,TimeUnit.SECONDS))
                            throw new IllegalStateException("source release timeout");
                    } catch(InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                });
                return null;
            });
            assertTrue(sourceApplied.await(10,TimeUnit.SECONDS));
            var javaJob=executor.submit((Callable<Void>)()->{
                javaStarted.countDown();
                assertThrows(NoteDraftEditConflict.class,()->links.sync(stale,admin));
                return null;
            });
            assertTrue(javaStarted.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->javaJob.get(300,TimeUnit.MILLISECONDS));
            releaseSource.countDown();
            sourceJob.get(15,TimeUnit.SECONDS);
            javaJob.get(15,TimeUnit.SECONDS);
            assertEquals(1,linkCount(e,a));
            assertEquals(1,refCount(e,a));
            assertEquals(0,linkCount(e,b));
            assertEquals(0,refCount(e,b));
        } finally {
            releaseSource.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void springJavaWriterBlocksSourceSaveUntilLinkCommitWithoutDeadlock() throws Exception {
        UUID e=memo(entryType),a=memo(exitType),b=memo(exitType);
        var command=emptySnapshot(e,a);
        var executor=Executors.newFixedThreadPool(2);
        var javaApplied=new CountDownLatch(1);
        var releaseJava=new CountDownLatch(1);
        var sourceStarted=new CountDownLatch(1);
        try {
            var javaJob=executor.submit((Callable<Void>)()->{
                new TransactionTemplate(manager).executeWithoutResult(status->{
                    links.sync(command,admin);
                    javaApplied.countDown();
                    try {
                        if(!releaseJava.await(10,TimeUnit.SECONDS))
                            throw new IllegalStateException("link release timeout");
                    } catch(InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                });
                return null;
            });
            assertTrue(javaApplied.await(10,TimeUnit.SECONDS));
            var sourceJob=executor.submit((Callable<Void>)()->{
                sourceStarted.countDown();
                sources.save(e,admin,false,exitSource(a,b));
                return null;
            });
            assertTrue(sourceStarted.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->sourceJob.get(300,TimeUnit.MILLISECONDS));
            releaseJava.countDown();
            javaJob.get(15,TimeUnit.SECONDS);
            sourceJob.get(15,TimeUnit.SECONDS);
            assertEquals(1,linkCount(e,a));
            assertEquals(1,linkCount(e,b));
            assertEquals(1,refCount(e,a));
            assertEquals(1,refCount(e,b));
        } finally {
            releaseJava.countDown();
            executor.shutdownNow();
        }
    }
}
