package io.github.waleed875.memo.modules.notes.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class NoteTransactionRetryPolicyTest {
    @Test void recognizesPostgresDeadlockAndSerializationThroughWrappedCauses() {
        assertEquals(NoteTransactionRetryPolicy.FailureKind.DEADLOCK,
            NoteTransactionRetryPolicy.classify(new RuntimeException(
                new SQLException("deadlock detected","40P01"))));
        assertEquals(NoteTransactionRetryPolicy.FailureKind.SERIALIZATION,
            NoteTransactionRetryPolicy.classify(new RuntimeException(
                new SQLException("could not serialize access","40001"))));
        assertEquals(NoteTransactionRetryPolicy.FailureKind.LOCK_TIMEOUT,
            NoteTransactionRetryPolicy.classify(new SQLException(
                "lock not available","55P03")));
    }

    @Test void applicationConflictsAndUnrelatedDatabaseErrorsAreNotRetryable() {
        assertFalse(NoteTransactionRetryPolicy.mayRetryInFreshTransaction(
            new io.github.waleed875.memo.modules.notes.application.NoteDraftEditConflict(
                "stale snapshot")));
        assertFalse(NoteTransactionRetryPolicy.mayRetryInFreshTransaction(
            new SQLException("unique violation","23505")));
        assertFalse(NoteTransactionRetryPolicy.mayRetryInFreshTransaction(null));
    }
}
