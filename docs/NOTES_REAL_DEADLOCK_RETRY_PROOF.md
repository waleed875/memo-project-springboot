# Phase 30 — real PostgreSQL deadlock and lock-timeout retry proof

The integration test `RetryingAdminNoteLinkSyncRealDeadlockPostgresIT`
uses PostgreSQL 16 Testcontainers and distinct real transactions.

* An independent transaction locks row 1 and requests row 2; a
  retry-decorated transaction locks row 2 and requests row 1.
  PostgreSQL's deadlock detector must produce SQLSTATE `40P01`.
  The first attempt rolls back completely, and a second fresh
  transaction commits the request once.
* A separate transaction holds a row lock through three
  `55P03` lock-timeout failures. All three aborted attempts
  must leave zero committed retry-probe rows.

Both tests check actual SQLSTATE classification, bounded retry count,
rollback effects, and database end state. These tests use a disposable
container, NOT a production database.

## Constraints

The test delegate uses probe tables to isolate transaction mechanics.
It does not establish that full original C# + Spring Boot writes
share an enforced global lock, nor prove exact behavior under arbitrary
source/links races or broad production load. The retry decorator remains
limited to internal admin note-link synchronization. No automatic
retry for uploads, delete, review transitions or note creation.

Follow-on release gates: full C# golden-master parity, complete migration
tests, idempotent external effects, security review and React E2E.
No main merge, deployment or original .NET repo modifications.
