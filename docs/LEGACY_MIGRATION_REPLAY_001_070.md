# PostgreSQL 16 legacy migration replay gate

This branch introduces a **separate** CI job which checks out the *original*
`waleed875/memo_project` repository at immutable commit
`dd8eb2ba57864eac2371a3addd0b786ccefa2367`. This exact source tree
contains precisely one numbered migration for every version 001–070. The
extra `database_clean_master_data.sql` is **not** an incremental migration
and is deliberately excluded.

The workflow starts an empty disposable PostgreSQL 16 container; executes
all 70 original SQL scripts in numeric order, stopping on the first error;
then runs SQL-level assertions against the **actual** production functions
and triggers introduced by migrations 063 and 070.

## Assertions
- Creating a physical `note_links` row creates an EXIT source and ref.
- The compatibility view emits each linked pair exactly once.
- An ENTRY note may reference multiple EXIT notes.
- Removing a physical link removes its corresponding source reference.
- Adding/removing a source reference adds/removes the legacy physical link.
- Transitioning a LOOT entry note to an EXIT source cleans stale LOOT data.
- Cross-company and same-type links are rejected by original database triggers.
- Schema functions, tables and view required by this contract exist.

## Boundaries and release gates
This SQL test rolls back its test-data transaction and NEVER targets production.
It does not itself validate **every** application business workflow, data
migration over populated historical datasets, legacy checksum-runner behavior,
or all concurrency/trigger interactions. The workflow does not modify or copy
migrations into the Spring Boot app: they are replayed read-only from a pinned
GitHub commit.

Next gates: run the admin link sync Java integration test against the **full
replayed schema** (not only the current isolated fixture), perform concurrent
legacy source-writer vs new synchronizer tests, and compare golden-master
results from the original C# application. Existing public POST/PUT endpoints
remain closed. Do not merge to `main` or deploy before review.
