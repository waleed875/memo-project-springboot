# Phase 27 — cooperative advisory lock proof (test-only)

The existing Java link synchronizer acquires the PostgreSQL transaction advisory
lock derived from hashtextextended('memo:admin-note-link-sync', 23070) **before**
reading and mutating note links.

This PR provides two deliberately interleaved integration tests on the original
001–070 PostgreSQL schema and migration 063/070 triggers:

1. A coordinated legacy source transaction acquires the SAME transaction lock,
   writes an EXIT reference, then holds the transaction open. The Java adapter
   must block, resume after the source commit and reject a stale expected link
   snapshot. The committed source reference remains intact.
2. A Java transaction invokes the real adapter, then remains open. A coordinated
   source writer must block on the same advisory key until Java commits. After
   acquiring the lock, the source writer inserts an additional EXIT reference,
   and the triggers keep both sides in sync.

These are genuine overlapping transactions with bounded waits and timeouts.
They prove a *cooperative* lock protocol, not universal isolation.

**Critical limitation:** The original C# source writer and any direct SQL
writers do **not** automatically take the advisory key. Neither this PR nor
migrations 001–070 add a database trigger enforcing the lock. It would be unsafe
to claim that every writer is serialized. A later coordinated source-writing
port or carefully reviewed database migration is required. A trigger that
takes the lock only after PostgreSQL has already acquired a row lock can
introduce lock-order inversion and deadlocks. Audit actual lock order,
choose a deadlock retry strategy, and test uncoordinated legacy writers before
any deployment.

Only CI fixtures and tests change here. Original .NET migrations remain
read-only; no production schema changes, HTTP exposure, main merge or deploy.
