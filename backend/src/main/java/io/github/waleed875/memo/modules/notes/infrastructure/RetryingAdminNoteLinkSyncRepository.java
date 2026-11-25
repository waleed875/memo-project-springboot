package io.github.waleed875.memo.modules.notes.infrastructure;

import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncCommand;
import io.github.waleed875.memo.modules.notes.application.AdminNoteLinkSyncRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retry boundary around the COMPLETE link-sync use case, never around a JDBC
 * statement inside an aborted transaction. Not exposed as an HTTP handler.
 *
 * This decorator deliberately refuses callers that already own a transaction:
 * a retry must not commit a separately nested transaction in an outer workflow.
 * The delegate checks active actor role, revision, expected prior link set and
 * validates all targets anew on EVERY attempt.
 */
public final class RetryingAdminNoteLinkSyncRepository implements AdminNoteLinkSyncRepository {
    public static final int MAX_ATTEMPTS = 3;
    private final AdminNoteLinkSyncRepository delegate;
    private final TransactionTemplate transaction;
    private final Pause pause;

    @FunctionalInterface
    public interface Pause {
        void sleep(long millis) throws InterruptedException;
    }

    public RetryingAdminNoteLinkSyncRepository(
        AdminNoteLinkSyncRepository delegate, PlatformTransactionManager manager
    ) {
        this(delegate, manager, Thread::sleep);
    }

    // Package-private injection seam for deterministic tests.
    RetryingAdminNoteLinkSyncRepository(
        AdminNoteLinkSyncRepository delegate, PlatformTransactionManager manager, Pause pause
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.transaction = new TransactionTemplate(
            Objects.requireNonNull(manager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    @Override
    public SyncResult sync(AdminNoteLinkSyncCommand command, UUID actorId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "Admin note link retries require a top-level transaction boundary.");
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transaction.execute(status -> delegate.sync(command, actorId));
            } catch (RuntimeException failure) {
                if (!NoteTransactionRetryPolicy.mayRetryInFreshTransaction(failure)
                    || attempt == MAX_ATTEMPTS) {
                    throw failure;
                }
                try {
                    // Strictly bounded delays, no hidden per-statement retries.
                    pause.sleep(25L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new NoteLinkRetryInterrupted(interrupted);
                }
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }
}
