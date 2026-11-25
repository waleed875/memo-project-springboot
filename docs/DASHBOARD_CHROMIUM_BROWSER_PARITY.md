# Phase 57 — real Chromium dashboard browser parity

## Independent original React source

GitHub Actions checks out the original pinned frontend from
`waleed875/memo_project@dd8eb2ba57864eac2371a3addd0b786ccefa2367`
and installs its original `package-lock.json` with `npm ci`.
The CI-only Vite entrypoint mounts the unchanged original DashboardLive
with its original ThemeProvider and LanguageProvider. It does not use
the original App startup gate, so it does **not** claim to validate
production setup/session cookies.

## Browser verification

Playwright 1.55 runs headed-less real Chromium on a loopback-only Vite
development server. The browser intercepts only the expected API
GET requests, returning **four JSON fixtures previously serialized by
Spring Boot MockMvc**, not handwritten React fake snapshots.
The suite checks executive/operations navigation, active chart elements,
filter persistence and query parameters, the company directory and
scoped company navigation, no-data handling, 403 and manual retry,
and that no API requests escape to a live backend.

Browser artifacts (screenshots, traces, JUnit) are kept on failures.
Playwright and all harness files belong only to the new Spring repo.
Temporary mount files are created only within the ephemeral Actions
checkout; the original .NET/React repository remains unchanged.

## Safety and release boundary

These tests exercise a real Chromium DOM and browser networking stack,
but HTTP responses are mocked from Spring's validated wire fixtures.
They are not live Spring-server session/authentication tests.
The public `/api/dashboard/snapshot` remains blocked, and this PR stays
draft. No original repo change, main merge, production database write,
Docker deployment or Gitea integration.
