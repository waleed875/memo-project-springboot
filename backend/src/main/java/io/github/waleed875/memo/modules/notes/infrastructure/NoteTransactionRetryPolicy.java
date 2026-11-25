package io.github.waleed875.memo.modules.notes.infrastructure;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Classification only. Do NOT retry a failed JDBC operation inside an
 * aborted PostgreSQL transaction. An eventual retry must re-enter the
 * application use case with a fresh transaction and recheck authorization,
 * revision and expected links; never silently retry optimistic conflicts.
 */
public final class NoteTransactionRetryPolicy {
    public enum FailureKind { DEADLOCK, SERIALIZATION, LOCK_TIMEOUT, OTHER }

    private NoteTransactionRetryPolicy() {}

    public static FailureKind classify(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && visited.add(t); t = t.getCause()) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("40P01".equals(state)) return FailureKind.DEADLOCK;
                if ("40001".equals(state)) return FailureKind.SERIALIZATION;
                if ("55P03".equals(state)) return FailureKind.LOCK_TIMEOUT;
            }
        }
        return FailureKind.OTHER;
    }

    public static boolean mayRetryInFreshTransaction(Throwable failure) {
        return classify(failure) != FailureKind.OTHER;
    }
}
