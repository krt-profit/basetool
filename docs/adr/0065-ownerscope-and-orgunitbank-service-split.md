# ADR-0065 — Split `OwnerScopeService` into a scope/gate/stamping trio and extract the org-unit-bank write mechanics

- **Status:** Proposed
- **Date:** 2026-07-02
- **Deciders:** Repository owner (@greluc)
- **Related:** issue #922 (L3, epic #905) · ADR-0061 / ADR-0062 (the `MissionService` / `JobOrderService` splits, same delegating-facade pattern) · ADR-0020 (org-unit-aware bank seam) · REQ-BANK-008 / REQ-AUDIT-001 · the ArchUnit invariants `orgUnitAwareBankSeamIsContainedToOneClass`, `bankClassesMustNotConsultOrgUnitScope`, `staffelScopedServicesMustWireOwnerScopeOrAuthHelper`

## Context

Issue #922 called for splitting two god-classes: `OwnerScopeService` (2061 LOC — the authorization/scope brain that gates every `@PreAuthorize` on the org-unit-scoped aggregates) and `OrgUnitBankAccessService` (1778 LOC — the single org-unit↔bank bridge).

Two facts dominate the design and make this different from the earlier pure-facade splits:

1. **`OwnerScopeService` is referenced from SpEL by bean name** — 64 `@PreAuthorize("@ownerScopeService.canX(...)")` strings across the controllers, plus the `SquadronScopeService` compatibility shim and 23 by-type injections. The bean name and every public method are load-bearing for security.
2. **The bank is org-unit-blind by construction.** `bankClassesMustNotConsultOrgUnitScope` forbids any `Bank*`-named class from depending on `OwnerScopeService`; `orgUnitAwareBankSeamIsContainedToOneClass` requires that the *only* class coupling `OwnerScopeService` **and** `BankAccountRepository` be `OrgUnitBankAccessService`. Any collaborator that touched both would fail the build. `OrgUnitBankAccessService` is also an **audited** area (REQ-AUDIT-001) — every mutation records a `bank_audit` event that must survive byte-for-byte.

## Decision

### `OwnerScopeService` → delegating facade over three verbatim slices

Keep `OwnerScopeService` as the bean and split its body into three same-package collaborators; the facade keeps every public method + the public `ACTIVE_ORG_UNIT_HEADER` constant and forwards each call one-line:

- **`RequestScopeResolver`** — the request-scoped context core: the active-context header / persistent Staffel resolution, the `ScopePredicate` vectors (`currentScopePredicate` / `currentUnitOverviewScope` / `currentOversightScope` / `currentOwnLevelOversightScope`), the caller's membership rows + cascading reach, the `can*` **membership** predicates the bank seam reads (`currentUserIsOlMember` / `currentUserIsBereichsleiter` / `currentUserHoldsRoleOnOrgUnit` / `currentUserIsMemberOfOrgUnit` / `currentUserHasAreaOrOlOversight`), the promotion-feature flags, and the per-request memoisation they all share. `readActiveSquadronFromHeader` became public here so the other two slices reuse the same untrusted-pin read.
- **`AccessGateService`** — the entire `can*` gate slab (`canSee*` / `canEdit*` for squadron/org-unit, mission, job-order, inventory, refinery, operation, ship, plus `hasRoleInOrgUnit`). Evaluates `requestScopeResolver.currentScopePredicate()` / `canViewJobOrders()` so a per-row check can never diverge from the scoped lists.
- **`OrgUnitStampingService`** — the create-time owner-stamping (`resolve*ForPickerOutput`) and the REQ-ORG-018 reassignment resolution; calls `requestScopeResolver` for the pin/membership reach and `accessGateService.canEditOrgUnit(...)` for the cascade-aware create-on-behalf widening.

Every moved method body is byte-for-byte identical to the original apart from the mechanical cross-call requalification (`readActiveSquadronFromHeader()` → `requestScopeResolver.readActiveSquadronFromHeader()`, the two static seat-classifier method refs re-pointed to `RequestScopeResolver`, and the cache-key constants re-prefixed with `RequestScopeResolver.class.getName()`). The class-level `@Transactional(readOnly = true)` is kept on the facade **and** all three slices — the resolver is pure read, so there is no read-only-write trap; the slices join the facade's read-only transaction. The bean graph is a one-way DAG: facade → {resolver, gates, stamping}; gates → resolver; stamping → {resolver, gates}. No Spring cycle.

