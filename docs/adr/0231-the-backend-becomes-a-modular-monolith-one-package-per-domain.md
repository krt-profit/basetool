# ADR-0231 — The backend becomes a modular monolith, one package per domain

- **Status:** Accepted — implementation pending (plan Phases 0 to 5)
- **Date:** 2026-09-29
- **Deciders:** @greluc (owner decision D-01)
- **Related:** [domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) §5.1, §5.2, §5.8,
  §7 · [ADR-0232](0232-modules-interact-through-commands-observers-and-after-commit-events.md)
  (interaction styles) · [ADR-0233](0233-module-boundaries-are-enforced-by-archunit-and-spring-modulith-in-test-scope.md)
  (enforcement) · [ADR-0047](0047-backend-package-acyclic-dependencies.md) (layer cycles, amended) ·
  [ADR-0205](0205-log-hygiene-lives-in-one-shipped-logging-support-module.md) (a kernel closed to domain
  meaning) · arc42 §8.14, §11.9

## Context

The backend is cut by technical layer (`controller`, `service`, `repository`, `model`, `mapper`,
`support`, …). The layers are acyclic and guarded (ADR-0047); the business domains are not. The
audit of 2026-09-29 (`95e945326`) found 1,383 of 1,389 types `public`, 21 of 23 non-kernel domains
in one strongly connected component, 1,565 cross-domain class edges — 206 of them a service using
another domain's repository — and Lager rows written by three foreign domains. No domain can be
changed, tested or reasoned about alone, and a second write path into an aggregate is found by
review or not at all (arc42 §11.9).

Four shapes were evaluated: **A** a package per domain inside the one `backend` Gradle module;
**B** a Gradle module per domain (about 45 projects, impossible while the cycle exists, because
Gradle forbids cyclic project dependencies); **C** A first, then Gradle modules only where the
compiler's guarantee is worth a project; **D** separately deployed services.

## Decision

We will build **target C**: a modular monolith whose modules are packages, and Gradle modules later
only for the exchange and the bank.

1. **One package per domain module** directly under `de.greluc.krt.profit.basetool.backend`, inside
   the existing `backend` Gradle module. Runtime, database, image and deployment are unchanged.
2. **Each module has a rank** (plan §5.1). A module depends only on modules of lower rank and on the
   ones its declaration allows; a dependency upward is inverted through an SPI or an observer owned
   by the lower module (ADR-0232).

   | Rank | Modules |
   | --- | --- |
   | 0 | `kernel`, `platform` |
   | 1 | `audit`, `notification`, `livesync` |
   | 2 | `catalogue` |
   | 3 | `identity` |
   | 4 | `orgunit` |
   | 5 | `scope` |
   | 6 | `admin`, `dashboard` |
   | 7 | `orgchart`, `promotion`, `personalinventory`, `hangar`, `blueprint` |
   | 8 | `inventory` |
   | 9 | `mission` |
   | 10 | `refinery`, `joborder`, `materialexchange` |
   | 11 | `operation`, `bank` |
   | 12 | `exchange` |
   | 13 | `privacy` — only during the migration, dissolved into identity-owned SPIs |
   | 14 | `app` — the composition root |

   The ranks are a starting point: the inventory/mission order is settled with the first core step,
   by swapping the two or by inverting inventory's edges into mission, whichever is cheaper.
3. **`kernel` and `platform` are closed to domain meaning**, like `logging-support` (ADR-0205): the
   kernel holds base types, value types, the error-model base (ADR-0235) and shared validation;
   `platform` holds web, logging and metrics infrastructure and the access core.
4. **Inside a module, three sub-packages:**
   - `api` — the only thing another module may use: command and query interfaces, their request and
     result records, the module's events and the SPIs it owns. The existing delegating facades
     (`MissionService`, `JobOrderService`, `InventoryItemService`, the `OwnerScopeService` split)
     become module APIs; their sub-services become package-private after the move.
   - `internal` — services, repositories, entities, mappers. The foundation modules `identity`,
     `orgunit` and `catalogue` additionally publish their entities as a named interface, because the
     cross-domain JPA associations into them stay.
   - `web` — the module's controllers, REST DTOs and REST mappers; no `@Transactional`, no
     repository and no entity there. The transaction boundary lives in the module API, and the REST
     DTOs of app-facing operations are separate types from the module-API records (ADR-0060).
5. **Only the eight business → business JPA associations become id references**; associations into
   identity, org units and the catalogue stay. One schema, one Flyway location and one global
   `V<n>` sequence stay in `backend`.
6. **Gradle modules only for `exchange` and `bank`**, after the foundation phase and only once each
   package boundary has stayed green for some releases: `backend-exchange` makes the exchange's
   reduced authority structural, `backend-bank` its org-unit blindness. Their prerequisites are a
   `build-logic` included build with convention plugins, typed per-module build settings, and a
   context-shape test. Every other domain stays a package.
7. **The frontend follows the same cut** (plan §5.9): kernel packages for the backend seam,
   security, session, layout, web and live sync, and `<domain>.web`, `.client` and `.model` per
   domain.

## Consequences

- A domain's rules can be read, tested and changed in one place; another module reaches them only
  through its `api`, which ADR-0233's gates enforce.
- Many existing gates are keyed on package names, class names or paths, and a move would disarm
  them silently. No class moves before the Phase 0 guards are green (plan §6); this decision is what
  makes those guards necessary.
- The sealed `AppException` cannot permit subclasses in other packages of a class-path application;
  the error model changes first (ADR-0235).
- Package-private sub-services mean tests of a module live in that module's package.
- Effort: roughly 25 to 35 pull requests for the backend phases, one domain per pull request.

## Alternatives considered

- **A alone, no Gradle modules ever** — leaves the exchange's reduced authority and the bank's
  org-unit blindness to test rules forever, where the compiler can hold them.
- **B, a Gradle module per domain** — impossible while the domains form one cycle, and about 45
  projects whose build wiring (coverage floors, test heap, mutation targets) is keyed on project
  names today.
- **D, separate services** — twelve of sixteen cross-domain write families and every audit write
  need the caller's transaction; one host and one maintainer.
- **JPMS (`module-info.java`)** — no Spring, Boot, Modulith or Hibernate jar ships a module
  descriptor, and the AOT cache forbids the `--add-opens` a module-path deployment would need.
