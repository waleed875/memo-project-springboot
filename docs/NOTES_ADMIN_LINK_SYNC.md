# Restricted administrator note-link synchronization

## Original contracts

The source of truth is the original C# `NoteService.SaveNoteAsync` explicit
`synchronizeRelatedNote` replacement mode and the legacy migrations
013, 026, 034, 063 and 070. The migration 063/070 trigger architecture
synchronizes `core.note_links` with `core.note_source_exit_refs` and makes
multiple EXIT references possible. Java does **not** duplicate the trigger
writes; the installed PostgreSQL triggers must remain authoritative.

## Implemented subset

This is an **internal-only** administrator link-set replacement command.
There is no HTTP mapping and no public POST/PUT expansion.

- Authentication: database-revalidated ADMIN identity, fail-closed write gate,
  and role recheck within a write transaction.
- Request: noteId, exact expectedUpdatedAt and an explicit relatedNoteIds set.
  Null list is invalid; empty list means clear. Maximum 100 nonzero, distinct,
  nonself targets.
- Validation: notes must exist and not be deleted; same company, opposite
  ENTRY/EXIT types; reject LOOT entry links. The database's production link
  validation triggers provide an additional authoritative check.
- Transaction: coarse advisory lock among this new command's callers,
  deterministic row locks for the note and affected targets, stale-version
  detection, idempotent no-op, symmetric delete/insert and JSONB LINK_NOTES
  / UNLINK_NOTES audits. Update note revision only if link set changed.
  Any audit failure rolls back every link modification.

## Release blockers and intentional differences

The original C# HTTP link/unlink endpoints have different roles, input
semantics and no mandatory optimistic token. This intentionally stricter
internal command is **not** a full wire-contract replacement.

The reduced PostgreSQL Testcontainers fixture does **not** run migrations
001–070 or reproduce all source-reference synchronization triggers. Before
deployment and before enabling the command on HTTP, replay the full legacy
migrations; verify link/source trigger recursion and idempotence, multi-exit
references, LOOT transitions, reverse-link deletes, competing source updates
and two-way consistency, and compare outcomes against the original C#.
The coarse advisory lock only coordinates new synchronizer calls, not
existing legacy/source writers.

React E2E and independent security review also remain mandatory. Do not
merge to main, do not deploy, and do not change the original .NET repository.
