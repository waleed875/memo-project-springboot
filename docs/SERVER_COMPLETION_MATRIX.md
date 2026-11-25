# Spring Boot server completion matrix

Baseline: original .NET repository `waleed875/memo_project` at pinned
`dd8eb2ba57864eac2371a3addd0b786ccefa2367`, compared with
Spring Boot feature stack through PR #34. **This is not a release claim.**

## How to read statuses

- **HTTP**: a Spring controller exists; exact contract/security parity still
  requires end-to-end verification, not just a matching name.
- **Internal**: application/infrastructure operation exists and is tested,
  but its public legacy endpoint is deliberately not yet enabled.
- **Gap**: no verified Spring counterpart to the original endpoint family.
- **Gate**: full C# source golden-master / DB / security / front-end E2E.

| Original endpoint family | Spring Boot status | Remaining verification or implementation |
|---|---|---|
| AuthEndpoints | HTTP: LegacyAuthController | Session/security parity; integration E2E |
| MasterDataEndpoints | HTTP: master-data and definition reads/writes | Complete field catalog and roles parity |
| NotesEndpoints search/details | HTTP | Pagination, filters, ownership and DTO parity |
| NotesEndpoints review queue/transitions | HTTP | Full UI contract, edit/review interoperability |
| NotesEndpoints delete | HTTP | Safety, audit and legacy semantics |
| NotesEndpoints sources | HTTP | Lock protocol with old writers, E2E |
| NotesEndpoints create | Internal | Public POST, multipart/JSON contract and error codes |
| NotesEndpoints existing-note edits | Internal, restricted owner draft + admin slices | Full legacy SaveNoteAsync edit semantics, number/catalog/date/source/flag changes; HTTP PUT |
| NotesEndpoints note attachments | Internal, selected admin operations and draft owner add | Public endpoints, download/delete/replace, streaming/security |
| NotesEndpoints link replacement | Internal | Public contract and old-client concurrency agreement |
| NotesEndpoints duplicate incidents | Read HTTP + internal resolution core | Three duplicate classifications now have internal write transactions; public resolution API/React parity pending |
| DashboardEndpoints | Gap | Dashboard metrics/filters and performance |
| ReportsEndpoints | Gap | Queries, DTOs, permissions, export behavior |
| CompanyReportEndpoints | Gap | Company-level report calculations/export |
| AdminEndpoints | Gap / some underlying master data writes | Reconcile every admin command and authorization |
| SetupEndpoints | Gap | Setup/bootstrap behavior and privileges |
| UpdateEndpoints | Gap | Update mechanism or explicit Docker deployment replacement |
| HealthEndpoints | Gap or infrastructure-specific | Health/readiness endpoints and operational checks |

## Required implementation sequence

1. **Finish note update core**: full original SaveNoteAsync variations; update
   identity metadata and workflow-safe admin edits, source and link sync,
   media replace/delete, duplicate-resolution HTTP/E2E coverage. Cover actual
   001–070 schema and concurrent writes. Keep public mutation HTTP closed.
2. **Open secure note mutation API** once command DTOs, idempotency,
   role matrices, optimistic concurrency, multipart streaming, error
   mapping and React contract E2E are verified.
3. **Port dashboard + reports + company reports** using equivalent query
   semantics; validate aggregations and date boundaries with fixtures.
4. **Port remaining admin and setup workflows** with strict privilege
   controls; review update endpoints against Docker/Portainer deployment
   target rather than copying unsafe remote-updater behavior.
5. **Operational readiness**: health checks, observability, backup/restore,
   migration startup policy, secrets, rate limiting, resource limits,
   image build CI, Docker Compose/Portainer deployment test.
6. **Final parity and release gates**: all old endpoint contracts accounted
   for, C# golden-master, full historic-data migration dry-run, security
   review, React UI E2E, load/concurrency tests, production roll-back plan.

## Migration and deployment protection

The original C# repository is read only. All work is on independent Spring
Boot GitHub feature branches with draft PRs. No automatic merge to main,
production database migration, deployment or Gitea setup is performed.
