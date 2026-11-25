package io.github.waleed875.memo.modules.notes.infrastructure;
import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.notes.application.*;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AdminNoteLinkSyncPostgresIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16-bookworm")
        .withDatabaseName("link_sync_it").withUsername("memo_it").withPassword("test_only");
    static final UUID ROLE=UUID.fromString("e7000000-0000-4000-8000-000000000001");
    static final UUID USER=UUID.fromString("e7000000-0000-4000-8000-000000000002");
    static final UUID COMPANY=UUID.fromString("e7000000-0000-4000-8000-000000000003");
    static final UUID OTHER_COMPANY=UUID.fromString("e7000000-0000-4000-8000-000000000004");
    static final UUID ENTRY=UUID.fromString("e7000000-0000-4000-8000-000000000005");
    static final UUID EXIT=UUID.fromString("e7000000-0000-4000-8000-000000000006");
    JdbcTemplate jdbc; AdminNoteLinkSyncRepository repository;

    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc=new JdbcTemplate(ds);
        for(String name:List.of("audit","core","auth","system")) jdbc.execute("drop schema if exists "+name+" cascade");
        new ResourceDatabasePopulator(
            new ClassPathResource("postgres-masterdata-schema.sql"),
            new ClassPathResource("postgres-notes-detail-schema.sql"),
            new ClassPathResource("postgres-notes-create-attachments-links.sql")).execute(ds);
        jdbc.execute("""
            create schema if not exists common;
            create or replace function common.set_updated_at() returns trigger
            language plpgsql as $memo$
            begin new.updated_at=now(); return new; end;
            $memo$;
            create trigger trg_link_updated before update on core.notes
              for each row execute function common.set_updated_at();
            """);
        jdbc.update("insert into auth.roles(id,code,name_ar) values (?,'ADMIN','Admin')",ROLE);
        jdbc.update("insert into auth.users(id,username,password_hash,full_name,role_id) values (?,'admin','hash','Admin',?)",USER,ROLE);
        jdbc.update("insert into core.companies(id,name) values (?,'Company'),(?,'Other')",COMPANY,OTHER_COMPANY);
        jdbc.update("insert into core.note_types(id,code,name_ar) values (?,'ENTRY','Entry'),(?,'EXIT','Exit')",ENTRY,EXIT);
        var proxy=new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcAdminNoteLinkSyncRepository(jdbc,new NamedParameterJdbcTemplate(ds)));
        proxy.setTransactionManager(new JdbcTransactionManager(ds));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{AdminNoteLinkSyncRepository.class});
        proxy.afterPropertiesSet();
        repository=(AdminNoteLinkSyncRepository)proxy.getObject();
    }
    UUID memo(UUID company,UUID type,boolean loot) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into core.notes(id,company_id,note_type_id,note_number,status,is_loot,created_by) values (?,?,?,'1','COMPLETE',?,?)",id,company,type,loot,USER);
        return id;
    }
    LocalDateTime version(UUID id) {
        return jdbc.queryForObject("select updated_at from core.notes where id=?",LocalDateTime.class,id);
    }
    java.util.List<UUID> current(UUID note) {
        return jdbc.query("""
            select case when source_note_id = ? then related_note_id
                        else source_note_id end
              from core.note_links where source_note_id = ? or related_note_id = ?
              order by 1
            """, (rs, i) -> rs.getObject(1, UUID.class), note, note, note);
    }
    AdminNoteLinkSyncCommand cmd(UUID note,UUID... targets) {
        return new AdminNoteLinkSyncCommand(note,version(note),current(note),List.of(targets));
    }
    int links(UUID note) {
        return jdbc.queryForObject("select count(*) from core.note_links where source_note_id=? or related_note_id=?",Integer.class,note,note);
    }
    int audits(UUID note) {
        return jdbc.queryForObject("select count(*) from audit.logs where record_id=?",Integer.class,note);
    }
    @Test void replacesAndClearsAndRetainsNoopRevision() {
        UUID n=memo(COMPANY,ENTRY,false), a=memo(COMPANY,EXIT,false), b=memo(COMPANY,EXIT,false);
        assertEquals(2,repository.sync(cmd(n,a,b),USER).added());
        assertEquals(2,links(n));assertEquals(2,audits(n));
        LocalDateTime revision=version(n);
        assertEquals(0,repository.sync(cmd(n,a,b),USER).added());
        assertEquals(revision,version(n));assertEquals(2,audits(n));
        assertEquals(1,repository.sync(cmd(n,b),USER).removed());
        assertEquals(1,links(n));
        assertEquals(1,repository.sync(cmd(n),USER).removed());
        assertEquals(0,links(n));assertEquals(4,audits(n));
    }
    @Test void rejectsForeignTypeLootAndInvalidTargets() {
        UUID n=memo(COMPANY,ENTRY,false),foreign=memo(OTHER_COMPANY,EXIT,false);
        UUID wrong=memo(COMPANY,ENTRY,false), loot=memo(COMPANY,ENTRY,true);
        UUID exit=memo(COMPANY,EXIT,false);
        assertThrows(NoteSaveValidationException.class,()->repository.sync(cmd(n,foreign),USER));
        assertThrows(NoteSaveValidationException.class,()->repository.sync(cmd(n,wrong),USER));
        assertThrows(NoteSaveValidationException.class,()->repository.sync(cmd(loot,exit),USER));
        assertThrows(NoteSaveValidationException.class,()->repository.sync(cmd(n,UUID.randomUUID()),USER));
        assertEquals(0,links(n));assertEquals(0,audits(n));
    }
    @Test void auditRollbackAndStaleVersionPreventPartialLinks() {
        UUID n=memo(COMPANY,ENTRY,false),target=memo(COMPANY,EXIT,false);
        assertThrows(NoteDraftEditConflict.class,()->repository.sync(
            new AdminNoteLinkSyncCommand(n,LocalDateTime.parse("2020-01-01T00:00:00"),current(n),List.of(target)),USER));
        LocalDateTime before=version(n);
        jdbc.execute("alter table audit.logs add constraint reject_link_it check (action_type <> 'LINK_NOTES')");
        assertThrows(Exception.class,()->repository.sync(cmd(n,target),USER));
        assertEquals(0,links(n));assertEquals(0,audits(n));assertEquals(before,version(n));
    }
    @Test void twoStaleConcurrentWritersCannotBothWin() throws Exception {
        UUID n=memo(COMPANY,ENTRY,false),a=memo(COMPANY,EXIT,false),b=memo(COMPANY,EXIT,false);
        LocalDateTime version=version(n);
        var ca=new AdminNoteLinkSyncCommand(n,version,List.of(),List.of(a));
        var cb=new AdminNoteLinkSyncCommand(n,version,List.of(),List.of(b));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        AtomicInteger successes=new AtomicInteger(),conflicts=new AtomicInteger();
        try {
            Callable<Void> one=()->{ready.countDown();go.await(10,TimeUnit.SECONDS);try{repository.sync(ca,USER);successes.incrementAndGet();}catch(NoteDraftEditConflict e){conflicts.incrementAndGet();}return null;};
            Callable<Void> two=()->{ready.countDown();go.await(10,TimeUnit.SECONDS);try{repository.sync(cb,USER);successes.incrementAndGet();}catch(NoteDraftEditConflict e){conflicts.incrementAndGet();}return null;};
            Future<Void> first=pool.submit(one),second=pool.submit(two);
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();
            first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS);
            assertEquals(1,successes.get());assertEquals(1,conflicts.get());
            assertEquals(1,links(n));assertEquals(1,audits(n));
        } finally {go.countDown();pool.shutdownNow();}
    }
}
