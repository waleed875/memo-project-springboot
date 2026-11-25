# Phase 32: full legacy schema review workflow and audit contract

## Scope

Tests the real Spring `NoteReviewTransitionService` plus transactional
`JdbcNoteReviewTransitionRepository` against the exact **unchanged** C#
PostgreSQL migrations 001–070, using a disposable PostgreSQL 16 CI service.

The expected transition names are grounded in the original C#
`DataServices.cs` implementations:
`StartReviewAsync`, `ReturnForCorrectionAsync`, `ReleaseReviewAsync`,
and `ApproveNoteAsync` at legacy pinned SHA
`dd8eb2ba57864eac2371a3addd0b786ccefa2367`.

- START_REVIEW -> UNDER_REVIEW; review actor/started timestamp set
- RETURN_FOR_CORRECTION -> RETURNED_FOR_CORRECTION; reason trimmed, reviewer
  and reviewStartedAt cleared
- ADMIN_RELEASE_REVIEW -> NEEDS_REVIEW; reviewer cleared; audit/history retained
- APPROVE writes history action **APPROVE** but audit action **APPROVE_NOTE**,
  sets approvedBy/approvedAt, clears reviewer, retains reviewStartedAt
- Required indicators are revalidated before approval; Unreviewed is rejected
- Fail closed for unauthorized/self-review/stale revision, with no audit writes
- Audit insertion failure rolls back note mutation and history atomically
- Two concurrent claims on the same note have exactly one winner

## What this does NOT prove

The tests compare the *documented C# transition/action semantics* with
the real Java adapter on the exact legacy SQL schema; they do NOT invoke the
full C# web service for these scenarios. Java structured audit old/new JSONB
is stricter than the original audit writer's minimal event, and the exact
C# wire contract has not been independently replayed.

This test fixture creates a dedicated note type and required field so it
does not depend on mutable seeded definitions. The tests do not exercise
historical populated database migrations, all SaveNoteAsync behavior, UI
end-to-end, or external C# writers. These remain release gates.

No public note mutation endpoint is opened. Do not merge to main, deploy
or modify the original .NET repository.
