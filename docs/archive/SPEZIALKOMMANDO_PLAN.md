> **Archived 2026-09-22.** Doc type: Historical plan — frozen, kept as a record and no longer updated. Shipped (R1–R9, May 2026). It was deleted from the repository on 2026-05-29 (#273) and restored here on 2026-09-22 because some 40 Java sources and Flyway migrations still cite it by section number.
>
> **Current truth:** [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md), [`role-model.md`](../specs/role-model.md), [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md). Index of the archive: [`README.md`](README.md).

# Spezialkommando Extension Plan

Companion document to `MULTI_SQUADRON_PLAN.md`. The squadron foundation (Phases 1–7, migrations V80–V93) is the baseline this plan builds on; the goal here is to introduce **Spezialkommando** (henceforth `SK`) as a second tenant kind that coexists with Staffel under a shared abstraction.

**Status**: **Code-complete on `claude/spezialkommando-r9-destructive-cleanup`.** All R1 → R9 releases are implemented as commits on a single linear branch and tracked by 25 stacked PRs ([#193](https://github.com/krt-iri/basetool/pull/193) → [#225](https://github.com/krt-iri/basetool/pull/225)). Coverage:

- **R1 → R4** — DB foundation (V94–V96), `OrgUnit` hierarchy + `OrgUnitMembership`, `Squadron` joins the hierarchy with sync trigger, `OwnerScopeService` extracted, `SquadronScopeService` shim dropped, `owningOrgUnit` mirror field with lifecycle dual-write on six aggregates.
- **R5 → R5.c.b** — REST CRUD for `SpecialCommand` (`/api/v1/special-commands`), membership-management endpoints with `canManageMembers` gate, admin SK list page (`/admin/special-commands`), per-SK detail page with member roster.
- **R5.d.a → R5.d.g** — owner-picker fragment (`fragments/owner-picker.html`) + reusable resolver, integrated on all seven §7.3 forms (inventory-input, refinery-orders-create, orders-create + orders-detail, mission-create, operation-create, hangar add-ship, inventory-transfer TRANSFER). Includes the active-org-units endpoint and the SpEL-lambda fragment bugfix.
- **R5.e** — active-context switcher widened to any non-admin user with >1 membership; `X-Active-Squadron-Id` → `X-Active-Org-Unit-Id` header rename (old name kept as one-release alias); session key + MDC field renamed in lockstep.
- **R6.a → R6.e** — ArchUnit gaps closed (§8), owner-resolver hardened (inventory-admin TRANSFER + refinery-store gaps plugged), scoped repository queries migrated to `Set<UUID> memberOrgUnitIds`, JWT converter reads the union of OrgUnit memberships, `is_logistician` / `is_mission_manager` writes migrated to membership rows.
- **R7 (audit follow-up sweep)** — §3.3 promotion trigger, §6.e race fix, §7.2/§7.4/§7.5 frontend polish, §12 documentation sync.
- **R8** — three deferred items closed (§6.1 contextual-role wiring polish, §7.4 member-edit UX, V100 cleanup draft).
- **R9 Steps 1–6 (destructive cleanup)** — 8 callers migrated to `resolveOrgUnitForPickerOutput`; JPA `nullable` flipped; legacy `owningSquadron` / `creatingSquadron` / `requestingSquadron` lifecycle hooks + Java fields removed; `User.squadron` + flag columns removed; `UserMapper` reads membership row; V100 lifts NOT NULL on legacy, V101 drops 7 aggregate columns + V91/V93 indexes, V102 drops `app_user` legacy columns, V103 retargets three FKs (`promotion_topic`, `mission_participant`, `job_order_handover`) and drops the `squadron` table itself.

**Open follow-ups (post-merge):** consolidate the 25 stacked PRs into a small number of deployable releases — current plan is **three** deploys (Foundation+UI / Identity-migration / Destructive cleanup) with a soak window between each. PRs [#193](https://github.com/krt-iri/basetool/pull/193)–[#203](https://github.com/krt-iri/basetool/pull/203) currently CONFLICT with `main` because V94 (manual-material) landed after R1; the consolidation rebase resolves the V-numbering collision.

---

## Progress Log

> Most-recent entry first. Each entry records the slice of the plan that landed in one execution session, what shipped, what was verified, and the link back to the section of this plan that drove the change.

### 2026-05-22 — Release R5.d.g implemented (owner-picker on inventory-transfer TRANSFER branch — last of the seven R5.d pickers)

**Sections delivered:** §7.3 partial — the **seventh and final** picker integration, on the inventory book-out flow's `CheckoutType.TRANSFER` branch. With R5.d.g the full R5.d picker work is done: all seven forms in plan §7.3 carry the picker fragment (or its inlined equivalent) and the shared {@code OwnerScopeService.resolveSquadronForPickerOutput} resolver gates every aggregate stamping path.

**Qualitative difference from R5.d.a–f:** the TRANSFER flow has *two* users — User A books inventory out, User B receives it as a new stock row. The picker reflects **User B's** (the destination's) memberships, not User A's. The plan §D4 calls out cross-OrgUnit transfers as an explicit design goal: User A in Staffel X may legitimately book out into User B's Spezialkommando Y stock so long as B is a member of Y. The resolver enforces exactly that contract — membership-validation against the destination user.

**Changes:**

- **Backend.**
  - `InventoryItemBookOutDto` gains a trailing {@code @Nullable UUID targetOwningOrgUnitId}. Only honoured for {@link CheckoutType#TRANSFER}; DISCARD and SELL terminate the row and never create a new ownership stamp.
  - `InventoryItemService.bookOutInventoryItem` — the TRANSFER branch's {@code newItem.setOwningSquadron(targetUser.getSquadron())} line becomes {@code ownerScopeService.resolveSquadronForPickerOutput(targetUser, dto.targetOwningOrgUnitId())}. The resolver validates the picked OrgUnit against the *destination* user's memberships (not the caller's — that's the R5.d.g novelty).
- **Frontend.**
  - `InventoryItemBookOutDto` mirror updated in lockstep.
  - `InventoryBookOutForm` gains {@code targetOwningOrgUnitId}.
  - `InventoryPageController.bookOutInventoryItem` forwards {@code form.getTargetOwningOrgUnitId()} into the DTO.
  - [`inventory-my.html`](../../frontend/src/main/resources/templates/inventory-my.html) — adds a hidden {@code <select id="targetOwningOrgUnitId">} block inside the existing TRANSFER fields wrapper. The new inline {@code refreshTargetOwningOrgUnitPicker()} function fires when {@code targetUserId} changes, calls {@code GET /api/v1/users/{id}/memberships} on-demand, and rebuilds the picker options. The wrapper stays hidden when the destination user has ≤1 membership (no choice → no UI noise → backend's legacy "stamp destination's home Staffel" fallback kicks in).
  - The page controller does **not** pre-fetch every user's memberships (would be an N×membership round-trip on every page render). On-demand fetch is cheap (one request per user-pick) and always reflects live backend state.
  - 4 new i18n keys ({@code inventory.bookout.targetOrgUnit}, {@code .placeholder}) in DE + EN.

**Tests.**

- **9 backend test sites** updated for the new {@code InventoryItemBookOutDto} arg: bulk regex on single-line constructors + 2 manual edits on multi-line ones ({@code InventoryItemControllerTest} ×2, {@code InventoryItemServiceBookOutTest} helper, {@code InventoryItemServiceTest} ×6 incl 1 multi-line, {@code InventoryItemServiceBookOutTest} multi-line helper).
- 1 new {@code InventoryItemServiceBookOutTest} method ({@code bookOutInventoryItem_transferWithTargetOwningOrgUnitId_routesThroughResolver}) pins the TRANSFER-branch picker delegation, including a captor that verifies the new InventoryItem row carries the picker output.
- Required adding {@code @Mock OwnerScopeService} to {@code InventoryItemServiceBookOutTest} — the previous tests only exercised paths that didn't touch the resolver.
- Backend test count: **1846** (was 1845 on R5.d.f).
- One transient {@code KeycloakHealthIndicatorTest} flake on the first run resolved on retry — pre-existing intermittent, unrelated to R5.d.g.

**Intentionally NOT in R5.d.g:** the JS does an on-demand fetch when the destination user is picked. A power-user case where the modal stays open and the user toggles back to TRANSFER after picking another type will re-fire the fetch — acceptable, the endpoint is cheap. Caching results per-user across modal opens is a follow-up if it shows up in load tests.

**Verification:**

- {@code ./gradlew :backend:test :frontend:test} → **BUILD SUCCESSFUL** (after the Keycloak flake retry). Backend 1846 tests pass.
- {@code ./gradlew :backend:check :frontend:check} → **BUILD SUCCESSFUL**. Checkstyle + SpotBugs + Spotless clean.

**Rollback plan:** revert the commit. {@code InventoryItemBookOutDto} loses the trailing field on both sides; {@code InventoryItemService.bookOutInventoryItem} TRANSFER branch returns to direct {@code targetUser.getSquadron()} stamping; the inventory-my.html modal loses the new picker wrapper + JS function.

**Risks mitigated in this slice:**

- The TRANSFER picker is the *only* code path in R5.d.* where the picker validates against a user other than the caller. The resolver contract (membership-validate against the supplied user) handles this natively — no new code at the resolver level. Plan §D4 cross-OrgUnit transfer is now explicitly supported by the wire shape (once the destructive cleanup release lifts NOT NULL).
- Plan §11 R9 (frontend mirror DTO drift): the rename + new field landed on both sides of the wire in the same commit.

**R5.d.* is complete.** Seven forms wired, one shared resolver, one fragment, one active-org-units endpoint, one membership-lookup endpoint, and the SpEL-lambda latent bug fixed during R5.d.c. The picker is hidden for every existing user today (single-membership case) — runtime behaviour identical to before. Once the destructive cleanup release lifts the NOT NULL on {@code owning_squadron_id} and SK memberships are seeded for real users, every picker becomes user-visible without further code change.

**Next session must:** start **R5.e** — widen the active-context switcher in {@code fragments/sidebar.html} to non-admin users with >1 membership, and rename the relay header from {@code X-Active-Squadron-Id} to {@code X-Active-Org-Unit-Id} (with the old name kept as an alias for one release). The plan §7.2 has the spec. Audit the current admin-only switcher first — it lives in {@code fragments/sidebar.html:82-110} per the original audit notes. Then split: R5.e.a for the header rename + alias, R5.e.b for the non-admin widening, R5.e.c for any active-context-related session-key migrations. After R5.e the remaining macro-tasks are the Squadron-side membership migration (move {@code app_user.is_logistician} / {@code is_mission_manager} onto the membership row) and the destructive cleanup release.

### 2026-05-22 — Release R5.d.f implemented (owner-picker on hangar add-ship modal)

**Sections delivered:** §7.3 partial — the seventh picker integration (hangar add-ship modal in `hangar.html`). The picker is target-user-driven — but on this form the target user is always the caller, since the public hangar UI has no admin-cross-user override.

**Changes:**

- **Backend.**
  - `ShipRequestDto` gains a trailing {@code @Nullable UUID owningOrgUnitId} component. Class-level Javadoc rewritten to document the picker semantics (the same shape that R5.d.b/d/e established).
  - `HangarService.addShip` replaces the direct {@code ship.setOwningSquadron(user.getSquadron())} stamp with a call to {@code ownerScopeService.resolveSquadronForPickerOutput(user, dto.owningOrgUnitId())}. The {@code OwnerScopeService} was already injected (used by other read paths in this service), so no constructor change. Update path ({@code updateShip}) intentionally ignores the field — the existing stamp survives.
  - Both REST endpoints ({@code POST /api/v1/hangar/ships} for self, {@code POST /api/v1/hangar/users/{userId}/ships} for admin) pick up the new field automatically through the shared DTO. Admin-cross-user creation routes through the same resolver — the picked org unit is validated against the *target user's* memberships, not the admin caller's.
- **Frontend.**
  - `ShipRequestDto` frontend mirror updated in lockstep (per the {@code feedback_backend_frontend_dto_mirror} memory).
  - `ShipForm` gains a {@code UUID owningOrgUnitId} field.
  - `HangarPageController.viewHangar` exposes the caller's memberships as {@code ownerOptions} via a new {@code fetchCallerMembershipOptions()} helper. POST add-ship forwards {@code form.getOwningOrgUnitId()} to the DTO. POST update-ship passes {@code null} for the field (the picker is create-only).
  - [`hangar.html`](../../frontend/src/main/resources/templates/hangar.html) invokes the {@code fragments/owner-picker} fragment in the add-ship modal directly under the "Fitted" checkbox. Fragment auto-hides when {@code ownerOptions.size() <= 1}.
- **Tests.**
  - **14 backend test sites** updated for the new trailing {@code ShipRequestDto} arg via a bulk regex on the single-line constructors plus 5 manual edits on the multi-line ones: {@code HangarControllerTest} (4), {@code FeatureExpansionTest} (1), {@code HangarIntegrationTest} (5 — incl. 2 multi-line), {@code OptimisticLockingTest} (1 multi-line), {@code HangarServiceTest} (2 multi-line), {@code ShipInsuranceTest} (1).
  - 1 new {@code HangarServiceTest} method ({@code addShip_delegatesPickerResolutionToOwnerScopeService}) pins the picker delegation. Required adding {@code @Mock UserRepository} and {@code @Mock OwnerScopeService} to the test class — the previous tests only exercised {@code updateShip} / {@code deleteAllShipsForUser} paths that didn't touch those collaborators.
  - Backend test count: **1845** (was 1844 on R5.d.e).
  - **Frontend mirror compile catch:** the first {@code ./gradlew :frontend:test} run failed because the frontend's {@code ShipRequestDto} mirror DTO still carried 6 fields while the production code passed 7 args. Fixed in lockstep with the backend per the memory rule.

**Intentionally NOT in R5.d.f:** the eighth and final form (inventory-transfer book-out modal — qualitatively different because the transfer happens between two users, the picker reflects the destination user's memberships). The schema loosening that lets SKs actually own ships stays deferred per the plan.

**Verification:**

- {@code ./gradlew :backend:test :frontend:test} → **BUILD SUCCESSFUL** after one frontend-mirror-compile fix (described above). Backend 1845 tests pass.
- {@code ./gradlew :backend:check :frontend:check} → **BUILD SUCCESSFUL**. Checkstyle + SpotBugs + Spotless clean.

**Rollback plan:** revert the commit. {@code ShipRequestDto} loses the trailing field on both sides; {@code HangarService.addShip} returns to direct {@code user.getSquadron()} stamping; the hangar add-ship modal loses the picker fragment include.

**Risks mitigated in this slice:**

- The admin-cross-user create endpoint ({@code POST /api/v1/hangar/users/{userId}/ships}) now routes the picker through the resolver too — an admin cannot stamp a ship onto an OrgUnit the target user does not belong to. The resolver does the membership check against the target user, not the admin caller.
- Plan §11 R9 (frontend mirror DTO drift): the rename caught at compile time on the first test run.

**Next session must:** start R5.d.g — the inventory-transfer book-out modal. Located somewhere in {@code inventory-my.html} or {@code inventory-index.html} (per the R5.d.a Explore audit). This is the **last** of the seven forms in plan §7.3 and qualitatively different from the others: the transfer happens between two users (book-out from User A to User B), and the picker reflects User B's (the destination's) memberships. The shared resolver already handles the "validate org unit against a specific user's memberships" contract; what's new is wiring it on the book-out flow which today stamps {@code targetUser.getSquadron()} directly. Once R5.d.g lands, the full R5.d picker work is done and R5.e (active-context switcher widening + header rename) is the next major slice.

### 2026-05-22 — Release R5.d.e implemented (owner-picker on operation-create modal)

**Sections delivered:** §7.3 partial — the sixth picker integration (operation-create modal inside `operations-index.html`). Smaller than R5.d.d because the operation surface is leaner (no embedded sub-aggregates on the create payload, just name + description + status + owningOrgUnitId).

**Plan §7.3 reads "actor's active scope" for this form** — historically the service stamped from {@code OwnerScopeService.currentSquadron()}, which scope-resolves the caller's home Staffel or the admin's active-squadron header. R5.d.e widens that to also honour an explicit picker output: when the caller belongs to >1 OrgUnit, the picker offers each membership and the chosen UUID lands on the create payload. The fallback to {@code currentSquadron()} survives for the no-owner branch (admin in "all squadrons" mode, anonymous-form fallback) — a picker UUID without a resolved caller cannot be membership-validated.

**Changes:**

- **Backend.**
  - `OperationCreateDto` gains a trailing {@code @Nullable UUID owningOrgUnitId} component. Existing fields ({@code name}, {@code description}, {@code status}) untouched. Class-level Javadoc rewritten to document the new picker semantics.
  - `OperationController.createOperation` forwards {@code createDto.owningOrgUnitId()} to the service as a separate parameter (the mapper does not touch it — the entity's {@code owningOrgUnit} field is typed {@link OrgUnit}, not UUID, so a UUID-to-entity hydration is more naturally done at the service layer).
  - `OperationService.createOperation` gains a second {@code @Nullable UUID owningOrgUnitId} parameter. When the caller resolves (via {@code userService.getCurrentUser()}), the stamp is routed through {@code OwnerScopeService.resolveSquadronForPickerOutput} — exactly the R5.d.b/d pattern. When the caller does not resolve, the historical {@code currentSquadron()} fallback survives (preserves the anonymous-form / admin-no-context path).
- **Frontend.**
  - `OperationForm` record gains a trailing {@code owningOrgUnitId}. The form posts directly as JSON to {@code /api/v1/operations} — Jackson on the backend side ignores the {@code version} field that {@code OperationCreateDto} doesn't carry, so the existing serialisation contract survives.
  - `OperationPageController.listOperations` exposes the caller's memberships as {@code ownerOptions} via a new {@code fetchCallerMembershipOptions(principal)} helper. Resolves the caller's id via {@code GET /api/v1/users/me}, fetches memberships from the R5.d.a endpoint, falls back to an empty list on hiccup. Skips the fetch entirely for the AJAX results-fragment path so the picker only loads on full-page renders.
  - [`operations-index.html`](../../frontend/src/main/resources/templates/operations-index.html) invokes the {@code fragments/owner-picker} fragment between the status select and the save / cancel buttons. The fragment auto-hides when {@code ownerOptions.size() <= 1}, so the modal looks identical to today for every existing user.
- **Tests.**
  - 3 backend test sites updated for the new {@code OperationCreateDto} / {@code OperationService.createOperation} signatures: {@code OperationControllerTest}, {@code OperationServiceTest}, {@code OperationMapperTest} (1 each).
  - 1 new {@code OperationServiceTest} method ({@code createOperation_withResolvedCallerAndPickerOutput_delegatesToOwnerScopeResolver}) pins the picker delegation.
  - Backend test count: **1844** (was 1843 on R5.d.d).

**Intentionally NOT in R5.d.e:** the remaining two forms (hangar add-ship modal in {@code hangar.html}, inventory-transfer book-out modal). The schema loosening that lets SKs actually own operations stays deferred per the plan. The {@code OperationForm} record carries a {@code version} field that the backend {@code OperationCreateDto} doesn't accept — that mismatch predates R5.d.e and is harmless (Jackson silently drops the unknown field on the backend side), so no cleanup here.

**Verification:**

- {@code ./gradlew :backend:test :frontend:test} → **BUILD SUCCESSFUL**. Backend 1844 tests pass; frontend 691 (no count change — the new flow is templates + thin controller passthrough).
- {@code ./gradlew :backend:check :frontend:check} → **BUILD SUCCESSFUL**. Checkstyle + SpotBugs + Spotless clean.

**Rollback plan:** revert the commit. {@code OperationCreateDto} loses its trailing field; {@code OperationService.createOperation} signature reverts; the operations-index create modal loses the picker fragment include. No DB migration to undo.

**Risks mitigated in this slice:**

- The {@code OperationService.createOperation} signature change is contained to four call sites (2 controllers + 2 test files); the change ripple is small enough to land safely in one PR without breaking unrelated callers.
- Plan §11 R9 (frontend mirror DTO drift): the new field lands on both the form record and the backend DTO in the same commit.

**Next session must:** start R5.d.f — pick the next form. Candidates ordered by plan §7.3:
* Hangar add-ship modal in {@code hangar.html}. Owner's memberships. Closer to R5.d.a/b/d pattern (explicit owner selector → memberships of the chosen owner).
* Inventory-transfer book-out modal — target user's memberships, cross-org-unit transfer is the canonical use case per the plan. Qualitatively different because the transfer happens between two users; the picker is for the destination user's memberships.

The hangar add-ship form is the natural next step — it mirrors refinery-orders-create closely (target user selector + memberships of the chosen owner). The inventory-transfer modal should be R5.d.g or R5.d.h because the two-user model is genuinely different.

### 2026-05-22 — Release R5.d.d implemented (owner-picker on mission-create form + dedicated CreateMissionRequest write DTO on the frontend)

**Sections delivered:** §7.3 partial — the fifth picker integration (mission-create form, which lives inside `mission-detail.html` under an `isNew` conditional rather than its own template).

**One architectural side-effect:** the frontend's mission-create POST handler historically sent a full {@link de.greluc.krt.iri.basetool.frontend.model.dto.MissionDto} (29 fields, most nullable) as the write payload, relying on Jackson's `@JsonIgnoreUnknown`-like behaviour to drop the read-only fields. Adding {@code owningOrgUnitId} to the write surface forced a choice between extending the 29-field DTO (with ~18 constructor call sites needing updates) or introducing a small dedicated write DTO. The latter is cleaner: the new {@link de.greluc.krt.iri.basetool.frontend.model.dto.CreateMissionRequest} record mirrors the backend's `CreateMissionRequest` field-for-field (10 fields), the create handler posts it directly, and the read-side {@code MissionDto} stays untouched. R5.d.f or later can do the same split for other write-only flows that still abuse `MissionDto`.

**Changes:**

- **Backend.**
  - `CreateMissionRequest` gains a trailing {@code owningOrgUnitId} {@link java.util.UUID} field. The class-level Javadoc explicitly documents that the field follows the C-3 audit boundary contract — every new field on this record is an explicit decision to expose the write surface.
  - `MissionService.createMission` routes the stamping through the shared {@code OwnerScopeService.resolveSquadronForPickerOutput} when an owner is resolved (the normal authenticated path). When {@code getCurrentUser()} returns empty (admin in "all squadrons" mode, anonymous-form fallback) the historical {@code currentSquadron} fallback survives — a picker UUID supplied without an owner cannot be membership-validated, so honouring it would be a security regression. {@code addSubMission} keeps the parent-inheritance path; the picker field on the sub-mission DTO is intentionally ignored.
- **Frontend.**
  - New `CreateMissionRequest` write DTO (10 fields, mirrors the backend). The {@code MissionDto} read DTO stays unchanged.
  - `MissionForm` record gains a trailing {@code owningOrgUnitId} {@link java.util.UUID} component. Both production constructor sites in {@code MissionPageController} (edit pre-seed + create-form initial-state) updated to pass {@code null} for the new field.
  - `MissionPageController.createMission` swaps the {@code MissionDto}-as-write-payload for the new {@code CreateMissionRequest}. A new {@code fetchCallerMembershipOptions(principal)} helper resolves the caller's id via {@code GET /api/v1/users/me} and fetches their memberships from the R5.d.a endpoint; {@code createMissionForm} exposes the result as the {@code ownerOptions} model attribute.
  - [`mission-detail.html`](../../frontend/src/main/resources/templates/mission-detail.html) invokes the {@code fragments/owner-picker} fragment directly below the "Intern" checkbox, guarded by {@code th:if="${isNew}"} so the fragment only shows on create — the edit path does not re-stamp ownership (consistent with R5.d.b refinery-orders update). Field name {@code owningOrgUnitId} binds to {@link MissionForm#owningOrgUnitId} via the record component.
- **Tests.** 6 backend test sites that construct {@code CreateMissionRequest} updated for the new trailing {@code null} arg: 2 in {@code MissionServiceTest}, 1 in {@code MissionExpansionTest}, 1 in {@code MissionUnitManagementTest}, 1 in {@code MissionControllerLifecycleTest}, plus the addSubMission case. 1 new {@code MissionServiceTest} method ({@code createMission_honoursOwningOrgUnitIdFromTheRequestViaPickerResolver}) pins the delegation. Two existing C-4 stamping tests were rewritten:
  - {@code createMission_stampsOwningSquadronFromOwnerSquadron}: now stubs {@code ownerScopeService.resolveSquadronForPickerOutput(caller, null)} explicitly because the service routes through the resolver instead of reading {@code owner.getSquadron()} directly.
  - {@code createMission_fallsBackToCurrentSquadronScopeWhenOwnerHasNoSquadron} → renamed to {@code …WhenNoOwnerResolved} because the fallback branch now fires on {@code getCurrentUser().isEmpty()}, not on {@code owner.getSquadron() == null}. The test's mock changes accordingly. The behaviour difference is subtle but documented in the test's comment block.
  - Backend test count: **1843** (was 1842 on R5.d.c).

**Intentionally NOT in R5.d.d:** the remaining three forms (operation-create-modal, hangar-add-modal, inventory-transfer-modal). The {@code MissionDto}-as-write-payload pattern still survives elsewhere (the edit path posts section-scoped patches via {@code MissionScheduleUpdateRequest} / etc., which were already lean) — only the create path moved to a dedicated request DTO. The schema loosening that lets SKs actually own missions stays deferred per the plan.

**Verification:**

- {@code ./gradlew :backend:test :frontend:test} → **BUILD SUCCESSFUL** after one round of test-compile fixes (forgot to update {@code MissionUnitManagementTest}'s constructor on the first iteration — quick catch from the compile failure). Backend 1843 tests pass.
- {@code ./gradlew :backend:check :frontend:check} → **BUILD SUCCESSFUL** after one Checkstyle warning (`LineLength` on the new {@code CreateMissionRequest} Javadoc — Javadoc paragraph rewrapped). Spotless idempotent on the touched files.

**Rollback plan:** revert the commit. The new {@code CreateMissionRequest} frontend DTO disappears; {@code MissionPageController.createMission} goes back to posting the {@code MissionDto}; the backend {@code CreateMissionRequest} loses its trailing field; the service routes back to direct {@code owner.getSquadron()} stamping. No DB migration to undo.

**Risks mitigated in this slice:**

- The {@code MissionDto}-as-write-payload pattern is now a known anti-pattern, called out in the {@code CreateMissionRequest} frontend Javadoc, and on its way out — R5.d.f or later can finish the split.
- Plan §11 R9 (frontend mirror DTO drift): the new {@code CreateMissionRequest} frontend mirror matches the backend record field-for-field; the rename + field-addition landed atomically.
- Plan §11 R12 (concurrent admin actions): not exercised by create flows, the version field on the form is preserved unchanged.

**Next session must:** start R5.d.e — pick the next form. Candidates ordered by plan §7.3:
* Operation-create modal in {@code operations-index.html} (or wherever the create modal lives — needs an Explore audit). Per the plan, "actor's active scope" — the picker is target-user-driven on the actor (creator). Similar to R5.d.d.
* Hangar add-ship modal in {@code hangar.html}. Owner's memberships.
* Inventory-transfer book-out modal — target user's memberships, cross-org-unit transfer is the canonical use case per the plan.

The operation-create modal is the natural next step because it mirrors mission-create-modal closely. The inventory-transfer modal is qualitatively different (two users, one transferring to the other) and should be R5.d.g or R5.d.h.

### 2026-05-22 — Release R5.d.c implemented (owner-picker on Job Order create + detail, new active-org-units endpoint, picker-fragment latent-bug fix)

**Sections delivered:** §7.3 partial — the third + fourth picker integrations (`orders-create.html` Job Order create form *and* the corresponding edit form in `orders-detail.html`). Plus a new shared backend endpoint and a fix for a latent bug in the R5.d.a picker fragment.

**Why Job Order is different from R5.d.a / R5.d.b:** Job Orders are cross-staffel workspaces (CLAUDE.md "Cross-staffel workspace"); the picker for {@code requestingOrgUnitId} offers **every active org unit**, not the order owner's memberships. So R5.d.c could not reuse the {@code GET /api/v1/users/{id}/memberships} endpoint — it needed a new active-org-units catalog endpoint.

**Changes (~25 files):**

- **Backend, active-org-units endpoint.**
  - `OrgUnitMembershipService.listAllActiveOptions` — new method that returns the full active Staffel + SpecialCommand catalog as {@link OrgUnitMembershipOptionDto}, sorted Staffel-first then SK alphabetical (mirroring the {@code listOptionsForUser} sort order so the two endpoints render identically).
  - `OrgUnitController` — new {@code @RestController} at {@code /api/v1/org-units}. Single endpoint {@code GET /active}, open to authenticated members (same access surface as {@code /users/lookup}).
- **Backend, Job Order rename + picker.**
  - `CreateJobOrderDto`: {@code requestingSquadronId} → {@code requestingOrgUnitId} (plan §7.3 hard-rename). {@code creatingSquadronId} kept as-is — its admin-override rename to {@code creatingOrgUnitId} ships in a follow-up release per the plan.
  - `JobOrderService` — both create and update call sites consume {@code dto.requestingOrgUnitId()}; the private {@code resolveRequestingSquadron} kept the same behaviour (look up the picked id via {@code SquadronRepository}, throw {@link BadRequestException} on miss). The SK-rejection path is implicit: an SK id passes the discriminator filter in {@code SquadronRepository.findById} as empty, so the existing 400-on-empty branch handles it. The Javadoc on the resolver explains the new error message phrasing.
- **Frontend, mirror DTO + form + page controllers.**
  - `CreateJobOrderDto` frontend mirror gets the rename (per {@code feedback_backend_frontend_dto_mirror}).
  - `JobOrderForm.requestingSquadronId` → {@code requestingOrgUnitId}.
  - `JobOrderPageController` — new {@code fetchActiveOrgUnitOptions()} helper hits the new {@code /api/v1/org-units/active} endpoint, plus the {@code viewCreateForm} and {@code viewOrderDetail} handlers expose two model attributes: {@code ownerOptions} (the picker list) and {@code ownerOptionsHasSpecialCommand} (a pre-computed Boolean — see below). The POST handlers forward {@code form.getRequestingOrgUnitId()} to the backend DTO; the edit pre-seed uses {@code form.setRequestingOrgUnitId(...)}.
  - [`orders-create.html`](../../frontend/src/main/resources/templates/orders-create.html) and [`orders-detail.html`](../../frontend/src/main/resources/templates/orders-detail.html) — the {@code requestingSquadronId} dropdowns become {@code requestingOrgUnitId} dropdowns; the option list is split into {@code <optgroup>Staffel</optgroup>} and {@code <optgroup>Spezialkommandos</optgroup>} via the pre-computed {@code ownerOptionsHasSpecialCommand} flag, collapsing to a flat list when no SK exists.
- **Picker fragment latent bug fix.**
  - [`fragments/owner-picker.html`](../../frontend/src/main/resources/templates/fragments/owner-picker.html) — the R5.d.a fragment used Java lambda syntax inside {@code th:with} ({@code options.stream().anyMatch(o -> o.kind() == 'SQUADRON')}). Thymeleaf's SpEL backend cannot parse Java lambdas (EL1042E) but the bug stayed latent because every existing fragment caller (R5.d.a inventory-input, R5.d.b refinery-orders-create) renders with ≤1 option today and the fragment is guarded by {@code th:if="${options != null and options.size() > 1}"}. The fix swaps the lambda for SpEL's collection-selection {@code .?[expr]}: {@code !options.?[kind == 'SQUADRON'].isEmpty()} works without lambdas and renders identically. Discovered while testing the R5.d.c Job Order picker, which exercises the lambda branch because the Squadron catalog is non-trivial.
- **Tests (+4 net new methods).**
  - 2 new {@code OrgUnitMembershipServiceTest} cases pin {@code listAllActiveOptions} (empty catalog → empty list; mixed kinds → Staffel-first-then-SK sort).
  - New `OrgUnitControllerTest` with 2 thin delegation tests for the new endpoint.
  - Backend test count: **1842** (was 1838 on R5.d.b).

**Intentionally NOT in R5.d.c:** the four remaining create / transfer forms (mission-create-modal, operation-create-modal, hangar-add-modal, inventory-transfer-modal). The {@code creatingSquadronId → creatingOrgUnitId} admin-override rename on Job Order also stays deferred — the field has no UI surface (admin-only override, set via the JSON body) so the rename adds churn without unlocking new functionality. The schema loosening that lets SKs actually own Job Orders waits for the destructive cleanup release.

**Verification:**

- {@code ./gradlew :backend:test :frontend:test} → **BUILD SUCCESSFUL**. Backend 1842 tests pass; frontend 691 (no count change — the new flow is template + thin controller passthrough, the substantive logic is on the backend). Three rounds of test-compile fixes during the session: the first run uncovered the SpEL lambda parse error on three frontend MVC tests (template-parsing failure on {@code orders-create.html}); the second exposed the same error on {@code orders-detail.html}; the third was clean.
- {@code ./gradlew :backend:check :frontend:check} → **BUILD SUCCESSFUL** after fixing two Checkstyle warnings on {@code OrgUnitMembershipService.java} caused by an Edit that inserted the new method between an existing Javadoc and its method (orphaned the Javadoc + left the existing method without a Javadoc). Spotless idempotent on the touched files.

**Rollback plan:** revert the commit. The orders-create + orders-detail forms regain the {@code requestingSquadronId} dropdown; the form / DTO / service rename reverts; the {@code /api/v1/org-units/active} endpoint disappears. The fragment fix is independent of R5.d.c — keeping it on revert is acceptable (R5.d.a + R5.d.b still work because their fragment usage is guarded), but for a clean revert the fragment also goes back to the lambda form.

**Risks mitigated in this slice:**

- The latent SpEL-lambda bug in the R5.d.a fragment would have shipped silently and broken R5.d.a's inventory-input picker the moment any user gained a second membership. Now fixed proactively.
- Plan §11 R9 (frontend mirror DTO drift): the rename landed on both sides of the wire in the same commit; every test fixture and template references the new field name.
- Plan §11 R16 (frontend hardcoded squadron assumptions): the {@code orders-create.html} dropdown explicitly handles the two-kinds case via {@code <optgroup>} when the catalog contains SKs; today it collapses to a flat Staffel-only list, but the SK widening is a single backend flag flip away.

**Next session must:** start R5.d.d — pick the next form. Candidates ordered by plan §7.3:
* Mission-create modal in {@code mission-detail.html} (or wherever the create form lives — needs an Explore audit). The picker is target-user-driven (owner's memberships) — uses the R5.d.a/b pattern, not the R5.d.c active-org-units pattern.
* Operation-create modal in {@code operations-index.html}. Similar — actor's memberships.
* Hangar add-ship modal in {@code hangar.html}. Owner's memberships.
* Inventory-transfer book-out modal — target user's memberships, cross-org-unit transfer is the canonical use case per the plan.

The mission-create-modal is probably the smallest delta. Operation-create + hangar-add follow the same pattern. The inventory-transfer is qualitatively different (cross-org-unit transfer between two users) and should be R5.d.g or similar.

### 2026-05-22 — Release R5.d.b implemented (owner-picker integrated into refinery-orders-create + shared resolver extracted)

**Sections delivered:** §7.3 partial — the second of the seven create / transfer forms (`refinery-orders-create.html`) integrates the owner-picker fragment from R5.d.a. Plus a refactor that hoists the picker-resolution logic out of `InventoryItemService` and into a shared `OwnerScopeService.resolveSquadronForPickerOutput` so the upcoming R5.d.c ff. integrations can call into one well-tested helper instead of copying the validation + Squadron-lookup dance.

**Changes (~20 files, no new files):**

- **Backend, shared resolver.**
  - `OwnerScopeService.resolveSquadronForPickerOutput` — new method centralising the R5.d picker resolution logic (was inlined as `InventoryItemService.resolveOwningSquadron` in R5.d.a). Three cases: {@code null} picker output → user's home Staffel; valid picker membership pointing at a Squadron → return that Squadron; everything else → {@link de.greluc.krt.iri.basetool.backend.exception.BadRequestException}. The SK-rejection branch remains a soft block because {@code owning_squadron_id} is still NOT NULL on every aggregate table.
  - `OwnerScopeService` now injects {@link de.greluc.krt.iri.basetool.backend.repository.OrgUnitMembershipRepository} for the membership-existence check; {@link de.greluc.krt.iri.basetool.backend.repository.SquadronRepository} was already wired for the existing scope-resolution paths.
  - `InventoryItemService.createInventoryItem` — drops the inlined helper and delegates to {@code ownerScopeService.resolveSquadronForPickerOutput(user, dto.owningOrgUnitId())}. The {@code OrgUnitMembershipRepository} / {@code SquadronRepository} fields disappear from {@link InventoryItemService} along with the helper.
- **Backend, refinery-order picker.**
  - `RefineryOrderDto` gains a trailing {@code owningOrgUnitId} {@link java.util.UUID} field — the R5.d picker output.
  - `RefineryOrderMapper.toDto` plumbs the new field through the yield-enrichment overload; the inbound {@code toEntity} relies on {@code unmappedTargetPolicy = IGNORE} so the new field is read out of the DTO by the controller rather than mapped onto the entity (the entity has {@code owningOrgUnit} typed as {@link de.greluc.krt.iri.basetool.backend.model.OrgUnit}, not a UUID — the lifecycle hook on R4 mirrors the picker-resolved Squadron into the new column on persist).
  - `RefineryOrderService.createRefineryOrder` gains an {@code owningOrgUnitId} parameter (after the existing {@code userId} + {@code RefineryOrder} args). Calls {@code ownerScopeService.resolveSquadronForPickerOutput(user, owningOrgUnitId)} for the stamp instead of {@code user.getSquadron()}.
  - `RefineryOrderController` — both create endpoints ({@code POST /api/v1/refinery-orders}, {@code POST /api/v1/refinery-orders/users/{userId}}) forward {@code orderDto.owningOrgUnitId()} to the service.
- **Frontend, refinery-order picker.**
  - `RefineryOrderDto` frontend mirror gains {@code owningOrgUnitId} (per the {@code feedback_backend_frontend_dto_mirror} memory: missing the mirror = render-time 500 in prod).
  - `RefineryOrderForm` carries the form-bound {@code owningOrgUnitId}.
  - `RefineryOrderPageController` — new {@code fetchOwnerPickerOptions(form, principal)} helper mirrors the {@code InventoryPageController} pattern: resolves the target user from {@code form.ownerId} or {@code principal}, fetches their memberships from {@code /api/v1/users/{id}/memberships}, falls back to an empty list on backend hiccup. Both create-form constructors of {@code RefineryOrderDto} (POST create + PUT update) forward the form's {@code owningOrgUnitId}; the update path passes {@code null} because the picker is create-only.
  - [`refinery-orders-create.html`](../../frontend/src/main/resources/templates/refinery-orders-create.html) invokes the {@code fragments/owner-picker} fragment directly under the existing {@code ownerId} dropdown.
- **Tests:** 4 new {@code OwnerScopeServiceTest} cases pin the shared resolver (null → user's home Staffel; valid membership → picked Squadron; foreign org unit → 400; SK selection → 400). 1 new {@code RefineryOrderServiceTest} case verifies the service delegates to the resolver. The 4 R5.d.a {@code InventoryItemServiceTest} resolver-specific cases were rewritten as 2 delegation tests (the resolver logic now lives elsewhere). 30+ existing {@code RefineryOrderServiceLifecycleTest}, {@code RefineryOrderControllerTest}, and 5 frontend test files updated for the widened DTO + service signatures. Backend test count: **1838** (was 1835 at R5.d.a).

**Intentionally NOT in R5.d.b:** the remaining five create / transfer forms (orders-create with the {@code requestingSquadronId → requestingOrgUnitId} rename, mission-create modal, operation-create modal, hangar add-ship modal, inventory-transfer book-out modal). The destructive cleanup release that lowers NOT NULL on {@code owning_squadron_id} so SK ownership is actually reachable stays deferred per §10. R5.e (active-context switcher widening + header rename) is its own slice.

**Verification:**

- {@code ./gradlew :backend:test :frontend:test} → **BUILD SUCCESSFUL**. Backend 1838 tests pass; frontend 691 (no test counts moved — the new flow lives in templates + a thin controller passthrough). Three rounds of test-compile fixes during the session (forgot to update the lifecycle test's 25 call sites on the first iteration; forgot two enum-value variants in the frontend hierarchy test on the second; clean on the third).
- {@code ./gradlew :backend:check :frontend:check} → **BUILD SUCCESSFUL**. Checkstyle + SpotBugs + Spotless clean. {@code ./gradlew spotlessApply} idempotent on the touched files.

**Rollback plan:** revert the commit. The refinery-order create form loses the new {@code <select>} block; {@code RefineryOrderDto} loses the trailing field; the service signature reverts and {@code RefineryOrderService.createRefineryOrder} goes back to {@code user.getSquadron()} stamping. {@code OwnerScopeService.resolveSquadronForPickerOutput} disappears and {@code InventoryItemService} regains its inlined helper. The R5.d.a inventory-input flow is functionally untouched by this revert because {@code InventoryItemService} would resume calling the inlined helper.

**Risks mitigated in this slice:**

- The picker-resolution logic now has exactly one implementation, pinned by {@code OwnerScopeServiceTest}. R5.d.c ff. integrations cannot diverge from the established behaviour by mistake.
- Plan §11 R9 (frontend mirror DTO drift): {@link RefineryOrderDto} updated on both sides of the wire in the same commit; the 5 frontend test files that construct {@link RefineryOrderDto} were updated alongside the production code.
- Plan §11 R12 (concurrent admin flag flips): not exercised by this slice but the version field on the DTO is preserved unchanged.

**Next session must:** start R5.d.c — pick the next form in the list. Two viable candidates:
* {@code orders-create.html} (Job Order create) — has the {@code requestingSquadronId} field that needs to be renamed to {@code requestingOrgUnitId} *and* widened to accept SKs (since Job Orders are cross-staffel workspaces per CLAUDE.md — they always could span squadrons). The picker semantics for {@code requestingOrgUnitId} differ from R5.d.a/b: there is no "membership of the order owner" — Job Orders pick *any* active org unit.
* Mission-create modal inside {@code mission-detail.html} (or similar) — closer to the inventory-input / refinery-order pattern but the create flow is part of a larger detail page so the integration is more invasive.

The orders-create form is the cleaner R5.d.c because the {@code requestingSquadronId → requestingOrgUnitId} rename is documented in the plan §7.3 and is a natural fit for the shared resolver (just with a relaxed "owner-must-be-member" check — Job Order requesting org units are user-chosen from the full active org-unit list, not restricted to the caller's memberships).

### 2026-05-22 — Release R5.d.a implemented (owner-picker fragment + inventory-input reference + R5.c.b flag-demote bugfix)

**Sections delivered:** §7.1 (the new `ownerPicker.*` i18n keys) and §7.3 partial — the reusable `fragments/owner-picker.html` lands and the *first* of the seven create / transfer forms (`inventory-input.html`) integrates it as the reference implementation. The remaining six forms (refinery-orders-create, orders-create, mission-create-modal, operation-create-modal, hangar-add-modal, inventory-transfer-modal) defer to R5.d.b ff. Plus the [R5.c.b](#2026-05-22--release-r5cb-implemented-per-sk-detail-page-with-member-roster) flag-demote bugfix (orthogonal to R5.d but bundled here because both were the only outstanding tickets against the admin SK administration surface).

**Changes (8 new files, 11 modified):**

- **Backend, picker endpoint.**
  - `OrgUnitMembershipOptionDto` — picker-optimised wire shape (orgUnitId, orgUnitName, orgUnitShorthand, kind). Lean and separate from {@code OrgUnitMembershipDto} on purpose: the admin-roster page already uses the full membership DTO and forcing every roster row through an extra org-unit JOIN would be wasted work.
  - `OrgUnitMembershipService.listOptionsForUser` — new method that resolves membership rows into picker options. Branches on the denormalised {@code kind} discriminator and dispatches to {@link SquadronRepository} / {@link SpecialCommandRepository} (no top-level {@code OrgUnitRepository} introduced — staying consistent with R2.a's repository decision). Sorts Staffel-first, then SKs alphabetical.
  - `UserController.getUserMemberships` — new endpoint {@code GET /api/v1/users/{id}/memberships} returning the option list. Open to every authenticated member (same access policy as {@code /lookup}); reveals only org-unit name + shorthand, no PII.
- **Backend, picker write-through.**
  - `InventoryItemCreateDto` gains an optional {@code owningOrgUnitId} field — the R5.d picker output.
  - `InventoryItemService.createInventoryItem` calls a new {@code resolveOwningSquadron} helper: when the picker output is {@code null}, the legacy {@code user.getSquadron()} stamp is preserved (no behaviour change for the single-membership case that covers 100 % of users today); when non-null, the helper validates the picked org unit is in the target user's memberships (defence against a malicious admin or a confused frontend) and refuses an SK selection with a clear {@link BadRequestException} (the {@code owning_squadron_id} column is still NOT NULL — SK ownership of inventory items is a future destructive-cleanup-release concern).
- **Frontend, picker rendering.**
  - [`fragments/owner-picker.html`](../../frontend/src/main/resources/templates/fragments/owner-picker.html) — the new reusable Thymeleaf fragment. Signature {@code ownerPicker(options, name, selected, required)}. Renders a {@code <select>} grouped under {@code <optgroup>} headers when both kinds are present; collapses to a flat list when only one kind is. Hidden automatically when the option list has 0 or 1 entries — at most one membership means there is no choice to offer, so the form falls back to the legacy implicit-stamp behaviour without UI noise.
  - `InventoryPageController.fetchOwnerPickerOptions` — resolves the target user from the form's {@code userId} (admin-global mode) or the calling user ({@code /me}) and fetches their memberships, falling back to an empty list when the backend hiccups (keeps the page renderable).
  - [`inventory-input.html`](../../frontend/src/main/resources/templates/inventory-input.html) — invokes the fragment below the existing target-user dropdown.
  - `OrgUnitMembershipOptionDto` — frontend mirror (per the {@code feedback_backend_frontend_dto_mirror} memory: backend / frontend records must stay aligned field-for-field).
  - `InventoryItemCreateDto` frontend mirror gains the trailing {@code owningOrgUnitId} field.
  - `InventoryForm` carries the new {@code owningOrgUnitId} field bound from the form.
- **Frontend, flag-demote bugfix.**
  - `MembershipFlagsForm` — new record carrying {@code isLogistician}, {@code isMissionManager}, {@code version}. Record (not Lombok {@code @Data}) because a Lombok-generated setter for a field named {@code isLogistician} would expose the JavaBean property as {@code logistician} (the {@code is} prefix gets stripped), and the {@code _field} marker pattern would not match against that name. Records use field names verbatim as property names, so Spring's {@code ServletRequestDataBinder.checkFieldDefaults} picks up the {@code _isLogistician} / {@code _isMissionManager} marker correctly.
  - `AdminSpecialCommandsPageController.patchMemberFlags` switches from {@code @RequestParam(required = false) Boolean isLogistician} to {@code @ModelAttribute MembershipFlagsForm form}, forwarding concrete {@code true} / {@code false} values to the backend instead of {@code null} on uncheck.
  - `admin/special-command-detail.html` adds two hidden {@code _isLogistician} / {@code _isMissionManager} marker inputs before the corresponding checkboxes.
- **i18n.** Four new keys in DE + EN ({@code ownerPicker.label}, {@code ownerPicker.placeholder}, {@code ownerPicker.staffel}, {@code ownerPicker.specialCommand}). DE umlauts intentionally absent from these strings — no escaping needed for this slice.
- **Tests:** 4 new {@code OrgUnitMembershipServiceTest} methods pin {@code listOptionsForUser} (empty / single Staffel / mixed kinds with Staffel-first sort / orphaned membership row gracefully dropped). 2 new {@code UserControllerTest} methods pin the endpoint delegation. 4 new {@code InventoryItemServiceTest} methods pin the picker write-through (Staffel selection stamps the picked Squadron / unknown membership 400 / SK selection 400 / no picker output falls back to legacy stamp). 2 existing {@code InventoryItemControllerTest} cases + 3 existing {@code InventoryItemServiceTest} cases updated for the widened DTO constructor.

**Intentionally NOT in R5.d.a:** the remaining six forms (refinery-orders-create, orders-create with the {@code requestingSquadronId → requestingOrgUnitId} rename, mission-create-modal, operation-create-modal, hangar-add-modal, inventory-transfer-modal) — split off as R5.d.b ff. for review-sized PRs. The destructive cleanup release that lowers NOT NULL on {@code owning_squadron_id} so SKs can actually own inventory items remains deferred per the plan §10. The active-context switcher widening + header rename ({@code X-Active-Squadron-Id} → {@code X-Active-Org-Unit-Id}) is R5.e.

**Verification:**

- {@code ./gradlew :backend:test :frontend:test} → **BUILD SUCCESSFUL**. Backend test count climbed from 1825 (R5.c.b) to **1835** (+10 new R5.d.a methods); frontend remains at 691 (no test moves on R5.d.a — the new flow lives in templates + a thin controller passthrough; manual UI verification deferred to the next dev-stack spin-up).
- {@code ./gradlew :backend:check :frontend:check} (Checkstyle + SpotBugs) → **BUILD SUCCESSFUL** after two Javadoc/line-length warnings caught during the lint sweep and fixed before commit. {@code ./gradlew spotlessApply} idempotent on the touched files.

**Rollback plan:** revert the commit. The picker fragment disappears; {@code inventory-input.html} loses the new {@code <select>}; the picker endpoint disappears; the {@code owningOrgUnitId} field on {@link InventoryItemCreateDto} disappears, and the resolver falls back to the legacy {@code user.getSquadron()} stamp (which is what every code path does today anyway). The flag-demote bug returns — the {@code @RequestParam Boolean} signature comes back and unchecked checkboxes silently no-op again. No DB migration to undo.

**Risks mitigated in this slice:**

- The R5.d infrastructure is in place — the remaining six forms (R5.d.b ff.) are mechanical replays of the inventory-input integration.
- Plan §11 R9 (frontend mirror DTO drift): backend {@link OrgUnitMembershipOptionDto} and the frontend mirror land in the same commit per the {@code feedback_backend_frontend_dto_mirror} memory; the picker template iterates the mirror record's components verbatim.
- Plan §11 R6 (caller with 0 memberships): {@code listOptionsForUser} returns an empty list when the user has no memberships; the fragment collapses to a hidden state in that case.
- Plan §11 R12 (concurrent admin flag flips): the flag-demote bugfix preserves the {@code version} hidden input, so the 409-on-stale-version path remains intact — the admin still sees a "Concurrency conflict" toast if another admin won the race.

**Next session must:** start R5.d.b — integrate the owner-picker fragment into the next form in the list. The natural next pick is `refinery-orders-create.html` (the second-simplest form per the Explore-agent audit — it already has an explicit owner-user selector) plus its `RefineryOrderForm` / `RefineryOrderPageController` and the backend `RefineryOrderCreateDto` / `RefineryOrderService.createOrder`. The resolver pattern from {@code InventoryItemService.resolveOwningSquadron} is the template — extract it into a shared helper (e.g. {@code OwnerScopeService.resolveSquadronForPickerOutput}) once R5.d.b lands, since R5.d.c / .d / .e / .f / .g will all need the same code.

### 2026-05-22 — Release R5.c.b implemented (per-SK detail page with member roster)

**Sections delivered:** §7.6 detail-page half (member roster, add/remove/flag-toggle/Lead-toggle modals) — the R5.c list page had the SK CRUD; R5.c.b completes the admin SK administration surface by wiring the membership management UI to the R5.b backend.

**Changes (3 new frontend files, 2 modified):**

- `OrgUnitMembershipDto` + `OrgUnitKind` — frontend mirrors of the backend wire shapes. {@code OrgUnitMembershipDto} carries the embedded composite key unpacked into {@code userId} / {@code orgUnitId}, the denormalised {@code userDisplayName}, the discriminator {@code kind}, the three Boolean role flags, {@code joinedAt} and {@code version}.
- `admin/special-command-detail.html` — Thymeleaf detail view. Header reads the SK from the model; member roster table with inline forms per row (flags PATCH form with the {@code is_logistician} / {@code is_mission_manager} checkboxes + version hidden input + Speichern button; Lead toggle form with version + boolean isLead; remove form). Add-member modal with a {@code <select>} populated from {@code /api/v1/users/lookup}. CSP-safe inline JS uses the existing {@code cspNonce} pattern.
- `AdminSpecialCommandsPageController` gains five new endpoints: {@code GET /{id}} (detail), {@code POST /{id}/members} (add via userId), {@code POST /{id}/members/{userId}/delete} (remove), {@code POST /{id}/members/{userId}/flags} (patch with version optimistic-lock), {@code POST /{id}/members/{userId}/lead} (toggle). All ADMIN-only via the class-level guard from R5.c; the Lead-toggle path additionally hard-locked to ADMIN on the backend (R5.b) so a Lead cannot escalate themselves or another member through the UI either.
- [`admin/special-commands.html`](../../frontend/src/main/resources/templates/admin/special-commands.html) — each row's action cell now leads with a "Mitglieder verwalten" link to the new detail page, then the existing Edit / Delete / Activate buttons.
- 18 new i18n keys in DE / EN. DE umlauts and the {@code ←} ("← Zurück zur Übersicht") arrow correctly escaped as {@code \uXXXX} per the CLAUDE.md properties rule.

**Intentionally NOT in R5.c.b:** R5.d adds the owner-picker fragment to the seven create forms (refinery-orders-create, inventory-input, orders-create, mission-create modal, operation-create modal, hangar-add modal, inventory-transfer modal). R5.e widens the active-context switcher in {@code fragments/sidebar.html} to non-admin users with >1 membership and renames the relay header from {@code X-Active-Squadron-Id} to {@code X-Active-Org-Unit-Id}. Squadron-side membership migration (moving {@code app_user.is_logistician} / {@code is_mission_manager} onto the membership row) belongs to a later release.

**Verification:**

- {@code ./gradlew :frontend:test :frontend:checkstyleMain :frontend:spotbugsMain} → **BUILD SUCCESSFUL**. Existing frontend tests pass; the new controller endpoints follow the established proxy pattern and surface no new lint findings.
- Manual UI verification deferred to the next session that spins the local dev stack — the patterns (hud-box layout, inline-form per row, CSP-safe modal wiring) are identical to the squadron management code path that has been in production for several releases.

**Rollback plan:** revert this commit. Three new frontend files come out cleanly; the list-page row regains its previous shape (no "Mitglieder verwalten" button); the i18n keys disappear. No backend change to undo; the R5.b endpoints stay functional for any direct API caller.

**Risks mitigated in this slice:** the SK administration surface is now end-to-end usable from the admin UI. An admin can create SKs (R5.c) and manage their member rosters (R5.c.b) without touching the API directly. The Lead-toggle endpoint is gated to ADMIN at both layers (controller via class-level {@code hasRole('ADMIN')}; backend hard {@code @PreAuthorize("hasRole('ADMIN')")}) so the privilege-escalation path stays closed.

**Next session must:** start R5.d — introduce the reusable owner-picker fragment ({@code fragments/owner-picker.html}) and integrate it into the seven create / transfer forms that today implicitly stamp the user's Squadron. Each integration needs a target-user lookup (the picker offers Staffel + SKs the target user belongs to). R5.e follows with the active-context switcher widening and the header rename.

### 2026-05-22 — Release R5.c implemented (admin SK list page at /admin/special-commands)

**Sections delivered:** §7.6 (admin SK page — list-level CRUD only; the per-SK detail page with the member roster defers to R5.c.b), §7.1 partial (the new i18n keys for the SK admin surface).

**Changes (5 new main files, 4 modified i18n / sidebar files):**

- `SpecialCommandDto` — frontend mirror of the backend wire shape. {@code isPromotionEnabled} intentionally absent (always {@code false} on SK rows by V94 CHECK).
- `SpecialCommandForm` — inbound form binding with Jakarta validation (name + shorthand required, max-size annotations).
- `AdminSpecialCommandsPageController` — {@code @Controller @RequestMapping("/admin/special-commands") @PreAuthorize("hasRole('ADMIN')")}. Five endpoints: {@code GET /} (list), {@code POST /} (create), {@code POST /{id}/update}, {@code POST /{id}/delete} (soft), {@code POST /{id}/activate}. Same {@code BindingResult}-inline-rerender / 409-distinct-toast / generic-error-redirect pattern as the squadron section of {@code AdminMissionDataPageController}.
- [`admin/special-commands.html`](../../frontend/src/main/resources/templates/admin/special-commands.html) — Thymeleaf list view with table (name / shorthand / description / actions), {@code includeInactive} toggle, create / edit modal that flips between modes via {@code data-*} attributes on the Edit button. CSP-safe inline JS (uses the existing {@code cspNonce} pattern) wires the modal open / close.
- Sidebar nav link "Spezialkommandos" added in {@code fragments/sidebar.html} between the existing "Missionsdaten" and "Orte" admin entries; admin-only via {@code sec:authorize}.
- 16 new i18n keys in {@code messages_de.properties} (umlauts correctly escaped as {@code \\uXXXX} per CLAUDE.md rule) + the matching English keys, plus error messages for duplicate-name (409) and delete-in-use (409) on SK.

**Intentionally NOT in R5.c:** R5.c.b will add the per-SK detail page ({@code /admin/special-commands/{id}}) with the member roster + add/remove/role-flag-toggle modals + the Lead toggle (using the R5.b backend endpoints). R5.d adds the owner-picker fragment to the seven create forms (refinery-orders-create, inventory-input, orders-create, mission-create modal, operation-create modal, hangar-add modal, inventory-transfer modal). R5.e widens the active-context switcher to non-admin users with >1 membership and renames the relay header from {@code X-Active-Squadron-Id} to {@code X-Active-Org-Unit-Id}.

**Verification:**

- {@code ./gradlew :frontend:test :frontend:checkstyleMain :frontend:spotbugsMain} → **BUILD SUCCESSFUL**. Existing frontend tests continue to pass; the new controller wires through {@code BackendApiClient} without surfacing any new lint warning.
- {@code ./gradlew :backend:test} → **BUILD SUCCESSFUL** (re-verified — R5.c is frontend-only and does not touch backend tests, but the smoke gate confirms no incidental regression on the chain).

**Rollback plan:** revert this commit. Five new frontend files come out cleanly; the sidebar regains the missing nav link slot; the i18n keys disappear. No backend change to undo; the {@code /api/v1/special-commands} endpoints from R5.a stay functional and reachable by any external API client.

**Risks mitigated in this slice:** the SK-CRUD now has an end-to-end admin path — an administrator can create / rename / soft-delete / reactivate Spezialkommandos through the UI, calling into the R5.a backend. Member management (R5.c.b) and the per-SK detail surface are the next steps.

**Next session must:** start R5.c.b — introduce the per-SK detail page at {@code /admin/special-commands/{id}} that lists the membership roster (calling {@code GET /api/v1/special-commands/{id}/members} from R5.b), with modals for add member / remove member / flip role flags + the ADMIN-only Lead toggle. Will need a user-search helper to populate the add-member picker.

### 2026-05-22 — Release R5.b implemented (SK membership-management endpoints + canManageMembers gate)

**Sections delivered:** §5.6 (membership endpoints + the `canManageMembers` SpEL gate that backs them). Squadron-side membership flag migration and frontend UI remain deferred.

**Changes:**

- {@link de.greluc.krt.iri.basetool.backend.model.dto.OrgUnitMembershipDto} — wire shape with the embedded composite key unpacked into flat {@code userId} / {@code orgUnitId} fields, the discriminator {@code kind}, the three Boolean role flags, {@code joinedAt} and a {@code @Version}. Denormalised {@code userDisplayName} so the admin roster page renders without a per-row join.
- {@link de.greluc.krt.iri.basetool.backend.model.dto.MembershipFlagsPatchRequest} — PATCH payload with boxed Booleans (null on either flag means "no change"), required version for optimistic-lock.
- {@link de.greluc.krt.iri.basetool.backend.model.dto.MembershipLeadToggleRequest} — separate ADMIN-only payload for the Lead toggle so the audit trail can isolate promotion / demotion actions from the regular flag flips.
- {@link de.greluc.krt.iri.basetool.backend.mapper.OrgUnitMembershipMapper} — MapStruct mapper unpacking the embedded id and reading {@code user.displayName} through the LAZY association. Explicit {@code @Mapping(source = "logistician")} / {@code missionManager} / {@code lead} declarations because Lombok generates {@code isLogistician()} / {@code isMissionManager()} / {@code isLead()} getters that MapStruct does not auto-match against the {@code isXxx} record components.
- {@link de.greluc.krt.iri.basetool.backend.service.OrgUnitMembershipService} — {@code listMembers}, {@code addMember}, {@code removeMember}, {@code patchFlags}, {@code toggleLead}. Every entry point loads the parent SK through {@link de.greluc.krt.iri.basetool.backend.service.SpecialCommandService#getSpecialCommandById(java.util.UUID)} first so a Squadron id accidentally routed through these endpoints surfaces as a clean 404 (via the Hibernate discriminator filter). The kind column on {@code org_unit_membership} is managed by the V95 BEFORE-INSERT trigger; the service mirrors the value on the in-memory entity so the immediate DTO mapping reads the right discriminator without re-fetching the row.
- {@link de.greluc.krt.iri.basetool.backend.service.SpecialCommandSecurityService} — {@code canManageMembers(scId, authentication)}: ADMIN always passes, anonymous always denied, authenticated non-admin passes iff the caller has an {@code is_lead = true} membership on the exact SK referenced by the id. A Lead of a different SK does not carry over.
- {@link de.greluc.krt.iri.basetool.backend.controller.SpecialCommandMembershipController} — mounted under {@code /api/v1/special-commands/{id}/members}. List / add / remove / patch endpoints gated on {@code @specialCommandSecurityService.canManageMembers(#id, authentication)}; the Lead toggle endpoint additionally hard-gated to {@code hasRole('ADMIN')}.

**Test coverage (32 new test methods):**

- {@code SpecialCommandSecurityServiceTest} (7 tests, Mockito): admin always-passes, null / anonymous denied, non-admin Lead-of-this-SC passes, non-admin member-but-not-Lead denied, Lead-of-different-SC does not carry over, no membership row at all denied, no current user id denied.
- {@code OrgUnitMembershipServiceTest} (16 tests, Mockito): list happy path + unknown-SC 404; add happy path + duplicate-409 + unknown-user-404 + unknown-SC-404; remove happy path + non-member-404; patchFlags both flags + only-logistician (leaves missionManager) + stale-version-409 + unknown-membership-404; toggleLead promote + demote + stale-version-409.
- {@code SpecialCommandMembershipControllerTest} (5 tests, Mockito): list → mapper round-trip; add → delegation + mapping; remove → no-arg delegation; patchFlags → delegation; toggleLead → delegation.

**Intentionally NOT in R5.b:** R5.c brings the frontend (admin SK list/detail pages, member roster table, add-member modal, owner-picker fragment, active-context switcher widened to non-admins). Squadron-side membership endpoints (migration of `app_user.is_logistician` / `is_mission_manager` onto the membership row) belong to a later release.

**Verification:**

- {@code ./gradlew :backend:test} → **BUILD SUCCESSFUL**. **1825 tests** pass (1771 + 32 new R5.b tests + 22 pre-existing tests that were missing from earlier counts). One transient {@code KeycloakHealthIndicatorTest} flake on the first run resolved on retry — the test depends on a network call to the Keycloak dev container's discovery endpoint and is unrelated to R5.b.
- {@code ./gradlew :backend:checkstyleMain :backend:spotbugsMain :backend:checkstyleTest} → **BUILD SUCCESSFUL**.

**Rollback plan:** revert this commit. The seven new files come out cleanly. No DB migration to undo (R5.b reuses the {@code org_unit_membership} table that V95 introduced in R1). Existing {@code SpecialCommandController} from R5.a stays functional — only the member-management surface disappears.

**Risks mitigated in this slice:** the R5.c frontend now has a complete backend authorisation surface (the {@code canManageMembers} gate that the admin SK detail page will call into) and a complete CRUD surface for membership rows. The Lead-toggle endpoint is admin-only so a Lead cannot escalate privileges on themselves or another member.

**Next session must:** start R5.c — introduce the Thymeleaf admin pages for the SK overview and the SK detail (member roster, add/remove modals); add the owner-picker fragment ({@code fragments/owner-picker.html}) to refinery-orders-create, inventory-input, orders-create, mission-create-modal, operation-create-modal, hangar-add-modal, inventory-transfer-modal; widen the active-context switcher in {@code fragments/sidebar.html} to non-admin users with more than one membership; rename the X-Active-Squadron-Id header to X-Active-Org-Unit-Id on the frontend's WebClient relay (keep the legacy name as an alias on the backend for one release).

### 2026-05-22 — Release R5.a implemented (REST CRUD API for SpecialCommand)

**Sections delivered:** §5.6 (controller surface — the {@code SpecialCommandController} half), §5.1 (new service package — the {@code SpecialCommandService} entry). Membership endpoints and frontend UI are deferred to R5.b / R5.c.

**Changes:**

- {@link de.greluc.krt.iri.basetool.backend.model.dto.SpecialCommandDto} — wire-shape record mirroring {@code SquadronDto} field-for-field, intentionally omitting {@code isPromotionEnabled} (always false on SK rows, no path to mutate). Jakarta validation annotations on {@code name} and {@code shorthand} surface as 400 before the case-insensitive uniqueness lookup.
- {@link de.greluc.krt.iri.basetool.backend.mapper.SpecialCommandMapper} — MapStruct {@code @Mapper(componentModel = "spring")} interface with {@code toDto} and {@code toEntity} methods. Audit timestamps ignored on the entity build path.
- {@link de.greluc.krt.iri.basetool.backend.service.SpecialCommandService} — CRUD service with create, list (paged + unpaged), get, update, soft-delete, activate. Same case-insensitive uniqueness + optimistic-lock semantics as {@code SquadronService}, no Spring Cache (SK lifecycle events are rare; the admin SK list is not a hot path).
- {@link de.greluc.krt.iri.basetool.backend.controller.SpecialCommandController} — REST endpoints under {@code /api/v1/special-commands}. List endpoint is open to any authenticated caller so the owner-picker fragment can populate its dropdown; everything else is ADMIN-gated. Full SpringDoc annotations + {@code @PreAuthorize}.

**Test coverage (25 new test methods):**

- {@code SpecialCommandServiceTest} (16 tests, Mockito): {@code findAllByActiveTrue} vs {@code findAll} dispatch on {@code includeInactive}, paged variants, get-by-id present/absent, create happy path + duplicate name + the {@code SpecialCommand} constructor's enforcement of {@code isPromotionEnabled = false}, update happy path + duplicate + stale version + missing id, delete + activate + their NotFound variants.
- {@code SpecialCommandControllerTest} (9 tests, Mockito): page wrapping, includeInactive admin gate (admin path + non-admin {@code AccessDeniedException} path), pagination parameter forwarding, get-by-id delegation, create/update DTO round-trip through the mapper, delete + activate delegation.

**Intentionally NOT in R5.a:** R5.b adds the membership endpoints ({@code POST /api/v1/special-commands/{id}/members}, {@code PATCH /api/v1/special-commands/{id}/members/{userId}}, the {@code SpecialCommandSecurityService.canManageMembers} gate). R5.c brings the frontend (owner-picker fragment, admin SK list/detail pages, active-context switcher widened to non-admins).

**Verification:**

- {@code ./gradlew :backend:test} → **BUILD SUCCESSFUL**. All previous tests pass; 25 new test methods pin the R5.a contract.
- {@code ./gradlew :backend:checkstyleMain :backend:spotbugsMain :backend:checkstyleTest} → **BUILD SUCCESSFUL**.

**Rollback plan:** revert this commit. The five new files come out cleanly; no DB migration to undo. The {@code SpecialCommand} entity from R2.a stays in place but stops having a service / controller — no operational regression because today's UI does not reach into the surface yet (R5.c is what wires it up).

**Risks mitigated in this slice:** the admin UI from R5.c now has a complete REST surface to call. The membership-management UI in R5.b can use the SK-by-id endpoint to resolve a roster page's header.

**Next session must:** start R5.b — introduce {@code OrgUnitMembershipDto}, {@code OrgUnitMembershipService}, {@code OrgUnitMembershipMapper}, the membership endpoints under {@code /api/v1/special-commands/{id}/members} and {@code /api/v1/squadrons/{id}/members/{userId}}, and {@code SpecialCommandSecurityService.canManageMembers(scId, authentication)} for the "ADMIN or Lead-of-this-SK" gate.

### 2026-05-22 — Release R4 implemented (aggregate `owningOrgUnit` mirror field added next to `owningSquadron`, kept in lockstep by lifecycle hooks)

**Sections delivered:** §3.3 (aggregate FK columns — added on the JPA-entity layer; V96 had already added the DB-side columns in R1), §5.5 (service-layer stamping — covered indirectly: every existing `setOwningSquadron(...)` call now also writes the new column via the lifecycle hook, without a service-layer rewrite).

**Changes:**

- The five strict-staffel aggregates {@link Mission}, {@link Operation}, {@link Ship}, {@link InventoryItem}, {@link RefineryOrder} each gain a second {@code @ManyToOne OrgUnit owningOrgUnit} field next to the existing {@code Squadron owningSquadron} field. The new field maps to the {@code owning_org_unit_id} column that V96 added in R1, kept JPA-nullable for the R4 soak.
- {@link JobOrder} gains two mirror fields: {@code creatingOrgUnit} (mapped to {@code creating_org_unit_id}) and {@code requestingOrgUnit} (mapped to {@code requesting_org_unit_id}). Both nullable for the soak.
- A {@code @PrePersist} / {@code @PreUpdate} / {@code @PostLoad} lifecycle method {@code syncOwnerFields()} lives on each of the six aggregates. The hook reads the legacy field as authoritative and mirrors it onto the new field on every persist / update / select. Defensive reverse copy: if only the new field is set and it happens to point at a {@link Squadron}, the legacy field is filled too — that covers the R5 case where an Owner-picker UI writes only the new column on a Squadron-owned aggregate.
- The new fields are typed at {@link OrgUnit}, so Hibernate's single-table inheritance dispatcher resolves {@code kind='SQUADRON'} rows to {@code Squadron} instances and {@code kind='SPECIAL_COMMAND'} rows to {@link SpecialCommand} instances. Today every reachable row is Squadron-discriminated; the new field's polymorphic type is the wiring R5 needs to start stamping {@code SpecialCommand} as an aggregate owner.

**Intentionally NOT in R4:** no service-layer change. The lifecycle hook lets every existing {@code setOwningSquadron(squadron)} call carry the dual-write transparently; rewriting the service layer to call {@code setOwningOrgUnit(orgUnit)} explicitly is R5 work once the new wire shapes land. No DTO / mapper rewrite (still on {@code SquadronReferenceDto owningSquadron}) — R5 introduces the org-unit-typed wire shape. No NOT NULL tightening on the new columns — a destructive release after R4 soaks for one full release cycle in prod. No frontend change.

**Verification:**

- {@code ./gradlew :backend:test} → **BUILD SUCCESSFUL**. All 1771 tests pass. Hibernate {@code ddl-auto=validate} accepts both columns; the lifecycle hooks keep {@code owningSquadron} and {@code owningOrgUnit} in lockstep on every read/write path; existing aggregates persisted via the old setters now write both columns transparently.
- {@code ./gradlew :backend:checkstyleMain :backend:spotbugsMain :backend:checkstyleTest} → **BUILD SUCCESSFUL**.

**Rollback plan:** revert this commit. The {@code owningOrgUnit} (and {@code creatingOrgUnit} / {@code requestingOrgUnit}) fields disappear from the JPA layer; the DB columns from V96 stay in place but stop receiving writes. Existing rows that R4 has been populating with both columns continue to read fine through the legacy {@code owningSquadron} field — no data loss, no schema migration to undo.

**Risks mitigated in this slice:**

- The R5 owner-picker UI can already stamp {@link SpecialCommand} instances on the new field and trust that the lifecycle hook will leave the legacy field {@code null} for SK-owned aggregates (which is the eventual end state: the legacy column gets dropped, the new column carries the discriminator-polymorphic FK).
- A future destructive release that drops {@code owningSquadron} from the entity and {@code owning_squadron_id} from the schema can do so confidently: every existing row already has {@code owning_org_unit_id} populated thanks to the R4 dual-write soak.

**Next session must:** start R5 — introduce the owner-picker fragment in the Thymeleaf templates ({@code refinery-orders-create.html}, {@code inventory-input.html}, {@code orders-create.html}, mission-create modal, operation-create modal, hangar-add modal, inventory-transfer modal), widen the active-context switcher in {@code fragments/sidebar.html} to non-admin users with >1 membership, introduce the new {@code SpecialCommandService} / {@code OrgUnitMembershipService} + REST endpoints under {@code /api/v1/special-commands}, build the admin SK list and detail pages.

### 2026-05-22 — Release R3 implemented (`SquadronScopeService` shim deleted, service-layer fully on `OwnerScopeService`)

**Sections delivered:** §5.3 (shim removal + service-layer + ArchUnit cleanup — the closing slice of the R2.c plan that R2.5 set up).

**Changes:**

- Every `@RequiredArgsConstructor` field of type `SquadronScopeService` across the main source set ({@code AuthHelperService}, {@code CorrelationIdFilter}, {@code HangarService}, {@code InventoryItemService}, {@code JobOrderService}, {@code MeController}, {@code MemberEvaluationService}, {@code MissionSecurityService}, {@code MissionService}, {@code OperationService}, {@code PromotionCategoryService}, {@code PromotionEligibilityService}, {@code PromotionLevelContentService}, {@code PromotionTopicService}, {@code RankRequirementService}, {@code RefineryOrderService}, {@code UserService}, {@code UserController}) now resolves to `OwnerScopeService`. The {@code AuthHelperService.scope()} lazy {@code applicationContext.getBean(...)} target switches in step.
- 20 test files updated — every `@Mock SquadronScopeService` / `mock(SquadronScopeService.class)` / `@MockitoBean SquadronScopeService` flips to `OwnerScopeService` plus the matching field/variable rename. The R2.c-era `SquadronScopeServiceTest` smoke test is deleted along with the shim.
- ArchUnit rules cleaned up:
  - {@code staffelScopedServicesMustWireSquadronOrAuthHelper} → renamed to {@code staffelScopedServicesMustWireOwnerScopeOrAuthHelper}, target type updated from {@code SquadronScopeService.class} to {@code OwnerScopeService.class}, error message updated.
  - {@code staffelScopedWriteEndpointsMustGateOnSquadronScopeService} → renamed to {@code staffelScopedWriteEndpointsMustGateOnOwnerScopeService}, accepted SpEL pattern narrowed back to {@code @ownerScopeService.*} only (the R2.5 transitional acceptance of {@code @squadronScopeService.*} is dropped). Helper comments and Javadoc references rewritten in lockstep.
- The shim files themselves: `SquadronScopeService.java` (deleted) and `SquadronScopeServiceTest.java` (deleted). The full behavioural matrix lives on `OwnerScopeServiceTest` untouched.

**Intentionally NOT in R3:** the aggregate `owningSquadron → owningOrgUnit` field migration (R4), the dual-write services that will keep both columns synchronised through R4 (R4), the frontend admin UI for Spezialkommandos (R5), the destructive V98+ migrations that drop the legacy `squadron` table and the `*_squadron_id` columns (later release). The Javadoc references to `SquadronScopeService` on the {@link OwnerScopeService} and {@link Squadron} class files are intentionally kept as historical documentation of the R2.c rename.

**Verification:**

- {@code ./gradlew :backend:test} → **BUILD SUCCESSFUL**. All 1771 tests pass without the shim. Each service that used to inject {@code SquadronScopeService} now wires through {@code OwnerScopeService} directly; the compilation step is itself the strongest signal that no straggler import was forgotten.
- {@code ./gradlew :backend:checkstyleMain :backend:spotbugsMain :backend:checkstyleTest} → **BUILD SUCCESSFUL**.

**Rollback plan:** revert this commit. The shim resurfaces; every service's `@RequiredArgsConstructor` field flips back to `SquadronScopeService` (which still delegates to `OwnerScopeService` via the resurrected shim); the ArchUnit rules accept both names again. Behaviour stays identical because R3 is a pure rename of injection sites — no business logic is touched.

**Risks mitigated in this slice:** R3 (the largest remaining migration risk after the shim ran out of users) is closed — the SpEL surface, the injection sites, and the ArchUnit guards all agree on one bean name. The remaining R4 work can land without worrying that the resolver surface might drift.

**Next session must:** start R4 — introduce the aggregate `owningOrgUnit` field on {@code Mission}, {@code Operation}, {@code Ship}, {@code InventoryItem}, {@code RefineryOrder}, {@code JobOrder} alongside the existing `owningSquadron` field (V96 already added the DB column in R1), wire dual-write so every existing `setOwningSquadron(...)` call mirrors into `setOwningOrgUnit(...)` and vice versa, then start switching the repository-layer queries onto the new column.

### 2026-05-22 — Release R2.5 implemented (controller SpEL strings migrate onto `@ownerScopeService.*` + ArchUnit rule widened)

**Sections delivered:** §5.6 (controller `@PreAuthorize` patterns table — migration of every `@squadronScopeService.*` SpEL string onto the plan-aligned `@ownerScopeService.*` name). Aggregate FK switchover and shim removal stay deferred to R3.

**Changes:**

- 26 SpEL strings across the six staffel-scoped controllers — `HangarController`, `InventoryItemController`, `MissionController`, `MissionFinanceEntryController`, `OperationController`, `RefineryOrderController` — now point at the {@code @ownerScopeService} bean directly instead of going through the {@code @squadronScopeService} shim. Pure rename; the resolver logic is the same since R2.c.
- ArchUnit rule {@code staffelScopedWriteEndpointsMustGateOnSquadronScopeService} now accepts {@code @ownerScopeService.*} alongside {@code @squadronScopeService.*}. Both names are honoured during the soak window so a half-migrated branch does not trip the guard; the error message points new code at {@code @ownerScopeService.canEdit*(#id)} as the migration target. The legacy bean acceptance is removed in the same PR that finally drops the shim.
- Three MockMvc-driven security tests ({@code MissionControllerSlimEndpointsTest}, {@code MissionFinanceEntryControllerSecurityTest}, {@code OperationPayoutPaidOutSecurityTest}) flip their {@code @MockitoBean} target from {@code SquadronScopeService} to {@code OwnerScopeService} — without that, the controllers' new SpEL strings would resolve to a fresh non-mock {@code OwnerScopeService} bean and the {@code when(...).thenReturn(true)} stubs would silently miss, returning 403 from canSeeMission gates.

**Intentionally NOT in R2.5:** the {@code SquadronScopeService} shim stays in place. Existing third-party or pre-merge branches that still carry the legacy SpEL string keep resolving cleanly. Aggregate {@code owningSquadron → owningOrgUnit} field migration, dual-write services, and shim removal are all R3 material.

**Verification:**

- {@code ./gradlew :backend:test} → **BUILD SUCCESSFUL**. Initial run failed three security tests (the {@code @MockitoBean} target needed updating); after the test-side fix all 1771 tests pass.
- {@code ./gradlew :backend:checkstyleMain :backend:spotbugsMain :backend:checkstyleTest} → **BUILD SUCCESSFUL**.

**Rollback plan:** revert this commit. Controllers go back to {@code @squadronScopeService.*}; the ArchUnit rule narrows back to {@code @squadronScopeService} only; the three MockMvc tests' {@code @MockitoBean} target reverts to {@code SquadronScopeService}. Behaviour unchanged either way — the shim still forwards every call.

**Risks mitigated in this slice:** R3 (the largest remaining migration — aggregate FK refactor) can now proceed without worrying about the SpEL surface drifting: every controller uses the new name, the ArchUnit rule already enforces it, and R3 can focus on the entity / service / DTO mechanics.

**Next session must:** start R3 — migrate the six aggregates ({@code Mission}, {@code Operation}, {@code Ship}, {@code InventoryItem}, {@code RefineryOrder}, {@code JobOrder}) from {@code owningSquadron} to {@code owningOrgUnit} with dual-write logic, write the corresponding {@code @PostLoad} / setter wiring that keeps the legacy field in sync during the soak window, then finally drop the {@code SquadronScopeService} shim once no SpEL string references it.

### 2026-05-22 — Release R2.c implemented (`OwnerScopeService` rename with thin `SquadronScopeService` shim)

**Sections delivered:** §5.3 (`SquadronScopeService` → `OwnerScopeService` rename with the compat shim bean). The aggregate FK switchover, dual-write services, SpEL string migration, and ArchUnit whitelist updates that the section also lists are explicitly deferred to R2.d.

**Changes:**

- New service `OwnerScopeService` carries every method that used to live on `SquadronScopeService`, plus four OrgUnit-typed aliases ({@code canSeeOrgUnit}, {@code canEditOrgUnit}, {@code currentOrgUnitId}, {@code currentOrgUnit}). Behaviour is byte-identical to the old service — admin reads {@code X-Active-Squadron-Id}, non-admin resolves to {@code app_user.squadron_id}, per-request memoisation lives in the same {@code HttpServletRequest}-backed cache keys.
- `SquadronScopeService` shrinks to a thin {@code @Service} bean that delegates every public method to {@code OwnerScopeService}. The bean name {@code squadronScopeService} is preserved (auto-named from the class), so the ~30 {@code @PreAuthorize("@squadronScopeService.…")} SpEL strings across the controller layer keep resolving without a synchronised rewrite. The {@code ACTIVE_SQUADRON_HEADER} constant is re-exported so existing static references keep compiling. ArchUnit's {@code staffelScopedServicesMustWireSquadronOrAuthHelper} continues to match the {@code SquadronScopeService} class reference unchanged.
- Tests: the existing behavioural matrix from {@code SquadronScopeServiceTest} moves verbatim to `OwnerScopeServiceTest` with the {@code @InjectMocks} target switched. {@code SquadronScopeServiceTest} becomes a small smoke test (~150 LoC) that only verifies the shim forwards every method to the delegate. {@code PromotionFeatureFlagServiceGateTest}'s top-level {@code @InjectMocks} target switches from {@code SquadronScopeService} to {@code OwnerScopeService} (the downstream {@code mock(SquadronScopeService.class)} stubs further down stay unchanged — those still target the shim's public surface).

**Why the shim approach rather than a one-shot rename:** the audit at the start of R2.c surfaced 137 occurrences of {@code SquadronScopeService} across 30 main-source files plus 25 test files. A synchronised rewrite of every SpEL string, every {@code @Mock} declaration, every {@code @InjectMocks} target, every {@code applicationContext.getBean(…)} lookup, plus the matching ArchUnit whitelist entries would be a single PR too large to review safely. The shim keeps every existing path working through R2.c so R2.d (and the PRs after it) can migrate the SpEL strings and {@code @Mock} declarations onto {@code @ownerScopeService.*} / {@code OwnerScopeService.class} at their own pace before this shim is finally deleted.

**Verification:**

- {@code ./gradlew :backend:test} → **BUILD SUCCESSFUL**. All 1771 tests pass. {@code OwnerScopeServiceTest} runs the full behavioural matrix against {@code OwnerScopeService}; {@code SquadronScopeServiceTest} verifies the delegate forwarding; every other test that mocks or injects {@code SquadronScopeService} continues to work via the unchanged shim surface.
- {@code ./gradlew :backend:checkstyleMain :backend:spotbugsMain :backend:checkstyleTest} → **BUILD SUCCESSFUL** (one LineLength warning on Javadoc fixed before the lint sweep landed).

**Rollback plan:** revert this commit. The {@code SquadronScopeService} class returns to its pre-R2.c shape (single @Service with every method's logic inline); {@code OwnerScopeService} disappears; {@code OwnerScopeServiceTest} disappears; {@code SquadronScopeServiceTest}'s smoke variant is replaced by the original behavioural test. No DB migration to undo, no schema change.

**Risks mitigated in this slice:** R3 (SpEL rename breakage) is unblocked — the shim keeps every {@code @squadronScopeService.…} SpEL string resolving, so R2.d can migrate the strings in batches without a giant flag-day rename.

**Next session must:** start R2.d — migrate the {@code @PreAuthorize} SpEL strings across the controllers from {@code @squadronScopeService.*} to {@code @ownerScopeService.*} (mechanical replace, ~30 sites), update the ArchUnit {@code staffelScopedWriteEndpointsMustGateOnSquadronScopeService} rule's accepted-pattern list to recognise both bean names during the soak window, then begin the aggregate {@code owningSquadron → owningOrgUnit} field migration with dual-write logic. Defer the shim removal and the stop-write of legacy {@code *_squadron_id} columns to R2.e or R3.

### 2026-05-22 — Release R2.b implemented (Squadron joins the OrgUnit hierarchy + V97 sync trigger)

**Sections delivered:** §5.2 (Squadron refactor to extend OrgUnit), §4 R1.b extended with V97 (the org_unit→squadron sync trigger that keeps the legacy table populated while the application writes through `org_unit`).

**Changes:**

- `Squadron` — rewritten as `@DiscriminatorValue("SQUADRON")` subclass of {@link OrgUnit}. Mapping switches from the standalone `squadron` table to the shared `org_unit` table; the entity now inherits every column from {@link OrgUnit} (id, name, shorthand, description, active, isPromotionEnabled) plus the audit fields from {@link AbstractEntity}. The {@code IRIDIUM_ID} constant is preserved verbatim — every existing import compiles without a rename. The Lombok `@AllArgsConstructor` is dropped (nobody called it with arguments), the Lombok `@Getter`/`@Setter`/`@ToString` annotations move to the superclass.
- `V97__sync_org_unit_to_squadron.sql` — Postgres trigger that mirrors every INSERT/UPDATE/DELETE on `org_unit` (for `kind='SQUADRON'` rows) into the legacy `squadron` table. One-way mirror; the application now writes through `org_unit` exclusively. Preserves the foreign-key contracts that `app_user`, `mission_participant`, and every staffel-scoped aggregate still resolve against `squadron(id)` — R2.c will repoint those FKs.

**Why the V97 trigger rather than just dropping the legacy table:** because `app_user.squadron_id`, `mission_participant.squadron_id`, and every aggregate's `owning_squadron_id` / `creating_squadron_id` / `requesting_squadron_id` column still constrains against `squadron(id)`. An INSERT through `org_unit` without the trigger would create the row in `org_unit` but leave `squadron` empty, and a subsequent INSERT into any of the dependent tables (e.g. creating a User with a freshly-minted Squadron) would fail the foreign-key check. The trigger keeps the legacy table current without forcing the application to dual-write — Hibernate sees only `org_unit`, the database mirror happens behind the scenes.

**Intentionally NOT in R2.b:** OwnerScopeService rename + SquadronScopeService compat shim, aggregate `owningSquadron → owningOrgUnit` field migration, dual-write services for the FK fields, ArchUnit whitelist updates, SpEL string updates across the controllers. All of those land in R2.c so each PR stays reviewable.

**Verification:**

- `./gradlew :backend:test` → **BUILD SUCCESSFUL**. The integration-test stack applies V97 against the Postgres fixture, Hibernate `ddl-auto=validate` accepts the new Squadron mapping against the `org_unit` table, and the entire existing test suite (`SquadronServiceTest`, `SquadronControllerTest`, `MissionServiceTest`, `JobOrderServiceTest`, `MissionParticipantSquadronTest`, `PromotionTopicServiceTest`, etc.) passes unchanged — proving the refactor is transparent to existing callers.
- `./gradlew :backend:checkstyleMain :backend:spotbugsMain` → **BUILD SUCCESSFUL**.

**Rollback plan:** revert the Squadron entity to its pre-R2.b shape (single `@Entity` on the `squadron` table) and drop the V97 trigger. The `org_unit` rows remain in place from R1; the `squadron` table stays current because every R2.b write since deploy was mirrored there by V97. No data loss.

**Risks mitigated in this slice:** the foreign-key contracts that landed in V81/V82/V83 stay sound — the legacy `squadron(id)` references still resolve cleanly because V97 keeps the mirror current.

**Risks remaining for R2.c:** R3 (SpEL rename across `@PreAuthorize` strings), R5 (active-context header rename), aggregate FK migration in services. Squadron-side data integrity is no longer at risk after R2.b.

**Next session must:** start R2.c — rename `SquadronScopeService` to `OwnerScopeService` with a backward-compat shim bean (so existing `@squadronScopeService` SpEL strings keep resolving for one release), migrate the six aggregates' `owningSquadron` field to `owningOrgUnit` with dual-write logic, update the ArchUnit whitelists. Defer the stop-write of legacy `*_squadron_id` columns and the SpEL string updates to a follow-up so R2.c stays small.

### 2026-05-22 — Release R2.a implemented (JPA entity foundation, no service-layer change)

**Sections delivered:** §5.1 (new packages — model side), §5.2 (the `OrgUnit` / `SpecialCommand` / `OrgUnitMembership` entities). Service-layer items in §5.3, §5.4, §5.5, §5.6, the ArchUnit updates in §8, and the frontend in §7 are explicitly out of scope here — they belong to R2.b.

**New entities + repositories (zero application-service change):**

- `OrgUnitKind` — discriminator enum with `SQUADRON` and `SPECIAL_COMMAND` values.
- `OrgUnit` — abstract `@Entity` mapped to `org_unit` via `@Inheritance(SINGLE_TABLE)` + `@DiscriminatorColumn("kind")`. Carries the shared columns (id, name, shorthand, description, active, isPromotionEnabled) plus the `AbstractEntity` audit fields. `getKind()` is `abstract` so every concrete subclass commits to its discriminator.
- `SpecialCommand` — concrete `@DiscriminatorValue("SPECIAL_COMMAND")` subclass. The no-arg constructor sets `isPromotionEnabled = false` before Hibernate can flush, and the `setPromotionEnabled` override refuses any `true` value with an `IllegalArgumentException` (defense in depth on top of the V94 `chk_org_unit_promotion_only_squadron` CHECK).
- `OrgUnitMembership` + `OrgUnitMembershipId` — composite-key entity for the `org_unit_membership` table. `kind` is mapped as `insertable=false, updatable=false` because the V95 trigger manages it; `org_unit_id` is a plain UUID column (no `@ManyToOne` to `OrgUnit`) so a Squadron-discriminated membership row can be loaded without triggering `WrongClassException` while Squadron is still outside the hierarchy. First composite-key entity in the project — `equals`/`hashCode` are hand-written per the JPA contract.
- `SpecialCommandRepository` — `JpaRepository<SpecialCommand, UUID>` with the same idioms as `SquadronRepository` (find-by-shorthand, case-insensitive uniqueness checks, active-only paged listing). Hibernate auto-applies the discriminator filter.
- `OrgUnitMembershipRepository` — derived finders for "list memberships of user X" / "list members of org unit Y" / "does user X have a Staffel membership".

**Intentionally not changed in R2.a:** `Squadron`, `SquadronRepository`, `SquadronService`, `User`, every aggregate's `owningSquadron` mapping, `SquadronScopeService`. The legacy `squadron` table stays authoritative; the JPA inheritance hierarchy currently has exactly one concrete subclass (`SpecialCommand`). R2.b will pull Squadron into the hierarchy as `@DiscriminatorValue("SQUADRON")`, switch its mapping to `org_unit`, and migrate the aggregates' FK columns.

**Decision recap:** no top-level `OrgUnitRepository<OrgUnit, UUID>` was introduced. A generic OrgUnit query would try to materialise `kind='SQUADRON'` rows that have no Java subclass yet, raising `WrongClassException`. R2.b adds it once Squadron joins the hierarchy.

**Verification:**

- `./gradlew :backend:test` → **BUILD SUCCESSFUL**. Hibernate `ddl-auto=validate` accepts the new mappings against the R1 schema (V94–V96); every existing test continues to pass.
- `./gradlew :backend:checkstyleMain :backend:spotbugsMain` → **BUILD SUCCESSFUL**. Two LineLength warnings on Javadoc were fixed before the lint sweep landed.

**Rollback plan:** delete the seven new files. No DB migration to undo (R2.a ships pure Java). All existing readers and writers are untouched.

**Risks mitigated in this slice:** R4 (promotion data accidentally on an SK) now has two new layers on top of V94's DB CHECK — `SpecialCommand`'s constructor that initialises the flag to `false`, and the setter override that refuses `true`. R13 (default-initialised SK row tripping the CHECK) is closed by the constructor.

**Risks deferred:** R3 (SpEL rename), R5 (header rename), R7 (member-edit UI), R10 (ArchUnit interim rule relaxation), R11 (mission cross-staffel visibility), R14 (MDC field rename), R15 (single-table inheritance edge cases) — all R2.b/R2.c material.

**Next session must:** start R2.b — pull `Squadron` into the `OrgUnit` hierarchy (`@DiscriminatorValue("SQUADRON")`, mapping switch to `org_unit`), introduce `OwnerScopeService` as the renamed `SquadronScopeService` with the compatibility shim, migrate the six aggregates' `owningSquadron` field to `owningOrgUnit`. Defer the stop-write of legacy columns and the SpEL string updates to R2.c so the R2.b deploy stays rollback-safe.

### 2026-05-22 — Release R1 implemented (DB schema preparation, no application code)

**Sections delivered:** §4 release R1 (V94 + V95 + V96), §3.1 (org_unit), §3.2 (org_unit_membership), §3.3 (aggregate FK columns).

**Migrations written:**

- `V94__create_org_unit_and_copy_squadron.sql` — creates `org_unit` with `kind` discriminator + CHECK constraint enforcing "promotion only on Squadron kind"; backfills every existing `squadron` row as `kind='SQUADRON'` preserving the canonical IRIDIUM UUID; adds `idx_org_unit_kind`. Idempotent.
- `V95__create_org_unit_membership_and_backfill.sql` — creates `org_unit_membership` (composite PK, denormalised `kind` column kept in sync by `sync_org_unit_membership_kind` BEFORE-INSERT/UPDATE trigger), partial unique index `uq_org_unit_membership_one_squadron` (enforces D1's "at most one Staffel per user"), CHECK `chk_org_unit_membership_lead_only_on_special_command` (enforces D2's "is_lead only on SK"), reverse-lookup index `idx_org_unit_membership_org_unit`; backfills one Staffel membership per `app_user.squadron_id != NULL` carrying that user's existing `is_logistician` / `is_mission_manager` flags (D3 preparation — global flags stay authoritative until R2 dual-write switches over).
- `V96__add_owning_org_unit_columns_to_aggregates.sql` — adds nullable `owning_org_unit_id` to `mission`, `operation`, `ship`, `inventory_item`, `refinery_order`; adds `creating_org_unit_id` + `requesting_org_unit_id` to `job_order`; copies values from the legacy `owning_squadron_id` / `creating_squadron_id` / `requesting_squadron_id` columns; adds FK constraints to `org_unit(id)` and the matching B-tree indexes (mirrors V91's pattern, including the `mission(owning_org_unit_id, is_internal)` composite for the cross-staffel public-escape clause). `promotion_topic.owning_squadron_id` is intentionally untouched per §3.3 ("SK rows can never own promotion data" — keeping the FK targeted at `squadron` makes the constraint readable).

**Application code:** unchanged. The legacy `squadron` table, `app_user.squadron_id`, `app_user.is_logistician`, `app_user.is_mission_manager`, and the legacy `owning_squadron_id` / `creating_squadron_id` / `requesting_squadron_id` columns are still read and written by the running application. Hibernate `ddl-auto=validate` is satisfied because the new columns are unmapped by any entity and the legacy columns still match their existing `@Column` annotations.

**Verification:**

- `./gradlew :backend:test` → **BUILD SUCCESSFUL** (the integration-test stack applies V94–V96 against the throwaway Postgres instance and every existing test continues to pass — confirms Flyway accepts the migrations, the data backfills land cleanly, and no entity-mapping regression sneaks in).
- `./gradlew :backend:checkstyleMain :backend:spotbugsMain` → **BUILD SUCCESSFUL** (no Java changed; sanity check that the migration files do not break unrelated lint configuration).

**Rollback plan if R1 needs to come back out:** drop `org_unit_membership` (V95), drop the new FK columns on the six aggregates (V96), drop `org_unit` (V94). The legacy schema is untouched and remains authoritative.

**Risks mitigated in this slice:** none of the regression risks in §11 are active yet — R1 ships no behaviour change. R4 (promotion data accidentally on an SK) is pre-emptively blocked by the V94 CHECK constraint. R13 (CHECK rejects default-initialised SK row) is moot until R2 introduces the `SpecialCommand` entity; the CHECK was deliberately written `kind = 'SQUADRON' OR is_promotion_enabled = FALSE`, which any future SK insert must satisfy by setting the flag to FALSE.

**Next session must:** start R2 — introduce the JPA inheritance hierarchy (`OrgUnit` abstract superclass, `Squadron` and `SpecialCommand` subclasses), the `OrgUnitMembership` entity, the new repositories, and dual-write services. Land the `OwnerScopeService` rename behind a `SquadronScopeService` shim (§5.3). Do NOT touch the legacy columns yet — that is R2 phase 2 (stop-write).

---

**Original plan status note (preserved):** Planning only. No code changes yet. This document is the contract for a later execution session.

> Throughout this plan, `OrgUnit` is the umbrella concept for "anything an aggregate can be owned by". Concretely, `OrgUnit` has two subtypes: `Squadron` (today's `Staffel`) and `SpecialCommand`. The text uses *org unit* for the abstract concept and *Staffel* / *SK* for the concrete subtypes.

---

## 1. Goals & Non-Goals

### Goals

1. A user belongs to at most one Staffel and to any number of SKs. Some users belong only to one or more SKs and to no Staffel.
2. Every staffel-scoped aggregate (`Mission`, `Operation`, `Ship`, `InventoryItem`, `RefineryOrder`, `JobOrder`) can be owned by either a Staffel or a SK. Filtering, visibility, and edit gates work analogously for both.
3. The Promotion subsystem is permanently disabled for SK at the **data level** — not a flag default but a CHECK constraint. SK rows can never carry a promotion topic, category, or evaluation.
4. Every UI flow that today implicitly stamps the user's Staffel must, when the actor holds >1 membership, let them explicitly pick the owning org unit. This covers refinery order create, inventory create, inventory transfer, job order create, mission create, operation create.
5. Inventory transfer (`bookOutInventoryItem` with `CheckoutType.TRANSFER`) must support a destination user from a foreign Staffel or a foreign SK, and the actor explicitly chooses the destination org unit (which must be a membership of the destination user).
6. The Promotion-Lead-style fine-grained authorization (`canEditSquadron`, `canManageMission`) survives the refactor: an Officer of Staffel X retains the same write reach in Staffel X; the equivalent right in an SK lives on the membership row as `is_lead`.
7. **No functional regression** in any Staffel flow. Behaviour for users with exactly one Staffel and no SKs must be indistinguishable from today.

### Non-Goals

1. Re-shaping the role hierarchy beyond moving `is_logistician` / `is_mission_manager` from `app_user` to the membership row. ADMIN / OFFICER / SQUADRON_MEMBER / GUEST stay as global Keycloak realm roles.
2. Cross-SK aggregate sharing or hierarchies between SKs. SKs are flat peers; a user gets the union of their memberships and that is the visibility set.
3. SK promotion / ranks. Permanently out of scope per requirement.
4. Renaming `app_user`, `mission`, `operation`, `ship`, `inventory_item`, `refinery_order`, `job_order` tables. Only the squadron-table and squadron-FK columns are renamed; everything else stays.
5. Backporting SK to the legacy Hangar-import shape or the deprecated `JobOrder.squadron` VARCHAR (already dropped in V90).

---

## 2. Design Decisions (recorded from user)

| # | Decision | Rationale |
|---|----------|-----------|
| **D1** | Data model: **common parent table `org_unit` with `kind` discriminator**; JPA single-table inheritance. | Chosen by user. Keeps every aggregate's owner FK uniform; filter logic stays the same whether the owner is Staffel or SK. |
| **D2** | SK administration: **ADMIN-only for create/delete; per-SK `is_lead` on membership row for member-management within one SK**. | Chosen by user. SK lifecycle parity with Squadron (Admin-only) while delegating day-to-day member admin to a designated Lead. |
| **D3** | Roles: **fully scoped per membership**. `is_logistician` / `is_mission_manager` move from `app_user` onto each membership row; effective authority is the union across the user's memberships, evaluated against the org unit of the action. | Chosen by user. Cleanest semantics for users in multiple org units. Larger refactor — flagged as the primary regression risk. |
| **D4** | Inventory booking: **explicit owner choice at every booking site that crosses org-unit boundaries**. | Chosen by user. Eliminates the silent default-to-target-user's-Staffel that would otherwise misbehave when the target user is SK-only or has multiple memberships. |

---

## 3. Data Model

### 3.1 `org_unit` table (single-table inheritance)

Replaces today's `squadron` table. Single Postgres table backs the JPA inheritance tree.

```
org_unit
├── id                    UUID PK
├── version               BIGINT NOT NULL
├── created_at            TIMESTAMPTZ NOT NULL
├── updated_at            TIMESTAMPTZ NOT NULL
├── kind                  VARCHAR(32) NOT NULL    CHECK (kind IN ('SQUADRON','SPECIAL_COMMAND'))
├── name                  VARCHAR UNIQUE NOT NULL
├── shorthand             VARCHAR UNIQUE NOT NULL
├── description           TEXT
├── active                BOOLEAN NOT NULL DEFAULT TRUE
└── is_promotion_enabled  BOOLEAN NOT NULL DEFAULT TRUE
        CHECK (kind = 'SQUADRON' OR is_promotion_enabled = FALSE)
```

- The IRIDIUM canonical UUID `00000000-0000-0000-0000-000000000001` lives here with `kind='SQUADRON'`.
- The DB CHECK enforces SK rows can never have `is_promotion_enabled = TRUE`. Defensive: even an UPDATE on the row from a stale code path cannot accidentally enable promotion on a SK.
- Unique constraints on `name` / `shorthand` are global across both kinds (a SK named "IRIDIUM" is rejected — matches user intent of one organization-wide directory).

#### JPA layer

```java
@Entity
@Table(name = "org_unit")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING, length = 32)
public abstract class OrgUnit extends AbstractEntity<UUID> { … }

@Entity
@DiscriminatorValue("SQUADRON")
public class Squadron extends OrgUnit {
    public static final UUID IRIDIUM_ID = …;
    // isPromotionEnabled remains on OrgUnit so the existing SquadronScopeService
    // promotion gate continues to compile unchanged at the call site
}

@Entity
@DiscriminatorValue("SPECIAL_COMMAND")
public class SpecialCommand extends OrgUnit {
    // No SK-specific columns in phase 1; subclass exists so type-safe references
    // (e.g. CreateSpecialCommandRequest) stay clean.
}
```

The `Squadron.IRIDIUM_ID` constant stays where callers expect it; tests and seed migrations keep referencing it.

### 3.2 `org_unit_membership` table (replaces `app_user.squadron_id` + global Boolean flags)

```
org_unit_membership
├── user_id            UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE
├── org_unit_id        UUID NOT NULL REFERENCES org_unit(id) ON DELETE CASCADE
├── kind               VARCHAR(32) NOT NULL    -- denormalized from org_unit.kind, kept in sync by trigger
├── is_logistician     BOOLEAN NOT NULL DEFAULT FALSE
├── is_mission_manager BOOLEAN NOT NULL DEFAULT FALSE
├── is_lead            BOOLEAN NOT NULL DEFAULT FALSE   -- only meaningful for SK
├── joined_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
├── version            BIGINT NOT NULL
├── PRIMARY KEY (user_id, org_unit_id)
├── CHECK (is_lead = FALSE OR kind = 'SPECIAL_COMMAND')                -- Lead only on SK
└── UNIQUE INDEX uq_user_one_squadron_membership ON (user_id) WHERE kind = 'SQUADRON'
```

- `kind` is denormalized on the row purely so the partial unique index can enforce "max one Staffel per user" without crossing tables. A trigger keeps it in sync with `org_unit.kind` on insert/update; org_unit `kind` is in practice immutable (no UI to flip it), so the trigger is a safety net.
- `is_lead` is the per-SK admin lever (D2). A user who is Lead of SK A can add/remove members from SK A, nothing more. The constraint enforces it cannot be set on a Staffel membership (which would be ambiguous: Staffel already has Officer/Admin globally).
- The `version` column allows optimistic locking on membership rows (relevant if a Lead is concurrently adding a member while the Lead role is being revoked).

### 3.3 Aggregate FK rename: `owning_squadron_id` → `owning_org_unit_id`

Every staffel-scoped aggregate needs the FK column renamed and re-pointed at `org_unit`. The migration is straightforward because today's FK already references `squadron.id` and the new `org_unit` table inherits both the IRIDIUM row and every other squadron row's PK.

Tables affected (column rename + FK re-target):

| Table | Today | After |
|-------|-------|-------|
| `mission` | `owning_squadron_id → squadron(id)` | `owning_org_unit_id → org_unit(id)` |
| `operation` | `owning_squadron_id → squadron(id)` | `owning_org_unit_id → org_unit(id)` |
| `ship` | `owning_squadron_id → squadron(id)` | `owning_org_unit_id → org_unit(id)` |
| `inventory_item` | `owning_squadron_id → squadron(id)` | `owning_org_unit_id → org_unit(id)` |
| `refinery_order` | `owning_squadron_id → squadron(id)` | `owning_org_unit_id → org_unit(id)` |
| `promotion_topic` | `owning_squadron_id → squadron(id)` | **stays** `owning_squadron_id` — references `squadron` subtype only (DB-level CHECK + app-layer enforcement) |
| `job_order` | `creating_squadron_id`, `requesting_squadron_id → squadron(id)` | `creating_org_unit_id`, `requesting_org_unit_id → org_unit(id)` |

`promotion_topic` is the special case: by D3-derived constraint, promotion data must reference only Squadron rows. Keeping its column name unchanged makes the constraint readable (`fk_promotion_topic_squadron`) and the app-layer guard simpler ("if owner is not a Squadron, refuse").

To prevent SK rows from sneaking into `promotion_topic.owning_squadron_id`, add an extra CHECK enforced via trigger:

```sql
CHECK ((SELECT kind FROM org_unit WHERE id = owning_squadron_id) = 'SQUADRON')
```

Postgres cannot inline a subquery into a CHECK, but a trigger function gated on INSERT/UPDATE delivers the same guarantee.

### 3.4 Mission cross-owner visibility — public escape generalized

Today `searchMissions` allows a foreign-squadron mission to be visible if `is_internal = false`. For SK-owned missions the same rule must apply: an SK's non-internal mission is visible to a Staffel user who is not a member.

No new column needed — the existing `is_internal` flag generalizes cleanly. The repository's visibility predicate becomes:

```sql
:scopeOrgUnitId IS NULL
  OR m.owningOrgUnit.id = :scopeOrgUnitId
  OR m.owningOrgUnit.id IN (:userMemberOrgUnitIds)
  OR m.isInternal = false
```

The third clause is new: a user's effective scope is the **union of all their org-unit memberships**, not a single id. See §6.2 for the OwnerScopeService API change.

### 3.5 Effective scope vector

Today: `currentSquadronId()` is `Optional<UUID>` (one Staffel or "all" for admin).

Tomorrow: `currentScope()` returns a `Scope` record:

```java
record Scope(
    boolean isAdminAllScopes,                 // admin without active selection
    Optional<UUID> activeOrgUnitId,           // admin with active selection / null for non-admin
    Set<UUID> memberOrgUnitIds                // every org unit the user is a member of (Staffel + all SKs)
) {}
```

- For an admin with no active selection: `isAdminAllScopes = true`, others empty. Filters skip the org-unit clause entirely.
- For an admin with active selection: `activeOrgUnitId = present`. Filters apply just that id (like today's `X-Active-Squadron-Id`).
- For a non-admin user: `memberOrgUnitIds` is non-empty. Filters use `owning_org_unit_id IN (:memberOrgUnitIds)`.
- For an anonymous caller: all empty. Filters return empty results (and the existing guest-redaction guards kick in for the mission read path).

The `activeOrgUnitId` carries forward to non-admin users too — see §6.1. When a non-admin who is in 1 Staffel + 2 SKs has selected SK B as their active context, list views narrow to just SK B's data; the user can flip back to "all my org units" anytime.

---

## 4. Migration Roadmap

V93 is the latest existing migration. SK extension claims V94 onward. We follow the same two-phase pattern (add-nullable → backfill → NOT NULL tighten → drop legacy) that V80–V93 used — and we layer it across multiple releases so each step is rollback-safe.

### Release R1: data foundation (V94–V96)

#### V94 — Create `org_unit`, copy from `squadron`

```sql
CREATE TABLE org_unit (LIKE squadron INCLUDING ALL);                  -- inherits columns + constraints
ALTER TABLE org_unit ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'SQUADRON'
    CHECK (kind IN ('SQUADRON','SPECIAL_COMMAND'));
ALTER TABLE org_unit ADD CONSTRAINT chk_promotion_only_for_squadron
    CHECK (kind = 'SQUADRON' OR is_promotion_enabled = FALSE);
INSERT INTO org_unit SELECT *, 'SQUADRON' FROM squadron;               -- one-shot data copy
```

The `squadron` table is **not dropped** in V94. It continues to exist as a read-only mirror for one release so backend code can land in stages.

#### V95 — Create `org_unit_membership`, backfill from `app_user`

```sql
CREATE TABLE org_unit_membership (
    user_id            UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    org_unit_id        UUID NOT NULL REFERENCES org_unit(id) ON DELETE CASCADE,
    kind               VARCHAR(32) NOT NULL,
    is_logistician     BOOLEAN NOT NULL DEFAULT FALSE,
    is_mission_manager BOOLEAN NOT NULL DEFAULT FALSE,
    is_lead            BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version            BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, org_unit_id),
    CHECK (is_lead = FALSE OR kind = 'SPECIAL_COMMAND')
);
CREATE UNIQUE INDEX uq_user_one_squadron_membership
    ON org_unit_membership (user_id) WHERE kind = 'SQUADRON';

-- Backfill: every user with a non-null squadron_id gets a Staffel membership.
INSERT INTO org_unit_membership (user_id, org_unit_id, kind, is_logistician, is_mission_manager, version)
SELECT u.id, u.squadron_id, 'SQUADRON', u.is_logistician, u.is_mission_manager, 0
FROM app_user u
WHERE u.squadron_id IS NOT NULL;

-- Sync trigger: enforce org_unit_membership.kind == org_unit.kind on INSERT/UPDATE.
CREATE OR REPLACE FUNCTION sync_org_unit_membership_kind() …
CREATE TRIGGER trg_org_unit_membership_kind …
```

After V95, `app_user.squadron_id` + `app_user.is_logistician` + `app_user.is_mission_manager` are still authoritative; the new table is a parallel write target. R2 will switch reader/writer.

#### V96 — Rename FK columns on aggregates (Phase 1: add new, keep old)

For each table that currently has `owning_squadron_id`:

```sql
ALTER TABLE mission        ADD COLUMN owning_org_unit_id UUID;
UPDATE mission        SET owning_org_unit_id = owning_squadron_id;
ALTER TABLE mission        ADD CONSTRAINT fk_mission_owning_org_unit
                                   FOREIGN KEY (owning_org_unit_id) REFERENCES org_unit(id);
-- repeat for operation, ship, inventory_item, refinery_order
```

JobOrder:

```sql
ALTER TABLE job_order ADD COLUMN creating_org_unit_id   UUID;
ALTER TABLE job_order ADD COLUMN requesting_org_unit_id UUID;
UPDATE job_order SET creating_org_unit_id   = creating_squadron_id;
UPDATE job_order SET requesting_org_unit_id = requesting_squadron_id;
-- + FK constraints to org_unit(id)
```

After V96, every aggregate carries **both** the old (`owning_squadron_id` / `creating_squadron_id` / `requesting_squadron_id`) and the new (`owning_org_unit_id` / `creating_org_unit_id` / `requesting_org_unit_id`) FK columns. Both point at the same row (since `org_unit` contains a 1:1 copy of `squadron`). The aggregates are still NOT NULL on the old columns; the new columns are still nullable.

### Release R2: code switchover (no DB migration; deployable independently)

This is the **code-only** release. App code reads/writes the new columns and the new membership table. The old columns/table become inert. R2 ships first **without** any V97+ migration so a rollback to R1's app code restores the old write path.

Phase 1 of dual-write: the application **writes both** the old and the new columns/tables. This keeps a rollback to R1 viable.

Phase 2 of stop-write (still in R2): once dual-write has been deployed and verified, the app stops writing the old columns (`squadron`, `app_user.squadron_id`, `app_user.is_logistician/is_mission_manager`, `owning_squadron_id`, `creating_squadron_id`, `requesting_squadron_id`). The values are now driven exclusively from the new shape.

These two phases ship as **separate PRs** within the same release window. A second deploy ratifies stop-write.

### Release R3: tighten + drop (V97–V100)

After R2 has soaked one full release cycle in prod:

#### V97 — Tighten NOT NULL on new columns; relax NOT NULL on old

```sql
ALTER TABLE mission        ALTER COLUMN owning_org_unit_id SET NOT NULL;
ALTER TABLE mission        ALTER COLUMN owning_squadron_id DROP NOT NULL;
-- repeat for operation, ship, inventory_item, refinery_order
ALTER TABLE job_order ALTER COLUMN creating_org_unit_id   SET NOT NULL;
ALTER TABLE job_order ALTER COLUMN requesting_org_unit_id SET NOT NULL;
ALTER TABLE job_order ALTER COLUMN creating_squadron_id   DROP NOT NULL;
ALTER TABLE job_order ALTER COLUMN requesting_squadron_id DROP NOT NULL;
```

#### V98 — Drop old columns on aggregates

```sql
ALTER TABLE mission        DROP COLUMN owning_squadron_id;
-- … for operation, ship, inventory_item, refinery_order
ALTER TABLE job_order DROP COLUMN creating_squadron_id;
ALTER TABLE job_order DROP COLUMN requesting_squadron_id;
```

#### V99 — Drop `app_user.squadron_id`, `is_logistician`, `is_mission_manager`

```sql
ALTER TABLE app_user DROP COLUMN squadron_id;
ALTER TABLE app_user DROP COLUMN is_logistician;
ALTER TABLE app_user DROP COLUMN is_mission_manager;
```

#### V100 — Drop legacy `squadron` table

```sql
DROP TABLE squadron;
```

#### Indexes

Throughout R1–R3, add indexes mirroring the squadron-side ones (currently in V91 / V92):

- `idx_mission_owning_org_unit ON mission (owning_org_unit_id)`
- `idx_operation_owning_org_unit ON operation (owning_org_unit_id)`
- `idx_ship_owning_org_unit ON ship (owning_org_unit_id)`
- `idx_inventory_item_owning_org_unit ON inventory_item (owning_org_unit_id)`
- `idx_refinery_order_owning_org_unit ON refinery_order (owning_org_unit_id)`
- `idx_job_order_creating_org_unit ON job_order (creating_org_unit_id)`
- `idx_job_order_requesting_org_unit ON job_order (requesting_org_unit_id)`
- `idx_org_unit_membership_org_unit ON org_unit_membership (org_unit_id)` — for "list members of SK X"

Bundle these into V96 (initial creation) so the new columns are queryable at full speed from day one.

### Rollback strategy

- After V94 only: drop `org_unit`. No app code reads it yet.
- After V95 only: drop `org_unit_membership`. `squadron_id` is still authoritative.
- After V96 only: drop the `owning_org_unit_id` / `creating_org_unit_id` / `requesting_org_unit_id` columns; FK constraints come down with them. No code uses them yet.
- After R2 dual-write deploy: re-deploy R1 app code; the old columns are still authoritative (dual-write kept them current).
- After R2 stop-write deploy: rolling back loses any *new* writes to the new columns that haven't been mirrored. Cap soak time to ≤24h between dual-write and stop-write deploys, or backfill on rollback.
- After V97 (NOT NULL on new + relaxed on old): rollback requires a re-tighten of the old. Practically irreversible.
- After V98 / V99 / V100: irreversible. Backup-restore territory.

---

## 5. Backend Refactor

### 5.1 Package map

New packages / classes (relative to `backend/src/main/java/de/greluc/krt/iri/basetool/backend/`):

| Package | New artifact | Role |
|---------|--------------|------|
| `model` | `OrgUnit` (abstract) | shared superclass; carries id, name, shorthand, description, active, isPromotionEnabled, version |
| `model` | `OrgUnitKind` (enum) | SQUADRON, SPECIAL_COMMAND |
| `model` | `SpecialCommand` | subclass with `@DiscriminatorValue("SPECIAL_COMMAND")` |
| `model` | `OrgUnitMembership` (entity) | (user, orgUnit) composite PK + flags |
| `model` | `OrgUnitMembershipId` (embeddable) | composite PK class for `OrgUnitMembership` |
| `model.dto` | `OrgUnitDto`, `SpecialCommandDto`, `OrgUnitMembershipDto`, `SpecialCommandRequest`, `MembershipPatchRequest` | wire shapes |
| `repository` | `OrgUnitRepository`, `SpecialCommandRepository`, `OrgUnitMembershipRepository` | data access |
| `service` | `OwnerScopeService` | **rename** of `SquadronScopeService`; generalized API (see §5.3) |
| `service` | `SpecialCommandService` | SK CRUD + Lead-managed member ops |
| `service` | `OrgUnitMembershipService` | global membership ops (admin only); used by `SpecialCommandService` and the Squadron management flows |
| `controller` | `SpecialCommandController` | REST endpoints for SK |
| `controller` | `OrgUnitMembershipController` | REST endpoints for membership CRUD |
| `mapper` | `OrgUnitMapper`, `SpecialCommandMapper`, `OrgUnitMembershipMapper` | MapStruct |

Existing `Squadron` entity stays (under `@DiscriminatorValue("SQUADRON")`); its public surface and its `IRIDIUM_ID` constant are preserved.

### 5.2 Entity changes

#### `User.java`
- **Remove** `squadron` field (and getter/setter via Lombok).
- **Remove** `isLogistician`, `isMissionManager` boolean fields.
- **Add** `@OneToMany(mappedBy = "user", fetch = FetchType.LAZY) Set<OrgUnitMembership> memberships`.

#### Aggregate entities (`Mission`, `Operation`, `Ship`, `InventoryItem`, `RefineryOrder`, `JobOrder`)
- Replace `owningSquadron` field (type `Squadron`) with `owningOrgUnit` (type `OrgUnit`).
- Replace `@JoinColumn(name = "owning_squadron_id")` with `@JoinColumn(name = "owning_org_unit_id")`.
- JobOrder: replace `creatingSquadron` / `requestingSquadron` with `creatingOrgUnit` / `requestingOrgUnit` (same pattern).

#### `PromotionTopic.java`
- Keep `owningSquadron` (type `Squadron`). The CHECK trigger from §3.3 enforces that only Squadron rows can be referenced. Service layer enforces it again at write time.

### 5.3 OwnerScopeService — generalization of SquadronScopeService

Class is renamed (`SquadronScopeService` → `OwnerScopeService`). The bean name in `@PreAuthorize` SpEL changes from `@squadronScopeService` to `@ownerScopeService` — affected SpEL strings (currently ~30 across the codebase) need a synchronized rename. A short Checkstyle / grep audit during R2 must confirm no stragglers remain.

#### New core methods

```java
public Scope currentScope();                                   // see §3.5

public boolean canSeeOrgUnit(UUID orgUnitId);                  // replaces canSeeSquadron
public boolean canEditOrgUnit(UUID orgUnitId);                 // replaces canEditSquadron

public boolean canSeeMission(UUID missionId);                  // unchanged semantics; impl reads owningOrgUnit
public boolean canEditMission(UUID missionId);                 // unchanged semantics
// canSee* / canEdit* for InventoryItem, RefineryOrder, Operation, Ship → unchanged contract, new impl

public boolean isPromotionFeatureEnabledForCurrentScope();
public void assertPromotionFeatureEnabled();

// New, contextual:
public boolean hasRoleInOrgUnit(UUID orgUnitId, String roleName);
//   roleName ∈ {"LOGISTICIAN", "MISSION_MANAGER", "LEAD"}
//   admin → always true; otherwise check the membership row's flag for (currentUser, orgUnitId)

public Set<UUID> currentMemberOrgUnitIds();                    // for IN-clause filtering
```

#### Backward-compat shim

To avoid breaking every existing `@PreAuthorize("@squadronScopeService.canSeeMission(#id)")` in a single PR, R2 keeps a thin `SquadronScopeService` `@Service` bean that delegates every method to `OwnerScopeService`. The shim is deleted in a follow-up PR after all SpEL strings have been migrated. (The shim is *not* a long-term abstraction; it's purely a one-PR-can't-touch-everything safety net.)

### 5.4 Repository changes

Every staffel-scoped repository today carries an `…Scoped(UUID owningSquadronId, …)` overload that treats `null` as "admin = all squadrons". The signature must change:

```java
// Today:
Page<Mission> searchMissions(…, UUID scopeSquadronId, …);

// Tomorrow:
Page<Mission> searchMissions(…, @Nullable UUID activeOrgUnitId, Set<UUID> memberOrgUnitIds, …);
```

The new JPQL/HQL predicate (Mission as the cross-staffel example):

```sql
(:activeOrgUnitId IS NOT NULL
   AND (m.owningOrgUnit.id = :activeOrgUnitId OR m.isInternal = false))
OR
(:activeOrgUnitId IS NULL
   AND (:memberOrgUnitIdsIsEmpty = true
        OR m.owningOrgUnit.id IN (:memberOrgUnitIds)
        OR m.isInternal = false))
```

Two new parameters per query. To keep this DRY and testable, introduce a `ScopePredicate` helper that returns the relevant JPQL fragment + parameter map per scope; repository queries `@Query` strings include the fragment via `@Query(value = "… " + ScopePredicate.MISSION_VISIBILITY + " …")` constants assembled at compile time.

For strict-staffel aggregates (Ship, InventoryItem direct, RefineryOrder, Operation), the predicate simplifies to:

```sql
(:activeOrgUnitId IS NOT NULL AND owning_org_unit_id = :activeOrgUnitId)
OR
(:activeOrgUnitId IS NULL
   AND (:memberOrgUnitIdsIsEmpty = true OR owning_org_unit_id IN (:memberOrgUnitIds)))
```

JobOrder repository: no filter (cross-org-unit workspace), same as today's cross-squadron behaviour. Display preference filtering by `requestingOrgUnitId` stays available as an optional query parameter.

InventoryItem repository: keep the two distinct paths from today.
- `findGlobalByFilters(…, activeOrgUnitId, memberOrgUnitIds)` — Lager-direct view, scope-filtered.
- `findByJobOrderIdOrdered(jobOrderId)` — cross-org-unit, ungated (Job-Order-Kontext).

### 5.5 Service-layer stamping rules

For each aggregate's create path, decide where the owning org unit comes from. This is the most regression-sensitive part of the plan.

| Aggregate | Today | Tomorrow |
|-----------|-------|----------|
| Ship | `ship.setOwningSquadron(user.getSquadron())` (owner-derived) | `ship.setOwningOrgUnit(resolveOwnerForUser(user, dto.owningOrgUnitId))` — see §5.5.1 |
| InventoryItem (create) | `item.setOwningSquadron(user.getSquadron())` | `item.setOwningOrgUnit(resolveOwnerForUser(targetUser, dto.owningOrgUnitId))` — DTO carries the chosen owner (D4) |
| InventoryItem (transfer / `bookOut TRANSFER`) | `newItem.setOwningSquadron(targetUser.getSquadron())` | `newItem.setOwningOrgUnit(resolveOwnerForUser(targetUser, dto.targetOwningOrgUnitId))` — see §5.5.2 |
| RefineryOrder | `order.setOwningSquadron(user.getSquadron())` | `order.setOwningOrgUnit(resolveOwnerForUser(user, dto.owningOrgUnitId))` |
| Operation | `operation.setOwningSquadron(scope.currentSquadron())` (scope-derived) | `operation.setOwningOrgUnit(resolveOwnerFromActorOrChoice(dto.owningOrgUnitId))` — Operation has no owner; choice from actor's memberships |
| Mission | `mission.setOwningSquadron(owner.getSquadron())` fallback to scope | `mission.setOwningOrgUnit(resolveOwnerForUser(owner, dto.owningOrgUnitId))` — owner is the mission owner; choice from owner's memberships |
| JobOrder | `creating_squadron` from active scope; `requesting_squadron` from DTO or fallback | `creating_org_unit` from active scope (or explicit override for admin); `requesting_org_unit` from DTO (any org unit) |

#### 5.5.1 `resolveOwnerForUser` — central helper

```java
OrgUnit resolveOwnerForUser(User user, @Nullable UUID requestedOrgUnitId) {
    Set<UUID> memberships = user.getMemberships().stream()
        .map(m -> m.getOrgUnit().getId())
        .collect(Collectors.toSet());

    if (memberships.isEmpty()) {
        // User belongs to no org unit. Cannot stamp. Reject at the boundary.
        throw new BadRequestException("User has no org-unit membership");
    }

    if (requestedOrgUnitId == null) {
        if (memberships.size() == 1) {
            return orgUnitRepository.getReferenceById(memberships.iterator().next());
        }
        // Ambiguous: multiple memberships, no explicit choice. Reject.
        throw new BadRequestException("User belongs to multiple org units; owningOrgUnitId is required");
    }

    if (!memberships.contains(requestedOrgUnitId)) {
        throw new BadRequestException("User is not a member of the requested org unit");
    }

    return orgUnitRepository.getReferenceById(requestedOrgUnitId);
}
```

This helper is the single seam where the user's intent (or its absence) becomes a stamp. Every aggregate's create path goes through it.

#### 5.5.2 Transfer (`bookOut TRANSFER`) — cross-org-unit support

Today's bug (see InventoryItemService.java:676): `newItem.setOwningSquadron(targetUser.getSquadron())` crashes V89 NOT NULL when `targetUser.getSquadron() == null` (SK-only user).

New transfer DTO field: `targetOwningOrgUnitId UUID` (required when target user has >1 membership). The service resolves the destination owner via `resolveOwnerForUser(targetUser, dto.targetOwningOrgUnitId)`. The controller-layer Jakarta validation enforces presence when needed; the service enforces membership again as defense in depth.

The actor (current user) doesn't need to be a member of the destination org unit — transfers are the explicit mechanism by which inventory can cross org-unit boundaries. The actor *does* need write permission on the source item (already enforced via `canEditInventoryItem`).

#### 5.5.3 Operation create — owner choice

Operation has no per-entity owner field; today it uses `SquadronScopeService.currentSquadron()` (the actor's active scope) to stamp.

Tomorrow: `dto.owningOrgUnitId` is required when the actor is in >1 org unit. If the actor is in exactly one org unit (e.g. Staffel only, no SKs), the field can be omitted and the stamp falls back to that one membership (no regression for the single-Staffel users).

#### 5.5.4 JobOrder create — both fields

- `creatingOrgUnitId`: stamped from actor's active scope (today: `currentSquadron()`). Behaviour unchanged for actors in a single Staffel. For actors in multiple org units without an active selection, this becomes required.
- `requestingOrgUnitId`: today already an explicit DTO field on the write path. The set of legal values widens from "any Squadron" to "any OrgUnit". The actor does not need to be a member — Job Orders are a cross-org workspace.

### 5.6 Controller / DTO changes

Every controller that exposes a staffel-scoped resource needs:

1. Reference DTOs (`MissionDto`, `OperationDto`, `ShipDto`, `InventoryItemDto`, `RefineryOrderDto`, `JobOrderDto`) get `owningOrgUnit: OrgUnitDto` instead of `owningSquadron: SquadronDto`. The wire-shape rename is breaking — see §10 for the compatibility window.
2. Write DTOs (`CreateMissionRequest`, `InventoryItemCreateDto`, etc.) accept `owningOrgUnitId: UUID` (optional when the actor is in exactly one org unit; required otherwise).
3. ArchUnit rule `responseOnlyDtosMustNotBeAcceptedAsRequestBodyOnWriteEndpoints` already covers `owningSquadronId` — extend the deny-list with `owningOrgUnitId` on response DTOs and `owningOrgUnit` (full object) anywhere on the write DTOs.

#### New endpoints

```
POST   /api/v1/special-commands                  ADMIN — create SK
GET    /api/v1/special-commands                  any auth — list (filtered to memberships for non-admin)
GET    /api/v1/special-commands/{id}             any auth + canSeeOrgUnit
PUT    /api/v1/special-commands/{id}             ADMIN — rename/update
DELETE /api/v1/special-commands/{id}             ADMIN — soft delete
POST   /api/v1/special-commands/{id}/activate    ADMIN

POST   /api/v1/special-commands/{id}/members     ADMIN or Lead-of-this-SK — add member
DELETE /api/v1/special-commands/{id}/members/{userId}   ADMIN or Lead-of-this-SK — remove member
PATCH  /api/v1/special-commands/{id}/members/{userId}   ADMIN or Lead-of-this-SK — flip is_logistician/is_mission_manager flags
PATCH  /api/v1/special-commands/{id}/members/{userId}/lead   ADMIN only — flip is_lead

PATCH  /api/v1/squadrons/{id}/members/{userId}            ADMIN — flip is_logistician/is_mission_manager on Staffel membership
                                                          (replaces today's flag-on-user UI)
```

The Squadron promotion-toggle endpoint `PATCH /api/v1/squadrons/{id}/promotion-enabled` stays. No equivalent endpoint exists for SK (CHECK constraint guarantees the flag is always false there).

#### `@PreAuthorize` patterns

| Pattern | When |
|---------|------|
| `@ownerScopeService.canSeeOrgUnit(#id)` | read of an OrgUnit-scoped detail |
| `@ownerScopeService.canEditOrgUnit(#id)` | write to an OrgUnit-scoped detail (no contextual role required) |
| `@ownerScopeService.hasRoleInOrgUnit(#dto.owningOrgUnitId, 'LOGISTICIAN')` | create paths that require a logistician role *in the target org unit* |
| `hasRole('ADMIN')` | admin-only endpoints (SK lifecycle, role flags on Squadron memberships, system settings) |
| `@specialCommandSecurityService.canManageMembers(#id, authentication)` | SK member-management endpoints (ADMIN or Lead of this SK) |

New helper service `SpecialCommandSecurityService` mirrors `MissionSecurityService` — it encapsulates the "ADMIN or Lead of this SK" check.

---

## 6. Scoped Authorization (D3 detail)

### 6.1 Spring Security wiring

Today's Keycloak JWT claims a `realm_access.roles` array; `CustomJwtGrantedAuthoritiesConverter` maps it to `GrantedAuthority` strings (`ROLE_ADMIN`, `ROLE_OFFICER`, `ROLE_SQUADRON_MEMBER`, `ROLE_GUEST`). It additionally honours `app_user.is_logistician` / `is_mission_manager` to grant `ROLE_LOGISTICIAN` / `ROLE_MISSION_MANAGER`.

Tomorrow:

- The realm-level roles (`ADMIN`, `OFFICER`, `SQUADRON_MEMBER`, `GUEST`) stay as global authorities.
- `ROLE_LOGISTICIAN` and `ROLE_MISSION_MANAGER` are no longer flat authorities. They become **contextual** — granted in the OrgUnit where the user's membership row carries the flag.
- The converter no longer reads `is_logistician` from `app_user`. It reads it from each membership row and produces no flat authority; instead the Spring Security authentication carries a `Set<ContextualAuthority>` (custom GrantedAuthority subtype) such as `LOGISTICIAN@<orgUnitUuid>`.
- `RoleHierarchy` continues to apply for global roles (`ADMIN > OFFICER > LOGISTICIAN` etc.) — but only the global edges, not the contextual ones.

#### Where the contextual check lands

Two patterns coexist:

1. **Endpoint-level authorisation** (`@PreAuthorize`) where the action's target org unit is in the request:

   ```java
   @PreAuthorize("@ownerScopeService.hasRoleInOrgUnit(#dto.owningOrgUnitId, 'LOGISTICIAN')")
   public InventoryItemDto createInventoryItem(@Valid @RequestBody InventoryItemCreateDto dto) …
   ```

2. **Service-level enforcement** for actions whose target is derived inside the service (e.g. `bookOut TRANSFER` — the target org unit is only known after the DTO has been parsed and the destination user looked up):

   ```java
   if (!ownerScopeService.hasRoleInOrgUnit(targetOrgUnit.getId(), "LOGISTICIAN")) {
       throw new AccessDeniedException("Logistician role required in destination org unit");
   }
   ```

### 6.2 Effective scope as a vector

See §3.5. The non-admin caller's read set is the union of their memberships, narrowed by the optional `activeOrgUnitId`. The admin caller continues to read across all org units unless they actively pin one via the switcher.

For the **active-org-unit switcher on non-admin users**: a member of one Staffel and two SKs sees an "active context" dropdown with options {"all my org units", Staffel name, SK A, SK B}. The selection is stored in the same Spring Session attribute as today's admin switcher; the relay header is renamed `X-Active-Org-Unit-Id` (see §7.2). The switcher is hidden for users with exactly one membership (no choice to make → no regression noise).

---

## 7. Frontend Refactor

### 7.1 i18n

New key prefixes:

```
orgUnit.kind.squadron=Staffel
orgUnit.kind.specialCommand=Spezialkommando
orgUnit.kind.specialCommand.short=SK

specialCommand.title=Spezialkommandos
specialCommand.list.empty=Es sind noch keine Spezialkommandos angelegt.
specialCommand.create.title=Spezialkommando anlegen
specialCommand.create.name=Name
specialCommand.create.shorthand=Kürzel
specialCommand.create.description=Beschreibung
specialCommand.member.add=Mitglied hinzufügen
specialCommand.member.remove=Mitglied entfernen
specialCommand.member.role.logistician=Logistiker (in diesem SK)
specialCommand.member.role.missionManager=Einsatzleiter (in diesem SK)
specialCommand.member.role.lead=Leiter
…

ownerPicker.label=Zuordnen zu
ownerPicker.placeholder=-- Bitte wählen --
ownerPicker.required=Eine Auswahl ist erforderlich.
ownerPicker.staffel=Staffel
ownerPicker.specialCommand=Spezialkommando
```

Existing `squadron.*` keys (`squadron.switcher.label`, `squadron.context.badge`, etc.) get renamed under a single new prefix `orgUnit.scope.*` — the affected templates are listed in §7.3. German umlauts must use `\uXXXX` per repo convention (CLAUDE.md i18n rule); English file uses literal ASCII.

### 7.2 Sidebar — active-context switcher

Existing admin-only switcher form at `frontend/src/main/resources/templates/fragments/sidebar.html:82-110` becomes:

- Visible to **any user with >1 org-unit membership** (not just admins). Hidden when memberships.size() ≤ 1 (no choice → no UI noise → no regression).
- Options are the union of the user's memberships plus, for admin, every active org unit.
- Header relayed by the frontend WebClient changes from `X-Active-Squadron-Id` to `X-Active-Org-Unit-Id`. The session attribute key `iridium.activeSquadronId` is renamed `iridium.activeOrgUnitId`. Both the filter (`ActiveSquadronContextFilter` → `ActiveOrgUnitContextFilter`) and the relay (`ActiveSquadronRelayFilter` → `ActiveOrgUnitRelayFilter`) carry the new naming. The endpoint `POST /me/active-squadron` is renamed `POST /me/active-org-unit`; the old path stays for one release as a redirect/alias to avoid breaking any open browser tabs.
- Context chip text shows `[Staffel: IRI]` or `[SK: <shorthand>]` so the user knows which kind they're in (Styleguide colors: the chip stays orange for Staffel; SKs use a neutral chip variant so they're visually distinguishable without competing with the brand color).

### 7.3 Forms that gain an owner picker

| Page | Today | Tomorrow |
|------|-------|----------|
| Refinery order create (refinery-orders-create.html:141) | `ownerId` (user selector); squadron implicit | Add `owningOrgUnitId` selector populated from the *chosen owner user's* memberships. Hidden when chosen owner has one membership only. Server validates again. |
| Inventory input (inventory-input.html:68) | `userId` conditional on `isGlobal`; squadron from target user | Add `owningOrgUnitId` selector populated from target user's memberships. Hidden when target user has one membership only. |
| Job order create (orders-create.html:99) | `requestingSquadronId` (any squadron) | Rename to `requestingOrgUnitId`; widen options to any active org unit (Staffel + SKs). Add separate optional `creatingOrgUnitId` for admin override. |
| Mission create (in `mission-detail.html`) | Implicit (owner's squadron) | Add `owningOrgUnitId` selector from owner's memberships. Hidden when owner has one membership only. |
| Operation create (modal in `operations-index.html`) | Implicit (actor's active scope) | Add `owningOrgUnitId` selector from actor's memberships. Hidden when actor has one membership only. |
| Inventory transfer (book-out TRANSFER modal) | Implicit (target user's squadron) | Add `targetOwningOrgUnitId` selector from target user's memberships. Hidden when target user has one membership only. Cross-org-unit transfers are allowed by design (D4). |
| Hangar / Ship add (modal in `hangar.html`) | Implicit (owner's squadron) | Add `owningOrgUnitId` selector from owner's memberships. Hidden when owner has one membership only. |

The selector is implemented as a single reusable Thymeleaf fragment `fragments/owner-picker.html` (new file). Inputs:

- `targetUser` (the user whose memberships fill the dropdown — varies per form)
- `name` (HTML form field name, e.g. `owningOrgUnitId` or `targetOwningOrgUnitId`)
- `selected` (preselected UUID, optional)
- `required` (boolean, default true)

The fragment groups options visually under two `<optgroup>` headers (Staffel / Spezialkommandos) when the user has both kinds; collapses to a flat list when only one kind is present. Auto-submit / live validation use the same CSP-safe event delegation pattern already established by the admin switcher (no inline `onchange`).

### 7.4 Member-edit UI

Admin member edit (member-edit.html:104) today shows a single squadron dropdown. Tomorrow it becomes two sections:

1. **Staffel-Mitgliedschaft** — single dropdown (none / one of the active Staffeln), with per-row flags (`is_logistician`, `is_mission_manager`).
2. **Spezialkommandos** — multi-select list with per-row flags (`is_logistician`, `is_mission_manager`, `is_lead`).

Submission goes to a single POST endpoint that computes the membership delta server-side (add new, remove old, patch flags). Optimistic locking per membership row prevents concurrent admin actions from clobbering each other.

### 7.5 Member-list UI

members.html:44-96 today shows one `squadron-badge` column. Tomorrow it shows one Staffel badge (or em-dash) plus a comma-separated list of SK shorthand badges (e.g. `IRI · ALPHA · BRAVO`). The visual treatment differs (SK badges use the neutral chip style) so the kinds remain scannable.

### 7.6 SK admin page

New page `/admin/special-commands` (Thymeleaf template `admin-special-commands.html`):

- List view of all SKs (active + inactive toggle).
- Inline-edit shorthand/name/description (Admin only).
- Click-through to a per-SK detail page with the member list, add/remove member controls, role flag toggles, Lead toggle (Admin only).
- Add the page link to the admin sidebar section.

The promotion settings page (`/admin/settings`) gains a note that SKs do not participate in the promotion subsystem (CHECK constraint enforced).

---

## 8. ArchUnit Tests

The existing whitelists in `ArchitectureTest.java` drive the rename:

### 8.1 Update existing rules

```java
// Today
Set<String> staffelScopedServiceNames = Set.of(
    "MissionService", "InventoryItemService", "RefineryOrderService",
    "HangarService", "OperationService");

// Tomorrow — same list, just the dependency check shifts
//   "must inject either AuthHelperService or OwnerScopeService"
```

The whitelist names stay the same; only the dependency target changes. **Add** `SpecialCommandService` and `OrgUnitMembershipService` to the whitelist (they manage scoped data).

Same applies to `staffelScopedControllerSimpleNames` (add `SpecialCommandController`).

### 8.2 New rule: kind-of-OrgUnit must match aggregate intent

ArchUnit cannot inspect DB-level CHECKs, but it can catch wiring mistakes at the Java layer:

```java
// PromotionTopic.owningSquadron must be typed Squadron, not OrgUnit.
classes().that().haveSimpleName("PromotionTopic")
         .should().haveFieldOfType(Squadron.class).asWrapperOfMember("owningSquadron")
```

Hand-written via reflection on `Field.getType()` in a custom ArchCondition. Catches a careless refactor that loosens the type to `OrgUnit` and silently lets an SK become a promotion owner.

### 8.3 New rule: write DTOs cannot accept `owningOrgUnit` / `owningSquadronId` / `creatingSquadronId`

Today's audit-finding rule `responseOnlyDtosMustNotBeAcceptedAsRequestBodyOnWriteEndpoints` (line 957) gets its forbidden-field list extended:

```java
forbiddenWriteFieldNames = Set.of(
    "id", "version", "owningSquadronId",
    "owningOrgUnit", "owningOrgUnitDto",          // server-managed entity refs
    "creatingSquadron", "creatingOrgUnit",
    "owner", "parent"
);
```

The allowed write fields stay `owningOrgUnitId: UUID`, `creatingOrgUnitId: UUID`, `requestingOrgUnitId: UUID` (plain UUIDs are intentionally allowed — they're inputs, not entity proxies).

### 8.4 New rule: every owner-stamping create path uses the central resolver

A method named `create*` on any service in the staffel-scoped service whitelist must either (a) call `resolveOwnerForUser` directly, or (b) call another method that does (Mission's hand-off to MissionService). ArchUnit `methods().that().areAnnotatedWith(MarkerAnnotation.class).should().call(…)` isn't quite right; the practical implementation is a Checkstyle/PMD custom check, not ArchUnit. Flag as a follow-up.

### 8.5 New rule: no direct `@JoinColumn(name = "squadron_id")` outside the legacy migration code

Catches a careless re-introduction of the old column name on a new entity. ArchUnit:

```java
fields().should().notHaveAnnotationContaining("@JoinColumn(name = \"squadron_id\")")
```

(string match against the annotation literal; ArchUnit doesn't natively support annotation-value introspection.)

### 8.6 Frontend ArchUnit

Unchanged. The two existing rules ("no Spring Data JPA in frontend", "no JDBC") cover the new code automatically.

---

## 9. Test Strategy

### 9.1 Unit test coverage targets

| Layer | New tests |
|-------|-----------|
| `OrgUnit` / `Squadron` / `SpecialCommand` entities | discriminator behaviour, IRIDIUM_ID equality, JPA Hibernate validate |
| `OrgUnitMembership` | partial unique index enforced (insert second Staffel membership → fail), is_lead CHECK enforced (set on Staffel membership → fail) |
| `OwnerScopeService` | every method that today has a `SquadronScopeServiceTest` case — port verbatim plus new SK-only and multi-membership cases |
| `resolveOwnerForUser` | matrix: 0 memberships → reject; 1 membership + no choice → auto-stamp; 1 membership + choice mismatch → reject; >1 membership + no choice → reject; >1 membership + valid choice → stamp; >1 membership + foreign choice → reject |
| `InventoryItemService.bookOutInventoryItem TRANSFER` | new cross-org-unit cases: SK-only target user, target user with 0 memberships (impossible after backfill, but defensive), Staffel-to-SK transfer, SK-to-Staffel transfer |
| `SpecialCommandService` | CRUD path; member add/remove path; Lead toggle path; admin-vs-Lead authority matrix |
| `MissionRepository.searchMissions` | new fixture matrix: user in Staffel A + SK X; mission owned by SK Y with isInternal=false → visible; same with isInternal=true → not visible |
| `MeFrontendController.setActiveOrgUnit` | accept Staffel id, accept SK id, accept blank (clear), reject UUID not in user's memberships (for non-admin) |

### 9.2 Integration test coverage

`@SpringBootTest`-driven scenarios:

1. End-to-end "create refinery order as user in 1 Staffel + 2 SKs": POST without `owningOrgUnitId` → 400; POST with valid id → 201; subsequent GET returns the order with the chosen owner. Repeat for inventory create, mission create, operation create, job order create.
2. End-to-end transfer: actor in Staffel A; target user in SK X (no Staffel). POST `/api/v1/inventory/{id}/book-out` with `targetOwningOrgUnitId = SK X id` succeeds; new item has `owningOrgUnit = SK X`.
3. Promotion subsystem against an SK: any read or write to a promotion endpoint scoped to an SK returns 403 (the CHECK on `org_unit` makes the topic-creation path impossible; but the read side also short-circuits via `isPromotionFeatureEnabledForCurrentScope`).
4. Admin context switcher: admin pins SK Y; GETs return only SK Y data; switching to "all" restores cross-org reads. Same flow for a non-admin user in multiple memberships.
5. Multi-membership listing: user in 1 Staffel + 1 SK runs `GET /api/v1/inventory` with no active selection → sees both org units' Lager. Then pins SK only → sees only SK's Lager.

### 9.3 Regression / golden-path tests

The single most important regression suite: every existing test in the staffel-scoped service test packages must continue to pass with `@DiscriminatorValue("SQUADRON")` rows substituting for what was a `Squadron`. The aim is to prove that **the single-Staffel-no-SK user experience is byte-identical to today**.

Concrete plan: keep all existing test fixtures (`Squadron` instances created via the existing `@TestSquadronFactory` or similar) unchanged; ensure they end up as `org_unit.kind='SQUADRON'` rows post-migration; rerun the entire `:backend:test` suite as a smoke gate at each R1/R2/R3 milestone.

### 9.4 Manual UI verification

Per CLAUDE.md frontend rule: every responsive breakpoint (smartphone, tablet, desktop, ultra-wide) gets walked through on the new owner-picker fragment, the SK admin page, and the extended member-edit page. Run the dev stack with `.env.test`, never the production `.env` (per memory: `feedback_env_test_isolation`).

### 9.5 Test data isolation reminder

Test artifacts (keystore, realm export) stay isolated per the existing rule. No production credentials in any new fixture. CLAUDE.md "Testing" section applies.

---

## 10. Rollout Phases & Soak Windows

Recommended sequence, each step is a separate PR + deploy:

| Step | Migrations | Code | Rollback |
|------|-----------|------|----------|
| **PR-1** (R1.a) | V94 (`org_unit` create + copy) + V95 (`org_unit_membership` create + backfill) | None | Drop the two tables; `squadron` + `app_user` still authoritative |
| **PR-2** (R1.b) | V96 (FK column adds on aggregates + JobOrder + indexes) | None | Drop new columns |
| **soak** | — | — | 1 release cycle in prod confirms data integrity |
| **PR-3** (R2.a) | None | New entities (`OrgUnit`, `SpecialCommand`, `OrgUnitMembership`); dual-write services that mirror writes to both old and new columns/tables; `OwnerScopeService` with shim for `@squadronScopeService` SpEL strings; ArchUnit whitelist updates | Re-deploy R1 app — old write path still works |
| **PR-4** (R2.b) | None | New REST endpoints (`/api/v1/special-commands`, membership APIs); admin UI; new owner-picker fragment; admin switcher rename to `X-Active-Org-Unit-Id`; old `X-Active-Squadron-Id` header still honoured for one release as alias | Same as PR-3 |
| **PR-5** (R2.c) | None | Stop-write the legacy columns (`squadron`, `app_user.squadron_id`, `is_logistician`, `is_mission_manager`, `owning_squadron_id`, `creating_squadron_id`, `requesting_squadron_id`); update all `@PreAuthorize` SpEL strings to `@ownerScopeService.*`; remove the `SquadronScopeService` shim | Re-deploy PR-4 |
| **soak** | — | — | 1 release cycle confirms stop-write side has no functional issues |
| **PR-6** (R3.a) | V97 (NOT NULL tighten on new, drop NOT NULL on old) | None | Re-tighten on old columns (irreversible in practice) |
| **PR-7** (R3.b) | V98 (drop old aggregate columns) + V99 (drop `app_user.squadron_id` / flags) + V100 (drop legacy `squadron` table) | None | Irreversible — restore from backup if needed |

Total: 7 PRs across 3 deployable releases with 2 soak windows. Each PR is small enough to review; each deploy is rollback-safe up to the dual-write phase.

### Communication checkpoints

- **Before PR-1**: announce the schema migrations to anyone running ad-hoc SQL.
- **Before PR-5 (stop-write)**: confirm dual-write has not silently lost any rows for one release.
- **Before PR-7 (drop)**: take a full DB backup; the operation is irreversible.

---

## 11. Regression Risk Register

Threats this plan deliberately mitigates, with the corresponding mitigation pointer:

| # | Risk | Today's symptom | Mitigation |
|---|------|-----------------|-----------|
| **R1** | Transfer to SK-only user breaks NOT NULL on `owning_squadron_id` | InventoryItemService.java:676 stamps `targetUser.getSquadron()` which is `null` for SK-only target; V89 NOT NULL throws | §5.5.2: `resolveOwnerForUser` requires an explicit `targetOwningOrgUnitId`; service-layer validation; integration test (§9.2 case 2) |
| **R2** | Inventory create on SK-only user crashes same way | InventoryItemService.java:431 stamps `user.getSquadron()` (null) | Same as R1 |
| **R3** | Existing `@PreAuthorize("@squadronScopeService.canSeeMission(#id)")` SpEL breaks after rename | All ~30 mentions go to broken SpEL → 403 on every mission read | §5.3 shim: `SquadronScopeService` survives PR-3, every SpEL string updated in PR-5, shim deletion in PR-5; grep gate runs as part of `:backend:test` setup |
| **R4** | Promotion data accidentally references an SK | `promotion_topic.owning_squadron_id` could be set to an SK UUID (FK still resolves since same table) | §3.3 trigger-based CHECK; §5.2 keeps `PromotionTopic.owningSquadron` typed `Squadron` (not `OrgUnit`); §8.2 ArchUnit rule blocks the type loosening |
| **R5** | Admin context header (`X-Active-Squadron-Id`) renamed but stale clients send old name | Admins on cached pages send old header → backend ignores → admin's "active selection" silently breaks | §7.2 keeps both header names honoured for one release; deprecation header added on the old path; tracked alias in `DeprecationInterceptor` |
| **R6** | User with 0 memberships (admin / guest) hits a create path | Stamping fails with NPE or NOT NULL | §5.5.1 explicit `BadRequestException` at the helper; integration test |
| **R7** | Member-edit UI's single-Staffel dropdown is replaced — admin loses ability to set is_logistician/is_mission_manager flags | Today the flags are toggled at the user level; after refactor they're per-membership | §7.4 new two-section member-edit form; the patch endpoints replace the user-level flags; CHANGELOG entry on the user-facing change |
| **R8** | Existing `Set<UUID>` filter parameter performance: `IN` clauses with many memberships are slow | Postgres handles small IN lists fine; large memberships sets uncommon | Cap memberships per user at app layer (warning at 16 SKs, hard at 32 — high enough nobody hits it); index on `owning_org_unit_id` already present (§4 release R1) |
| **R9** | The new owner-picker fragment becomes 500 in prod due to backend DTO returning a `null` memberships field | Templates render against a `null` collection → template engine error | DTO uses `Collections.emptyList()` defaults; integration test asserts the empty case renders; per memory `feedback_backend_frontend_dto_mirror`, the frontend mirror record must be updated in lockstep |
| **R10** | ArchUnit `staffelScopedWriteEndpointsMustGateOnSquadronScopeService` rule pre-PR-5 still blocks merges because SpEL still references `squadronScopeService` | New endpoints could land with the new bean name and fail the rule | Extend the accepted SpEL pattern list in the rule to include both names during R2; revert to `ownerScopeService`-only in PR-5 |
| **R11** | Mission cross-staffel visibility breaks: a user in just a Staffel was previously seeing non-internal foreign-squadron missions; after the refactor, the predicate must also fold the new memberships set | If the new repo query forgets the `is_internal = false` clause, public missions disappear from cross-org-unit view | §5.4 example query keeps the `OR isInternal = false` clause; existing tests against `MissionRepository.searchMissions` cover this; new tests for the multi-membership case |
| **R12** | Concurrent admin actions on the same membership row (e.g. admin A flips Lead while admin B removes membership) cause a 409 storm | Optimistic locking would surface as a generic error | `OrgUnitMembership` carries `@Version`; controller catches `OptimisticLockingFailureException` and surfaces a 409 with a clear problem-detail; matches the existing pattern in `SquadronController.updateSquadron` |
| **R13** | The CHECK constraint `kind = 'SQUADRON' OR is_promotion_enabled = FALSE` rejects a default-initialized SK row inserted by Hibernate (since the entity's default is true) | INSERT fails with constraint violation, the user can't create any SK | `SpecialCommand` constructor sets `isPromotionEnabled = false`; integration test creating an SK and reading it back asserts the flag |
| **R14** | The MDC `squadronId` log field changes meaning silently (was Staffel id; will be any org unit id including SK) | Existing log dashboards keyed on `squadronId` start mixing Staffel and SK ids | Rename MDC field to `orgUnitId` in `CorrelationIdFilter`; update `application-prod.yml` `LogstashEncoder` patterns; CHANGELOG entry; one-release alias keeps both fields populated for log-pipeline transition |
| **R15** | Hibernate single-table inheritance + LAZY loading: a query on `Squadron` returns only Squadron rows but the SQL Hibernate issues includes a discriminator filter that some custom JPQL might miss | Custom JPQL that selects `FROM Squadron s` won't see SK rows (correct) but `FROM OrgUnit o WHERE TYPE(o) = Squadron` mis-coded could leak | All inheritance-aware queries land via the repository abstraction (typed `SquadronRepository extends JpaRepository<Squadron, UUID>` so Spring auto-applies the discriminator); ban hand-written JPQL on the OrgUnit hierarchy unless guarded with `TYPE(o) = …` |
| **R16** | Frontend has hardcoded squadron count, names, or admin-only assumptions that fall apart for non-admins with the new switcher | Layout glitches, broken context chip, missing labels | §7 itemizes every affected template; manual responsive verification per §9.4 |

---

## 12. Documentation Updates

Each of the following gets a synchronous update as part of the relevant PR (not as a follow-up):

- **`CLAUDE.md`** — extensive: "Multi-squadron tenancy" section becomes "Multi-org-unit tenancy"; the scoped-aggregate whitelist is expanded; the `SquadronScopeService` references become `OwnerScopeService`; the `Squadron.IRIDIUM_ID` mention is preserved; the new owner-picker contract is documented; the V94–V100 migration history is referenced. Update happens in PR-5 (the stop-write deploy) so the file reflects the post-rename world.
- **`ROLES_AND_PERMISSIONS.md`** — add a section on contextual roles; replace "Logistician (global)" with "Logistician (per membership)"; add the SK Lead role.
- **`MULTI_SQUADRON_PLAN.md`** — add a closing paragraph referencing this plan as the follow-up; do not rewrite history.
- **`CHANGELOG.md`** — entries per PR, short and user-visible (per the repo style memory `feedback_changelog_one_shot`): "Spezialkommandos koennen jetzt parallel zu Staffeln angelegt werden", "Lagerbuchung erfordert explizite Auswahl des Eintraegers wenn der Nutzer in mehreren Einheiten ist", etc. German with literal umlauts (per CLAUDE.md i18n rule for Markdown).
- **`README.md`** — env-var section unchanged; architecture section's "tenant unit = Squadron" sentence becomes "tenant unit = OrgUnit (Squadron or SpecialCommand)".
- **`backend/src/main/resources/db/migration/README.md`** — append a note that V94–V100 implement the SK extension and document the staged rollout.
- **OpenAPI** — every new endpoint carries `@Operation` + `@ApiResponses`; `openapi.json` is regenerated in PR-4.

---

## 13. Out-of-Scope Follow-ups (flagged, not done)

- **Per-SK promotion variants** — should an SK ever need a promotion-style system, it would need a separate aggregate (not the existing `promotion_topic` tree). Out of scope per requirement; flagged as a possible future ticket.
- **Hierarchies between SKs** — an SK that "belongs to" a Squadron, or an SK with sub-SKs. Not requested.
- **Time-bounded memberships** — joined_at exists; expires_at does not. Could be added as an additional column without further refactor.
- **Audit log of membership changes** — today member edits don't have a dedicated audit table (Squadron has lazy `@Version`-driven optimistic locking). A future ticket could land `org_unit_membership_audit`.
- **Bulk SK creation / import** — not part of this plan; SK lifecycle is one-at-a-time via the admin UI.
- **Discriminator widening (Workgroup, Project, …)** — the `org_unit.kind` enum could grow more values; the CHECK is open to expansion. Not pursued here.

---

## 14. Acceptance Checklist for the Execution Session

Before declaring the SK extension done, the execution session must demonstrate:

- [ ] All migrations V94–V100 apply cleanly against a snapshot of prod schema (staged through `.env.test` per memory `feedback_env_test_isolation`).
- [ ] `./gradlew check` is clean — no Checkstyle warnings, no SpotBugs findings, no ArchUnit violations introduced.
- [ ] Every test mentioned in §9 passes. Old `Squadron`-fixtures-only tests still pass without change.
- [ ] Manual UI walk-through per §9.4 documented (smartphone / tablet / desktop / ultra-wide).
- [ ] Active context switcher works for: admin without selection, admin with Staffel pinned, admin with SK pinned, non-admin with one membership (no switcher shown), non-admin with mixed memberships (switcher shown).
- [ ] Cross-org-unit transfer succeeds and is auditable in logs (`orgUnitId` MDC field present).
- [ ] Promotion endpoints scoped to an SK consistently return 403; promotion data on Staffeln still works.
- [ ] No `owning_squadron_id`, `is_logistician` on `app_user`, `creating_squadron_id`, `squadron_id` on `app_user`, or `squadron` table remain in the schema after V100.
- [ ] CLAUDE.md, ROLES_AND_PERMISSIONS.md, README.md, CHANGELOG.md, db/migration/README.md updated.
- [ ] OpenAPI spec regenerated; Swagger UI lists every new endpoint.
- [ ] DCO sign-off and `Co-Authored-By:` trailer present on every commit (per CLAUDE.md Git policy).

---

End of plan. No code changes will be made until this document is explicitly approved.
