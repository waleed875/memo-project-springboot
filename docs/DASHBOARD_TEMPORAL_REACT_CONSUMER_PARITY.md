# Phase 56 — original React consumer and temporal precision

All PR #55 checks succeeded. Java now retains non-midnight arrivals in
internal LocalDateTime fields without losing existing LocalDate DTO projections.
The legacy adapter emits timestamps without inventing UTC offsets.
The original-query PostgreSQL differential test asserts these datetime values.

Existing GitHub dashboard-wire-parity CI exports four actual Spring MockMvc
snapshot JSON responses and tests original pinned React with Vitest/jsdom.
Only HTTP responses are intercepted; the original api.getSnapshot,
useDashboardData hook and DashboardLive component remain unmodified.
Cases cover global/scoped/empty/metrics snapshots, URL, forbidden responses,
availability, chart rendering and date presentation.

This is not a real-browser Playwright E2E or live production-network test.
The original public /api/dashboard/snapshot stays forbidden.
No edits to original repository, main, production or Docker/Gitea.
