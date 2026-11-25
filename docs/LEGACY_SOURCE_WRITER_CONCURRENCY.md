# Cross-writer link safety — PostgreSQL 16, migrations 001–070

## Problem

Original migrations 063/070 propagate changes from core.note_source_exit_refs to core.note_links without necessarily advancing core.notes.updated_at. An admin replace-all based only on the memo timestamp could overwrite a source link that appeared after the user viewed the memo.

## Mitigation

The internal AdminNoteLinkSyncCommand now requires explicit expectedRelatedNoteIds (the caller's previous complete link set) as well as the desired relatedNoteIds and expectedUpdatedAt. The service validates both sets. The JDBC adapter compares the expected set with the current physical links before and after acquiring relevant row locks. A stale snapshot is refused with a conflict instead of silently replacing somebody else's link.

This deliberately changes an internal-only command; there is no public endpoint.

## Full-schema tests

1. Direct legacy source insertion followed by stale Java replace: conflict; links and references retained; refresh succeeds.
2. Direct legacy source deletion followed by stale Java replace: conflict; no accidental resurrection.
3. Two worker threads coordinate source commit before stale Java command: conflict, no lost update.

These tests use a disposable PostgreSQL 16 database with original 001–070 migrations and 063/070 triggers.

## Remaining release blockers

The optimistic snapshot detects committed changes visible at the time of comparison. Direct legacy writers do not take the Java advisory lock. A direct source write occurring AFTER the snapshot check but BEFORE Java commits may still race. This is not yet proven linearizable across independent writers. Before production, adopt a shared lock protocol or database-level synchronization and test deliberate transaction interleavings, including deadlocks/retries.

C# golden-master, populated-schema migration testing, security review, and React end-to-end remain required. No main merge, no deployment, no changes to original .NET repository.
