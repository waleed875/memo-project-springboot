package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncRepository;
import io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict;
import io.github.waleed875.memo.modules.notes.domain.NoteSaveValidationException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;

/**
 * Unlike the small Testcontainers fixture, these tests exercise the real
 * JDBC adapter with all original C# migrations 001-070 and triggers installed.
 *
 * The dedicated GitHub Actions workflow provisions a disposable PostgreSQL 16
 * database. Tests never apply migrations and never connect to production.
 */
@EnabledIfEnvironmentVariable(named = "MEMO_FULL_LEGACY_SCHEMA", matches = "true")
class LegacyFullSchemaNoteLinkSyncTest {
    private JdbcTemplate jdbc;
    private AdminNoteLinkSyncRepository repository;
    private UUID admin;
    private UUID company;
    private UUID entryType;
    private UUID exitType;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
            "jdbc:postgresql://" + System.getenv("PGHOST") + ":" + System.getenv("PGPORT")
                + "/" + System.getenv("PGDATABASE"),
            System.getenv("PGUSER"), System.getenv("PGPASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        admin = jdbc.queryForObject("""
            select u.id from auth.users u join auth.roles r on r.id = u.role_id
             where r.code = 'ADMIN' and u.is_active and not u.is_deleted
             order by u.created_at limit 1
            """, UUID.class);
        entryType = jdbc.queryForObject(
            "select id from core.note_types where code = 'ENTRY'", UUID.class);
        exitType = jdbc.queryForObject(
            "select id from core.note_types where code = 'EXIT'", UUID.class);
        assertNotNull(admin);
        assertNotNull(entryType);
        assertNotNull(exitType);
        company = UUID.randomUUID();
        jdbc.update("insert into core.companies(id, name) values (?, 'Full schema test company')", company);

        var proxy = new TransactionProxyFactoryBean();
        proxy.setTarget(new JdbcAdminNoteLinkSyncRepository(
            jdbc, new NamedParameterJdbcTemplate(dataSource)));
        proxy.setTransactionManager(new JdbcTransactionManager(dataSource));
        proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.setProxyInterfaces(new Class<?>[]{AdminNoteLinkSyncRepository.class});
        proxy.afterPropertiesSet();
        repository = (AdminNoteLinkSyncRepository) proxy.getObject();
    }

    private UUID note(UUID companyId, UUID noteType, boolean loot) {
        UUID id = UUID.randomUUID();
        String numericNumber = Long.toString(
            100_000L + Math.floorMod(id.getMostSignificantBits(), 1_000_000_000L));
        jdbc.update("""
            insert into core.notes(
                id, note_number, note_number_normalized, note_type_id,
                company_id, writing_date, arrival_at, created_by, status, is_loot)
            values (?, ?, ?, ?, ?, current_date, current_date, ?, 'COMPLETE', ?)
            """, id, numericNumber, numericNumber, noteType, companyId, admin, loot);
        return id;
    }
    private UUID entry() { return note(company, entryType, false); }
    private UUID exit() { return note(company, exitType, false); }

    private LocalDateTime revision(UUID noteId) {
        return jdbc.queryForObject(
            "select updated_at from core.notes where id = ?", LocalDateTime.class, noteId);
    }
    private AdminNoteLinkSyncCommand command(UUID noteId, UUID... exits) {
        return new AdminNoteLinkSyncCommand(noteId, revision(noteId), List.of(exits));
    }
    private int linkCount(UUID entry, UUID exit) {
        return jdbc.queryForObject("""
            select count(*) from core.note_links
             where (source_note_id = ? and related_note_id = ?)
                or (source_note_id = ? and related_note_id = ?)
            """, Integer.class, entry, exit, exit, entry);
    }
    private int sourceRefCount(UUID entry, UUID exit) {
        return jdbc.queryForObject("""
            select count(*) from core.note_source_exit_refs r
              join core.note_sources s on s.id = r.source_id
             where s.note_id = ? and r.exit_note_id = ?
            """, Integer.class, entry, exit);
    }
    private int compatibleCount(UUID entry, UUID exit) {
        return jdbc.queryForObject("""
            select count(*) from core.v_note_links_compat
             where (source_note_id = ? and related_note_id = ?)
                or (source_note_id = ? and related_note_id = ?)
            """, Integer.class, entry, exit, exit, entry);
    }
    private int audits(UUID note) {
        return jdbc.queryForObject("""
            select count(*) from audit.logs
             where record_id = ? and action_type in ('LINK_NOTES', 'UNLINK_NOTES')
            """, Integer.class, note);
    }

    @Test
    void javaLinkReplacementPropagatesThroughOriginal063And070Triggers() {
        UUID entry = entry(), a = exit(), b = exit(), c = exit();
        var first = repository.sync(command(entry, a, b), admin);
        assertEquals(2, first.added());
        for (UUID id : List.of(a, b)) {
            assertEquals(1, linkCount(entry, id));
            assertEquals(1, sourceRefCount(entry, id));
            assertEquals(1, compatibleCount(entry, id));
        }
        LocalDateTime version = revision(entry);
        var noOp = repository.sync(command(entry, a, b), admin);
        assertEquals(0, noOp.added());
        assertEquals(0, noOp.removed());
        assertEquals(version, revision(entry));
        var replaced = repository.sync(command(entry, b, c), admin);
        assertEquals(1, replaced.added());
        assertEquals(1, replaced.removed());
        assertEquals(0, sourceRefCount(entry, a));
        assertEquals(0, linkCount(entry, a));
        for (UUID id : List.of(b, c)) {
            assertEquals(1, linkCount(entry, id));
            assertEquals(1, sourceRefCount(entry, id));
            assertEquals(1, compatibleCount(entry, id));
        }
        assertEquals(4, audits(entry));
        var cleared = repository.sync(command(entry), admin);
        assertEquals(2, cleared.removed());
        for (UUID id : List.of(b, c)) {
            assertEquals(0, linkCount(entry, id));
            assertEquals(0, sourceRefCount(entry, id));
        }
        assertEquals(6, audits(entry));
    }

    @Test
    void sourceSideOperationsAreSeenByJavaThenSafelyReconciled() {
        UUID e = entry(), a = exit(), b = exit();
        UUID sourceId = UUID.randomUUID();
        jdbc.update("""
            insert into core.note_sources(id, note_id, source_type, created_by)
            values (?, ?, 'EXIT', ?)
            """, sourceId, e, admin);
        jdbc.update("""
            insert into core.note_source_exit_refs(source_id, exit_note_id, reference_order)
            values (?, ?, 1)
            """, sourceId, a);
        assertEquals(1, linkCount(e, a));
        assertEquals(1, compatibleCount(e, a));
        var synced = repository.sync(command(e, b), admin);
        assertEquals(1, synced.added());
        assertEquals(1, synced.removed());
        assertEquals(0, linkCount(e, a));
        assertEquals(0, sourceRefCount(e, a));
        assertEquals(1, linkCount(e, b));
        assertEquals(1, sourceRefCount(e, b));
        assertEquals(1, compatibleCount(e, b));
        jdbc.update("""
            delete from core.note_source_exit_refs
            where source_id = ? and exit_note_id = ?
            """, sourceId, b);
        assertEquals(0, linkCount(e, b));
        assertEquals(0, sourceRefCount(e, b));
        assertEquals(0, compatibleCount(e, b));
    }

    @Test
    void auditErrorRollsBackBothJavaLinksAndLegacyTriggerSideEffects() {
        UUID e = entry(), x = exit();
        LocalDateTime before = revision(e);
        String constraintName = "ck_reject_link_full_schema";
        jdbc.execute("""
            alter table audit.logs add constraint ck_reject_link_full_schema
            check (action_type <> 'LINK_NOTES') not valid
            """);
        try {
            assertThrows(Exception.class, () -> repository.sync(command(e, x), admin));
            assertEquals(0, linkCount(e, x));
            assertEquals(0, sourceRefCount(e, x));
            assertEquals(0, audits(e));
            assertEquals(before, revision(e));
        } finally {
            jdbc.execute("alter table audit.logs drop constraint if exists " + constraintName);
        }
    }

    @Test
    void twoJavaWritersWithSameRevisionCannotBothCommit() throws Exception {
        UUID e = entry(), a = exit(), b = exit();
        LocalDateTime version = revision(e);
        var first = new AdminNoteLinkSyncCommand(e, version, List.of(a));
        var second = new AdminNoteLinkSyncCommand(e, version, List.of(b));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger(), conflicts = new AtomicInteger();
        try {
            Callable<Void> taskA = () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try { repository.sync(first, admin); accepted.incrementAndGet(); }
                catch (NoteDraftEditConflict expected) { conflicts.incrementAndGet(); }
                return null;
            };
            Callable<Void> taskB = () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try { repository.sync(second, admin); accepted.incrementAndGet(); }
                catch (NoteDraftEditConflict expected) { conflicts.incrementAndGet(); }
                return null;
            };
            var aFuture = pool.submit(taskA);
            var bFuture = pool.submit(taskB);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            aFuture.get(20, TimeUnit.SECONDS);
            bFuture.get(20, TimeUnit.SECONDS);
            assertEquals(1, accepted.get());
            assertEquals(1, conflicts.get());
            assertEquals(1, linkCount(e, a) + linkCount(e, b));
            assertEquals(1, sourceRefCount(e, a) + sourceRefCount(e, b));
            assertEquals(1, audits(e));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsCrossCompanyAndSameSideBeforeAnyDatabaseWrites() {
        UUID e = entry(), otherEntry = entry();
        UUID otherCompanyId = UUID.randomUUID();
        jdbc.update("insert into core.companies(id,name) values (?, 'Foreign')",
            otherCompanyId);
        UUID foreignExit = note(otherCompanyId, exitType, false);
        assertThrows(NoteSaveValidationException.class,
            () -> repository.sync(command(e, otherEntry), admin));
        assertThrows(NoteSaveValidationException.class,
            () -> repository.sync(command(e, foreignExit), admin));
        assertEquals(0, audits(e));
        assertEquals(0, linkCount(e, otherEntry));
        assertEquals(0, linkCount(e, foreignExit));
    }
}
