# Phase 28 — shared link/source lock inside Spring Boot

All Spring Boot writers known to mutate `core.note_links` or
`core.note_source_exit_refs` are now required to take the same PostgreSQL
transaction-scoped advisory lock **before** any memo row lock:
`hashtextextended('memo:admin-note-link-sync', 23070)`.

Changes:
- `JdbcAdminNoteLinkSyncRepository.sync` delegates to shared lock helper.
- `JdbcNoteSourceStore.save/remove` acquire before locking a note or writing
  source records whose original migrations 063/070 triggers modify links.
- `JdbcNoteDeleteRepository.delete` acquires before locking/deleting links.
- `JdbcNoteCreator.create` acquires before operation/catalog locks because
  it may create related note links in the same transaction.

The helper is deliberately package-private. It MUST be called within an
existing `@Transactional` write method: otherwise the advisory lock is released
immediately on the connection's single-statement transaction.

The global key serializes writers across **all** notes. This errs on the
side of correctness while a per-note canonical lock ordering is designed.
It may reduce throughput, so load tests and lock wait metrics are needed.

## Release boundary

This serializes **Spring Boot paths** that use the helper; arbitrary direct SQL
and the original C# source API still do not acquire it. Full isolation across
old and new processes is not claimed. Avoid blindly adding an AFTER/BEFORE
trigger taking a lock after PostgreSQL already took row locks, because
opposite acquisition order risks deadlocks.

Cross-writer full-schema tests with the actual source repository will verify
that source-save and link-sync transactions block in both orders and maintain
trigger consistency. No source repository edit, production database migration,
main merge, public HTTP note create/PUT enablement or deployment.
