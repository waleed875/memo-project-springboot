# Full-schema Spring Boot link synchronization verification

This stage follows PR #24's successful PostgreSQL 16 replay of all pinned,
unaltered C# migrations 001–070. It runs the **actual JDBC Spring Boot adapter**
\`JdbcAdminNoteLinkSyncRepository\` against that fully replayed disposable
database, not the reduced test schema.

The dedicated GitHub Actions workflow first runs the SQL migration replay.
Then, with \`MEMO_FULL_LEGACY_SCHEMA=true\`, a dedicated JUnit test executes:
1. Java insert/reconcile/clear/no-op and verifies both physical links and
   original trigger-generated EXIT source references and compatibility view.
2. Existing legacy source-reference writes, followed by Java reconciliation.
3. Injected audit failure and rollback of Java plus DB trigger side effects.
4. Two Java writers using the same expected revision: one commits, one conflicts.
5. Same-side and cross-company rejection without side effects.

The tests operate only against CI's ephemeral PostgreSQL 16 service database
and do not apply migrations themselves or write to the original repository.
The connection is read only from CI's PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD.

This **does not yet certify** concurrent Java writer versus direct legacy
source writer or the full C# golden-master API contract. It is an isolated
integration gate rather than a ready-to-deploy implementation. Keep all
mutation APIs closed, retain PR as Draft, and require security review and React
E2E before merging to main or deploying.
