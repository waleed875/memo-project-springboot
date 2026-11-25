package io.github.waleed875.memo.modules.notes.infrastructure;
import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.modules.notes.application.*;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
class RetryingAdminNoteLinkSyncRepositoryTest {
    static class Recorder implements PlatformTransactionManager {
        int started,committed,rolledBack;
        @Override public TransactionStatus getTransaction(TransactionDefinition d) {
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,d.getPropagationBehavior());
            started++; return new SimpleTransactionStatus(true);
        }
        @Override public void commit(TransactionStatus status) {committed++;}
        @Override public void rollback(TransactionStatus status) {rolledBack++;}
    }
    static AdminNoteLinkSyncCommand command() {
        return new AdminNoteLinkSyncCommand(UUID.randomUUID(),
            LocalDateTime.parse("2026-11-25T09:00:00"),List.of(),List.of(UUID.randomUUID()));
    }
    static RuntimeException transientError(String state) {
        return new RuntimeException("wrapped",new SQLException("transient",state));
    }
    static AdminNoteLinkSyncRepository.SyncResult result(AdminNoteLinkSyncCommand c) {
        return new AdminNoteLinkSyncRepository.SyncResult(
            c.noteId(),c.relatedNoteIds(),1,0,c.expectedUpdatedAt());
    }
    @Test void retriesTransientStatesOnlyAndUsesFreshTransactions() {
        Recorder tm=new Recorder();AtomicInteger count=new AtomicInteger(),pauses=new AtomicInteger();
        AdminNoteLinkSyncRepository delegate=(c,u)->{
            int n=count.incrementAndGet();
            if(n<3)throw transientError(n==1?"40P01":"40001");
            return result(c);
        };
        var retry=new RetryingAdminNoteLinkSyncRepository(delegate,tm,millis->{
            assertTrue(millis==25||millis==50);pauses.incrementAndGet();
        });
        var c=command();
        assertEquals(result(c),retry.sync(c,UUID.randomUUID()));
        assertEquals(3,count.get());assertEquals(3,tm.started);
        assertEquals(2,tm.rolledBack);assertEquals(1,tm.committed);
        assertEquals(2,pauses.get());
    }
    @Test void staleRevisionIsNeverRetried() {
        Recorder tm=new Recorder();AtomicInteger count=new AtomicInteger();
        AdminNoteLinkSyncRepository delegate=(c,u)->{
            count.incrementAndGet();throw new NoteDraftEditConflict("stale");
        };
        var retry=new RetryingAdminNoteLinkSyncRepository(delegate,tm,millis->fail("no sleep"));
        assertThrows(NoteDraftEditConflict.class,()->retry.sync(command(),UUID.randomUUID()));
        assertEquals(1,count.get());assertEquals(1,tm.rolledBack);
    }
    @Test void retriesAreStrictlyBounded() {
        Recorder tm=new Recorder();AtomicInteger count=new AtomicInteger();
        AdminNoteLinkSyncRepository delegate=(c,u)->{
            count.incrementAndGet();throw transientError("55P03");
        };
        var retry=new RetryingAdminNoteLinkSyncRepository(delegate,tm,millis->{});
        assertThrows(RuntimeException.class,()->retry.sync(command(),UUID.randomUUID()));
        assertEquals(3,count.get());assertEquals(3,tm.rolledBack);assertEquals(0,tm.committed);
    }
    @Test void interruptionStopsNewWritesAndPreservesFlag() {
        Recorder tm=new Recorder();AtomicInteger count=new AtomicInteger();
        AdminNoteLinkSyncRepository delegate=(c,u)->{
            count.incrementAndGet();throw transientError("40P01");
        };
        var retry=new RetryingAdminNoteLinkSyncRepository(delegate,tm,millis->{
            throw new InterruptedException("stop");
        });
        try {
            assertThrows(NoteLinkRetryInterrupted.class,()->retry.sync(command(),UUID.randomUUID()));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,count.get());
        } finally {Thread.interrupted();}
    }
    @Test void neverStartsNestedIndependentCommit() {
        Recorder tm=new Recorder();
        var retry=new RetryingAdminNoteLinkSyncRepository(
            (c,u)->{throw new AssertionError("not reached");},tm,millis->{});
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThrows(IllegalStateException.class,()->retry.sync(command(),UUID.randomUUID()));}
        finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertEquals(0,tm.started);
    }
}
