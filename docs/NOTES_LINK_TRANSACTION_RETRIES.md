# Phase 29 — bounded retries for administrator link synchronization

## Scope
Only the INTERNAL AdminNoteLinkSyncRepository port is decorated. The public
POST/PUT note endpoints remain closed. The raw JdbcAdminNoteLinkSyncRepository
still checks active ADMIN identity, expectedUpdatedAt, the previous link set,
counterpart validity, and atomic audit on every invocation.

The primary Spring repository bean uses RetryingAdminNoteLinkSyncRepository.
This refuses an already-active caller transaction to prevent independent nested
commits inside a larger workflow. Each attempt gets its own REQUIRES_NEW
transaction with full rollback on failure. A new attempt is possible only for
PostgreSQL SQLSTATE 40P01 (deadlock), 40001 (serialization) or 55P03 (lock
unavailable). At most THREE attempts, with 25 ms and 50 ms pauses, are made.
An interrupted thread stops immediately and remains interrupted.

Logical NoteDraftEditConflict, validation, authorization, constraint and audit
errors are NOT retried. The client must reload its note/link snapshot on stale
version. Never re-execute part of an aborted transaction.

JUnit tests cover transient and logical errors, maximum attempts, interruption,
nested-caller rejection and new transaction creation. PostgreSQL 16
Testcontainers verifies that a failed first attempt writes no persistent row,
and a second transaction commits exactly once.

## Boundaries / next gates
Automatic retry is NOT added to note creation, deletion, source edits, review,
or attachments without their own idempotency and outbox designs. The retry
tests use injected SQLSTATE failure; a genuine two-transaction deadlock must
also be tested. The advisory lock coordinates Spring Boot paths but does not
enroll original C# or arbitrary SQL. Full migration replay, C# golden-master,
React E2E and code/security review remain required before merging or deploying.

No original .NET repository changes, no main merge, no deployment.
