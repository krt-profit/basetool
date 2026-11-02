# ADR-0327 — The admin dashboard widgets listen to one backend-published admin room

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** @greluc
- **Related:** REQ-UI-034 · REQ-FE-015 · [ADR-0094](0094-tool-wide-topic-room-live-sync-relay.md) ·
  [ADR-0274](0274-the-backends-app-live-sync-bridge-ends-and-the-pwa-keeps-ws-sync.md) · spec
  [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)

## Context

The four admin dashboard widgets (open deletion requests, Discord registrations, members without
accepted terms, sync and import status) polled every ten minutes. Their state changes in four
places no single frontend page owns: a member files a deletion request, a Discord login files a
registration through the Keycloak provider, a member accepts the terms, and a scheduled or
uploaded run finishes in the backend.

## Decision

1. **One global room `admin-dashboard` with one section per widget** (`deletion-requests`,
   `discord-registrations`, `terms`, `sync-status`). It is admitted to `ROLE_ADMIN` by the local
   role check of the relay, with no backend call, like `members` and `exchange-clients`.
2. **The backend publishes it, after the commit** (ADR-0274), through `AdminDashboardLiveSync`.
   The owning modules call it where the state changes: the privacy and identity modules listen to
   their own events, the terms acceptance and the sync report service call it directly. Publishing
   on the committed write covers every origin, including the ones no browser makes.
3. **The widgets follow the room and keep a slow fallback only where time alone changes them.**
   Three widgets drop their poll. The sync status keeps the ten-minute reload, because a run
   leaves its range with no write at all.
4. **The sync report counts the runs of a range on the backend.** `GET
   /api/v1/catalog/admin/sync-reports/runs?days=` returns, per source, the runs whose newest event
   lies inside the range and when the source last ran. The read is bounded by the retention of the
   newest 30 runs per source.

## Consequences

- A change to the admin queues shows on every open admin dashboard within the 1.5 s coalesce window
  instead of the next poll.
- A new admin widget with its own state change is a section key at every mirror point (the
  backend registry, the relay accept-list and the dashboard's `LIVE_ROOMS`, pinned by the parity
  tests) plus one `AdminDashboardLiveSync` call.
- The room carries no data, so admitting a second role later changes only the role check.
