# ADR-0288 — The live-sync publisher defers to after the commit and coalesces per transaction

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** @greluc
- **Related:** [ADR-0274](0274-the-backends-app-live-sync-bridge-ends-and-the-pwa-keeps-ws-sync.md) ·
  [ADR-0260](0260-the-unit-hangar-overview-listens-to-one-room-per-unit-and-one-for-all.md) ·
  `REQ-FE-010`, `REQ-XCH-013`, `REQ-OBS-011` · issue #2739

## Context

`LiveSyncPublisher` announces a committed write but published at once. Its two callers each
hand-rolled the deferral over `TransactionSynchronizationManager`, and differently:
`ExchangeLiveSync` registered one synchronization per call, so two stock changes in one
transaction sent two frames; `HangarUnitLiveSync` bound a per-transaction collection and sent one
frame per room. ADR-0274 keeps the bridge but does not say where the deferral belongs.

## Decision

1. **The deferral lives in the publisher.** `LiveSyncPublisher.publishAfterCommit(topic,
   sections)` inside a transaction collects the call; after the commit it publishes one frame per
   room carrying the union of the sections named for that room; a rollback drops everything.
   Outside a transaction it publishes at once.
2. **The adapters name rooms and sections only.** `ExchangeLiveSync` and `HangarUnitLiveSync` no
   longer touch `TransactionSynchronizationManager`.
3. **`publish` stays** as the immediate primitive that `publishAfterCommit` flushes through; its
   whitelist clipping, failure swallowing and `basetool_livesync_publish_accepted_total` counting
   are unchanged. The frame format is unchanged.

## Consequences

- The after-commit guarantee is tested once, in `LiveSyncPublisherTest`; a new caller gets it
  without writing a synchronization.
- `basetool_livesync_publish_accepted_total` counts frames, so a transaction touching one room
  twice now counts once; no dashboard panel or alert depends on the per-call count.
- Frames of one transaction leave in the order the rooms were first named.

## Alternatives considered

- **Leave the deferral in each adapter** — two implementations of one rule, already diverged.
- **Defer through Spring application events** — an event per call and a listener per adapter add
  a hop without the coalescing.