### `OrgUnitBankAccessService` — the authorization brain stays; only the write mechanics extract

The ArchUnit seam rule makes a clean visibility/approval *service* extraction impossible: `canView`, `isResponsibleHolder`, `canConfigureVisibility`, `resolveApplicableLimit` and the account loads all couple `OwnerScopeService` to `BankAccountRepository`, and only `OrgUnitBankAccessService` may. So the facade **stays the sole bridge and keeps the whole authorization brain + read flows**; each settings method still `requireAccount` → authorize → validate → **delegate the persistence + audit** → `toSettingsDto`. Two collaborators (deliberately `OrgUnitBank*`-named, not `Bank*`) hold only the mechanics:

- **`OrgUnitBankVisibilityService`** — `grantRole` / `revokeRole` / `setAllMembers` / `grantUser` / `revokeUser` plus the private **`createViewGrant` factory** that dedupes the three previously copy-pasted grant-insert blocks. Depends on **neither** `OwnerScopeService` **nor** `BankAccountRepository` (only `viewGrantRepository` + `userRepository` + `bankAuditService`) — it can never become a second bridge.
- **`OrgUnitBankApprovalLimitService`** — `setRole` / `clearRole` / `setAllMembers` / `clearAllMembers` / `setUser` / `clearUser` plus the private row-locked `upsertLimit`. Injects `BankAccountRepository` **only** for the `SELECT … FOR UPDATE` lock in `upsertLimit`, and does **not** inject `OwnerScopeService`.

Each collaborator mutation is `@Transactional` (propagation `REQUIRED`) so it joins the facade's read-write transaction; the facade re-reads the settings snapshot in the same transaction. Every `AuditEventType` and details payload (`kind.name()+":"+roleCode`, `"MEMBERSHIP_ROLE:"+roleCode+"="+plain(limit)`, `AuditDetails.of("ALL_MEMBERS"/"USER", …)`, the `if (removed > 0)` / `existsBy`-idempotency guards) moved with its mutation unchanged.

The read-side **`resolveApplicableLimit`** (both overloads) and **`setBalanceTarget`** deliberately **stay in the facade** — the former consults the caller's org-unit roles (`OwnerScopeService`), the latter mutates the account's own `@Version` row via `saveAndFlush`; neither is a candidate for the org-unit-blind collaborators.

## Consequences

- **No API change** — `openapi.json` unchanged; the `@ownerScopeService.*` SpEL strings, the `SquadronScopeService` shim and all by-type injections resolve unchanged; `OwnerScopeService.ACTIVE_ORG_UNIT_HEADER` still resolves.
- **Security + audit invariants preserved.** The full backend suite — including `OwnerScopeServiceTest`, `OrgUnitBankAccessServiceTest`, the promotion-gate tests and `ArchitectureTest` (the seam + whitelist + no-security-context rules) — passes; `OrgUnitBankAccessService` remains the sole `OwnerScope`+`BankAccountRepository` bridge and keeps its `OwnerScopeService` dependency for the staffel-scoped whitelist.
- **Tests follow the code.** The three mixed unit tests (`OwnerScopeServiceTest`, `PromotionFeatureFlagServiceGateTest`, `OrgUnitBankAccessServiceTest`) build real, mock-fed collaborators and inject them into the `@InjectMocks` facade via `ReflectionTestUtils` — a single shared `RequestScopeResolver` instance so the request-scoped memoisation still collapses repeated reads — because Mockito does not inject one `@InjectMocks` target into another.
- **`OwnerScopeService` 2061 → ~470 LOC facade; `OrgUnitBankAccessService` shed its ~230 LOC of write mechanics.** The bank split is intentionally narrower than a symmetric visibility/approval-*service* extraction: the ArchUnit seam pins the authorization brain to the one bridge, so only the mechanical persistence + audit could move.

