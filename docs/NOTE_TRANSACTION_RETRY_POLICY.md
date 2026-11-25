# Transaction failures and retry policy (design gate)

PostgreSQL returns SQLSTATE `40P01` for deadlock, `40001` for serialization
failure, and `55P03` for lock-not-available. `NoteTransactionRetryPolicy`
classifies these without making any automatic writes.

**Never retry in a failed transaction.** A later orchestration use case may
re-run the entire idempotent operation in a **fresh transaction**, for example
with up to three attempts and bounded backoff/jitter, after checking current
actor permissions, memo revision and expected link snapshot each time. That
retry orchestration is **not implemented** in this phase.

Do not retry `NoteDraftEditConflict` (stale expected link snapshot) or SQLSTATE
`23505` blindly; these are logical conflicts requiring refresh or review.
Do not retry externally visible side effects until idempotency and outbox
semantics are guaranteed. Instrument SQLSTATE, lock wait and attempt counts.

The cooperative advisory lock approach is global to Spring Boot writers and
does not automatically enroll legacy C# or arbitrary SQL. It must not be
treated as a production-ready universal lock architecture.
