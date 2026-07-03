# ADR-0068 — Split the four mega frontend page controllers into read + write controllers

- **Status:** Proposed
- **Date:** 2026-07-03
- **Deciders:** Repository owner (@greluc)
- **Related:** issue #924 (L5, epic #905) · ADR-0061/0062/0063/0064 (the backend god-class splits, same behaviour-preserving campaign) · REQ-FE-001…010 (krtFetch/fragment-swap live update) · #916 (S10) / #918 (S12)

## Context

The four frontend page controllers had grown into mega-files: `MissionPageController` (3027 LOC, 53 handlers), `JobOrderPageController` (2478 LOC, 35), `RefineryOrderPageController` (1504 LOC, 14), `InventoryPageController` (1298 LOC, 16). Issue #924 stages the L5 work; this first slice splits each controller into a **read** controller (page renders + fragment re-renders, keeps the original class name so every prose/Javadoc reference stays valid) and a **write** controller (the krtFetch mutations and classic form POSTs). The inline-JS extraction of #924 ships separately, template by template.

Unlike the backend splits there is no facade: a frontend controller's public API **is its route table**, so the safety bar is route identity, not signature identity. A normalized inventory of all 118 routes (verb, full path, `headers=`/`consumes=` attributes, effective `@PreAuthorize` — including its deliberate absence on the guest-flow endpoints — and `@ResponseBody`) was captured before the split and diffed after: **byte-identical, 118/118**.

## Decision

One new `XxxWriteController` per area (same package, same class-level `@RequestMapping` prefix), every handler moved verbatim; the read class keeps the original name. Split-hazard handling per area:

- **Mission** (4 reads / 49 writes — 10 write handlers use fully-qualified `@org.springframework.web.bind.annotation.Put/PatchMapping` spellings, preserved as-is): the write controller **injects the read controller** for the BindingResult error re-renders (`missionDetail`/`createMissionForm`), the exact pattern `MissionFinancePageController` already uses in production. The static package-private `propagateBackendError` **stays on the read class** (its external caller `MissionFinancePageController` is untouched); the write class calls it statically. The local `@InitBinder` (StringTrimmerEditor) is **duplicated verbatim into both classes** — it overrides the global `GlobalBindingAdvice` editor, so dropping it on either side would silently change String binding. The `MISSION`/`MISSION_TIME_ZONE` constants are duplicated verbatim; `messageSource` moved write-side.
- **JobOrder** (8 reads / 27 writes): the four fragment-rendering assignee writes keep their rendering machinery (`populateAssigneeSectionModel`, `callAssigneeMutation`); the three shared leaf helpers (`fetchUsers`, `getCurrentUserId`, `isLogistician`) + `PAGE_OF_USER` are **duplicated verbatim** (campaign precedent: duplicate small leaf helpers over new shared types). The inline duplicated blocks in the classic handlers were deliberately **not** deduplicated; the cookie-writing `viewOrders` GET stays read-side byte-identical; the nested `AssigneeNoteRequest` record moved with its endpoint.
- **RefineryOrder** (6 reads / 8 writes): **documented exception** — `importExtractAjax` (POST, but non-mutating per its own Javadoc: relays the extract to the backend matcher, persists nothing) stays on the read controller, because it is the only POST needing the whole create-page render fan-out (`populateCreateFormModel`, `parallelPageLoader`, `roleHierarchy`). That collapses the write controller's dependencies to exactly `backendApiClient`.
- **Inventory** (7 reads / 9 writes): the write controller injects the read controller for the two inline error re-renders (`viewInputPage`, `viewMy`/`viewAllInventory`) — the Mission pattern — and carries the class-level `@PreAuthorize("isAuthenticated()")` + `@RequestMapping("/inventory")` mirroring the read class. The shadowing nested `GroupedInventoryDto` record stays read-side untouched.

The bean graph is strictly one-way (write → read); tests followed the code (direct constructions retargeted per endpoint owner, argument order matching the Lombok field order; route-driven `@SpringBootTest` files needed only Javadoc-link retargets).

## Consequences

- **No route, security, or behaviour change** — the route-inventory diff is empty; every `@PreAuthorize` asymmetry (including the intentionally unannotated guest-flow mission/order endpoints) is preserved; the full frontend suite, Checkstyle, SpotBugs and Spotless pass.
- The four page controllers shrink to their read surface (e.g. Mission 3027 → ~800 LOC), and every write endpoint now lives in a class whose single concern is the mutation surface — the natural seam for the upcoming per-template JS-extraction PRs of #924.
- Accepted costs: ~60 LOC of verbatim leaf-helper duplication in JobOrder, duplicated `@InitBinder`/constants in Mission, and a handful of Javadoc `{@link}`s that now cross the class pair.

