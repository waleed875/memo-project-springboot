# Phase 40 — internal administrative indicator and approved metadata HTTP commands

This phase exposes two deliberately limited JSON write paths. Neither is a
substitute for the original .NET `PUT /api/notes/{id}`. The original route
remains blocked, and existing React clients must **not** be repointed yet.

## 1. Admin indicator upsert

`PUT /api/internal/notes/{id}/admin/indicators`

```json
{
  "expectedUpdatedAt": "2026-11-24T10:20:30",
  "markAllIndicatorsCorrect": false,
  "fieldValues": [
    {
      "fieldId": "a267f1a1-eb1b-466c-8d63-5de75d950ecb",
      "value": "Correct",
      "inactiveReason": null
    }
  ]
}
```

- `ADMIN` only, active DB role checked again during the transaction.
- Existing C# rules are ported by `AdminNoteIndicatorEditService` and
  `JdbcAdminNoteIndicatorEditRepository`. It disallows edits to DRAFT notes,
  checks required active definitions, upserts submitted active indicator
  values, retains old/inactive history, and writes audit events atomically.
- `fieldValues` is required (even when empty). No other note properties can
  be changed by this request. Enums use exact C# spellings.
- Optimistic concurrency requires the exact `expectedUpdatedAt`.

## 2. Admin approved metadata replacement

`PUT /api/internal/notes/{id}/admin/approved-metadata`

```json
{
  "expectedUpdatedAt": "2026-11-24T10:20:30",
  "metadata": {
    "noteNumber": "1234",
    "companyId": "bc6a9a70-3ae4-40c9-a582-0133697f9faf",
    "noteTypeId": "9eec0d95-8658-47ad-af23-5eea400ac6d9",
    "factoryId": null,
    "productionLineId": null,
    "writingDate": "2026-11-22",
    "arrivalAt": "2026-11-24"
  }
}
```

- `ADMIN` only, existing `APPROVED` notes only.
- All **seven** metadata keys must be present: null is allowed for factory,
  line and writing date; omission is never interpreted as clearing data.
- The repository rejects a changed company or note type even if the request
  supplies a valid ID; migration of existing source/link history requires a
  separate use case. It validates duplicate numbers, catalog relationships
  and date order before writing.
- Existing approval, reviewer, history, indicators, source, media, and
  counterpart links remain unchanged. Audit is in the same transaction.

## HTTP and security

- 200: JSON containing `id`, `updatedAt` and Arabic success `message`.
- 400: malformed/unknown fields, malformed indicator ID, illegal date or value.
- 401: no active server session.
- 403: missing CSRF or insufficient ADMIN permissions.
- 409: stale revision, incompatible note state or read-only system.

All state-changing requests require Spring CSRF; the actor identity comes only
from the server session, never the JSON body. The JDBC writer independently
rechecks currently active DB roles. The full legacy PUT remains denied.

MockMvc verifies session/CSRF, role matrix, DTO forwarding, input rejection,
optimistic conflict mapping and legacy-route denial. Existing PostgreSQL
full-schema integration tests cover business transactions independently.
This does **not** replace the missing multi-action HTTP/React end-to-end tests.

No .NET repository, main, production DB, or deployment changes.
