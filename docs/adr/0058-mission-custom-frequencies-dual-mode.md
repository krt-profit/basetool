# ADR-0058 — Custom mission-specific frequencies as a dual-mode MissionFrequency row

- **Status:** Accepted (owner-approved)
- **Date:** 2026-07-01
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-MISSION-004/-014 ([`docs/specs/mission-detail-tabs.md`](../specs/mission-detail-tabs.md)) ·
  REQ-AUDIT-001 ([`docs/specs/audit.md`](../specs/audit.md)) ·
  REQ-FE-001…010 ([`docs/specs/frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)) ·
  [ADR-0012](0012-frontend-krtfetch-json-mutations-csrf-retry.md) /
  [ADR-0031](0031-live-mission-sync-over-presence-websocket.md)

## Context

Until now a `MissionFrequency` row always referenced a shared, admin-curated `FrequencyType` (the
global "Frequenztypen" like *Einsatzleitung* / *Umschlagplatz*); a mission could only assign a
per-mission **value** to one of those fixed types. Mission planners want to add **ad-hoc,
mission-specific radio channels** — a free-text label plus a value — that exist only for that mission
(e.g. "Bergungsteam", "Recon"), with the **same value input limits** as the shared types.

Two shapes were considered:

1. **Extend `MissionFrequency`** to be dual-mode: a row is either *typed* (references a
   `FrequencyType`, no label) or *custom* (carries a `name`, no `FrequencyType`).
2. **A separate `MissionCustomFrequency` entity** with its own repository, DTO, mapper, controller
   sub-resource and overview/Verwaltung rendering.

## Decision

**Extend `MissionFrequency` to be dual-mode (option 1).** The row carries an optional `name
VARCHAR(100)` and a now-nullable `frequency_type_id`; exactly one of the two is set, DB-enforced by a
`frequency_type_id XOR name` check constraint (V201). The existing `(mission_id, frequency_type_id)`
unique constraint keeps at most one typed row per type, and — because PostgreSQL treats each NULL
`frequency_type_id` as distinct — imposes no bound on custom rows, so a mission may carry several.

- **Value limits are shared.** Custom values reuse the `precision = 5, scale = 2` column and the
  frontend `^\d{1,3}([.,]\d{1,2})?$` pattern (0 – 999.99, two decimals); the request DTO mirrors this
  with `@Digits(integer = 3, fraction = 2)` + `@DecimalMin/@DecimalMax`, and the label is `@NotBlank
  @Size(max = 100)`.
- **Reuses the existing frequency stack.** The single `frequencies` collection, the `MissionFrequency`
  → `MissionFrequencyDto` mapper (auto-mapping the added `name`), the outsider-forwarding rule, the
  overview "Funk" panel and the `MISSION_FREQUENCY_CHANGED` / `MISSION_FREQUENCY_REMOVED` audit events
  all carry custom rows with no parallel machinery. Delete reuses the generic by-id endpoint; only add
  (`POST …/frequencies/custom/slim`) and edit (`PUT …/frequencies/custom/{id}/slim`) are new.
- **Concurrency.** Edits optimistic-lock on the frequency row's own `@Version`; the `frequencies`
  collection stays `@OptimisticLock(excluded = true)` so a frequency change never 409s a concurrent
  core/schedule/flags edit. The Verwaltung "Weitere Frequenzen" editor and overview Funk panel
  re-render in place via `krtRefreshMissionSection(['frequencies','overview'])` and propagate to peers
  over the presence socket (REQ-FE-010).

## Consequences

- **Positive:** minimal new surface; custom channels appear everywhere typed ones do (Funk overview,
  outsider forwarding, audit) for free; one collection to reason about; the XOR constraint makes the
  invariant undrainable at the data layer.
- **Negative / trade-offs:** `frequency_type_id` is no longer NOT NULL, and code that reads
  `frequency.frequencyType` must treat it as nullable (the mapper and templates already null-guard it).
  The audited-area duty forbids the free-text label in the details payload, so audit rows log only the
  row id, matching the typed path and the step/goal precedents.
- **Rejected:** a separate entity — it would duplicate the DTO, mapper, controller sub-resource, audit
  wiring and both render sites without any modelling benefit, since a custom channel is the same thing
  (a mission-scoped radio value) minus the shared-type reference.
