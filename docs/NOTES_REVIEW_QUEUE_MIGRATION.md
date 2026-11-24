# Read-only reviewer queue: parity boundary

This draft ports **GET /api/notes/review-queue** from the original
\`NotesEndpoints.cs\` and \`NoteService.GetReviewQueueAsync\` to the Spring Boot modular monolith.

## Legacy behavior

- Requires an authenticated user with an active REVIEWER or ADMIN role.
- Excludes soft-deleted notes and notes created by the current reviewer.
- Includes COMPLETE, HAS_ERRORS and NEEDS_REVIEW as available entries,
  plus the current reviewer's own UNDER_REVIEW notes.
- Returns the original \`ReviewQueueItemModel\` property names in camelCase
  (not the separate dashboard \`ReviewQueueResult\` paged contract).
- Claimed-by-self notes sort first, followed by ascending arrival date.

## Additional safety

The Java query adds \`reviewer_id is null\` for the available statuses.
This deliberately avoids exposing inconsistent records claimed by other
reviewers, matching the legacy StartReview command's claim precondition.
This stricter filter must be evaluated against real production data during
C# versus Java parity verification before approving the deployment.

The HTTP endpoint takes reviewer identity **only** from the DB-revalidated
session resolver, never from a request query. An unknown role is denied.
No change to note status is implemented here; no POST/PUT review route is enabled.
CSRF continues to apply to mutations.

## Tests and release gates

- Unit tests: ADMIN/REVIEWER only, invalid identity/role fail closed.
- MockMvc: 401/403/200 and no reviewer-id injection.
- PostgreSQL 16 Testcontainers: own notes, soft deletion, claimed-by-other,
  stale claimed eligible status, status eligibility and deterministic order.
- Required before release: full migration replay (001–070), C# golden-master
  parity, Spring + React E2E, and audit of review claim/update transactions.

No .NET source changes, no production deployment, and no main branch merge.

